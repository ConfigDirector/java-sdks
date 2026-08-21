package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JsonValuesTest {

  @Nested
  @DisplayName("scalars")
  class Scalars {

    @Test
    void strings_numbers_and_booleans_are_scalar() {
      assertThat(JsonValues.isScalar("a")).isTrue();
      assertThat(JsonValues.isScalar(1)).isTrue();
      assertThat(JsonValues.isScalar(1.5)).isTrue();
      assertThat(JsonValues.isScalar(true)).isTrue();
    }

    @Test
    void lists_maps_and_null_are_not() {
      assertThat(JsonValues.isScalar(List.of())).isFalse();
      assertThat(JsonValues.isScalar(Map.of())).isFalse();
      assertThat(JsonValues.isScalar(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("rendering")
  class Rendering {

    @Test
    void booleans_use_json_spelling() {
      assertThat(JsonValues.toJsonString(true)).isEqualTo("true");
      assertThat(JsonValues.toJsonString(false)).isEqualTo("false");
    }

    @Test
    void a_string_renders_as_itself() {
      assertThat(JsonValues.toJsonString("hello")).isEqualTo("hello");
      assertThat(JsonValues.toJsonString("")).isEmpty();
    }

    @Test
    void a_whole_double_loses_its_decimal_point() {
      // JSON makes no int/float distinction, so 26.0 and 26 must render alike.
      assertThat(JsonValues.toJsonString(26.0)).isEqualTo("26");
      assertThat(JsonValues.toJsonString(26)).isEqualTo("26");
      assertThat(JsonValues.toJsonString(26L)).isEqualTo("26");
      assertThat(JsonValues.toJsonString(-26.0)).isEqualTo("-26");
      assertThat(JsonValues.toJsonString(0.0)).isEqualTo("0");
    }

    @Test
    void a_fractional_double_keeps_its_digits() {
      assertThat(JsonValues.toJsonString(26.5)).isEqualTo("26.5");
      assertThat(JsonValues.toJsonString(0.1)).isEqualTo("0.1");
    }

    @Test
    void large_whole_doubles_stay_in_plain_notation() {
      assertThat(JsonValues.toJsonString(1e20)).isEqualTo("100000000000000000000");
    }

    @Test
    void non_finite_doubles_use_the_javascript_names() {
      assertThat(JsonValues.toJsonString(Double.NaN)).isEqualTo("NaN");
      assertThat(JsonValues.toJsonString(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
      assertThat(JsonValues.toJsonString(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
    }

    @Test
    void integral_types_render_without_conversion() {
      assertThat(JsonValues.toJsonString(BigInteger.TEN)).isEqualTo("10");
      assertThat(JsonValues.toJsonString(new BigDecimal("10.50"))).isEqualTo("10.50");
    }

    @Test
    void null_renders_as_the_json_literal() {
      assertThat(JsonValues.toJsonString(null)).isEqualTo("null");
    }
  }
}
