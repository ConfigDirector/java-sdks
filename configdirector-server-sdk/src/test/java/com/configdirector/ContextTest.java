package com.configdirector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ContextTest {

  @Nested
  @DisplayName("building")
  class Building {

    @Test
    void carries_every_field() {
      Context context =
          Context.builder().id("u1").name("Ada").trait("plan", "pro").anonymous(true).build();

      assertThat(context.id()).isEqualTo("u1");
      assertThat(context.name()).isEqualTo("Ada");
      assertThat(context.traits()).containsExactly(Map.entry("plan", "pro"));
      assertThat(context.anonymous()).isTrue();
    }

    @Test
    void defaults_to_absent_fields() {
      Context context = Context.builder().build();

      assertThat(context.id()).isNull();
      assertThat(context.name()).isNull();
      assertThat(context.traits()).isNull();
      assertThat(context.anonymous()).isFalse();
    }

    @Test
    void the_empty_context_carries_nothing() {
      assertThat(Context.empty().id()).isNull();
      assertThat(Context.empty().traits()).isNull();
    }

    @Test
    void traits_replaces_rather_than_merges() {
      Context context =
          Context.builder().trait("a", 1).traits(Map.of("b", 2)).build();

      assertThat(context.traits()).containsOnlyKeys("b");
    }

    @Test
    void a_null_trait_map_clears_the_traits() {
      Context context = Context.builder().trait("a", 1).traits(null).build();

      assertThat(context.traits()).isNull();
    }

    @Test
    void accepts_a_trait_whose_value_is_null() {
      // Map.copyOf would reject this, but a JSON null is a legitimate trait value.
      Map<String, Object> traits = new HashMap<>();
      traits.put("optional", null);

      Context context = Context.builder().traits(traits).build();

      assertThat(context.traits()).containsKey("optional");
      assertThat(context.traits().get("optional")).isNull();
    }
  }

  @Nested
  @DisplayName("isolation")
  class Isolation {

    @Test
    void a_later_change_to_the_callers_map_does_not_leak_in() {
      Map<String, Object> traits = new HashMap<>();
      traits.put("plan", "free");
      Context context = Context.builder().traits(traits).build();

      traits.put("plan", "pro");

      assertThat(context.traits()).containsEntry("plan", "free");
    }

    @Test
    void reusing_the_builder_does_not_alter_an_already_built_context() {
      Context.Builder builder = Context.builder().trait("plan", "free");
      Context first = builder.build();

      builder.trait("plan", "pro");
      Context second = builder.build();

      assertThat(first.traits()).containsEntry("plan", "free");
      assertThat(second.traits()).containsEntry("plan", "pro");
    }

    @Test
    void the_returned_traits_cannot_be_modified() {
      Context context = Context.builder().trait("plan", "free").build();

      assertThatExceptionOfType(UnsupportedOperationException.class)
          .isThrownBy(() -> context.traits().put("plan", "pro"));
    }
  }

  @Nested
  @DisplayName("value semantics")
  class ValueSemantics {

    @Test
    void equal_contexts_are_equal_and_hash_alike() {
      Context first = Context.builder().id("u1").name("Ada").trait("plan", "pro").build();
      Context second = Context.builder().id("u1").name("Ada").trait("plan", "pro").build();

      assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void every_field_takes_part_in_equality() {
      Context base = Context.builder().id("u1").name("Ada").trait("plan", "pro").build();

      assertThat(base).isNotEqualTo(Context.builder().id("u2").name("Ada").trait("plan", "pro").build());
      assertThat(base).isNotEqualTo(Context.builder().id("u1").name("Bob").trait("plan", "pro").build());
      assertThat(base).isNotEqualTo(Context.builder().id("u1").name("Ada").trait("plan", "free").build());
      assertThat(base)
          .isNotEqualTo(
              Context.builder().id("u1").name("Ada").trait("plan", "pro").anonymous(true).build());
    }

    @Test
    void is_not_equal_to_other_types_or_null() {
      Context context = Context.builder().id("u1").build();

      assertThat(context).isNotEqualTo(null).isNotEqualTo("u1").isEqualTo(context);
    }

    @Test
    void describes_itself_for_logging() {
      Context context = Context.builder().id("u1").name("Ada").build();

      assertThat(context.toString()).contains("u1").contains("Ada");
    }
  }
}
