package com.configdirector.internal.value;

import com.configdirector.ConfigState;
import com.configdirector.EvaluationReason;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Coerces an evaluated value into the type the caller asked for. The requested type comes from the
// default, not from how the config was declared in the dashboard: a caller who passes a boolean
// gets a boolean or their default back, never a string that happens to read as one.
public final class ValueParser {

  // The only characters a decimal literal may contain. Checking membership up front rules out what
  // Java's parsers would otherwise accept: surrounding whitespace, a trailing "d" or "f", hex,
  // and the words "Infinity" and "NaN".
  private static final String DECIMAL_CHARACTERS = "0123456789+-.eE";

  private ValueParser() {}

  public static ParseResult parse(ConfigState state, Object defaultValue) {
    String raw = state.value();
    if (raw == null || raw.isEmpty()) {
      return usedDefault(defaultValue, EvaluationReason.VALUE_MISSING);
    }

    if (defaultValue instanceof Boolean) {
      String lowered = raw.toLowerCase(Locale.ROOT);
      if (lowered.equals("true")) {
        return matched(true, state);
      }
      return lowered.equals("false")
          ? matched(false, state)
          : usedDefault(defaultValue, EvaluationReason.INVALID_BOOLEAN);
    }
    if (defaultValue instanceof String) {
      return matched(raw, state);
    }
    if (defaultValue instanceof Integer || defaultValue instanceof Long) {
      Long parsed = parseInteger(raw);
      if (parsed == null) {
        return usedDefault(defaultValue, EvaluationReason.INVALID_NUMBER);
      }
      return matched(defaultValue instanceof Integer ? (Object) parsed.intValue() : parsed, state);
    }
    if (defaultValue instanceof Double || defaultValue instanceof Float) {
      Double parsed = parseDouble(raw);
      return parsed == null
          ? usedDefault(defaultValue, EvaluationReason.INVALID_NUMBER)
          : matched(defaultValue instanceof Float ? (Object) parsed.floatValue() : parsed, state);
    }

    // A map or a list: the config holds JSON.
    try {
      JsonElement parsed = JsonParser.parseString(raw);
      Object converted = toJava(parsed);
      boolean shapeMatches =
          (defaultValue instanceof Map && converted instanceof Map)
              || (defaultValue instanceof List && converted instanceof List);
      return shapeMatches
          ? matched(converted, state)
          : usedDefault(defaultValue, EvaluationReason.INVALID_JSON);
    } catch (JsonParseException malformed) {
      return usedDefault(defaultValue, EvaluationReason.INVALID_JSON);
    }
  }

  private static ParseResult matched(Object value, ConfigState state) {
    return new ParseResult(value, EvaluationReason.FOUND_MATCH, false, state.valueId());
  }

  private static ParseResult usedDefault(Object defaultValue, EvaluationReason reason) {
    return new ParseResult(defaultValue, reason, true, null);
  }

  private static Long parseInteger(String value) {
    if (!isDecimal(value)) {
      return null;
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException notAnInteger) {
      // A whole number the server happened to write with a decimal point, such as "26.0".
      Double parsed = parseDouble(value);
      return parsed == null || parsed != Math.rint(parsed) ? null : (long) (double) parsed;
    }
  }

  private static Double parseDouble(String value) {
    if (!isDecimal(value)) {
      return null;
    }
    try {
      double parsed = Double.parseDouble(value);
      return Double.isFinite(parsed) ? parsed : null;
    } catch (NumberFormatException notANumber) {
      return null;
    }
  }

  private static boolean isDecimal(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      if (DECIMAL_CHARACTERS.indexOf(value.charAt(index)) < 0) {
        return false;
      }
    }
    return true;
  }

  // JSON reaches the caller as plain JDK types, so no Gson type appears in this SDK's API.
  private static Object toJava(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return null;
    }
    if (element.isJsonObject()) {
      Map<String, Object> object = new LinkedHashMap<>();
      for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
        object.put(entry.getKey(), toJava(entry.getValue()));
      }
      return object;
    }
    if (element.isJsonArray()) {
      JsonArray array = element.getAsJsonArray();
      List<Object> values = new ArrayList<>(array.size());
      for (JsonElement item : array) {
        values.add(toJava(item));
      }
      return values;
    }

    JsonPrimitive primitive = element.getAsJsonPrimitive();
    if (primitive.isBoolean()) {
      return primitive.getAsBoolean();
    }
    if (primitive.isNumber()) {
      BigDecimal number = primitive.getAsBigDecimal();
      try {
        return number.longValueExact();
      } catch (ArithmeticException fractional) {
        return number.doubleValue();
      }
    }
    return primitive.getAsString();
  }
}
