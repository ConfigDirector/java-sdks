package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DateComparisonTest {

  private static final String BEFORE = "is before";
  private static final String AFTER = "is after";

  private static boolean compare(String value, String operator, String... targets) {
    return DateComparison.compare(value, operator, List.of(targets));
  }

  @Nested
  @DisplayName("accepted formats")
  class Formats {

    @Test
    void a_bare_date() {
      assertThat(compare("2026-01-01", BEFORE, "2026-01-02")).isTrue();
      assertThat(compare("2026-01-02", AFTER, "2026-01-01")).isTrue();
    }

    @Test
    void a_year_alone_means_january_first() {
      assertThat(compare("2026", BEFORE, "2026-01-02")).isTrue();
      assertThat(compare("2026", AFTER, "2025-12-31")).isTrue();
    }

    @Test
    void a_year_and_month_mean_the_first_of_that_month() {
      assertThat(compare("2026-06", BEFORE, "2026-06-02")).isTrue();
    }

    @Test
    void hours_and_minutes_without_seconds() {
      assertThat(compare("2026-01-01T12:30", AFTER, "2026-01-01T12:29")).isTrue();
    }

    @Test
    void an_extended_positive_year() {
      assertThat(compare("+002026-01-01", BEFORE, "2026-01-02")).isTrue();
    }

    @Test
    void a_lowercase_zulu_marker() {
      assertThat(compare("2026-01-01T00:00:00z", BEFORE, "2026-01-02")).isTrue();
    }
  }

  @Nested
  @DisplayName("timezone handling")
  class Timezones {

    @Test
    void a_value_with_no_offset_reads_as_utc() {
      // Not the timezone of whichever machine ran the evaluation.
      assertThat(compare("2026-01-01T00:00:00", BEFORE, "2026-01-01T00:00:00Z")).isFalse();
      assertThat(compare("2026-01-01T00:00:00", AFTER, "2026-01-01T00:00:00Z")).isFalse();
    }

    @Test
    void a_positive_offset_is_earlier_in_utc() {
      assertThat(compare("2026-01-01T00:00:00+05:00", BEFORE, "2026-01-01T00:00:00Z")).isTrue();
    }

    @Test
    void a_negative_offset_is_later_in_utc() {
      assertThat(compare("2026-01-01T00:00:00-05:00", AFTER, "2026-01-01T00:00:00Z")).isTrue();
    }

    @Test
    void the_same_instant_in_two_zones_is_neither_before_nor_after() {
      assertThat(compare("2026-01-01T05:00:00+05:00", BEFORE, "2026-01-01T00:00:00Z")).isFalse();
      assertThat(compare("2026-01-01T05:00:00+05:00", AFTER, "2026-01-01T00:00:00Z")).isFalse();
    }
  }

  @Nested
  @DisplayName("fractional seconds")
  class Fractions {

    @Test
    void milliseconds_are_compared() {
      assertThat(compare("2026-01-01T00:00:00.100Z", BEFORE, "2026-01-01T00:00:00.200Z")).isTrue();
    }

    @Test
    void finer_precision_is_truncated_not_rounded() {
      // .1239 truncates to .123, which is still before .124 rather than equal to it.
      assertThat(compare("2026-01-01T00:00:00.1239Z", BEFORE, "2026-01-01T00:00:00.124Z")).isTrue();
      // .9999 truncates to .999 rather than rounding up to a whole second.
      assertThat(compare("2026-01-01T00:00:00.9999Z", BEFORE, "2026-01-01T00:00:01Z")).isTrue();
    }

    @Test
    void fewer_than_three_digits_are_padded() {
      assertThat(compare("2026-01-01T00:00:00.1Z", AFTER, "2026-01-01T00:00:00.099Z")).isTrue();
      assertThat(compare("2026-01-01T00:00:00.1Z", BEFORE, "2026-01-01T00:00:00.101Z")).isTrue();
    }
  }

  @Nested
  @DisplayName("rejected values")
  class Rejected {

    @ParameterizedTest
    @ValueSource(strings = {
      "2026-02-30", "2026-13-01", "2026-00-01", "2026-01-32",
      "garbage", "", "2026-01-01t00:00:00z", "01/01/2026",
      "2026-1-1", "2026-01-01T25:00:00Z", "2026-01-01 00:00:00",
    })
    void neither_before_nor_after(String value) {
      assertThat(compare(value, BEFORE, "2026-06-01")).isFalse();
      assertThat(compare(value, AFTER, "2026-06-01")).isFalse();
    }

    @Test
    void an_unparseable_target_never_matches() {
      assertThat(compare("2026-01-01", BEFORE, "not-a-date")).isFalse();
      assertThat(compare("2026-01-01", AFTER, "not-a-date")).isFalse();
    }

    @Test
    void an_empty_target_list_never_matches() {
      assertThat(DateComparison.compare("2026-01-01", BEFORE, List.of())).isFalse();
    }
  }

  @Nested
  @DisplayName("operator handling")
  class Operators {

    @Test
    void equal_instants_satisfy_neither_operator() {
      assertThat(compare("2026-01-01", BEFORE, "2026-01-01")).isFalse();
      assertThat(compare("2026-01-01", AFTER, "2026-01-01")).isFalse();
    }

    @Test
    void an_unknown_operator_never_matches() {
      assertThat(compare("2026-01-01", "is around", "2026-01-01")).isFalse();
    }

    @Test
    void the_operator_is_case_sensitive_here() {
      // Unlike the other comparisons, the date operators are matched exactly, as the other SDKs do.
      assertThat(compare("2026-01-01", "IS BEFORE", "2026-01-02")).isFalse();
    }

    @Test
    void only_the_first_target_is_used() {
      assertThat(compare("2026-01-01", BEFORE, "2026-01-02", "1999-01-01")).isTrue();
    }
  }
}
