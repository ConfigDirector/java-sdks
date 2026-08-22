package com.configdirector.internal.evaluation;

import java.util.List;
import java.util.Locale;

final class TextComparison {

  private TextComparison() {}

  static boolean compare(String value, String operator, List<String> targetValues) {
    String first = targetValues.isEmpty() ? null : targetValues.get(0);

    return switch (operator.toLowerCase(Locale.ROOT)) {
      case "=", "equals" -> value.equals(first);
      case "!=", "does not equal" -> first != null && !value.equals(first);
      case "is one of" -> targetValues.contains(value);
      case "is not one of" -> !targetValues.contains(value);
      case "starts with any of" -> targetValues.stream().anyMatch(value::startsWith);
      case "does not start with any of" -> targetValues.stream().noneMatch(value::startsWith);
      case "ends with any of" -> targetValues.stream().anyMatch(value::endsWith);
      case "does not end with any of" -> targetValues.stream().noneMatch(value::endsWith);
      default -> false;
    };
  }
}
