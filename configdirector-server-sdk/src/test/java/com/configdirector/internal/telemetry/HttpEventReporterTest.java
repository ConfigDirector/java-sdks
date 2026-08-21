package com.configdirector.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import com.configdirector.Context;
import com.configdirector.EvaluationReason;
import com.configdirector.internal.transport.HttpClient;
import com.configdirector.testing.TestHttpServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class HttpEventReporterTest {

  private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");
  private static final Instant END = Instant.parse("2026-08-21T10:00:30Z");

  private final List<TestHttpServer.Session> requests = Collections.synchronizedList(new ArrayList<>());
  private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
  private final HttpClient http = new HttpClient();

  private TestHttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    http.close();
  }

  private HttpEventReporter reporterAgainst(int status) {
    return reporterAgainst(status, "");
  }

  private HttpEventReporter reporterAgainst(int status, String body) {
    server =
        start(
            session -> {
              requests.add(session);
              bodies.add(session.bodyAsString());
              session.respond(status, "Content-Length: " + body.length(), "Connection: close");
              session.send(body);
              session.close();
            });
    return reporterFor(server.url("/"));
  }

  private HttpEventReporter reporterFor(String baseUrl) {
    return new HttpEventReporter(
        "sdk-key", baseUrl, LoggerFactory.getLogger(HttpEventReporterTest.class), http);
  }

  private static TestHttpServer start(Consumer<TestHttpServer.Session> handler) {
    try {
      return TestHttpServer.start(handler);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static EvaluatedConfigEvent event(String key, Context context) {
    return EvaluatedConfigEvent.of(
        new ConfigEvaluation(key, "hello", false, EvaluationReason.FOUND_MATCH, "server-id", context),
        "fallback",
        ConfigType.STRING,
        context == null ? null : context.id());
  }

  private static EventReport reportOf(EvaluatedConfigEvent... events) {
    return new EventReport(
        AggregatedEvent.aggregate(new EventQueue.Snapshot(START, END, List.of(events), 0)),
        0,
        List.of(),
        0);
  }

  private JsonObject sentPayload() {
    synchronized (bodies) {
      return JsonParser.parseString(bodies.get(bodies.size() - 1)).getAsJsonObject();
    }
  }

  private static <T> List<T> snapshot(List<T> shared) {
    synchronized (shared) {
      return new ArrayList<>(shared);
    }
  }

  @Nested
  @DisplayName("the request")
  class Request {

    @Test
    void posts_to_the_telemetry_endpoint() {
      reporterAgainst(200).report(reportOf(event("a", null)));

      TestHttpServer.Session request = snapshot(requests).get(0);
      assertThat(request.method()).isEqualTo("POST");
      assertThat(request.path()).isEqualTo("/server/telemetry/v1");
    }

    @Test
    void keeps_every_segment_of_a_proxy_base_url() {
      server =
          start(
              session -> {
                requests.add(session);
                session.respond(200, "Content-Length: 0", "Connection: close");
                session.close();
              });

      reporterFor(server.url("/configdirector")).report(reportOf(event("a", null)));

      assertThat(snapshot(requests).get(0).path())
          .isEqualTo("/configdirector/server/telemetry/v1");
    }

    @Test
    void identifies_the_sdk_and_sends_json() {
      reporterAgainst(200).report(reportOf(event("a", null)));

      TestHttpServer.Session request = snapshot(requests).get(0);
      assertThat(request.header("User-Agent")).startsWith("java-server-sdk/");
      assertThat(request.header("Content-Type")).isEqualTo("application/json");
    }
  }

  @Nested
  @DisplayName("the payload")
  class Payload {

    @Test
    void carries_the_sdk_key_and_the_sdk_identity() {
      reporterAgainst(200).report(reportOf(event("a", null)));

      JsonObject payload = sentPayload();
      assertThat(payload.get("serverSdkKey").getAsString()).isEqualTo("sdk-key");
      JsonObject metaContext = payload.getAsJsonObject("metaContext");
      assertThat(metaContext.get("sdkName").getAsString()).isEqualTo("java-server-sdk");
      assertThat(metaContext.get("sdkVersion").getAsString()).isNotBlank();
    }

    @Test
    void sends_each_aggregated_evaluation_with_its_window_and_count() {
      reporterAgainst(200).report(reportOf(event("a", null), event("a", null)));

      JsonObject aggregated =
          sentPayload()
              .getAsJsonObject("aggregatedEvents")
              .getAsJsonArray("evaluatedConfig")
              .get(0)
              .getAsJsonObject();
      assertThat(aggregated.get("count").getAsInt()).isEqualTo(2);
      assertThat(aggregated.get("startTime").getAsString()).isEqualTo("2026-08-21T10:00:00.000Z");
      assertThat(aggregated.get("endTime").getAsString()).isEqualTo("2026-08-21T10:00:30.000Z");
      assertThat(aggregated.getAsJsonObject("event").get("key").getAsString()).isEqualTo("a");
    }

    @Test
    void sends_the_captured_contexts() {
      Context user =
          Context.builder().id("user-1").name("Ada").trait("plan", "pro").trait("seats", 3).build();
      EventReport report = new EventReport(List.of(), 0, List.of(user), 0);

      reporterAgainst(200).report(report);

      JsonObject captured =
          sentPayload()
              .getAsJsonObject("discreteEvents")
              .getAsJsonArray("capturedContexts")
              .get(0)
              .getAsJsonObject();
      assertThat(captured.get("id").getAsString()).isEqualTo("user-1");
      assertThat(captured.get("name").getAsString()).isEqualTo("Ada");
      assertThat(captured.getAsJsonObject("traits").get("plan").getAsString()).isEqualTo("pro");
      assertThat(captured.getAsJsonObject("traits").get("seats").getAsInt()).isEqualTo(3);
    }

    @Test
    void omits_context_fields_that_were_not_supplied() {
      EventReport report =
          new EventReport(List.of(), 0, List.of(Context.builder().id("user-1").build()), 0);

      reporterAgainst(200).report(report);

      JsonObject captured =
          sentPayload()
              .getAsJsonObject("discreteEvents")
              .getAsJsonArray("capturedContexts")
              .get(0)
              .getAsJsonObject();
      assertThat(captured.keySet()).containsExactly("id");
    }

    @Test
    void always_sends_both_dropped_counts() {
      reporterAgainst(200).report(reportOf(event("a", null)));

      JsonObject dropped = sentPayload().getAsJsonObject("droppedEvents");
      assertThat(dropped.get("evaluatedConfig").getAsInt()).isZero();
      assertThat(dropped.get("capturedContexts").getAsInt()).isZero();
    }

    @Test
    void reports_what_was_dropped() {
      reporterAgainst(200).report(new EventReport(List.of(), 7, List.of(), 3));

      JsonObject dropped = sentPayload().getAsJsonObject("droppedEvents");
      assertThat(dropped.get("evaluatedConfig").getAsInt()).isEqualTo(7);
      assertThat(dropped.get("capturedContexts").getAsInt()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("an empty report")
  class Empty {

    @Test
    void is_not_worth_a_request() {
      HttpEventReporter reporter = reporterAgainst(200);

      ReporterResponse response = reporter.report(new EventReport(List.of(), 0, List.of(), 0));

      assertThat(response.success()).isTrue();
      assertThat(server.connectionCount()).isZero();
    }

    @Test
    void a_report_holding_only_dropped_counts_is_still_sent() {
      reporterAgainst(200).report(new EventReport(List.of(), 5, List.of(), 0));

      assertThat(snapshot(bodies)).hasSize(1);
    }
  }

  @Nested
  @DisplayName("failures")
  class Failures {

    @Test
    void a_successful_status_reports_success() {
      ReporterResponse response = reporterAgainst(200).report(reportOf(event("a", null)));

      assertThat(response.success()).isTrue();
      assertThat(response.fatal()).isFalse();
    }

    @Test
    void a_client_error_is_fatal() {
      ReporterResponse response = reporterAgainst(401).report(reportOf(event("a", null)));

      assertThat(response.success()).isFalse();
      assertThat(response.fatal()).isTrue();
    }

    @Test
    void a_server_error_is_worth_retrying() {
      ReporterResponse response = reporterAgainst(503).report(reportOf(event("a", null)));

      assertThat(response.success()).isFalse();
      assertThat(response.fatal()).isFalse();
    }

    @Test
    void stops_sending_after_a_fatal_response() {
      HttpEventReporter reporter = reporterAgainst(401);
      reporter.report(reportOf(event("a", null)));

      ReporterResponse response = reporter.report(reportOf(event("b", null)));

      assertThat(response.fatal()).isTrue();
      assertThat(snapshot(bodies)).hasSize(1);
    }

    @Test
    void a_connection_error_is_worth_retrying() {
      server = start(TestHttpServer.Session::close);

      ReporterResponse response = reporterFor(server.url("/")).report(reportOf(event("a", null)));

      assertThat(response.success()).isFalse();
      assertThat(response.fatal()).isFalse();
    }

    @Test
    void an_unusable_url_is_fatal() {
      ReporterResponse response = reporterFor("not-a-url").report(reportOf(event("a", null)));

      assertThat(response.fatal()).isTrue();
    }

    @Test
    void gives_up_on_a_request_sooner_than_the_transport_does() {
      assertThat(HttpEventReporter.REQUEST_TIMEOUT).isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
  }

  @Nested
  @DisplayName("the value the server reads back")
  class Values {

    @Test
    void a_json_document_is_reported_by_id_rather_than_inline() {
      EvaluatedConfigEvent json =
          EvaluatedConfigEvent.of(
                  new ConfigEvaluation(
                      "a", Map.of("on", true), false, EvaluationReason.FOUND_MATCH, null, null),
                  Map.of("on", false),
                  ConfigType.JSON,
                  null)
              .compacted();

      reporterAgainst(200).report(reportOf(json));

      JsonObject reported =
          sentPayload()
              .getAsJsonObject("aggregatedEvents")
              .getAsJsonArray("evaluatedConfig")
              .get(0)
              .getAsJsonObject()
              .getAsJsonObject("event");
      assertThat(reported.getAsJsonObject("evaluatedValue").get("valueId").getAsString())
          .isEqualTo(ValueIds.generate("{\"on\":true}"));
      assertThat(reported.getAsJsonObject("defaultValue").has("value")).isFalse();
    }
  }
}
