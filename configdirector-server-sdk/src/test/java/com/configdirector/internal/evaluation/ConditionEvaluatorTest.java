package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.Context;
import com.configdirector.Metadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConditionEvaluatorTest {

  private final ConditionEvaluator evaluator = new ConditionEvaluator();

  private static Condition condition(
      String attribute, String operator, String targetType, String trait, String... targets) {
    return new Condition("c", attribute, operator, targetType, List.of(targets), trait);
  }

  private static EvaluationContext contextWith(Context context) {
    return new EvaluationContext(context, null);
  }

  private static EvaluationContext withTraits(Map<String, Object> traits) {
    return contextWith(Context.builder().id("u1").traits(traits).build());
  }

  private boolean evaluate(Condition condition, EvaluationContext context) {
    return evaluator.evaluate(condition, context);
  }

  @Nested
  @DisplayName("attribute resolution")
  class Attributes {

    @Test
    void reads_the_context_identifier() {
      EvaluationContext context = contextWith(Context.builder().id("u1").build());

      assertThat(evaluate(condition("identifier", "=", "text", null, "u1"), context)).isTrue();
      assertThat(evaluate(condition("identifier", "=", "text", null, "other"), context)).isFalse();
    }

    @Test
    void reads_the_context_name() {
      EvaluationContext context = contextWith(Context.builder().name("Ada").build());

      assertThat(evaluate(condition("name", "=", "text", null, "Ada"), context)).isTrue();
    }

    @Test
    void reads_the_application_metadata() {
      EvaluationContext context =
          new EvaluationContext(Context.empty(), new Metadata("checkout", "1.2.3"));

      assertThat(evaluate(condition("appName", "=", "text", null, "checkout"), context)).isTrue();
      assertThat(evaluate(condition("appVersion", "=", "semver", null, "1.2.3"), context)).isTrue();
    }

    @Test
    void reads_a_trait_by_pointer() {
      EvaluationContext context = withTraits(Map.of("plan", "pro", "nested", Map.of("tier", 2)));

      assertThat(evaluate(condition("traits", "=", "text", "/plan", "pro"), context)).isTrue();
      assertThat(evaluate(condition("traits", "=", "number", "/nested/tier", "2"), context)).isTrue();
    }
  }

  @Nested
  @DisplayName("an unknown attribute")
  class UnknownAttribute {

    @ParameterizedTest
    @ValueSource(strings = {"somethingNew", "email", "IDENTIFIER", ""})
    void never_matches_even_a_negative_operator(String attribute) {
      EvaluationContext context = contextWith(Context.builder().id("u1").build());

      // Unlike an absent value, there is nothing sensible to compare, so both directions are false.
      assertThat(evaluate(condition(attribute, "=", "text", null, "u1"), context)).isFalse();
      assertThat(evaluate(condition(attribute, "!=", "text", null, "u1"), context)).isFalse();
      assertThat(evaluate(condition(attribute, "is not one of", "text", null, "x"), context))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("an absent value")
  class AbsentValue {

    @Test
    void compares_as_the_empty_string_so_a_negative_operator_can_match() {
      EvaluationContext context = contextWith(Context.empty());

      assertThat(evaluate(condition("identifier", "does not equal", "text", null, "u1"), context))
          .isTrue();
      assertThat(evaluate(condition("identifier", "is not one of", "text", null, "a"), context))
          .isTrue();
      assertThat(evaluate(condition("identifier", "=", "text", null, "u1"), context)).isFalse();
    }

    @Test
    void an_absent_trait_is_absent_rather_than_unknown() {
      EvaluationContext context = withTraits(Map.of("plan", "pro"));

      assertThat(evaluate(condition("traits", "does not equal", "text", "/missing", "x"), context))
          .isTrue();
    }

    @Test
    void a_traits_condition_with_no_pointer_is_absent() {
      EvaluationContext context = withTraits(Map.of("plan", "pro"));

      assertThat(evaluate(condition("traits", "does not equal", "text", null, "x"), context))
          .isTrue();
      assertThat(evaluate(condition("traits", "=", "text", "", "pro"), context)).isFalse();
    }

    @Test
    void a_null_context_or_metadata_leaves_everything_absent() {
      assertThat(evaluate(condition("identifier", "does not equal", "text", null, "x"), null))
          .isTrue();
      assertThat(
              evaluate(
                  condition("appName", "does not equal", "text", null, "x"),
                  new EvaluationContext(null, null)))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("non-scalar values")
  class NonScalar {

    @Test
    void render_as_empty_for_a_text_comparison() {
      EvaluationContext context = withTraits(Map.of("tags", List.of("a", "b")));

      assertThat(evaluate(condition("traits", "=", "text", "/tags", ""), context)).isTrue();
      assertThat(evaluate(condition("traits", "=", "text", "/tags", "a"), context)).isFalse();
    }

    @Test
    void reach_an_array_comparison_unrendered() {
      EvaluationContext context = withTraits(Map.of("tags", List.of("a", "b")));

      assertThat(evaluate(condition("traits", "contains any of", "array", "/tags", "b"), context))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("target type dispatch")
  class TargetTypes {

    @Test
    void each_type_reaches_its_own_comparison() {
      EvaluationContext context =
          withTraits(
              Map.of(
                  "count", 42,
                  "version", "2.1.0",
                  "signedUp", "2026-01-01",
                  "tags", List.of("beta")));

      assertThat(evaluate(condition("traits", ">", "number", "/count", "40"), context)).isTrue();
      assertThat(evaluate(condition("traits", ">=", "semver", "/version", "2.0.0"), context))
          .isTrue();
      assertThat(
              evaluate(condition("traits", "is before", "datetime", "/signedUp", "2026-06-01"), context))
          .isTrue();
      assertThat(evaluate(condition("traits", "contains any of", "array", "/tags", "beta"), context))
          .isTrue();
      assertThat(evaluate(condition("traits", "=", "text", "/version", "2.1.0"), context)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "TEXT", "boolean"})
    void an_unrecognized_target_type_never_matches(String targetType) {
      EvaluationContext context = contextWith(Context.builder().id("u1").build());

      assertThat(evaluate(condition("identifier", "=", targetType, null, "u1"), context)).isFalse();
    }
  }
}
