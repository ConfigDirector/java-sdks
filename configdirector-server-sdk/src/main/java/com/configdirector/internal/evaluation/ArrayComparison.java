package com.configdirector.internal.evaluation;

import java.util.List;
import java.util.Locale;

final class ArrayComparison {

  private ArrayComparison() {}

  static boolean compare(Object value, String operator, List<String> targetValues) {
    String lowercaseOperator = operator.toLowerCase(Locale.ROOT);
    if (!(value instanceof List<?> elements)) {
      return lowercaseOperator.equals("does not contain any of");
    }

    return switch (lowercaseOperator) {
      case "contains any of" -> containsAny(elements, targetValues);
      case "does not contain any of" -> !containsAny(elements, targetValues);
      default -> false;
    };
  }

  private static boolean containsAny(List<?> elements, List<String> targetValues) {
    // Nested lists, objects and nulls have no text form, so they are dropped rather than matching
    // an empty target value.
    return elements.stream()
        .filter(JsonValues::isScalar)
        .map(JsonValues::toJsonString)
        .anyMatch(targetValues::contains);
  }
}
