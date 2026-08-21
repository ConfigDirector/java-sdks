package com.configdirector.internal.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class TextComparison {

  // Distinct patterns come from targeting rules, so the set is small and stable. The cap stops a
  // pathological config growing this without bound.
  private static final int MAX_CACHED_PATTERNS = 256;

  private static final ConcurrentMap<String, Optional<Pattern>> PATTERNS = new ConcurrentHashMap<>();

  private TextComparison() {}

  static boolean compare(String value, String operator, List<String> targetValues) {
    String first = targetValues.isEmpty() ? null : targetValues.get(0);

    return switch (operator.toLowerCase(Locale.ROOT)) {
      case "=", "equals" -> first != null && value.equals(first);
      case "!=", "does not equal" -> first != null && !value.equals(first);
      case "is one of" -> targetValues.contains(value);
      case "is not one of" -> !targetValues.contains(value);
      case "starts with any of" -> targetValues.stream().anyMatch(value::startsWith);
      case "does not start with any of" -> targetValues.stream().noneMatch(value::startsWith);
      case "ends with any of" -> targetValues.stream().anyMatch(value::endsWith);
      case "does not end with any of" -> targetValues.stream().noneMatch(value::endsWith);
      case "matches regex" -> first != null && matchesRegex(first, value);
      case "does not match regex" -> first != null && !matchesRegex(first, value);
      default -> false;
    };
  }

  private static boolean matchesRegex(String pattern, String value) {
    Optional<Pattern> compiled =
        PATTERNS.size() >= MAX_CACHED_PATTERNS
            ? compile(pattern)
            : PATTERNS.computeIfAbsent(pattern, TextComparison::compile);
    // find(), not matches(): the other SDKs search anywhere in the value rather than requiring the
    // pattern to span all of it.
    return compiled.isPresent() && compiled.get().matcher(value).find();
  }

  private static Optional<Pattern> compile(String pattern) {
    try {
      return Optional.of(Pattern.compile(pattern));
    } catch (PatternSyntaxException invalid) {
      // An unusable pattern matches nothing rather than discarding the rule.
      return Optional.empty();
    }
  }
}
