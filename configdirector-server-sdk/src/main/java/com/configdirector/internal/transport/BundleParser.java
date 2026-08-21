package com.configdirector.internal.transport;

import com.configdirector.ConfigType;
import com.configdirector.internal.evaluation.Condition;
import com.configdirector.internal.evaluation.EnumTypeConstraints;
import com.configdirector.internal.evaluation.JsonValues;
import com.configdirector.internal.evaluation.NumericTypeConstraints;
import com.configdirector.internal.evaluation.ConditionalRule;
import com.configdirector.internal.evaluation.Config;
import com.configdirector.internal.evaluation.Percentage;
import com.configdirector.internal.evaluation.PercentageRule;
import com.configdirector.internal.evaluation.Rule;
import com.configdirector.internal.evaluation.TargetingRules;
import com.configdirector.internal.evaluation.TypeConstraints;
import com.configdirector.internal.evaluation.Variation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

public final class BundleParser {

  private BundleParser() {}

  public static ConfigBundle parse(String payload, Logger logger) {
    JsonElement document;
    try {
      document = JsonParser.parseString(payload);
    } catch (JsonParseException malformed) {
      throw new BundleFormatException("The config bundle is not valid JSON: " + malformed, malformed);
    }
    if (!document.isJsonObject()) {
      throw new BundleFormatException("Expected the config bundle to be a JSON object");
    }

    JsonObject root = document.getAsJsonObject();
    return new ConfigBundle(
        parseConfigs(root.get("configs"), logger),
        "delta".equals(optionalString(root.get("kind")))
            ? ConfigBundle.BundleKind.DELTA
            : ConfigBundle.BundleKind.FULL,
        optionalString(root.get("environmentId")),
        optionalString(root.get("projectId")),
        optionalString(root.get("timestamp")));
  }

