package com.configdirector.internal.telemetry;

import com.configdirector.ConfigType;
import com.configdirector.internal.evaluation.JsonValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// One side of an evaluation -- what was asked for, or what came back -- in the form telemetry
// reports it: either the value spelled out, or the ID of a value too large to spell out.
public record TelemetryValue(String value, String valueId, ConfigType type) {

  // Longer than this and the value is reported by ID instead, to keep payloads small.
  public static final int CONFIG_VALUE_MAX_LENGTH = 500;

  // valueId is the one the server sent alongside the config state, when there was one.
  public static TelemetryValue of(Object value, String valueId, ConfigType type) {
    if (isJson(value, type)) {
      return valueId == null
          ? new TelemetryValue(render(value, type), null, ConfigType.JSON)
          : new TelemetryValue(null, valueId, ConfigType.JSON);
    }

    String rendered = render(value, type);
    if (rendered.length() <= CONFIG_VALUE_MAX_LENGTH) {
      return new TelemetryValue(rendered, null, null);
    }
    return valueId == null
        ? new TelemetryValue(rendered, null, null)
        : new TelemetryValue(null, valueId, null);
  }

  // The form that goes on the wire: an oversized value, and every JSON document, is replaced by
  // its ID. This is the only step that hashes, which is why it runs on the flush thread rather
  // than on the caller's.
  public TelemetryValue compacted() {
    if (valueId != null) {
      return new TelemetryValue(null, valueId, null);
    }
    if (value != null
        && !value.isEmpty()
        && (type == ConfigType.JSON || value.length() > CONFIG_VALUE_MAX_LENGTH)) {
      return new TelemetryValue(null, ValueIds.generate(value), null);
    }
    return new TelemetryValue(value, null, null);
  }

  public Map<String, Object> toWire() {
    Map<String, Object> wire = new LinkedHashMap<>();
    if (value != null) {
      wire.put("value", value);
    }
    if (valueId != null) {
      wire.put("valueId", valueId);
    }
    if (type != null) {
      wire.put("type", type.wireName());
    }
    return wire;
  }

  public static String render(Object value, ConfigType type) {
    return isJson(value, type) ? TelemetryJson.stringify(value) : JsonValues.toJsonString(value);
  }

  public static String idFor(Object value, ConfigType type) {
    return ValueIds.generate(render(value, type));
  }

  // An evaluation that found no config state has no declared type, so the value itself is all
  // there is to go on.
  private static boolean isJson(Object value, ConfigType type) {
    return type == ConfigType.JSON
        || (type == null && (value instanceof Map || value instanceof List));
  }
}
