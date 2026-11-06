package com.configdirector.internal.telemetry;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TrackedEventLimits {

  static final int EVENTS_PER_BATCH = 1_000;
  static final int NAME_MAXIMUM_CHARACTERS = 150;
  static final int DETAILS_MAXIMUM_TOP_LEVEL_KEYS = 20;
  static final int DETAILS_MAXIMUM_JSON_BYTES = 1_024;

  private static final int LEAST_JSON_BYTES_PER_VALUE = 2;
  private static final int MOST_VALUES_THAT_FIT_THE_JSON_LIMIT =
      (DETAILS_MAXIMUM_JSON_BYTES + 1) / LEAST_JSON_BYTES_PER_VALUE;

  private TrackedEventLimits() {}

  static boolean isNameWithinTheCharacterLimit(String name) {
    return name != null
        && !name.isEmpty()
        && name.codePointCount(0, name.length()) <= NAME_MAXIMUM_CHARACTERS;
  }

  static ReportableOption<Number> reportableValueOf(Number value) {
    if (value == null) {
      return ReportableOption.notGiven();
    }
    try {
      Number written = writtenNumberOf(value);
      return isFinite(written)
          ? ReportableOption.reportable(written)
          : ReportableOption.leftOut(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER);
    } catch (RuntimeException notANumber) {
      return ReportableOption.leftOut(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER);
    }
  }

  static ReportableOption<Map<String, Object>> reportableDetailsOf(Map<String, Object> details) {
    if (details == null) {
      return ReportableOption.notGiven();
    }
    try {
      if (details.size() > DETAILS_MAXIMUM_TOP_LEVEL_KEYS) {
        return ReportableOption.leftOut(TrackedEventWarning.DETAILS_NOT_REPORTABLE);
      }
      Map<String, Object> copy = new DetailsCopy().objectCopyOf(details);
      return isWithinTheJsonLimit(copy)
          ? ReportableOption.reportable(copy)
          : ReportableOption.leftOut(TrackedEventWarning.DETAILS_NOT_REPORTABLE);
    } catch (RuntimeException notJsonShaped) {
      return ReportableOption.leftOut(TrackedEventWarning.DETAILS_NOT_REPORTABLE);
    }
  }

  private static boolean isFinite(Number written) {
    return !(written instanceof Double number) || Double.isFinite(number);
  }

  private static boolean isWithinTheJsonLimit(Map<String, Object> details) {
    byte[] json = TelemetryJson.stringify(details).getBytes(StandardCharsets.UTF_8);
    return json.length <= DETAILS_MAXIMUM_JSON_BYTES;
  }

  private static Number writtenNumberOf(Number number) {
    if (number instanceof Float) {
      return Double.valueOf(number.toString());
    }
    if (number instanceof Double
        || number instanceof Long
        || number instanceof Integer
        || number instanceof Short
        || number instanceof Byte
        || number instanceof BigInteger
        || number instanceof BigDecimal) {
      return number;
    }
    return new BigDecimal(number.toString());
  }

  private static final class DetailsCopy {

    private int valuesCopied;

    private Map<String, Object> objectCopyOf(Map<?, ?> members) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> member : members.entrySet()) {
        if (!(member.getKey() instanceof String key)) {
          throw new IllegalArgumentException("A key in the details is not a String");
        }
        copy.put(key, copyOf(member.getValue()));
      }
      return Collections.unmodifiableMap(copy);
    }

    private List<Object> arrayCopyOf(Collection<?> items) {
      List<Object> copy = new ArrayList<>();
      for (Object item : items) {
        copy.add(copyOf(item));
      }
      return Collections.unmodifiableList(copy);
    }

    private Object copyOf(Object value) {
      valuesCopied++;
      if (valuesCopied > MOST_VALUES_THAT_FIT_THE_JSON_LIMIT) {
        throw new IllegalArgumentException("The details hold more values than fit the JSON limit");
      }
      if (value == null || value instanceof String || value instanceof Boolean) {
        return value;
      }
      if (value instanceof Number number) {
        Number written = writtenNumberOf(number);
        return isFinite(written) ? written : null;
      }
      if (value instanceof Map<?, ?> members) {
        return objectCopyOf(members);
      }
      if (value instanceof Collection<?> items) {
        return arrayCopyOf(items);
      }
      throw new IllegalArgumentException("A value in the details has no JSON form");
    }
  }
}
