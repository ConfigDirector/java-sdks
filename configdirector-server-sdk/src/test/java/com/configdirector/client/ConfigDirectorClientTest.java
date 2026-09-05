package com.configdirector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

  private static String deltaOf(String configsJson) {
    return "{\"kind\":\"delta\",\"timestamp\":\"t2\",\"configs\":{" + configsJson + "}}";
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

  // Serves "value" to the named identifier and the default to everyone else, so a test can tell
  // which context an evaluation ran against.
  private static String targetedConfig(
      String key, String type, String defaultValue, String identifier, String value) {
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
        + "\",\"rules\":[{\"id\":\"r-"
        + key
        + "\",\"type\":\"conditional\",\"order\":1,\"target\":\"value\",\"value\":"
        + value
        + ",\"valueId\":\"rv-"
        + key
        + "\",\"conditions\":[{\"id\":\"c-"
        + key
        + "\",\"attribute\":\"identifier\",\"operator\":\"=\",\"targetType\":\"text\","
        + "\"targetValues\":[\""
        + identifier
        + "\"],\"trait\":null}]}]}}";
  }

  private ConfigDirectorClient clientServing(String bundle) {
    server = start(session -> respond(session, bundle));
    String url = server.url("/");
    return build(connection -> connection.mode(ConnectionMode.POLLING).url(url));
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
    void narrowing_ignores_a_key_the_server_does_not_know() {
      client =
          clientServing(
              bundleOf(config("a", "string", "\"1\"") + "," + config("b", "string", "\"2\"")));
      client.initialize();

      assertThat(client.getAllConfigs(null, List.of("b", "nope"))).containsOnlyKeys("b");
    }

    @Test
    void narrowing_to_a_key_asked_for_twice_evaluates_it_once() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));
      client.initialize();

      assertThat(client.getAllConfigs(null, List.of("a", "a"))).containsOnlyKeys("a");
    }

    @Test
    void narrowing_to_nothing_returns_nothing() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));
      client.initialize();

      assertThat(client.getAllConfigs(null, List.of())).isEmpty();
    }

    @Test
    void narrowing_keeps_the_order_the_configs_came_in() {
      // The result follows config state, not the order the keys were asked for.
      client =
          clientServing(
              bundleOf(config("a", "string", "\"1\"") + "," + config("b", "string", "\"2\"")));
      client.initialize();

      assertThat(client.getAllConfigs(null, List.of("b", "a")).keySet())
          .containsExactly("a", "b");
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

    @Test
    void a_timeout_longer_than_the_http_client_accepts_is_rejected() {
      // OkHttp refuses a timeout over Integer.MAX_VALUE milliseconds. initialize() catches that
      // and returns, so without this check the caller saw no error at all -- just a client that
      // stayed unready and served defaults forever, against a server answering perfectly well.
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));

      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> client.initialize(Duration.ofDays(30)))
          .withMessageContaining("longest the HTTP client accepts");
    }

    @Test
    void the_longest_timeout_the_http_client_accepts_is_allowed() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));

      assertThatNoException()
          .isThrownBy(() -> client.initialize(Duration.ofMillis(Integer.MAX_VALUE)));
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
    void close_returns_within_its_budget_when_the_server_stops_answering() throws Exception {
      AtomicInteger connections = new AtomicInteger();
      CountDownLatch release = new CountDownLatch(1);
      // Answers the first poll and then goes quiet: the requests still go out, and nothing ever
      // comes back. This is the state a shutdown is most likely to find the network in.
      server =
          start(
              session -> {
                if (connections.incrementAndGet() == 1) {
                  respond(session, bundleOf(config("a", "string", "\"1\"")));
                  return;
                }
                try {
                  release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              });
      String url = server.url("/");
      client =
          build(
              connection ->
                  connection
                      .mode(ConnectionMode.POLLING)
                      .url(url)
                      .pollingInterval(Duration.ofMillis(50))
                      // Generous on purpose: a poll stuck on the wire has a long way to run, and
                      // close() must not inherit it.
                      .timeout(Duration.ofSeconds(30)));

      try {
        client.initialize();
        client.getString("a", "fallback");
        await().atMost(TIMEOUT).until(() -> connections.get() > 1);

        long start = System.nanoTime();
        client.close(Duration.ofMillis(300));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).isLessThan(Duration.ofSeconds(2));
        assertThat(client.isClosed()).isTrue();
      } finally {
        release.countDown();
      }
    }

    @Test
    void a_budget_of_zero_closes_without_waiting_for_anything() {
      client = clientServing(bundleOf(config("a", "string", "\"1\"")));
      client.initialize();

      assertThatNoException().isThrownBy(() -> client.close(Duration.ZERO));
      assertThat(client.isClosed()).isTrue();
    }

    @Test
    void an_unreachable_server_leaves_the_client_unready_without_throwing() {
      client =
          build(
              connection ->
                  connection
                      .mode(ConnectionMode.POLLING)
                      .url("http://127.0.0.1:1/")
                      .timeout(Duration.ofMillis(500)));

      client.initialize();

      assertThat(client.isReady()).isFalse();
      assertThat(client.getBoolean("flag", true)).isTrue();
    }
  }

  @Nested
  @DisplayName("streaming")
  class Streaming {

    private ConfigDirectorClient streamingClient(BlockingQueue<TestHttpServer.Session> live) {
      server =
          start(
              session -> {
                session.respondStreaming();
                live.add(session);
              });
      String url = server.url("/");
      return build(connection -> connection.mode(ConnectionMode.STREAMING).url(url));
    }

    // A frame the server sends for any other reason carries no configs object. Applying it as an
    // empty full bundle would leave the client ready but holding nothing.
    @Test
    void a_frame_that_is_not_a_config_bundle_leaves_config_state_intact() throws Exception {
      BlockingQueue<TestHttpServer.Session> live = new LinkedBlockingQueue<>();
      client = streamingClient(live);
      client.initialize();

      TestHttpServer.Session session = live.poll(5, TimeUnit.SECONDS);
      assertThat(session).isNotNull();
      session.send("data: " + bundleOf(config("flag", "boolean", "\"true\"")) + "\n\n");
      await().atMost(TIMEOUT).until(client::isReady);
      assertThat(client.getBoolean("flag", false)).isTrue();

      // The delta that follows is the sync point: once its key lands, the heartbeat ahead of it
      // has certainly been processed, so the flag below is not merely a message still in flight.
      session.send("data: {\"type\":\"heartbeat\"}\n\n");
      session.send("data: " + deltaOf(config("other", "string", "\"x\"")) + "\n\n");
      await().atMost(TIMEOUT).until(() -> "x".equals(client.getString("other", "")));

      assertThat(client.getBoolean("flag", false)).isTrue();
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

    private ConfigDirectorClient watchingClientServing(String bundle) {
      server = start(session -> respond(session, bundle));
      String url = server.url("/");
      return build(
          connection ->
              connection
                  .mode(ConnectionMode.POLLING)
                  .pollingInterval(Duration.ofMillis(50))
                  .url(url));
    }

    @Test
    void a_watcher_fires_when_an_update_carries_its_key() {
      List<Boolean> seen = Collections.synchronizedList(new ArrayList<>());
      client = watchingClientServing(bundleOf(config("flag", "boolean", "\"true\"")));

      client.watchBoolean("flag", false, seen::add);
      client.initialize();

      await().atMost(TIMEOUT).until(() -> !snapshot(seen).isEmpty());
      assertThat(snapshot(seen).get(0)).isTrue();
    }

    @Test
    void each_watch_delivers_the_value_in_its_own_type() {
      List<Boolean> flags = Collections.synchronizedList(new ArrayList<>());
      List<String> names = Collections.synchronizedList(new ArrayList<>());
      List<Integer> counts = Collections.synchronizedList(new ArrayList<>());
      List<Double> rates = Collections.synchronizedList(new ArrayList<>());
      List<Map<String, Object>> layouts = Collections.synchronizedList(new ArrayList<>());
      List<List<Object>> tags = Collections.synchronizedList(new ArrayList<>());
      client =
          watchingClientServing(
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

      client.watchBoolean("flag", false, flags::add);
      client.watchString("name", "x", names::add);
      client.watchInteger("count", 0, counts::add);
      client.watchDouble("rate", 0.0, rates::add);
      client.watchJsonObject("layout", Map.of(), layouts::add);
      client.watchJsonArray("tags", List.of(), tags::add);
      client.initialize();

      await()
          .atMost(TIMEOUT)
          .until(
              () ->
                  !snapshot(flags).isEmpty()
                      && !snapshot(names).isEmpty()
                      && !snapshot(counts).isEmpty()
                      && !snapshot(rates).isEmpty()
                      && !snapshot(layouts).isEmpty()
                      && !snapshot(tags).isEmpty());
      assertThat(snapshot(flags).get(0)).isTrue();
      assertThat(snapshot(names).get(0)).isEqualTo("hello");
      assertThat(snapshot(counts).get(0)).isEqualTo(26);
      assertThat(snapshot(rates).get(0)).isEqualTo(1.5);
      assertThat(snapshot(layouts).get(0)).containsEntry("a", 1L);
      assertThat(snapshot(tags).get(0)).containsExactly(1L, 2L);
    }

    @Test
    void a_watch_that_will_not_coerce_delivers_the_default() {
      List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
      client = watchingClientServing(bundleOf(config("name", "string", "\"not-a-number\"")));

      client.watchInteger("name", 7, seen::add);
      client.initialize();

      await().atMost(TIMEOUT).until(() -> !snapshot(seen).isEmpty());
      assertThat(snapshot(seen).get(0)).isEqualTo(7);
    }

    @Test
    @SuppressWarnings("deprecation")
    void the_deprecated_watch_takes_its_type_from_the_default() {
      List<Boolean> seen = Collections.synchronizedList(new ArrayList<>());
      client = watchingClientServing(bundleOf(config("flag", "boolean", "\"true\"")));

      client.watch("flag", false, seen::add);
      client.initialize();

      await().atMost(TIMEOUT).until(() -> !snapshot(seen).isEmpty());
      assertThat(snapshot(seen).get(0)).isTrue();
    }

    @Test
    void a_watch_evaluates_every_update_against_its_own_context() {
      List<String> targeted = Collections.synchronizedList(new ArrayList<>());
      List<String> untargeted = Collections.synchronizedList(new ArrayList<>());
      client =
          watchingClientServing(
              bundleOf(targetedConfig("name", "string", "\"fallback\"", "u1", "\"matched\"")));

      client.watchString("name", "x", targeted::add, Context.builder().id("u1").build());
      client.watchString("name", "x", untargeted::add, Context.builder().id("u2").build());
      client.initialize();

      await()
          .atMost(TIMEOUT)
          .until(() -> !snapshot(targeted).isEmpty() && !snapshot(untargeted).isEmpty());
      assertThat(snapshot(targeted).get(0)).isEqualTo("matched");
      assertThat(snapshot(untargeted).get(0)).isEqualTo("fallback");
    }

    @Test
    void a_watch_rejects_a_null_callback() {
      client = clientServing(bundleOf(config("flag", "boolean", "\"true\"")));

      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> client.watchBoolean("flag", false, null));
    }

    @Test
    void closing_the_subscription_stops_the_callbacks() throws Exception {
      List<Boolean> seen = Collections.synchronizedList(new ArrayList<>());
      client = watchingClientServing(bundleOf(config("flag", "boolean", "\"true\"")));
      Subscription subscription = client.watchBoolean("flag", false, seen::add);
      client.initialize();
      await().atMost(TIMEOUT).until(() -> !snapshot(seen).isEmpty());

      subscription.close();
      int afterClose = snapshot(seen).size();

      Thread.sleep(300);
      assertThat(snapshot(seen)).hasSize(afterClose);
    }

    @Test
    void unwatch_stops_the_callbacks() throws Exception {
      List<Boolean> seen = Collections.synchronizedList(new ArrayList<>());
      client = watchingClientServing(bundleOf(config("flag", "boolean", "\"true\"")));
      client.watchBoolean("flag", false, seen::add);
      client.initialize();
      await().atMost(TIMEOUT).until(() -> !snapshot(seen).isEmpty());

      client.unwatch("flag");
      int afterUnwatch = snapshot(seen).size();

      Thread.sleep(300);
      assertThat(snapshot(seen)).hasSize(afterUnwatch);
    }

    @Test
    void a_faulty_watcher_does_not_cost_the_others_their_update() {
      List<Boolean> seen = Collections.synchronizedList(new ArrayList<>());
      client = watchingClientServing(bundleOf(config("flag", "boolean", "\"true\"")));

      client.watchBoolean(
          "flag",
          false,
          value -> {
            throw new IllegalStateException("watcher blew up");
          });
      client.watchBoolean("flag", false, seen::add);
      client.initialize();

      await().atMost(TIMEOUT).until(() -> !snapshot(seen).isEmpty());
    }
  }
}
