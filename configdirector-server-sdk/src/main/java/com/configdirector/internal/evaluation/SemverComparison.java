package com.configdirector.internal.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SemverComparison {

  // node-semver's coerce pattern. The 16-digit cap matches node-semver and keeps the pattern free
  // of unbounded adjacent repeats. [0-9] rather than \d because only ASCII digits count here.
  private static final Pattern COERCE =
      Pattern.compile("(^|[^0-9])([0-9]{1,16})(?:\\.([0-9]{1,16}))?(?:\\.([0-9]{1,16}))?(?:$|[^0-9])");

  private SemverComparison() {}

  static boolean compare(String value, String operator, List<String> targetValues) {
    String lowercaseOperator = operator.toLowerCase(Locale.ROOT);
    if (value.isBlank()) {
      return lowercaseOperator.equals("is not one of");
    }

    Version parsed = coerce(value);
    List<Version> targets = targetValues.stream().map(SemverComparison::coerce).toList();
    Version first = targets.isEmpty() ? null : targets.get(0);

    return switch (lowercaseOperator) {
      case "=" -> equal(parsed, first);
      case "<" -> ordered(parsed, first, comparison -> comparison < 0);
      case "<=" -> ordered(parsed, first, comparison -> comparison <= 0);
      case ">" -> ordered(parsed, first, comparison -> comparison > 0);
      case ">=" -> ordered(parsed, first, comparison -> comparison >= 0);
      case "is one of" -> targets.stream().anyMatch(target -> equal(parsed, target));
      case "is not one of" -> targets.stream().noneMatch(target -> equal(parsed, target));
      default -> false;
    };
  }

  private static Version coerce(String value) {
    Matcher matcher = COERCE.matcher(value);
    if (!matcher.find()) {
      return null;
    }
    return new Version(
        Long.parseLong(matcher.group(2)), component(matcher.group(3)), component(matcher.group(4)));
  }

  private static long component(String group) {
    return group == null ? 0 : Long.parseLong(group);
  }

  private static boolean equal(Version value, Version target) {
    return value != null && target != null && value.compareTo(target) == 0;
  }

  // An operand that could not be coerced never satisfies an ordering comparison.
  private static boolean ordered(Version value, Version target, IntPredicate satisfied) {
    return value != null && target != null && satisfied.test(value.compareTo(target));
  }

  private record Version(long major, long minor, long patch) implements Comparable<Version> {

    @Override
    public int compareTo(Version other) {
      int byMajor = Long.compare(major, other.major);
      if (byMajor != 0) {
        return byMajor;
      }
      int byMinor = Long.compare(minor, other.minor);
      return byMinor != 0 ? byMinor : Long.compare(patch, other.patch);
    }
  }
}
