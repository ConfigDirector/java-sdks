package com.configdirector.internal.evaluation;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DateComparison {

  // Every component is a fixed-length digit run except the fraction, whose + is followed only by
  // characters it cannot match, so there is nothing to backtrack over.
  private static final Pattern ISO =
      Pattern.compile(
          "^(?<year>[+-][0-9]{6}|[0-9]{4})"
              + "(?:-(?<month>[0-9]{2})(?:-(?<day>[0-9]{2}))?)?"
              + "(?:T(?<hour>[0-9]{2}):(?<minute>[0-9]{2})"
              + "(?::(?<second>[0-9]{2})(?:\\.(?<fraction>[0-9]+))?)?"
              + "(?<offset>[Zz]|[+-][0-9]{2}:[0-9]{2})?)?$");

  private DateComparison() {}

  static boolean compare(String value, String operator, List<String> targetValues) {
    if (targetValues.isEmpty()) {
      return false;
    }

    Instant parsedValue = parseDate(value);
    Instant parsedTarget = parseDate(targetValues.get(0));
    if (parsedValue == null || parsedTarget == null) {
      return false;
    }

    return switch (operator) {
      case "is after" -> parsedValue.isAfter(parsedTarget);
      case "is before" -> parsedValue.isBefore(parsedTarget);
      default -> false;
    };
  }

  private static Instant parseDate(String value) {
    Matcher matcher = ISO.matcher(value);
    if (!matcher.matches()) {
      return null;
    }

    try {
      LocalDateTime local =
          LocalDateTime.of(
              Integer.parseInt(matcher.group("year")),
              component(matcher.group("month"), 1),
              component(matcher.group("day"), 1),
              component(matcher.group("hour"), 0),
              component(matcher.group("minute"), 0),
              component(matcher.group("second"), 0),
              nanosOf(matcher.group("fraction")));
      return local.toInstant(offsetOf(matcher.group("offset")));
    } catch (DateTimeException outOfRange) {
      // Admitted by the pattern but not by the calendar, such as month 13 or 2026-02-30.
      return null;
    }
  }

  private static int component(String group, int fallback) {
    return group == null ? fallback : Integer.parseInt(group);
  }

  // Precision is milliseconds; finer digits are truncated rather than rounded.
  private static int nanosOf(String fraction) {
    if (fraction == null) {
      return 0;
    }
    return Integer.parseInt((fraction + "00").substring(0, 3)) * 1_000_000;
  }

  // A value with no offset is read as UTC, not as the running machine's zone.
  private static ZoneOffset offsetOf(String offset) {
    if (offset == null || offset.equalsIgnoreCase("Z")) {
      return ZoneOffset.UTC;
    }
    return ZoneOffset.of(offset);
  }
}
