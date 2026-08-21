package com.configdirector.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import com.configdirector.Context;
import com.configdirector.EvaluationReason;
import com.configdirector.internal.transport.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TelemetryCollectorTest {

  // Long enough that nothing flushes on its own unless a test asks for it.
  private static final Duration NEVER = Duration.ofMinutes(10);

  private final FakeEventReporter reporter = new FakeEventReporter();
  private final HttpClient http = new HttpClient();

  private TelemetryCollector collector;

  @AfterEach
  void tearDown() {
    if (collector != null) {
      collector.close();
    }
    http.close();
  }

  private TelemetryCollector collectorWith(int queueLimit, Duration initialDelay) {
    collector =
        new TelemetryCollector(
            new TelemetryCollectorOptions(
                "sdk-key",
                "https://api.test",
                LoggerFactory.getLogger(TelemetryCollectorTest.class),
                http,
                queueLimit,
                NEVER,
                initialDelay),
            reporter);
    return collector;
  }

  private TelemetryCollector collector() {
    return collectorWith(1_000, NEVER);
  }

  private static ConfigEvaluation evaluation(String key, Object value, Context context) {
    return new ConfigEvaluation(key, value, false, EvaluationReason.FOUND_MATCH, null, context);
  }

  private static void record(TelemetryCollector collector, String key) {
    collector.recordEvaluation(evaluation(key, "hello", null), "fallback", ConfigType.STRING);
  }

  private static List<String> keysOf(EventReport report) {
    return report.evaluations().stream().map(entry -> entry.event().key()).toList();
  }

  @Nested
  @DisplayName("recording evaluations")
  class Recording {

    @Test
    void reports_what_was_recorded_on_flush() {
      TelemetryCollector collector = collector();
      record(collector, "a");

      collector.flush();

      assertThat(keysOf(reporter.lastReport())).containsExactly("a");
    }

    @Test
    void collapses_identical_evaluations_into_a_count() {
      TelemetryCollector collector = collector();
      record(collector, "a");
      record(collector, "a");

      collector.flush();

      assertThat(reporter.lastReport().evaluations())
          .singleElement()
          .extracting(AggregatedEvent::count)
          .isEqualTo(2);
    }

    @Test
    void keeps_evaluations_of_different_configs_apart() {
      TelemetryCollector collector = collector();
      record(collector, "a");
      record(collector, "b");

      collector.flush();

      assertThat(keysOf(reporter.lastReport())).containsExactly("a", "b");
    }

    @Test
    void a_flush_does_not_resend_what_was_already_reported() {
      TelemetryCollector collector = collector();
      record(collector, "a");
      collector.flush();

      record(collector, "b");
      collector.flush();

      assertThat(keysOf(reporter.lastReport())).containsExactly("b");
    }

    @Test
    void sends_nothing_when_there_is_nothing_to_report() {
      collector().flush();

      assertThat(reporter.reportCount()).isZero();
    }

    @Test
    void reports_a_value_too_large_to_send_inline_by_its_id() {
      String oversized = "x".repeat(TelemetryValue.CONFIG_VALUE_MAX_LENGTH + 1);
      TelemetryCollector collector = collector();
      collector.recordEvaluation(
          evaluation("a", oversized, null), "fallback", ConfigType.STRING);

      collector.flush();

      TelemetryValue reported = reporter.lastReport().evaluations().get(0).event().evaluatedValue();
      assertThat(reported.value()).isNull();
      assertThat(reported.valueId()).isEqualTo(ValueIds.generate(oversized));
    }

    @Test
    void reports_how_many_evaluations_were_dropped() {
      // 70% of the queue limit belongs to evaluations, the rest to the contexts they were made
      // against.
      TelemetryCollector collector = collectorWith(100, NEVER);
      for (int index = 0; index < 72; index++) {
        record(collector, "a");
      }

      collector.flush();

      assertThat(reporter.lastReport().droppedEvaluations()).isEqualTo(2);
      assertThat(reporter.lastReport().evaluations().get(0).count()).isEqualTo(70);
    }
  }

  @Nested
  @DisplayName("capturing contexts")
  class Contexts {

    private final Context user = Context.builder().id("user-1").name("Ada").build();

    @Test
    void captures_the_context_an_evaluation_was_made_against() {
      TelemetryCollector collector = collector();
      collector.recordEvaluation(evaluation("a", "hello", user), "fallback", ConfigType.STRING);

      collector.flush();

      assertThat(reporter.lastReport().contexts()).containsExactly(user);
      assertThat(reporter.lastReport().evaluations().get(0).event().contextId()).isEqualTo("user-1");
    }

    @Test
    void captures_each_distinct_context_once() {
      TelemetryCollector collector = collector();
      Context other = Context.builder().id("user-2").build();
      collector.recordEvaluation(evaluation("a", "hello", user), "fallback", ConfigType.STRING);
      collector.recordEvaluation(evaluation("a", "hello", user), "fallback", ConfigType.STRING);
      collector.recordEvaluation(evaluation("a", "hello", other), "fallback", ConfigType.STRING);

      collector.flush();

      assertThat(reporter.lastReport().contexts()).containsExactly(user, other);
    }

    @Test
    void ignores_a_context_without_an_id() {
      TelemetryCollector collector = collector();
      Context unidentified = Context.builder().name("Ada").build();
      collector.recordEvaluation(
          evaluation("a", "hello", unidentified), "fallback", ConfigType.STRING);

      collector.flush();

      assertThat(reporter.lastReport().contexts()).isEmpty();
      assertThat(reporter.lastReport().evaluations().get(0).event().contextId()).isNull();
    }

    @Test
    void an_anonymous_context_is_neither_captured_nor_identified() {
      // It still targets rules, but it is not persisted and must not be identifiable in what is
      // reported.
      TelemetryCollector collector = collector();
      Context anonymous = Context.builder().id("user-1").anonymous(true).build();
      collector.recordEvaluation(evaluation("a", "hello", anonymous), "fallback", ConfigType.STRING);

      collector.flush();

      assertThat(reporter.lastReport().contexts()).isEmpty();
      assertThat(reporter.lastReport().evaluations().get(0).event().contextId()).isNull();
    }

    @Test
    void a_flush_does_not_resend_contexts() {
      TelemetryCollector collector = collector();
      collector.recordEvaluation(evaluation("a", "hello", user), "fallback", ConfigType.STRING);
      collector.flush();

      record(collector, "b");
      collector.flush();

      assertThat(reporter.lastReport().contexts()).isEmpty();
    }

    @Test
    void reports_how_many_contexts_were_dropped() {
      // 30% of the queue limit belongs to contexts.
      TelemetryCollector collector = collectorWith(100, NEVER);
      for (int index = 0; index < 32; index++) {
        Context each = Context.builder().id("user-" + index).build();
        collector.recordEvaluation(evaluation("a", "hello", each), "fallback", ConfigType.STRING);
      }

      collector.flush();

      assertThat(reporter.lastReport().droppedContexts()).isEqualTo(2);
      assertThat(reporter.lastReport().contexts()).hasSize(30);
    }
  }

  @Nested
  @DisplayName("the flush interval")
  class Interval {

    @Test
    void flushes_on_its_own_without_being_asked() {
      TelemetryCollector collector = collectorWith(1_000, Duration.ofMillis(50));
      record(collector, "a");

      await().atMost(Duration.ofSeconds(5)).until(() -> reporter.reportCount() >= 1);
      assertThat(keysOf(reporter.lastReport())).containsExactly("a");
    }

    @Test
    void keeps_flushing_after_the_first_report() {
      collector =
          new TelemetryCollector(
              new TelemetryCollectorOptions(
                  "sdk-key",
                  "https://api.test",
                  LoggerFactory.getLogger(TelemetryCollectorTest.class),
                  http,
                  1_000,
                  Duration.ofMillis(50),
                  Duration.ofMillis(50)),
              reporter);
      record(collector, "a");
      await().atMost(Duration.ofSeconds(5)).until(() -> reporter.reportCount() >= 1);

      record(collector, "b");

      await().atMost(Duration.ofSeconds(5)).until(() -> reporter.reportCount() >= 2);
      assertThat(keysOf(reporter.lastReport())).containsExactly("b");
    }

    @Test
    void an_idle_collector_makes_no_requests() {
      collectorWith(1_000, Duration.ofMillis(50));

      await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> reporter.reportCount() == 0);
    }
  }

  @Nested
  @DisplayName("a fatal response")
  class FatalErrors {

    @Test
    void stops_collection_for_good() {
      TelemetryCollector collector = collector();
      reporter.respondWith(ReporterResponse.FATAL);
      record(collector, "a");
      collector.flush();

      record(collector, "b");
      collector.flush();

      assertThat(reporter.reportCount()).isEqualTo(1);
    }

    @Test
    void discards_what_was_already_collected() {
      TelemetryCollector collector = collector();
      record(collector, "a");
      reporter.respondWith(ReporterResponse.FATAL);
      collector.flush();
      reporter.respondWith(ReporterResponse.SUCCEEDED);

      collector.close();

      assertThat(reporter.reportCount()).isEqualTo(1);
    }

    @Test
    void a_failure_worth_retrying_leaves_collection_running() {
      TelemetryCollector collector = collector();
      reporter.respondWith(ReporterResponse.FAILED);
      record(collector, "a");
      collector.flush();

      record(collector, "b");
      collector.flush();

      assertThat(reporter.reportCount()).isEqualTo(2);
    }

    @Test
    void a_reporter_that_raises_does_not_stop_collection() {
      TelemetryCollector collector = collector();
      reporter.throwOnReport(new IllegalStateException("boom"));
      record(collector, "a");
      collector.flush();

      record(collector, "b");
      collector.flush();

      assertThat(reporter.reportCount()).isEqualTo(2);
    }

    @Test
    void the_flush_schedule_stops_itself_after_a_fatal_response() {
      reporter.respondWith(ReporterResponse.FATAL);
      collector =
          new TelemetryCollector(
              new TelemetryCollectorOptions(
                  "sdk-key",
                  "https://api.test",
                  LoggerFactory.getLogger(TelemetryCollectorTest.class),
                  http,
                  1_000,
                  Duration.ofMillis(50),
                  Duration.ofMillis(50)),
              reporter);
      record(collector, "a");

      await().atMost(Duration.ofSeconds(5)).until(() -> reporter.reportCount() >= 1);
      record(collector, "b");
      await()
          .during(Duration.ofMillis(300))
          .atMost(Duration.ofSeconds(2))
          .until(() -> reporter.reportCount() == 1);
    }
  }

  @Nested
  @DisplayName("closing")
  class Closing {

    @Test
    void reports_what_is_left() {
      TelemetryCollector collector = collector();
      record(collector, "a");

      collector.close();

      assertThat(keysOf(reporter.lastReport())).containsExactly("a");
    }

    @Test
    void stops_collecting() {
      TelemetryCollector collector = collector();
      collector.close();

      record(collector, "a");
      collector.flush();

      assertThat(reporter.reportCount()).isZero();
    }

    @Test
    void closing_twice_reports_once() {
      TelemetryCollector collector = collector();
      record(collector, "a");

      collector.close();
      collector.close();

      assertThat(reporter.reportCount()).isEqualTo(1);
    }

    @Test
    void survives_the_closing_report_failing() {
      TelemetryCollector collector = collector();
      reporter.throwOnReport(new IllegalStateException("boom"));
      record(collector, "a");

      collector.close();

      assertThat(reporter.reportCount()).isEqualTo(1);
    }

    @Test
    void an_evaluation_of_a_json_value_survives_the_round_trip() {
      TelemetryCollector collector = collector();
      collector.recordEvaluation(
          evaluation("a", Map.of("on", true), null), Map.of("on", false), ConfigType.JSON);

      collector.close();

      EvaluatedConfigEvent reported = reporter.lastReport().evaluations().get(0).event();
      assertThat(reported.evaluatedValue().valueId()).isEqualTo(ValueIds.generate("{\"on\":true}"));
      assertThat(reported.defaultValue().valueId()).isEqualTo(ValueIds.generate("{\"on\":false}"));
    }
  }
}
