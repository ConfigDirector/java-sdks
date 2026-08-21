package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SemverComparisonTest {

  private static boolean compare(String value, String operator, String... targets) {
    return SemverComparison.compare(value, operator, List.of(targets));
  }

  @Nested
  @DisplayName("coercion")
  class Coercion {

    @ParameterizedTest(name = "{0} coerces to {1}")
    @CsvSource({
      "1.2.3, 1.2.3",
      "1.2, 1.2.0",
      "1, 1.0.0",
      "v1.2.3, 1.2.3",
      "1.2.3.4, 1.2.3",
      "1.2.3.4.5, 1.2.3",
      "0.1.645-a, 0.1.645",
      "1.2.3-beta+build, 1.2.3",
      "release-1.2.3, 1.2.3",
      "x1.2, 1.2.0",
      "'.1.2', 1.2.0",
      "1.02.3, 1.2.3",
    })
    void coerces_partial_and_prefixed_versions(String value, String equivalent) {
      assertThat(compare(value, "=", equivalent)).isTrue();
    }

    @Test
    void caps_a_component_at_sixteen_digits() {
      assertThat(compare("1234567890123456", "=", "1234567890123456")).isTrue();
      // A seventeenth digit leaves a trailing digit the pattern cannot absorb, so nothing coerces.
      assertThat(compare("12345678901234567", "is not one of", "1.0.0")).isTrue();
      assertThat(compare("12345678901234567", "is one of", "12345678901234567")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "no digits here", "-", "..."})
    void an_uncoercible_value_matches_nothing(String value) {
      assertThat(compare(value, "=", "1.0.0")).isFalse();
      assertThat(compare(value, ">", "1.0.0")).isFalse();
      assertThat(compare(value, "<", "1.0.0")).isFalse();
      assertThat(compare(value, "is one of", "1.0.0")).isFalse();
      assertThat(compare(value, "is not one of", "1.0.0")).isTrue();
    }

    @Test
    void an_uncoercible_target_satisfies_only_the_negative_operator() {
      assertThat(compare("1.0.0", "=", "abc")).isFalse();
      assertThat(compare("1.0.0", ">", "abc")).isFalse();
      assertThat(compare("1.0.0", "is one of", "abc")).isFalse();
      assertThat(compare("1.0.0", "is not one of", "abc")).isTrue();
    }
  }

  @Nested
  @DisplayName("a blank value")
  class BlankValue {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "   "})
    void satisfies_only_is_not_one_of(String value) {
      assertThat(compare(value, "is not one of", "1.0.0")).isTrue();
      assertThat(compare(value, "is one of", "1.0.0")).isFalse();
      assertThat(compare(value, "=", "1.0.0")).isFalse();
      assertThat(compare(value, "<", "1.0.0")).isFalse();
      assertThat(compare(value, ">", "1.0.0")).isFalse();
    }
  }

  @Nested
  @DisplayName("ordering")
  class Ordering {

    @Test
    void compares_major_then_minor_then_patch() {
      assertThat(compare("2.0.0", ">", "1.9.9")).isTrue();
      assertThat(compare("1.2.0", ">", "1.1.9")).isTrue();
      assertThat(compare("1.1.2", ">", "1.1.1")).isTrue();
      assertThat(compare("1.9.9", "<", "2.0.0")).isTrue();
    }

    @Test
    void the_inclusive_operators_admit_equality() {
      assertThat(compare("1.2.3", ">=", "1.2.3")).isTrue();
      assertThat(compare("1.2.3", "<=", "1.2.3")).isTrue();
      assertThat(compare("1.2.3", ">", "1.2.3")).isFalse();
      assertThat(compare("1.2.3", "<", "1.2.3")).isFalse();
    }

    @Test
    void a_larger_component_does_not_order_lexically() {
      // "10" sorts before "9" as text, and after it as a version.
      assertThat(compare("1.10.0", ">", "1.9.0")).isTrue();
    }

    @Test
    void ordering_uses_only_the_first_target() {
      assertThat(compare("1.5.0", ">", "1.0.0", "9.0.0")).isTrue();
    }
  }

  @Nested
  @DisplayName("set membership")
  class Membership {

    @Test
    void matches_any_listed_version() {
      assertThat(compare("1.2.3", "is one of", "0.0.1", "1.2.3", "2.0.0")).isTrue();
      assertThat(compare("1.2.3", "is one of", "0.0.1", "2.0.0")).isFalse();
      assertThat(compare("1.2.3", "is not one of", "0.0.1", "2.0.0")).isTrue();
    }

    @Test
    void an_empty_target_list_contains_nothing() {
      assertThat(SemverComparison.compare("1.2.3", "is one of", List.of())).isFalse();
      assertThat(SemverComparison.compare("1.2.3", "is not one of", List.of())).isTrue();
    }

    @Test
    void an_empty_target_list_fails_the_single_target_operators() {
      assertThat(SemverComparison.compare("1.2.3", "=", List.of())).isFalse();
      assertThat(SemverComparison.compare("1.2.3", ">", List.of())).isFalse();
    }
  }

  @Nested
  @DisplayName("operator handling")
  class Operators {

    @Test
    void operator_names_are_case_insensitive() {
      assertThat(compare("1.2.3", "IS ONE OF", "1.2.3")).isTrue();
      assertThat(compare("1.2.3", "is NOT one of", "9.9.9")).isTrue();
    }

    @Test
    void an_unknown_operator_never_matches() {
      assertThat(compare("1.2.3", "rhymes with", "1.2.3")).isFalse();
    }
  }
}
