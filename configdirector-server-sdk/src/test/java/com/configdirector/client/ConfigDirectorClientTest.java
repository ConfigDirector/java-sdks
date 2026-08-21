package com.configdirector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.configdirector.ClientReadyEvent;
import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConfigEvaluatedEvent;
import com.configdirector.ConfigState;
import com.configdirector.ConfigsUpdatedEvent;
import com.configdirector.ConnectionMode;
import com.configdirector.ConnectionOptions;
import com.configdirector.Context;
import com.configdirector.EvaluationReason;
import com.configdirector.Subscription;
import com.configdirector.testing.TestHttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConfigDirectorClientTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private ConfigDirectorClient client;
  private TestHttpServer server;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
  }

  private static <T> List<T> snapshot(List<T> shared) {
    synchronized (shared) {
      return new ArrayList<>(shared);
    }
  }

  private static String bundleOf(String configsJson) {
    return "{\"timestamp\":\"t1\",\"configs\":{" + configsJson + "}}";
  }

  private static String config(String key, String type, String defaultValue) {
    return "\""
        + key
        + "\":{\"id\":\"id-"
        + key
        + "\",\"key\":\""
        + key
        + "\",\"type\":\""
        + type
        + "\",\"target\":{\"defaultValue\":"
        + defaultValue
        + ",\"defaultValueId\":\"dv-"
        + key
        + "\",\"rules\":[]}}";
  }

  private ConfigDirectorClient clientServing(String bundle) {
    server = start(session -> respond(session, bundle));
    String url = server.url("/");
    return build(connection -> connection.mode(ConnectionMode.ONE_TIME).url(url));
  }

  private ConfigDirectorClient build(Consumer<ConnectionOptions.Builder> connection) {
    return ConfigDirector.client(
        "sdk-key",
        options -> options.connection(builder -> {
          builder.timeout(TIMEOUT);
          connection.accept(builder);
        }));
  }

  private static void respond(TestHttpServer.Session session, String body) {
    session.respond(
        200,
        "Content-Type: application/json",
        "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length,
        "Connection: close");
    session.send(body);
    session.close();
  }

  private static TestHttpServer start(Consumer<TestHttpServer.Session> handler) {
    try {
      return TestHttpServer.start(handler);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  @Nested
  @DisplayName("reading values")
  class Reading {

    @Test
    void returns_the_evaluated_value_for_each_type() {
      client =
          clientServing(
              bundleOf(
                  config("flag", "boolean", "\"true\"")
                      + ","
                      + config("name", "string", "\"hello\"")
                      + ","
                      + config("count", "integer", "\"26\"")
                      + ","
                      + config("rate", "float", "\"1.5\"")
                      + ","
                      + config("layout", "json", "\"{\\\"a\\\":1}\"")
                      + ","
                      + config("tags", "json", "\"[1,2]\"")));
      client.initialize();

      assertThat(client.getBoolean("flag", false)).isTrue();
      assertThat(client.getString("name", "x")).isEqualTo("hello");
      assertThat(client.getInteger("count", 0)).isEqualTo(26);
      assertThat(client.getDouble("rate", 0.0)).isEqualTo(1.5);
      assertThat(client.getJsonObject("layout", Map.of())).containsEntry("a", 1L);
      assertThat(client.getJsonArray("tags", List.of())).containsExactly(1L, 2L);
    }

    @Test
    void the_generic_getter_takes_its_type_from_the_default() {
      client = clientServing(bundleOf(config("flag", "boolean", "\"true\"")));
      client.initialize();

      Boolean value = client.getValue("flag", false);

      assertThat(value).isTrue();
    }

    @Test
    void an_unknown_key_returns_the_default() {
      client = clientServing(bundleOf(config("flag", "boolean", "\"true\"")));
      client.initialize();

      assertThat(client.getBoolean("no-such-key", true)).isTrue();
      assertThat(client.getString("no-such-key", "fallback")).isEqualTo("fallback");
    }

    @Test
    void a_value_that_will_not_coerce_returns_the_default() {
      client = clientServing(bundleOf(config("name", "string", "\"not-a-number\"")));
      client.initialize();

      assertThat(client.getInteger("name", 7)).isEqualTo(7);
    }

    @Test
    void before_initialization_every_getter_returns_its_default() {
      client = clientServing(bundleOf(config("flag", "boolean", "\"true\"")));

      assertThat(client.isReady()).isFalse();
      assertThat(client.getBoolean("flag", false)).isFalse();
    }
  }

  @Nested
  @DisplayName("getAllConfigs")
  class AllConfigs {

    @Test
    void returns_every_config_evaluated() {
      client =
          clientServing(
              bundleOf(config("a", "string", "\"1\"") + "," + config("b", "string", "\"2\"")));
      client.initialize();

      Map<String, ConfigState> all = client.getAllConfigs();

      assertThat(all).containsOnlyKeys("a", "b");
      assertThat(all.get("a").value()).isEqualTo("1");
      assertThat(all.get("a").valueId()).isEqualTo("dv-a");
    }

    @Test
    void can_be_narrowed_to_named_keys() {
      client =
          clientServing(
              bundleOf(config("a", "string", "\"1\"") + "," + config("b", "string", "\"2\"")));
      client.initialize();

      assertThat(client.getAllConfigs(null, List.of("b"))).containsOnlyKeys("b");
    }

    @Test
    void is_empty_before_the_first_bundle() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));

      assertThat(client.getAllConfigs()).isEmpty();
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    void a_blank_sdk_key_is_rejected() {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> ConfigDirector.client("  "))
          .withMessageContaining("server SDK key");
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> ConfigDirector.client(null));
    }

    @Test
    void an_unusable_connection_url_is_rejected() {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(
              () -> ConfigDirector.client("k", options -> options.connection(c -> c.url("not-a-url"))))
          .withMessageContaining("Invalid connection URL");
    }

    @Test
    void a_blank_config_key_is_rejected() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));

      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> client.getBoolean("  ", false))
          .withMessageContaining("config key");
    }

    @Test
    void a_null_default_is_rejected() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));

      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> client.getString("a", null))
          .withMessageContaining("must not be null");
    }

    @Test
    void an_unsupported_default_type_is_rejected() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));

      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> client.getValue("a", Duration.ofSeconds(1)))
          .withMessageContaining("Supported types");
    }

    @Test
    void a_non_positive_timeout_is_rejected() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));

      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> client.initialize(Duration.ZERO));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> client.initialize(Duration.ofSeconds(-1)));
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    void is_ready_once_the_first_bundle_arrives() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));

      client.initialize();

      assertThat(client.isReady()).isTrue();
      assertThat(client.isClosed()).isFalse();
    }

    @Test
    void close_stops_the_client_and_is_idempotent() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));
      client.initialize();

      client.close();
      client.close();

      assertThat(client.isClosed()).isTrue();
      assertThat(client.isReady()).isFalse();
    }

    @Test
    void a_closed_client_cannot_be_initialized_again() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));
      client.close();

      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> client.initialize())
          .withMessageContaining("closed");
    }

    @Test
    void a_closed_client_still_returns_defaults_rather_than_throwing() {
      client = clientServing(bundleOf(config("flag", "boolean", "\"true\"")));
      client.initialize();
      client.close();

      assertThat(client.getBoolean("flag", false)).isFalse();
    }

    @Test
    void an_unreachable_server_leaves_the_client_unready_without_throwing() {
      client =
          build(
              connection ->
                  connection
                      .mode(ConnectionMode.ONE_TIME)
                      .url("http://127.0.0.1:1/")
                      .timeout(Duration.ofMillis(500)));

      client.initialize();

      assertThat(client.isReady()).isFalse();
      assertThat(client.getBoolean("flag", true)).isTrue();
    }
  }

  @Nested
  @DisplayName("events")
  class Events {

    @Test
    void client_ready_fires_once_when_state_arrives() {
      List<ClientReadyEvent> events = Collections.synchronizedList(new ArrayList<>());
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));
      client.onClientReady(events::add);

      client.initialize();

      await().atMost(TIMEOUT).until(() -> !snapshot(events).isEmpty());
      assertThat(snapshot(events)).hasSize(1);
    }

    @Test
    void configs_updated_carries_the_keys_it_delivered() {
      List<ConfigsUpdatedEvent> events = Collections.synchronizedList(new ArrayList<>());
      client =
          clientServing(
              bundleOf(config("b", "string", "\"1\"") + "," + config("a", "string", "\"2\"")));
      client.onConfigsUpdated(events::add);

      client.initialize();

      await().atMost(TIMEOUT).until(() -> !snapshot(events).isEmpty());
      assertThat(snapshot(events).get(0).keys()).containsExactly("a", "b");
    }

    @Test
    void config_evaluated_reports_the_reason() {
      List<ConfigEvaluatedEvent> events = Collections.synchronizedList(new ArrayList<>());
      client = clientServing(bundleOf(config("flag", "boolean", "\"true\"")));
      client.initialize();
      client.onConfigEvaluated(events::add);

      client.getBoolean("flag", false);
      client.getBoolean("missing", false);

      List<ConfigEvaluatedEvent> seen = snapshot(events);
      assertThat(seen).hasSize(2);
      assertThat(seen.get(0).evaluation().reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
      assertThat(seen.get(0).evaluation().isDefault()).isFalse();
      assertThat(seen.get(0).evaluation().valueId()).isEqualTo("dv-flag");
      assertThat(seen.get(1).evaluation().reason()).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING);
      assertThat(seen.get(1).evaluation().isDefault()).isTrue();
    }

    @Test
    void a_handler_that_throws_does_not_break_the_caller() {
      client = clientServing(bundleOf(config("flag", "boolean", "\"true\"")));
      client.initialize();
      client.onConfigEvaluated(
          event -> {
            throw new IllegalStateException("handler blew up");
          });

      assertThat(client.getBoolean("flag", false)).isTrue();
    }

    @Test
    void a_subscription_can_be_cancelled() {
      List<ConfigEvaluatedEvent> events = Collections.synchronizedList(new ArrayList<>());
      client = clientServing(bundleOf(config("flag", "boolean", "\"true\"")));
      client.initialize();
      Subscription subscription = client.onConfigEvaluated(events::add);

      client.getBoolean("flag", false);
      subscription.close();
      client.getBoolean("flag", false);

      assertThat(snapshot(events)).hasSize(1);
    }
  }

  @Nested
  @DisplayName("watching")
  class Watching {

    @Test
    void a_watcher_fires_when_an_update_carries_its_key() throws Exception {
      List<Boolean> seen = Collections.synchronizedList(new ArrayList<>());
      server =
          start(
              session ->
                  respond(session, bundleOf(config("flag", "boolean", "\"true\""))));
      String url = server.url("/");
      client =
          build(
              connection ->
                  connection
                      .mode(ConnectionMode.POLLING)
                      .pollingInterval(Duration.ofMillis(50))
                      .url(url));

      client.watch("flag", false, seen::add);
      client.initialize();

      await().atMost(TIMEOUT).until(() -> !snapshot(seen).isEmpty());
      assertThat(snapshot(seen).get(0)).isTrue();
    }

    @Test
    void unwatch_stops_the_callbacks() throws Exception {
      List<Boolean> seen = Collections.synchronizedList(new ArrayList<>());
      server =
          start(
              session ->
                  respond(session, bundleOf(config("flag", "boolean", "\"true\""))));
      String url = server.url("/");
      client =
          build(
              connection ->
                  connection
                      .mode(ConnectionMode.POLLING)
                      .pollingInterval(Duration.ofMillis(50))
                      .url(url));
      client.watch("flag", false, seen::add);
      client.initialize();
      await().atMost(TIMEOUT).until(() -> !snapshot(seen).isEmpty());

      client.unwatch("flag");
      int afterUnwatch = snapshot(seen).size();

      Thread.sleep(300);
      assertThat(snapshot(seen)).hasSize(afterUnwatch);
    }

    @Test
    void a_faulty_watcher_does_not_cost_the_others_their_update() throws Exception {
      List<Boolean> seen = Collections.synchronizedList(new ArrayList<>());
      server =
          start(
              session ->
                  respond(session, bundleOf(config("flag", "boolean", "\"true\""))));
      String url = server.url("/");
      client =
          build(
              connection ->
                  connection
                      .mode(ConnectionMode.POLLING)
                      .pollingInterval(Duration.ofMillis(50))
                      .url(url));

      client.watch(
          "flag",
          false,
          value -> {
            throw new IllegalStateException("watcher blew up");
          });
      client.watch("flag", false, seen::add);
      client.initialize();

      await().atMost(TIMEOUT).until(() -> !snapshot(seen).isEmpty());
    }
  }
}
