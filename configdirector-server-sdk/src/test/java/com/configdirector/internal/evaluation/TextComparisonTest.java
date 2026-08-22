package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TextComparisonTest {

  private static boolean compare(String value, String operator, String... targets) {
    return TextComparison.compare(value, operator, List.of(targets));
  }

  private static boolean withNoTargets(String value, String operator) {
    return TextComparison.compare(value, operator, List.of());
  }

  @Nested
  @DisplayName("equality")
  class Equality {

    @Test
    void compares_against_the_first_target_only() {
      assertThat(compare("a", "equals", "a", "z")).isTrue();
      assertThat(compare("a", "equals", "z", "a")).isFalse();
    }

    @Test
    void is_case_sensitive_in_the_value() {
      assertThat(compare("ABC", "equals", "abc")).isFalse();
    }

    @Test
    void the_symbol_and_the_word_agree() {
      assertThat(compare("a", "=", "a")).isTrue();
      assertThat(compare("a", "!=", "b")).isTrue();
      assertThat(compare("a", "does NOT equal", "b")).isTrue();
    }

    @Test
    void an_empty_target_list_fails_both_directions() {
      // There is nothing to compare against, so even the negative operator is false.
      assertThat(withNoTargets("a", "equals")).isFalse();
      assertThat(withNoTargets("a", "does not equal")).isFalse();
    }
  }

  @Nested
  @DisplayName("set membership")
  class Membership {

    @Test
    void matches_any_listed_value() {
      assertThat(compare("b", "is one of", "a", "b", "c")).isTrue();
      assertThat(compare("z", "is one of", "a", "b")).isFalse();
      assertThat(compare("z", "is not one of", "a", "b")).isTrue();
    }

    @Test
    void an_empty_target_list_contains_nothing() {
      assertThat(withNoTargets("a", "is one of")).isFalse();
      // "is NOT one of nothing" falls out as true.
      assertThat(withNoTargets("a", "is not one of")).isTrue();
    }
  }

  @Nested
  @DisplayName("prefixes and suffixes")
  class Affixes {

    @Test
    void matches_any_prefix() {
      assertThat(compare("hello", "starts with any of", "x", "he")).isTrue();
      assertThat(compare("hello", "starts with any of", "x")).isFalse();
      assertThat(compare("hello", "does not start with any of", "x")).isTrue();
    }

    @Test
    void matches_any_suffix() {
      assertThat(compare("hello", "ends with any of", "x", "lo")).isTrue();
      assertThat(compare("hello", "does not end with any of", "x")).isTrue();
    }

    @Test
    void an_empty_target_matches_every_value_as_an_affix() {
      assertThat(compare("hello", "starts with any of", "")).isTrue();
      assertThat(compare("hello", "ends with any of", "")).isTrue();
    }

    @Test
    void an_empty_target_list_matches_nothing() {
      assertThat(withNoTargets("hello", "starts with any of")).isFalse();
      assertThat(withNoTargets("hello", "does not start with any of")).isTrue();
    }
  }

  @Nested
  @DisplayName("an absent attribute")
  class AbsentValue {

    @Test
    void compares_as_the_empty_string() {
      assertThat(compare("", "equals", "")).isTrue();
      assertThat(compare("", "does not equal", "anything")).isTrue();
      assertThat(compare("", "is not one of", "a", "b")).isTrue();
      assertThat(compare("", "equals", "a")).isFalse();
    }
  }

  @Nested
  @DisplayName("retired operators")
  class Retired {

    @Test
    void the_regex_operators_no_longer_match() {
      assertThat(compare("hello world", "matches regex", "^hello")).isFalse();
      assertThat(compare("hello world", "does not match regex", "^x")).isFalse();
    }
  }

  @Nested
  @DisplayName("operator handling")
  class Operators {

    @Test
    void operator_names_are_case_insensitive() {
      assertThat(compare("a", "IS ONE OF", "a")).isTrue();
      assertThat(compare("a", "Starts With Any Of", "a")).isTrue();
    }

    @Test
    void an_unknown_operator_never_matches() {
      assertThat(compare("a", "sounds like", "a")).isFalse();
    }
  }
}
