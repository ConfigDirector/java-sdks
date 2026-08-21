package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ArrayComparisonTest {

  private static final String CONTAINS = "contains any of";
  private static final String EXCLUDES = "does not contain any of";

  private static boolean compare(Object value, String operator, String... targets) {
    return ArrayComparison.compare(value, operator, List.of(targets));
  }

  @Nested
  @DisplayName("membership")
  class Membership {

    @Test
    void matches_when_any_element_is_listed() {
      assertThat(compare(List.of("a", "b"), CONTAINS, "b", "c")).isTrue();
      assertThat(compare(List.of("a", "b"), CONTAINS, "c")).isFalse();
    }

    @Test
    void the_negative_operator_is_the_inverse() {
      assertThat(compare(List.of("a", "b"), EXCLUDES, "c")).isTrue();
      assertThat(compare(List.of("a", "b"), EXCLUDES, "b")).isFalse();
    }

    @Test
    void an_empty_list_contains_nothing() {
      assertThat(compare(List.of(), CONTAINS, "a")).isFalse();
      assertThat(compare(List.of(), EXCLUDES, "a")).isTrue();
    }

    @Test
    void an_empty_target_list_matches_nothing() {
      assertThat(ArrayComparison.compare(List.of("a"), CONTAINS, List.of())).isFalse();
      assertThat(ArrayComparison.compare(List.of("a"), EXCLUDES, List.of())).isTrue();
    }
  }

  @Nested
  @DisplayName("element rendering")
  class Rendering {

    @Test
    void numbers_match_their_text_form() {
      assertThat(compare(List.of(1, 2, 3), CONTAINS, "2")).isTrue();
      assertThat(compare(List.of(1.0, 2.5), CONTAINS, "1")).isTrue();
      assertThat(compare(List.of(1.0, 2.5), CONTAINS, "2.5")).isTrue();
    }

    @Test
    void booleans_match_their_json_spelling() {
      assertThat(compare(List.of(true, false), CONTAINS, "true")).isTrue();
    }

    @Test
    void elements_with_no_text_form_are_dropped_rather_than_matching_empty() {
      List<Object> value = Arrays.asList(List.of("nested"), Map.of("k", "v"), null);

      assertThat(ArrayComparison.compare(value, CONTAINS, List.of(""))).isFalse();
      assertThat(ArrayComparison.compare(value, EXCLUDES, List.of(""))).isTrue();
    }

    @Test
    void a_scalar_alongside_non_scalars_still_matches() {
      List<Object> value = Arrays.asList(null, "found", Map.of("k", "v"));

      assertThat(compare(value, CONTAINS, "found")).isTrue();
    }
  }

  @Nested
  @DisplayName("values that are not lists")
  class NotAList {

    @Test
    void a_string_contains_nothing_even_when_comma_separated() {
      assertThat(compare("a,b", CONTAINS, "a")).isFalse();
      assertThat(compare("a,b", EXCLUDES, "a")).isTrue();
    }

    @Test
    void null_and_scalars_contain_nothing() {
      assertThat(compare(null, CONTAINS, "a")).isFalse();
      assertThat(compare(null, EXCLUDES, "a")).isTrue();
      assertThat(compare(26, CONTAINS, "26")).isFalse();
      assertThat(compare(Map.of("a", 1), EXCLUDES, "a")).isTrue();
    }
  }

  @Nested
  @DisplayName("operator handling")
  class Operators {

    @Test
    void operator_names_are_case_insensitive() {
      assertThat(compare(List.of("a"), "CONTAINS ANY OF", "a")).isTrue();
      assertThat(compare(List.of("a"), "does NOT contain any of", "b")).isTrue();
    }

    @Test
    void an_unknown_operator_never_matches() {
      assertThat(compare(List.of("a"), "smells like", "a")).isFalse();
      assertThat(compare("not a list", "smells like", "a")).isFalse();
    }
  }
}
