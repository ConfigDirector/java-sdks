package com.configdirector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.configdirector.ClientOptions;
import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigEvaluatedEvent;
import com.configdirector.ConnectionMode;
import com.configdirector.Context;
import com.configdirector.internal.telemetry.ValueIds;
import com.configdirector.testing.TestHttpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClientTelemetryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final String BUNDLE =
      "{\"timestamp\":\"t1\",\"configs\":{\"greeting\":{\"id\":\"c1\",\"key\":\"greeting\","
          + "\"type\":\"string\",\"target\":{\"defaultValue\":\"hello\",\"defaultValueId\":\"dv-1\","
          + "\"rules\":[]}}}}";

  private final List<String> telemetryBodies = Collections.synchronizedList(new ArrayList<>());

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

  private ConfigDirectorClient readyClient() {
    return readyClient(options -> {});
  }

  private ConfigDirectorClient readyClient(Consumer<ClientOptions> customize) {
    startedClient(customize);
    client.initialize();
    return client;
  }

  private ConfigDirectorClient startedClient(Consumer<ClientOptions> customize) {
    server =
        start(
            session -> {
              if (session.path().endsWith("/server/telemetry/v1")) {
                telemetryBodies.add(session.bodyAsString());
                session.respond(200, "Content-Length: 0", "Connection: close");
              } else {
                byte[] body = BUNDLE.getBytes(StandardCharsets.UTF_8);
                session.respond(
                    200,
                    "Content-Type: application/json",
                    "Content-Length: " + body.length,
                    "Connection: close");
                session.send(BUNDLE);
              }
              session.close();
            });
    String url = server.url("/");
    client =
        ConfigDirector.client(
            "sdk-key",
            options -> {
              options.connection(
                  connection -> connection.mode(ConnectionMode.POLLING).url(url).timeout(TIMEOUT));
              customize.accept(options);
            });
    return client;
  }

  private static TestHttpServer start(Consumer<TestHttpServer.Session> handler) {
    try {
      return TestHttpServer.start(handler);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  // The client reports whatever is left when it closes, which is what makes a short-lived process
  // observable without waiting out a flush interval.
  private JsonObject reportAfterClosing() {
    client.close();
    await().atMost(TIMEOUT).until(() -> !telemetryBodies.isEmpty());
    synchronized (telemetryBodies) {
      return JsonParser.parseString(telemetryBodies.get(0)).getAsJsonObject();
    }
  }

  private static JsonArray evaluationsOf(JsonObject report) {
    return report.getAsJsonObject("aggregatedEvents").getAsJsonArray("evaluatedConfig");
  }

  private static JsonObject eventOf(JsonObject report, int index) {
    return evaluationsOf(report).get(index).getAsJsonObject().getAsJsonObject("event");
  }

  private static JsonArray contextsOf(JsonObject report) {
    return report.getAsJsonObject("discreteEvents").getAsJsonArray("capturedContexts");
  }

  private static JsonArray invalidContextsOf(JsonObject report) {
    return report.getAsJsonObject("aggregatedEvents").getAsJsonArray("invalidContext");
  }

  private static JsonObject invalidContextEntry(JsonObject report, String kindSample) {
    for (JsonElement entry : invalidContextsOf(report)) {
      JsonObject event = entry.getAsJsonObject().getAsJsonObject("event");
      if (event.has("kindSample") && event.get("kindSample").getAsString().equals(kindSample)) {
        return entry.getAsJsonObject();
      }
    }
    throw new AssertionError("No invalid context entry for '" + kindSample + "' in " + report);
  }

  private static List<JsonObject> invalidContextEntriesWithoutSample(JsonObject report) {
    List<JsonObject> entries = new ArrayList<>();
    for (JsonElement entry : invalidContextsOf(report)) {
      if (!entry.getAsJsonObject().getAsJsonObject("event").has("kindSample")) {
        entries.add(entry.getAsJsonObject());
      }
    }
    return entries;
  }

  private static Context withKind(String kind) {
    return Context.builder().id("user-1").kind(kind).name("Ada").trait("plan", "pro").build();
  }

  @Nested
  @DisplayName("what the client reports")
  class Reporting {

    @Test
    void reports_the_configs_the_application_evaluated() {
      readyClient().getString("greeting", "fallback");

      JsonObject report = reportAfterClosing();

      JsonObject event = eventOf(report, 0);
      assertThat(event.get("key").getAsString()).isEqualTo("greeting");
      assertThat(event.get("evaluationReason").getAsString()).isEqualTo("found-match");
      assertThat(event.get("usedDefault").getAsBoolean()).isFalse();
      assertThat(event.get("requestedType").getAsString()).isEqualTo("String");
      assertThat(event.getAsJsonObject("evaluatedValue").get("value").getAsString())
          .isEqualTo("hello");
      assertThat(event.getAsJsonObject("defaultValue").get("value").getAsString())
          .isEqualTo("fallback");
    }

    @Test
    void collapses_repeated_evaluations_into_a_count() {
      ConfigDirectorClient client = readyClient();
      client.getString("greeting", "fallback");
      client.getString("greeting", "fallback");
      client.getString("greeting", "fallback");

      JsonObject report = reportAfterClosing();

      assertThat(evaluationsOf(report)).hasSize(1);
      assertThat(evaluationsOf(report).get(0).getAsJsonObject().get("count").getAsInt()).isEqualTo(3);
    }

    @Test
    void reports_a_config_it_has_never_heard_of() {
      readyClient().getString("unknown-key", "fallback");

      JsonObject event = eventOf(reportAfterClosing(), 0);

      assertThat(event.get("evaluationReason").getAsString()).isEqualTo("config-state-missing");
      assertThat(event.get("usedDefault").getAsBoolean()).isTrue();
      assertThat(event.has("type")).isFalse();
    }

    @Test
    void identifies_the_sdk_and_the_key_that_collected_the_events() {
      readyClient().getString("greeting", "fallback");

      JsonObject report = reportAfterClosing();

      assertThat(report.get("serverSdkKey").getAsString()).isEqualTo("sdk-key");
      assertThat(report.getAsJsonObject("metaContext").get("sdkName").getAsString())
          .isEqualTo("java-server-sdk");
    }

    @Test
    void identifies_the_application_the_way_config_requests_do() {
      readyClient(options -> options.metadata("checkout", "4.5.6"))
          .getString("greeting", "fallback");

      JsonObject metaContext = reportAfterClosing().getAsJsonObject("metaContext");

      assertThat(metaContext.get("appName").getAsString()).isEqualTo("checkout");
      assertThat(metaContext.get("appVersion").getAsString()).isEqualTo("4.5.6");
    }

    @Test
    void leaves_the_application_out_when_none_was_configured() {
      readyClient().getString("greeting", "fallback");

      JsonObject metaContext = reportAfterClosing().getAsJsonObject("metaContext");

      assertThat(metaContext.keySet()).containsExactlyInAnyOrder("sdkName", "sdkVersion");
    }

    @Test
    void says_nothing_when_the_application_evaluated_nothing() {
      readyClient();

      client.close();

      assertThat(telemetryBodies).isEmpty();
    }

    @Test
    void hydrating_through_get_all_configs_is_not_an_evaluation() {
      // Intended for server-side rendering, where the values are handed to a client SDK that
      // reports its own evaluations.
      readyClient().getAllConfigs();

      client.close();

      assertThat(telemetryBodies).isEmpty();
    }
  }

  @Nested
  @DisplayName("the context an evaluation was made for")
  class Contexts {

    @Test
    void is_captured_and_named_on_the_event() {
      Context user = Context.builder().id("user-1").name("Ada").trait("plan", "pro").build();
      readyClient().getString("greeting", "fallback", user);

      JsonObject report = reportAfterClosing();

      JsonObject captured = contextsOf(report).get(0).getAsJsonObject();
      assertThat(captured.get("id").getAsString()).isEqualTo("user-1");
      assertThat(captured.get("name").getAsString()).isEqualTo("Ada");
      assertThat(captured.getAsJsonObject("traits").get("plan").getAsString()).isEqualTo("pro");
      assertThat(eventOf(report, 0).get("contextId").getAsString()).isEqualTo("user-1");
    }

    @Test
    void an_anonymous_one_is_neither_captured_nor_identified() {
      Context anonymous = Context.builder().id("user-1").anonymous(true).build();
      readyClient().getString("greeting", "fallback", anonymous);

      JsonObject report = reportAfterClosing();

      assertThat(contextsOf(report)).isEmpty();
      assertThat(eventOf(report, 0).has("contextId")).isFalse();
    }
  }

  @Nested
  @DisplayName("the kind of the context an evaluation was made for")
  class Kinds {

    @Test
    void contexts_of_two_kinds_sharing_an_identifier_are_both_captured_with_their_kinds() {
      ConfigDirectorClient client = readyClient();
      client.getString("greeting", "fallback", Context.builder().id("42").build());
      client.getString("greeting", "fallback", Context.builder().id("42").kind("device").build());

      JsonObject report = reportAfterClosing();

      assertThat(contextsOf(report)).hasSize(2);
      JsonObject user = contextsOf(report).get(0).getAsJsonObject();
      JsonObject device = contextsOf(report).get(1).getAsJsonObject();
      assertThat(user.get("id").getAsString()).isEqualTo("42");
      assertThat(user.get("kind").getAsString()).isEqualTo("user");
      assertThat(device.get("id").getAsString()).isEqualTo("42");
      assertThat(device.get("kind").getAsString()).isEqualTo("device");
    }

    @Test
    void an_omitted_kind_and_an_explicit_user_kind_are_one_captured_context() {
      ConfigDirectorClient client = readyClient();
      client.getString("greeting", "fallback", Context.builder().id("42").build());
      client.getString("greeting", "fallback", Context.builder().id("42").kind("user").build());

      JsonObject report = reportAfterClosing();

      assertThat(contextsOf(report)).hasSize(1);
      assertThat(contextsOf(report).get(0).getAsJsonObject().get("kind").getAsString())
          .isEqualTo("user");
    }

    @Test
    void evaluated_config_events_carry_the_kind_beside_the_identifier() {
      ConfigDirectorClient client = readyClient();
      client.getString("greeting", "fallback", Context.builder().id("42").build());
      client.getString("greeting", "fallback", Context.builder().id("42").kind("device").build());

      JsonObject report = reportAfterClosing();

      assertThat(eventOf(report, 0).get("contextId").getAsString()).isEqualTo("42");
      assertThat(eventOf(report, 0).get("contextKind").getAsString()).isEqualTo("user");
      assertThat(eventOf(report, 1).get("contextId").getAsString()).isEqualTo("42");
      assertThat(eventOf(report, 1).get("contextKind").getAsString()).isEqualTo("device");
    }

    @Test
    void an_event_without_an_identified_context_carries_no_kind() {
      ConfigDirectorClient client = readyClient();
      client.getString("greeting", "fallback", Context.builder().kind("device").build());
      client.getString(
          "greeting", "fallback", Context.builder().id("42").kind("tenant").anonymous(true).build());

      JsonObject report = reportAfterClosing();

      assertThat(evaluationsOf(report)).hasSize(1);
      assertThat(evaluationsOf(report).get(0).getAsJsonObject().get("count").getAsInt()).isEqualTo(2);
      assertThat(eventOf(report, 0).has("contextKind")).isFalse();
      assertThat(contextsOf(report)).isEmpty();
    }
  }

  @Nested
  @DisplayName("contexts ignored for an invalid kind")
  class InvalidContexts {

    @Test
    void are_counted_once_per_evaluation_with_a_sample_of_the_kind() {
      ConfigDirectorClient client = readyClient();
      client.getString("greeting", "fallback", withKind("Tenant"));
      client.getString("greeting", "fallback", withKind("Tenant"));
      client.getString("unknown-key", "fallback", withKind("Tenant"));

      JsonObject report = reportAfterClosing();

      assertThat(invalidContextsOf(report)).hasSize(1);
      JsonObject entry = invalidContextEntry(report, "Tenant");
      assertThat(entry.get("count").getAsInt()).isEqualTo(3);
      assertThat(entry.getAsJsonObject("event").get("reason").getAsString())
          .isEqualTo("invalid-context-kind");
      assertThat(entry.get("startTime").getAsString()).isNotBlank();
      assertThat(entry.get("endTime").getAsString()).isNotBlank();
    }

    @Test
    void are_reported_without_the_identifier_name_or_traits() {
      readyClient().getString("greeting", "fallback", withKind("Tenant"));

      JsonObject report = reportAfterClosing();

      assertThat(invalidContextEntry(report, "Tenant").getAsJsonObject("event").keySet())
          .containsExactlyInAnyOrder("reason", "kindSample");
      assertThat(contextsOf(report)).isEmpty();
      assertThat(eventOf(report, 0).has("contextId")).isFalse();
      assertThat(eventOf(report, 0).has("contextKind")).isFalse();
      assertThat(report.toString()).doesNotContain("user-1").doesNotContain("Ada");
    }

    @Test
    void count_every_watcher_re_evaluation() {
      List<String> first = Collections.synchronizedList(new ArrayList<>());
      List<String> second = Collections.synchronizedList(new ArrayList<>());
      ConfigDirectorClient client = startedClient(options -> {});
      client.watchString("greeting", "fallback", first::add, withKind("Tenant"));
      client.watchString("greeting", "fallback", second::add, withKind("Tenant"));
      client.initialize();
      await().atMost(TIMEOUT).until(() -> !first.isEmpty() && !second.isEmpty());

      JsonObject report = reportAfterClosing();

      assertThat(invalidContextEntry(report, "Tenant").get("count").getAsInt()).isEqualTo(2);
    }

    @Test
    void count_each_update_that_re_evaluates_the_same_watcher() throws InterruptedException {
      BlockingQueue<TestHttpServer.Session> live = new LinkedBlockingQueue<>();
      server =
          start(
              session -> {
                if (session.path().endsWith("/server/telemetry/v1")) {
                  telemetryBodies.add(session.bodyAsString());
                  session.respond(200, "Content-Length: 0", "Connection: close");
                  session.close();
                } else {
                  session.respondStreaming();
                  session.send("data: " + BUNDLE + "\n\n");
                  live.add(session);
                }
              });
      String url = server.url("/");
      client =
          ConfigDirector.client(
              "sdk-key",
              options ->
                  options.connection(
                      connection ->
                          connection.mode(ConnectionMode.STREAMING).url(url).timeout(TIMEOUT)));
      List<String> seen = Collections.synchronizedList(new ArrayList<>());
      client.watchString("greeting", "fallback", seen::add, withKind("Tenant"));
      client.initialize();
      await().atMost(TIMEOUT).until(() -> seen.size() == 1);

      live.take().send("data: " + BUNDLE + "\n\n");
      await().atMost(TIMEOUT).until(() -> seen.size() == 2);

      JsonObject report = reportAfterClosing();

      assertThat(invalidContextEntry(report, "Tenant").get("count").getAsInt()).isEqualTo(2);
    }

    @Test
    void count_get_all_configs_once_per_call_in_a_report_that_holds_nothing_else() {
      ConfigDirectorClient client = readyClient();
      client.getAllConfigs(withKind("Tenant"));
      client.getAllConfigs(withKind("Tenant"), List.of("greeting"));

      JsonObject report = reportAfterClosing();

      assertThat(evaluationsOf(report)).isEmpty();
      assertThat(contextsOf(report)).isEmpty();
      assertThat(invalidContextEntry(report, "Tenant").get("count").getAsInt()).isEqualTo(2);
    }

    @Test
    void count_get_all_configs_before_the_client_is_ready() {
      startedClient(options -> {}).getAllConfigs(withKind("Tenant"));

      JsonObject report = reportAfterClosing();

      assertThat(invalidContextEntry(report, "Tenant").get("count").getAsInt()).isEqualTo(1);
    }

    @Test
    void keep_twenty_kind_samples_and_count_the_rest_in_an_entry_without_one() {
      ConfigDirectorClient client = readyClient();
      IntStream.range(0, 25)
          .forEach(i -> client.getString("greeting", "fallback", withKind("Kind" + i)));
      client.getString("greeting", "fallback", withKind("Kind0"));
      client.getString("greeting", "fallback", withKind("Kind24"));

      JsonObject report = reportAfterClosing();

      assertThat(invalidContextsOf(report)).hasSize(21);
      assertThat(invalidContextEntry(report, "Kind0").get("count").getAsInt()).isEqualTo(2);
      assertThat(invalidContextEntry(report, "Kind19").get("count").getAsInt()).isEqualTo(1);
      List<JsonObject> unsampled = invalidContextEntriesWithoutSample(report);
      assertThat(unsampled).hasSize(1);
      assertThat(unsampled.get(0).get("count").getAsInt()).isEqualTo(6);
      assertThat(unsampled.get(0).getAsJsonObject("event").get("reason").getAsString())
          .isEqualTo("invalid-context-kind");
    }

    @Test
    void sample_a_long_kind_by_its_first_128_characters() {
      readyClient().getString("greeting", "fallback", withKind("k".repeat(200)));

      JsonObject report = reportAfterClosing();

      invalidContextEntry(report, "k".repeat(128));
      assertThat(invalidContextsOf(report)).hasSize(1);
    }

    @Test
    void start_afresh_after_each_report() {
      ConfigDirectorClient client = readyClient();
      client.getString("greeting", "fallback", withKind("Tenant"));
      await().atMost(Duration.ofSeconds(15)).until(() -> !telemetryBodies.isEmpty());
      client.getString("greeting", "fallback", withKind("Device"));

      client.close();
      await().atMost(TIMEOUT).until(() -> telemetryBodies.size() == 2);

      JsonObject second;
      synchronized (telemetryBodies) {
        second = JsonParser.parseString(telemetryBodies.get(1)).getAsJsonObject();
      }
      assertThat(invalidContextsOf(second)).hasSize(1);
      invalidContextEntry(second, "Device");
    }
  }

  @Nested
  @DisplayName("value IDs")
  class ValueIdentifiers {

    private List<ConfigEvaluatedEvent> evaluationsOfClient(ConfigDirectorClient client) {
      List<ConfigEvaluatedEvent> events = Collections.synchronizedList(new ArrayList<>());
      client.onConfigEvaluated(events::add);
      return events;
    }

    private String lastValueId(List<ConfigEvaluatedEvent> events) {
      synchronized (events) {
        return events.get(events.size() - 1).evaluation().valueId();
      }
    }

    @Test
    void a_server_selected_value_carries_the_server_id() {
      ConfigDirectorClient client = readyClient();
      List<ConfigEvaluatedEvent> events = evaluationsOfClient(client);

      client.getString("greeting", "fallback");

      assertThat(lastValueId(events)).isEqualTo("dv-1");
    }

    @Test
    void a_missing_config_gets_a_computed_id() {
      ConfigDirectorClient client = readyClient();
      List<ConfigEvaluatedEvent> events = evaluationsOfClient(client);

      client.getString("unknown-key", "fallback");

      assertThat(lastValueId(events)).isEqualTo(ValueIds.generate("fallback"));
    }

    @Test
    void a_default_used_after_a_type_mismatch_gets_a_computed_id() {
      ConfigDirectorClient client = readyClient();
      List<ConfigEvaluatedEvent> events = evaluationsOfClient(client);

      assertThat(client.getInteger("greeting", 42)).isEqualTo(42);

      assertThat(lastValueId(events)).isEqualTo(ValueIds.generate("42"));
    }

    @Test
    void a_json_default_is_digested_the_way_telemetry_reports_it() {
      // The same document must not be counted under two IDs depending on which side hashed it.
      ConfigDirectorClient client = readyClient();
      List<ConfigEvaluatedEvent> events = evaluationsOfClient(client);

      client.getJsonObject("unknown-key", Map.of("on", true));

      assertThat(lastValueId(events)).isEqualTo(ValueIds.generate("{\"on\":true}"));
    }
  }
}
