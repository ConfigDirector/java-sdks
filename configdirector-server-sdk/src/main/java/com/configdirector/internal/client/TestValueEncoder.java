package com.configdirector.internal.client;

import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConfigType;
import com.configdirector.internal.evaluation.Config;
import com.configdirector.internal.evaluation.TargetingRules;
import com.configdirector.internal.evaluation.Variation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

final class TestValueEncoder {

  private TestValueEncoder() {}

  static Config encode(String key, Object value) {
    validateKey(key);
    if (value instanceof Boolean flag) {
      return config(key, ConfigType.BOOLEAN, flag.toString());
    }
    if (value instanceof Integer || value instanceof Long) {
      return config(key, ConfigType.INTEGER, value.toString());
    }
    if (value instanceof Float || value instanceof Double) {
      return config(key, ConfigType.FLOAT, plainDecimal(key, (Number) value));
    }
    if (value instanceof String text) {
      return config(key, ConfigType.STRING, text);
    }
    if (value instanceof Map || value instanceof List) {
      return config(key, ConfigType.JSON, jsonContents(key, value, "").toString());
    }
    throw new ConfigDirectorValidationException(
        "Invalid test value for '"
            + key
            + "': "
            + describe(value)
            + ". Use a Boolean, an Integer, a Long, a Float, a Double, a String, a Map with String"
            + " keys, or a List.");
  }

  static Config encodeJsonText(String key, String json) {
    validateKey(key);
    if (json == null) {
      throw invalidJsonText(key, "null is not JSON text");
    }
    JsonElement document;
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      reader.setStrictness(Strictness.STRICT);
      document = JsonParser.parseReader(reader);
      requireNothingAfterTheValue(key, reader);
    } catch (JsonParseException | IOException malformed) {
      throw invalidJsonText(key, malformed.getMessage());
    }
    if (!document.isJsonObject() && !document.isJsonArray()) {
      throw invalidJsonText(key, "a JSON config holds an object or an array");
    }
    return config(key, ConfigType.JSON, document.toString());
  }

  private static void requireNothingAfterTheValue(String key, JsonReader reader) throws IOException {
    boolean ended;
    try {
      ended = reader.peek() == JsonToken.END_DOCUMENT;
    } catch (MalformedJsonException trailing) {
      ended = false;
    }
    if (!ended) {
      throw invalidJsonText(key, "the text continues after the JSON value");
    }
  }

  private static Config config(String key, ConfigType type, String text) {
    return new Config(
        "test-config:" + key,
        key,
        type,
        new TargetingRules(text, "test-value:" + key, List.of()),
        List.of(new Variation(text, null)),
        null);
  }

  private static void validateKey(String key) {
    if (key == null || key.isBlank()) {
      throw new ConfigDirectorValidationException(
          "Invalid config key. The key of a test value must be a non-empty string.");
    }
  }

  private static String plainDecimal(String key, Number number) {
    String text = number.toString();
    if (!Double.isFinite(number.doubleValue())) {
      throw new ConfigDirectorValidationException(
          "Invalid test value for '" + key + "': " + text + " is not a finite number.");
    }
    if (text.indexOf('E') < 0) {
      return text;
    }
    return new BigDecimal(text).stripTrailingZeros().toPlainString();
  }

  private static JsonElement jsonContents(String key, Object value, String path) {
    if (value == null) {
      return JsonNull.INSTANCE;
    }
    if (value instanceof Boolean flag) {
      return new JsonPrimitive(flag);
    }
    if (value instanceof Integer || value instanceof Long) {
      return new JsonPrimitive((Number) value);
    }
    if (value instanceof Float || value instanceof Double) {
      if (!Double.isFinite(((Number) value).doubleValue())) {
        throw invalidJsonContents(key, path, value + " is not a finite number");
      }
      return new JsonPrimitive((Number) value);
    }
    if (value instanceof String text) {
      return new JsonPrimitive(text);
    }
    if (value instanceof List<?> items) {
      JsonArray array = new JsonArray(items.size());
      for (int index = 0; index < items.size(); index++) {
        array.add(jsonContents(key, items.get(index), path + "[" + index + "]"));
      }
      return array;
    }
    if (value instanceof Map<?, ?> map) {
      JsonObject object = new JsonObject();
      for (Map.Entry<?, ?> entry : orderedEntries(key, map, path)) {
        String property = (String) entry.getKey();
        String propertyPath = path.isEmpty() ? property : path + "." + property;
        object.add(property, jsonContents(key, entry.getValue(), propertyPath));
      }
      return object;
    }
    throw invalidJsonContents(key, path, describe(value) + " cannot be encoded as JSON");
  }

  private static Iterable<? extends Map.Entry<?, ?>> orderedEntries(
      String key, Map<?, ?> map, String path) {
    for (Object property : map.keySet()) {
      if (!(property instanceof String)) {
        throw invalidJsonContents(key, path, "the key " + describe(property) + " is not a String");
      }
    }
    if (map instanceof LinkedHashMap || map instanceof SortedMap) {
      return map.entrySet();
    }
    return new TreeMap<>(map).entrySet();
  }

  private static ConfigDirectorValidationException invalidJsonContents(
      String key, String path, String problem) {
    return new ConfigDirectorValidationException(
        "Invalid test value for '"
            + key
            + "'"
            + (path.isEmpty() ? "" : " at '" + path + "'")
            + ": "
            + problem
            + ". JSON contents can hold Booleans, finite Integers, Longs, Floats and Doubles,"
            + " Strings, null, Lists, and Maps with String keys.");
  }

  private static ConfigDirectorValidationException invalidJsonText(String key, String problem) {
    return new ConfigDirectorValidationException(
        "Invalid JSON text for '" + key + "': " + problem);
  }

  private static String describe(Object value) {
    return value == null ? "null" : "a " + value.getClass().getName() + " instance";
  }
}
