package com.configdirector.internal.evaluation;

import java.util.List;
import java.util.Locale;

final class NumericComparison {

  // Membership is a single pass, with none of the backtracking a pattern of adjacent digit repeats
  // would allow.
  private static final String DECIMAL_CHARACTERS = "0123456789+-.eE";

  private NumericComparison() {}

  static boolean compare(Object value, String operator, List<String> targetValues) {
    String lowercaseOperator = operator.toLowerCase(Locale.ROOT);

    Double parsed = parseFinite(value);
    if (parsed == null) {
      return lowercaseOperator.equals("!=") || lowercaseOperator.equals("does not equal");
    }
    if (targetValues.isEmpty()) {
      return false;
    }
    Double target = parseFinite(targetValues.get(0));
    if (target == null) {
      return false;
    }

    double a = parsed;
    double b = target;
    return switch (lowercaseOperator) {
      case "=", "equals" -> a == b;
      case "!=", "does not equal" -> a != b;
      case "<" -> a < b;
      case "<=" -> a <= b;
      case ">" -> a > b;
      case ">=" -> a >= b;
      default -> false;
    };
  }

  private static Double parseFinite(Object value) {
    // Booleans are not numbers here, even though a JSON reader may hand them over as one.
    if (value == null || value instanceof Boolean) {
      return null;
    }
    if (value instanceof Number number) {
      double parsed = number.doubleValue();
      return Double.isFinite(parsed) ? parsed : null;
    }
    if (!(value instanceof String text) || text.isEmpty()) {
      return null;
    }

    // parseDouble would accept whitespace, a trailing "d"/"f", hex, "Infinity" and "NaN". The
    // character check rules those out; parseDouble then rejects the likes of "1.2.3" and "1e".
    for (int index = 0; index < text.length(); index++) {
      if (DECIMAL_CHARACTERS.indexOf(text.charAt(index)) < 0) {
        return null;
      }
    }
    try {
      double parsed = Double.parseDouble(text);
      return Double.isFinite(parsed) ? parsed : null;
    } catch (NumberFormatException notANumber) {
      return null;
    }
  }
}