  private static Map<String, Config> parseConfigs(JsonElement raw, Logger logger) {
    if (raw == null || !raw.isJsonObject()) {
      return Map.of();
    }

    Map<String, Config> configs = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject().entrySet()) {
      try {
        configs.put(entry.getKey(), parseConfig(object(entry.getValue())));
      } catch (RuntimeException error) {
        // One unreadable config must not cost the application every other config in the bundle.
        // It keeps whatever definition it already had, or falls back to defaults.
        logger.warn(
            "[BundleParser] Skipping the config {}, its definition could not be read",
            entry.getKey(),
            error);
      }
    }
    return configs;
  }

  private static Config parseConfig(JsonObject raw) {
    JsonObject target = object(raw.get("target"));
    return new Config(
        requiredString(raw, "id"),
        requiredString(raw, "key"),
        ConfigType.fromWireName(optionalString(raw.get("type"))),
        new TargetingRules(
            asString(target.get("defaultValue")),
            optionalString(target.get("defaultValueId")),
            map(target.get("rules"), element -> parseRule(object(element)))),
        map(raw.get("variations"), element -> parseVariation(object(element))),
        parseTypeConstraints(raw.get("typeConstraints")));
  }

  private static Rule parseRule(JsonObject raw) {
    List<Percentage> percentages = map(raw.get("percentages"), element -> parsePercentage(object(element)));
    String kind = optionalString(raw.get("type"));

    if ("percentage".equals(kind)) {
      return new PercentageRule(requiredString(raw, "id"), optionalInt(raw.get("order")), percentages);
    }

    // Anything that is not a percentage rule is carried as a conditional rule. A kind this SDK
    // version predates then matches nothing, rather than being discarded before evaluation.
    return new ConditionalRule(
        requiredString(raw, "id"),
        optionalInt(raw.get("order")),
        map(raw.get("conditions"), element -> parseCondition(object(element))),
        orDefault(optionalString(raw.get("target")), "value"),
        ruleValue(raw.get("value")),
        optionalString(raw.get("valueId")),
        percentages);
  }

  private static Condition parseCondition(JsonObject raw) {
    return new Condition(
        requiredString(raw, "id"),
        requiredString(raw, "attribute"),
        requiredString(raw, "operator"),
        requiredString(raw, "targetType"),
        map(raw.get("targetValues"), BundleParser::asString),
        optionalString(raw.get("trait")));
  }

  private static Percentage parsePercentage(JsonObject raw) {
    return new Percentage(
        requiredString(raw, "id"),
        requiredNumber(raw, "percentage"),
        ruleValue(raw.get("value")),
        optionalString(raw.get("valueId")));
  }

  private static Variation parseVariation(JsonObject raw) {
    JsonElement value = raw.get("value");
    return new Variation(
        isScalar(value) ? scalar(value.getAsJsonPrimitive()) : asString(value),
        optionalString(raw.get("name")));
  }

  private static TypeConstraints parseTypeConstraints(JsonElement raw) {
    if (raw == null || !raw.isJsonObject()) {
      return null;
    }
    JsonObject constraints = raw.getAsJsonObject();
    if (constraints.has("valueType")) {
      return new EnumTypeConstraints(
          "number".equals(optionalString(constraints.get("valueType"))) ? "number" : "string",
          map(constraints.get("values"), BundleParser::asString));
    }
    return new NumericTypeConstraints(bound(constraints.get("min")), bound(constraints.get("max")));
  }

  private static NumericTypeConstraints.Bound bound(JsonElement raw) {
    if (raw == null || !raw.isJsonObject()) {
      return null;
    }
    JsonObject value = raw.getAsJsonObject();
    Double magnitude = optionalNumber(value.get("value"));
    String relation = optionalString(value.get("relation"));
    return magnitude == null || relation == null
        ? null
        : new NumericTypeConstraints.Bound(relation, magnitude);
  }

  private static <T> List<T> map(JsonElement raw, java.util.function.Function<JsonElement, T> parse) {
    if (raw == null || !raw.isJsonArray()) {
      return List.of();
    }
    JsonArray array = raw.getAsJsonArray();
    List<T> parsed = new ArrayList<>(array.size());
    for (JsonElement element : array) {
      parsed.add(parse.apply(element));
    }
    return parsed;
  }

  private static JsonObject object(JsonElement raw) {
    if (raw == null || !raw.isJsonObject()) {
      throw new BundleFormatException("Expected a JSON object");
    }
    return raw.getAsJsonObject();
  }

  private static String requiredString(JsonObject raw, String field) {
    String value = optionalString(raw.get(field));
    if (value == null) {
      throw new BundleFormatException("Missing the required field '" + field + "'");
    }
    return value;
  }

  private static double requiredNumber(JsonObject raw, String field) {
    Double value = optionalNumber(raw.get(field));
    if (value == null) {
      throw new BundleFormatException("Missing the required numeric field '" + field + "'");
    }
    return value;
  }

  private static String optionalString(JsonElement raw) {
    return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()
        ? raw.getAsString()
        : null;
  }

  private static Double optionalNumber(JsonElement raw) {
    return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber()
        ? raw.getAsDouble()
        : null;
  }

  // Rules without a usable order evaluate last, in the order the server sent them.
  private static Integer optionalInt(JsonElement raw) {
    Double value = optionalNumber(raw);
    return value == null ? null : (int) (double) value;
  }

  private static boolean isScalar(JsonElement raw) {
    return raw != null && raw.isJsonPrimitive();
  }

  // A structured value reaches the application as the JSON text it was sent as.
  private static Object ruleValue(JsonElement raw) {
    if (raw == null || raw.isJsonNull()) {
      return null;
    }
    return isScalar(raw) ? scalar(raw.getAsJsonPrimitive()) : raw.toString();
  }

  private static Object scalar(JsonPrimitive primitive) {
    if (primitive.isBoolean()) {
      return primitive.getAsBoolean();
    }
    if (primitive.isNumber()) {
      return narrow(primitive.getAsBigDecimal());
    }
    return primitive.getAsString();
  }

  // JSON has one number type. A whole number becomes a Long so it renders as "26" rather than
  // "26.0", which is what every other ConfigDirector SDK spells it as.
  private static Object narrow(BigDecimal number) {
    try {
      return number.longValueExact();
    } catch (ArithmeticException fractional) {
      return number.doubleValue();
    }
  }

  private static String asString(JsonElement raw) {
    if (raw == null || raw.isJsonNull()) {
      return "";
    }
    if (isScalar(raw)) {
      JsonPrimitive primitive = raw.getAsJsonPrimitive();
      return primitive.isString()
          ? primitive.getAsString()
          : JsonValues.toJsonString(scalar(primitive));
    }
    return raw.toString();
  }

  private static String orDefault(String value, String fallback) {
    return value == null ? fallback : value;
  }
}
