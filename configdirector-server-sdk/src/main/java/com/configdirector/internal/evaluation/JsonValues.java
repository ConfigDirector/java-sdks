package com.configdirector.internal.evaluation;

import java.math.BigDecimal;

public final class JsonValues {

  private static final double MAX_PLAIN_INTEGER = 1e21;

  private JsonValues() {}

  public static boolean isScalar(Object value) {
    return value instanceof String || value instanceof Number || value instanceof Boolean;
  }

  // Must spell values exactly as the other SDKs do, or the same config would resolve to different
  // text depending on which one read it.
  public static String toJsonString(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof String text) {
      return text;
    }
    if (value instanceof Boolean flag) {
      return flag.toString();
    }
    if (value instanceof Double || value instanceof Float) {
      return renderDouble(((Number) value).doubleValue());
    }
    return value.toString();
  }

  private static String renderDouble(double value) {
    if (Double.isNaN(value)) {
      return "NaN";
    }
    if (Double.isInfinite(value)) {
      return value > 0 ? "Infinity" : "-Infinity";
    }
    // JSON makes no int/float distinction, so 26.0 has to render as "26".
    if (value == Math.rint(value) && Math.abs(value) < MAX_PLAIN_INTEGER) {
      return BigDecimal.valueOf(value).toBigInteger().toString();
    }
    return Double.toString(value);
  }
}
