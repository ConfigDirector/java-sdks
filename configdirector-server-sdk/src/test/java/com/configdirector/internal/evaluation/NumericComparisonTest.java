package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NumericComparisonTest {

  private static boolean compare(Object value, String operator, String... targets) {
    return NumericComparison.compare(value, operator, List.of(targets));
  }

  @Nested
  @DisplayName("parsing")
  class Parsing {

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.5", "-5", "+5", "1e3", "1E3", ".5", "5."})
    void accepts_plain_decimal_notation(String value) {
      assertThat(compare(value, "!=", "999999")).isTrue();
      assertThat(compare(value, "=", value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"26abc", " 42", "42 ", "0x10", "Infinity", "-Infinity", "NaN", "", "1_000", "1.2.3", "1e", "1d", "1f", "abc", "--5"})
    void rejects_anything_else(String value) {
      // An unparseable value satisfies only the negative operator.
      assertThat(compare(value, "!=", "10")).isTrue();
      assertThat(compare(value, "=", "10")).isFalse();
      assertThat(compare(value, ">", "10")).isFalse();
      assertThat(compare(value, "<", "10")).isFalse();
    }

    @Test
    void accepts_every_boxed_number_type() {
      assertThat(compare(10, "=", "10")).isTrue();
      assertThat(compare(10L, "=", "10")).isTrue();
      assertThat(compare(10.0d, "=", "10")).isTrue();
      assertThat(compare(10.0f, "=", "10")).isTrue();
      assertThat(compare(new BigDecimal("10"), "=", "10")).isTrue();
      assertThat(compare(BigInteger.TEN, "=", "10")).isTrue();
    }

    @Test
    void a_boolean_is_not_a_number() {
      assertThat(compare(true, "!=", "1")).isTrue();
      assertThat(compare(true, "=", "1")).isFalse();
    }

    @Test
    void null_is_not_a_number() {
      assertThat(compare(null, "!=", "1")).isTrue();
      assertThat(compare(null, "=", "1")).isFalse();
    }

    @Test
    void a_non_finite_number_is_not_a_number() {
      assertThat(compare(Double.NaN, "!=", "1")).isTrue();
      assertThat(compare(Double.POSITIVE_INFINITY, "=", "1")).isFalse();
      assertThat(compare(Double.NEGATIVE_INFINITY, ">", "1")).isFalse();
    }

    @Test
    void a_list_or_map_is_not_a_number() {
      assertThat(compare(List.of(1), "!=", "1")).isTrue();
      assertThat(compare(List.of(1), "=", "1")).isFalse();
    }
  }

  @Nested
  @DisplayName("comparison")
  class Comparison {

    @Test
    void orders_numerically_rather_than_lexically() {
      assertThat(compare(9, "<", "10")).isTrue();
      assertThat(compare("9", "<", "10")).isTrue();
    }

    @Test
    void the_inclusive_operators_admit_equality() {
      assertThat(compare(10, ">=", "10")).isTrue();
      assertThat(compare(10, "<=", "10")).isTrue();
      assertThat(compare(10, ">", "10")).isFalse();
      assertThat(compare(10, "<", "10")).isFalse();
    }

    @Test
    void an_integer_equals_its_decimal_form() {
      assertThat(compare(10, "=", "10.0")).isTrue();
      assertThat(compare(10.0, "=", "10")).isTrue();
    }

    @Test
    void handles_negative_numbers() {
      assertThat(compare(-5, "<", "0")).isTrue();
      assertThat(compare(-5, ">", "-10")).isTrue();
    }
  }

  @Nested
  @DisplayName("targets")
  class Targets {

    @Test
    void an_empty_target_list_never_matches_a_parseable_value() {
      assertThat(NumericComparison.compare(10, "=", List.of())).isFalse();
      assertThat(NumericComparison.compare(10, "!=", List.of())).isFalse();
      assertThat(NumericComparison.compare(10, ">", List.of())).isFalse();
    }

    @Test
    void an_unparseable_value_beats_an_empty_target_list() {
      // The value is decided first, so "!=" is true even with nothing to compare against.
      assertThat(NumericComparison.compare("abc", "!=", List.of())).isTrue();
    }

    @Test
    void an_unparseable_target_never_matches() {
      assertThat(compare(10, "=", "abc")).isFalse();
      assertThat(compare(10, "!=", "abc")).isFalse();
      assertThat(compare(10, ">", "abc")).isFalse();
    }

    @Test
    void only_the_first_target_is_used() {
      assertThat(compare(10, "=", "10", "99")).isTrue();
      assertThat(compare(10, "=", "99", "10")).isFalse();
    }
  }

  @Nested
  @DisplayName("operator handling")
  class Operators {

    @Test
    void accepts_both_the_symbol_and_the_word() {
      assertThat(compare(10, "equals", "10")).isTrue();
      assertThat(compare(10, "EQUALS", "10")).isTrue();
      assertThat(compare(10, "does NOT equal", "11")).isTrue();
    }

    @Test
    void an_unknown_operator_never_matches() {
      assertThat(compare(10, "approximately", "10")).isFalse();
    }

    @Test
    void an_unknown_operator_still_rejects_an_unparseable_value() {
      assertThat(compare("abc", "approximately", "10")).isFalse();
    }
  }
}
