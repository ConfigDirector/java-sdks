package com.configdirector.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.TrackOptions;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrackedEventQueueTest {

  private final TrackedEventQueue queue = new TrackedEventQueue();

  private static TrackedEventAdmission admissionOf(String name, TrackOptions options) {
    return TrackedEventAdmission.of(name, null, options, Instant.EPOCH);
  }

  private void fillTheBatch() {
    for (int number = 1; number <= 1_000; number++) {
      queue.add(admissionOf("event-" + number, TrackOptions.builder().build()));
    }
  }

  private List<String> namesOfTheBatch() {
    return queue.takeBatch().stream().map(TrackedEventEntry::name).toList();
  }

  @Test
  void warns_only_about_the_limit_for_an_event_beyond_it_whose_value_would_be_left_out() {
    fillTheBatch();

    List<TrackedEventWarning> warnings =
        queue.add(admissionOf("late", TrackOptions.builder().value(Double.NaN).build()));

    assertThat(warnings).containsExactly(TrackedEventWarning.BATCH_ALREADY_FULL);
  }

  @Test
  void warns_about_a_name_outside_the_limit_when_the_batch_is_already_full() {
    fillTheBatch();

    List<TrackedEventWarning> warnings =
        queue.add(admissionOf("", TrackOptions.builder().build()));

    assertThat(warnings).containsExactly(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT);
  }

  @Test
  void warns_about_a_cause_once_until_the_batch_is_taken() {
    List<TrackedEventWarning> first = queue.add(admissionOf("", TrackOptions.builder().build()));
    List<TrackedEventWarning> second = queue.add(admissionOf("", TrackOptions.builder().build()));
    queue.takeBatch();
    List<TrackedEventWarning> third = queue.add(admissionOf("", TrackOptions.builder().build()));

    assertThat(first).containsExactly(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT);
    assertThat(second).isEmpty();
    assertThat(third).containsExactly(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT);
  }

  @Test
  void keeps_an_event_tracked_after_a_full_batch_was_taken() {
    fillTheBatch();
    queue.add(admissionOf("beyond-the-limit", TrackOptions.builder().build()));
    queue.takeBatch();

    queue.add(admissionOf("checkout-completed", TrackOptions.builder().build()));

    assertThat(namesOfTheBatch()).containsExactly("checkout-completed");
  }

  @Test
  void clearing_empties_the_batch() {
    queue.add(admissionOf("checkout-completed", TrackOptions.builder().build()));

    queue.clear();

    assertThat(namesOfTheBatch()).isEmpty();
  }
}
