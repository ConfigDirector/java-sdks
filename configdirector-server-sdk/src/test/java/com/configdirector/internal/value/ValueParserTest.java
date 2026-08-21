package com.configdirector.internal.value;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigState;
import com.configdirector.ConfigType;
import com.configdirector.EvaluationReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValueParserTest {

  private static ConfigState state(String value) {
    return new ConfigState("c1", "k", ConfigType.STRING, value, "vid-1");
  }

  private static ParseResult parse(String value, Object defaultValue) {
    return ValueParser.parse(state(value), defaultValue);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object value) {
    return (List<Object>) value;
  }

  @Nested
  @DisplayName("a missing value")
  class Missing {

    @ParameterizedTest
    @ValueSource(strings = {""})
    void falls_back_to_the_default(String value) {
      ParseResult result = parse(value, "fallback");

      assertThat(result.value()).isEqualTo("fallback");
      assertThat(result.usedDefault()).isTrue();
      assertThat(result.reason()).isEqualTo(EvaluationReason.VALUE_MISSING);
    }

    @Test
    void a_null_value_falls_back_too() {
      assertThat(parse(null, "fallback").reason()).isEqualTo(EvaluationReason.VALUE_MISSING);
    }

    @Test
    void the_default_carries_no_value_id() {
      assertThat(parse(null, "fallback").valueId()).isNull();
    }
  }

  @Nested
  @DisplayName("booleans")
  class Booleans {

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True"})
    void accepts_true_in_any_case(String value) {
      assertThat(parse(value, false).value()).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "False"})
    void accepts_false_in_any_case(String value) {
      assertThat(parse(value, true).value()).isEqualTo(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", "on", "t", "truthy"})
    void anything_else_is_an_invalid_boolean(String value) {
      ParseResult result = parse(value, true);

      assertThat(result.value()).isEqualTo(true);
      assertThat(result.usedDefault()).isTrue();
      assertThat(result.reason()).isEqualTo(EvaluationReason.INVALID_BOOLEAN);
    }
  }

  @Nested
  @DisplayName("strings")
  class Strings {

    @Test
    void take_the_value_verbatim() {
      // A caller who asked for a string gets the text, whatever it happens to look like.
      assertThat(parse("true", "x").value()).isEqualTo("true");
      assertThat(parse("26", "x").value()).isEqualTo("26");
      assertThat(parse("{\"a\":1}", "x").value()).isEqualTo("{\"a\":1}");
    }
  }

  @Nested
  @DisplayName("integers")
  class Integers {

    @Test
    void reads_a_whole_number() {
      assertThat(parse("26", 0).value()).isEqualTo(26);
    }

    @Test
    void reads_a_whole_number_the_server_wrote_with_a_decimal_point() {
      assertThat(parse("26.0", 0).value()).isEqualTo(26);
    }

    @Test
    void a_long_default_yields_a_long() {
      assertThat(parse("26", 0L).value()).isEqualTo(26L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"26.5", "abc", "", "1_000", "0x10", "Infinity", " 26", "26 "})
    void anything_else_is_an_invalid_number(String value) {
      ParseResult result = parse(value, 7);

      assertThat(result.value()).isEqualTo(7);
      assertThat(result.reason())
          .isIn(EvaluationReason.INVALID_NUMBER, EvaluationReason.VALUE_MISSING);
    }

    @Test
    void a_negative_number_is_read() {
      assertThat(parse("-5", 0).value()).isEqualTo(-5);
    }
  }

  @Nested
  @DisplayName("doubles")
  class Doubles {

    @Test
    void reads_a_fractional_number() {
      assertThat(parse("26.5", 0.0).value()).isEqualTo(26.5);
    }

    @Test
    void reads_a_whole_number_as_a_double() {
      assertThat(parse("26", 0.0).value()).isEqualTo(26.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "NaN", "Infinity", "1_000"})
    void anything_else_is_an_invalid_number(String value) {
      assertThat(parse(value, 1.5).reason()).isEqualTo(EvaluationReason.INVALID_NUMBER);
    }
  }

  @Nested
  @DisplayName("json")
  class Json {

    @Test
    void reads_an_object_into_plain_jdk_types() {
      ParseResult result = parse("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null}", Map.of());

      assertThat(result.reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
      Map<String, Object> value = asMap(result.value());
      assertThat(value.get("a")).isEqualTo(1L);
      assertThat(value.get("b")).isEqualTo("x");
      assertThat(value.get("c")).isEqualTo(true);
      assertThat(value).containsKey("d");
      assertThat(value.get("d")).isNull();
    }

    @Test
    void reads_an_array() {
      ParseResult result = parse("[1,\"two\",false]", List.of());

      assertThat(asList(result.value())).containsExactly(1L, "two", false);
    }

    @Test
    void reads_nested_structures() {
      ParseResult result = parse("{\"outer\":{\"inner\":[1,2]}}", Map.of());

      Map<String, Object> outer = asMap(asMap(result.value()).get("outer"));
      assertThat(asList(outer.get("inner"))).containsExactly(1L, 2L);
    }

    @Test
    void a_shape_that_does_not_match_the_default_is_rejected() {
      // Asking for an object and getting an array is not a usable answer.
      assertThat(parse("[1,2]", Map.of()).reason()).isEqualTo(EvaluationReason.INVALID_JSON);
      assertThat(parse("{\"a\":1}", List.of()).reason()).isEqualTo(EvaluationReason.INVALID_JSON);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{", "{\"a\":}"})
    void malformed_json_falls_back(String value) {
      ParseResult result = parse(value, Map.of("fallback", true));

      assertThat(result.usedDefault()).isTrue();
      assertThat(result.reason()).isEqualTo(EvaluationReason.INVALID_JSON);
    }
  }

  @Nested
  @DisplayName("the value id")
  class ValueIds {

    @Test
    void travels_with_a_matched_value() {
      assertThat(parse("hello", "x").valueId()).isEqualTo("vid-1");
    }

    @Test
    void is_absent_when_the_default_was_used() {
      assertThat(parse("not-a-number", 1).valueId()).isNull();
    }
  }
}
