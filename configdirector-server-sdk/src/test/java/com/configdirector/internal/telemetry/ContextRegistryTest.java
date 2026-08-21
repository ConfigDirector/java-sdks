package com.configdirector.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ContextRegistryTest {

  private static Context context(String id) {
    return Context.builder().id(id).build();
  }

  private static void add(ContextRegistry registry, String... ids) {
    for (String id : ids) {
      registry.add(id, context(id));
    }
  }

  @Nested
  @DisplayName("collecting contexts")
  class Collecting {

    @Test
    void keeps_the_contexts_it_is_given() {
      ContextRegistry registry = new ContextRegistry(10);
      add(registry, "a", "b");

      assertThat(registry.takeSnapshot().contexts())
          .containsExactly(context("a"), context("b"));
    }

    @Test
    void keeps_only_the_most_recent_context_for_an_id() {
      ContextRegistry registry = new ContextRegistry(10);
      registry.add("a", Context.builder().id("a").name("first").build());
      registry.add("a", Context.builder().id("a").name("second").build());

      assertThat(registry.takeSnapshot().contexts())
          .singleElement()
          .extracting(Context::name)
          .isEqualTo("second");
    }

    @Test
    void a_snapshot_starts_the_next_batch_over() {
      ContextRegistry registry = new ContextRegistry(10);
      add(registry, "a");

      registry.takeSnapshot();

      assertThat(registry.takeSnapshot().contexts()).isEmpty();
    }

    @Test
    void clearing_discards_the_contexts_and_the_dropped_count() {
      ContextRegistry registry = new ContextRegistry(1);
      add(registry, "a", "b");

      registry.clear();

      ContextRegistry.Snapshot snapshot = registry.takeSnapshot();
      assertThat(snapshot.contexts()).isEmpty();
      assertThat(snapshot.droppedCount()).isZero();
    }
  }

  @Nested
  @DisplayName("the limit")
  class Limit {

    @Test
    void evicts_the_oldest_context_once_full() {
      ContextRegistry registry = new ContextRegistry(2);
      add(registry, "a", "b", "c");

      ContextRegistry.Snapshot snapshot = registry.takeSnapshot();
      assertThat(snapshot.contexts()).containsExactly(context("b"), context("c"));
      assertThat(snapshot.droppedCount()).isEqualTo(1);
    }

    @Test
    void seeing_a_context_again_does_not_save_it_from_eviction() {
      // Re-inserting leaves the key where it was, which is how the other SDKs' Maps behave.
      ContextRegistry registry = new ContextRegistry(2);
      add(registry, "a", "b");
      add(registry, "a");
      add(registry, "c");

      assertThat(registry.takeSnapshot().contexts()).containsExactly(context("b"), context("c"));
    }
  }
}
