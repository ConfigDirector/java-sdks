package com.configdirector.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigEvaluation;
import com.configdirector.EvaluationReason;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventQueueTest {

  private static EvaluatedConfigEvent event(String key) {
    return EvaluatedConfigEvent.of(
        new ConfigEvaluation(key, "hello", false, EvaluationReason.FOUND_MATCH, null, null),
        "fallback",
        null,
        null);
  }

  private static List<String> keysOf(EventQueue.Snapshot snapshot) {
    return snapshot.events().stream().map(EvaluatedConfigEvent::key).toList();
  }

  @Nested
  @DisplayName("collecting")
  class Collecting {

    @Test
    void takes_a_snapshot_of_what_was_pushed() {
      EventQueue queue = new EventQueue(10);
      queue.push(event("a"));
      queue.push(event("b"));

      assertThat(keysOf(queue.takeSnapshot())).containsExactly("a", "b");
    }

    @Test
    void a_snapshot_empties_the_queue() {
      EventQueue queue = new EventQueue(10);
      queue.push(event("a"));

      queue.takeSnapshot();

      assertThat(queue.takeSnapshot().events()).isEmpty();
    }

    @Test
    void clearing_discards_the_events_and_the_dropped_count() {
      EventQueue queue = new EventQueue(1);
      queue.push(event("a"));
      queue.push(event("b"));

      queue.clear();

      EventQueue.Snapshot snapshot = queue.takeSnapshot();
      assertThat(snapshot.events()).isEmpty();
      assertThat(snapshot.droppedCount()).isZero();
    }
  }

  @Nested
  @DisplayName("the limit")
  class Limit {

    @Test
    void drops_the_oldest_events_once_full() {
      EventQueue queue = new EventQueue(2);
      queue.push(event("a"));
      queue.push(event("b"));
      queue.push(event("c"));

      EventQueue.Snapshot snapshot = queue.takeSnapshot();
      assertThat(keysOf(snapshot)).containsExactly("b", "c");
      assertThat(snapshot.droppedCount()).isEqualTo(1);
    }

    @Test
    void the_dropped_count_starts_over_after_a_snapshot() {
      EventQueue queue = new EventQueue(1);
      queue.push(event("a"));
      queue.push(event("b"));

      queue.takeSnapshot();
      queue.push(event("c"));

      assertThat(queue.takeSnapshot().droppedCount()).isZero();
    }
  }

  @Nested
  @DisplayName("the window a snapshot covers")
  class Window {

    @Test
    void starts_at_the_first_event_and_ends_at_the_snapshot() {
      EventQueue queue = new EventQueue(10);
      Instant before = Instant.now();
      queue.push(event("a"));

      EventQueue.Snapshot snapshot = queue.takeSnapshot();

      assertThat(snapshot.startTime()).isBetween(before, snapshot.endTime());
      assertThat(snapshot.endTime()).isAfterOrEqualTo(snapshot.startTime());
    }

    @Test
    void an_empty_snapshot_is_not_a_zero_length_window() {
      EventQueue.Snapshot snapshot = new EventQueue(10).takeSnapshot();

      assertThat(snapshot.startTime()).isEqualTo(snapshot.endTime());
    }

    @Test
    void starts_over_with_the_next_batch() {
      EventQueue queue = new EventQueue(10);
      queue.push(event("a"));
      Instant firstEnd = queue.takeSnapshot().endTime();
      queue.push(event("b"));

      assertThat(queue.takeSnapshot().startTime()).isAfterOrEqualTo(firstEnd);
    }
  }

  @Nested
  @DisplayName("under concurrency")
  class Concurrency {

    @Test
    void every_push_lands() throws Exception {
      EventQueue queue = new EventQueue(1_000);
      int threads = 8;
      int perThread = 100;
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(threads);

      for (int index = 0; index < threads; index++) {
        Thread pusher =
            new Thread(
                () -> {
                  try {
                    start.await();
                    for (int count = 0; count < perThread; count++) {
                      queue.push(event("a"));
                    }
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  } finally {
                    finished.countDown();
                  }
                });
        pusher.setDaemon(true);
        pusher.start();
      }

      start.countDown();
      assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();

      EventQueue.Snapshot snapshot = queue.takeSnapshot();
      assertThat(snapshot.events()).hasSize(threads * perThread);
      assertThat(snapshot.droppedCount()).isZero();
    }
  }
}
