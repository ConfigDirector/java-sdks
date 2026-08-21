package com.configdirector.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigEvaluation;
import com.configdirector.EvaluationReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AggregatedEventTest {

  private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");
  private static final Instant END = Instant.parse("2026-08-21T10:00:30Z");

  private static EvaluatedConfigEvent event(String key) {
    return EvaluatedConfigEvent.of(
        new ConfigEvaluation(key, "hello", false, EvaluationReason.FOUND_MATCH, null, null),
        "fallback",
        null,
        null);
  }

  private static EventQueue.Snapshot snapshotOf(EvaluatedConfigEvent... events) {
    return new EventQueue.Snapshot(START, END, List.of(events), 0);
  }

  @Nested
  @DisplayName("aggregating a snapshot")
  class Aggregating {

    @Test
    void collapses_identical_events_into_one_entry_with_a_count() {
      List<AggregatedEvent> aggregated =
          AggregatedEvent.aggregate(snapshotOf(event("a"), event("a"), event("a")));

      assertThat(aggregated).singleElement().extracting(AggregatedEvent::count).isEqualTo(3);
    }

    @Test
    void keeps_events_that_differ_apart() {
      List<AggregatedEvent> aggregated =
          AggregatedEvent.aggregate(snapshotOf(event("a"), event("b"), event("a")));

      assertThat(aggregated).extracting(AggregatedEvent::count).containsExactly(2, 1);
      assertThat(aggregated).extracting(entry -> entry.event().key()).containsExactly("a", "b");
    }

    @Test
    void every_entry_carries_the_window_the_snapshot_covers() {
      List<AggregatedEvent> aggregated = AggregatedEvent.aggregate(snapshotOf(event("a"), event("b")));

      assertThat(aggregated)
          .allSatisfy(
              entry -> {
                assertThat(entry.startTime()).isEqualTo(START);
                assertThat(entry.endTime()).isEqualTo(END);
              });
    }

    @Test
    void aggregating_nothing_produces_nothing() {
      assertThat(AggregatedEvent.aggregate(snapshotOf())).isEmpty();
    }
  }

  @Nested
  @DisplayName("the wire form")
  class Wire {

    @Test
    void carries_the_window_the_count_and_the_event() {
      Map<String, Object> wire =
          AggregatedEvent.aggregate(snapshotOf(event("a"), event("a"))).get(0).toWire();

      assertThat(wire)
          .containsEntry("startTime", "2026-08-21T10:00:00.000Z")
          .containsEntry("endTime", "2026-08-21T10:00:30.000Z")
          .containsEntry("count", 2);
      assertThat(wire.get("event")).isEqualTo(event("a").toWire());
    }

    @Test
    void writes_timestamps_to_the_millisecond_in_utc() {
      EventQueue.Snapshot snapshot =
          new EventQueue.Snapshot(
              Instant.parse("2026-08-21T10:00:00.123456Z"), END, List.of(event("a")), 0);

      assertThat(AggregatedEvent.aggregate(snapshot).get(0).toWire())
          .containsEntry("startTime", "2026-08-21T10:00:00.123Z");
    }
  }
}
