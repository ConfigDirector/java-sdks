package com.configdirector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigEvaluatedEvent;
import com.configdirector.ConnectionMode;
import com.configdirector.Context;
import com.configdirector.internal.telemetry.ValueIds;
import com.configdirector.testing.TestHttpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
            options ->
                options.connection(
                    connection -> connection.mode(ConnectionMode.POLLING).url(url).timeout(TIMEOUT)));
    client.initialize();
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
