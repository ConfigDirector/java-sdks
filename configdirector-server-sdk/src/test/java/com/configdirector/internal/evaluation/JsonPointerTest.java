package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonPointerTest {

  private static final Map<String, Object> DOCUMENT = document();

  private static Map<String, Object> document() {
    Map<String, Object> nested = new HashMap<>();
    nested.put("b", 1);

    Map<String, Object> root = new HashMap<>();
    root.put("a", nested);
    root.put("arr", List.of(10, 20));
    root.put("nulls", Arrays.asList(1, null, 3));
    root.put("n", null);
    root.put("", "empty-key");
    root.put("x/y", "slash");
    root.put("x~y", "tilde");
    root.put("scalar", "text");
    return root;
  }

  // Parsed then resolved, which is how a condition uses it: the pointer is split once when the
  // config is parsed, and only the path reaches the evaluation.
  private static Object find(String pointer) {
    return JsonPointer.findByPath(JsonPointer.parse(pointer), DOCUMENT);
  }

  private static Object findIn(String pointer, Object document) {
    return JsonPointer.findByPath(JsonPointer.parse(pointer), document);
  }

  private static final Map<String, Object> ELEVEN_ELEMENTS =
      Map.of("list", List.of("e0", "e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "e10"));

  @Nested
  @DisplayName("resolution")
  class Resolution {

    @Test
    void reads_a_top_level_member() {
      assertThat(find("/scalar")).isEqualTo("text");
    }

    @Test
    void reads_a_nested_member() {
      assertThat(find("/a/b")).isEqualTo(1);
    }

    @Test
    void returns_a_whole_subtree() {
      assertThat(find("/a")).isEqualTo(Map.of("b", 1));
    }

    @Test
    void indexes_into_a_list() {
      assertThat(find("/arr/0")).isEqualTo(10);
      assertThat(find("/arr/1")).isEqualTo(20);
    }

    @Test
    void addresses_a_member_whose_name_is_empty() {
      assertThat(find("/")).isEqualTo("empty-key");
    }

    @Test
    void a_null_member_resolves_to_null() {
      assertThat(find("/n")).isNull();
      assertThat(find("/nulls/1")).isNull();
    }
  }

  @Nested
  @DisplayName("escaping")
  class Escaping {

    @Test
    void tilde_one_is_a_slash() {
      assertThat(find("/x~1y")).isEqualTo("slash");
    }

    @Test
    void tilde_zero_is_a_tilde() {
      assertThat(find("/x~0y")).isEqualTo("tilde");
    }

    @Test
    void tilde_zero_one_is_a_literal_tilde_one() {
      // Un-escaping ~0 first would turn this into "/" instead.
      Map<String, Object> document = Map.of("~1", "literal");

      assertThat(JsonPointer.findByPath(JsonPointer.parse("/~01"), document))
          .isEqualTo("literal");
    }
  }

  @Nested
  @DisplayName("valid escapes")
  class ValidEscapes {

    @Test
    void a_lone_tilde_zero_is_a_member_named_tilde() {
      assertThat(findIn("/~0", Map.of("~", "tilde", "~0", "literal"))).isEqualTo("tilde");
    }

    @Test
    void a_lone_tilde_one_is_a_member_named_slash() {
      assertThat(findIn("/~1", Map.of("/", "slash", "~1", "literal"))).isEqualTo("slash");
    }

    @Test
    void tilde_zero_one_is_the_name_tilde_one_rather_than_slash() {
      assertThat(findIn("/~01", Map.of("~1", "tilde-one", "/", "slash"))).isEqualTo("tilde-one");
    }

    @Test
    void tilde_one_zero_is_the_name_slash_zero() {
      assertThat(findIn("/~10", Map.of("/0", "slash-zero", "~10", "literal")))
          .isEqualTo("slash-zero");
    }

    @Test
    void tilde_zero_zero_is_the_name_tilde_zero() {
      assertThat(findIn("/~00", Map.of("~0", "tilde-zero", "~", "tilde"))).isEqualTo("tilde-zero");
    }

    @Test
    void repeated_tilde_zero_escapes_each_decode_to_a_tilde() {
      assertThat(findIn("/a~0b~0c", Map.of("a~b~c", "tildes"))).isEqualTo("tildes");
    }

    @Test
    void repeated_tilde_one_escapes_each_decode_to_a_slash() {
      assertThat(findIn("/a~1b~1c", Map.of("a/b/c", "slashes"))).isEqualTo("slashes");
    }

    @Test
    void mixed_escapes_decode_in_place() {
      assertThat(findIn("/~0~1", Map.of("~/", "tilde-slash"))).isEqualTo("tilde-slash");
      assertThat(findIn("/~1~0", Map.of("/~", "slash-tilde"))).isEqualTo("slash-tilde");
    }

    @Test
    void escapes_decode_in_every_token_of_the_path() {
      Map<String, Object> document = Map.of("a/b~c", Map.of("d~/e", "deep"));

      assertThat(findIn("/a~1b~0c/d~0~1e", document)).isEqualTo("deep");
    }
  }

  @Nested
  @DisplayName("invalid escapes")
  class InvalidEscapes {

    @Test
    void a_pointer_with_an_invalid_escape_addresses_nothing() {
      assertThat(JsonPointer.parse("/~2")).isNull();
    }

    @Test
    void a_tilde_followed_by_two_is_absent() {
      assertThat(findIn("/~2", Map.of("~2", "literal"))).isNull();
    }

    @Test
    void a_tilde_followed_by_a_letter_is_absent() {
      assertThat(findIn("/~a", Map.of("~a", "literal"))).isNull();
    }

    @Test
    void a_tilde_that_is_the_whole_token_is_absent() {
      assertThat(findIn("/~", Map.of("~", "literal"))).isNull();
    }

    @Test
    void a_tilde_at_the_end_of_a_token_is_absent() {
      assertThat(findIn("/a~", Map.of("a~", "literal"))).isNull();
    }

    @Test
    void a_tilde_followed_by_a_tilde_is_absent() {
      assertThat(findIn("/~~", Map.of("~~", "literal"))).isNull();
    }

    @Test
    void a_tilde_before_a_separator_is_absent() {
      assertThat(findIn("/a~/b", Map.of("a~", Map.of("b", "literal")))).isNull();
    }

    @Test
    void an_invalid_escape_in_a_later_token_is_absent() {
      assertThat(findIn("/a/~2", Map.of("a", Map.of("~2", "literal")))).isNull();
    }

    @Test
    void an_invalid_escape_in_an_earlier_token_is_absent() {
      assertThat(findIn("/~2/b", Map.of("~2", Map.of("b", "literal")))).isNull();
    }

    @Test
    void an_invalid_escape_after_a_valid_one_is_absent() {
      assertThat(findIn("/~0~2", Map.of("~~2", "literal", "~~0", "other"))).isNull();
    }
  }

  @Nested
  @DisplayName("array indexes")
  class ArrayIndexes {

    @Test
    void zero_selects_the_first_element() {
      assertThat(findIn("/list/0", ELEVEN_ELEMENTS)).isEqualTo("e0");
    }

    @Test
    void a_multi_digit_index_selects_its_element() {
      assertThat(findIn("/list/10", ELEVEN_ELEMENTS)).isEqualTo("e10");
    }

    @Test
    void an_index_equal_to_the_size_is_absent() {
      assertThat(findIn("/list/11", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void a_leading_zero_is_absent() {
      assertThat(findIn("/list/01", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void a_double_zero_is_absent() {
      assertThat(findIn("/list/00", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void a_leading_zero_before_a_multi_digit_index_is_absent() {
      assertThat(findIn("/list/010", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void a_plus_sign_is_absent() {
      assertThat(findIn("/list/+1", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void negative_zero_is_absent() {
      assertThat(findIn("/list/-0", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void an_arabic_indic_digit_is_absent() {
      assertThat(findIn("/list/\u0661", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void a_fullwidth_digit_is_absent() {
      assertThat(findIn("/list/\uFF11", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void a_non_ascii_digit_after_an_ascii_digit_is_absent() {
      assertThat(findIn("/list/1\u0660", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void the_end_of_array_marker_is_absent() {
      assertThat(findIn("/list/-", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void an_empty_index_is_absent() {
      assertThat(findIn("/list/", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void a_decimal_index_is_absent() {
      assertThat(findIn("/list/1.0", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void an_exponent_index_is_absent() {
      assertThat(findIn("/list/1e0", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void a_surrounding_space_is_absent() {
      assertThat(findIn("/list/ 1", ELEVEN_ELEMENTS)).isNull();
      assertThat(findIn("/list/1 ", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void a_digit_separator_is_absent() {
      assertThat(findIn("/list/1_0", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void an_index_just_past_the_int_range_is_absent() {
      assertThat(findIn("/list/2147483648", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void an_index_past_the_long_range_is_absent() {
      assertThat(findIn("/list/99999999999999999999999999", ELEVEN_ELEMENTS)).isNull();
    }

    @Test
    void an_index_that_wraps_to_a_valid_int_is_absent() {
      assertThat(findIn("/list/4294967297", ELEVEN_ELEMENTS)).isNull();
    }
  }

  @Nested
  @DisplayName("misses")
  class Misses {

    @Test
    void an_absent_member_is_null() {
      assertThat(find("/missing")).isNull();
      assertThat(find("/a/missing")).isNull();
    }

    @Test
    void an_out_of_range_index_is_null() {
      assertThat(find("/arr/2")).isNull();
      assertThat(find("/arr/99")).isNull();
    }

    @Test
    void a_negative_index_is_null() {
      // Java's own negative indexing must not leak into RFC 6901's unsigned indexes.
      assertThat(find("/arr/-1")).isNull();
    }

    @Test
    void a_non_numeric_index_is_null() {
      assertThat(find("/arr/x")).isNull();
    }

    @Test
    void stepping_into_a_scalar_is_null() {
      assertThat(find("/scalar/deeper")).isNull();
      assertThat(find("/a/b/c")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a/b", "relative", "#/a"})
    void a_pointer_that_does_not_start_with_a_slash_is_null(String pointer) {
      assertThat(find(pointer)).isNull();
    }

    @Test
    void a_null_pointer_or_document_is_null() {
      assertThat(JsonPointer.parse(null)).isNull();
      assertThat(JsonPointer.findByPath(null, DOCUMENT)).isNull();
      assertThat(JsonPointer.findByPath(JsonPointer.parse("/a"), null)).isNull();
    }

    @Test
    void a_pointer_is_split_once_and_carries_its_escapes_already_resolved() {
      assertThat(JsonPointer.parse("/a/b")).containsExactly("a", "b");
      assertThat(JsonPointer.parse("/x~1y")).containsExactly("x/y");
      assertThat(JsonPointer.parse("/x~0y")).containsExactly("x~y");
      assertThat(JsonPointer.parse("/a/")).containsExactly("a", "");
    }
  }
}
