package com.configdirector.internal.telemetry;

import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import com.configdirector.EvaluationReason;
import com.configdirector.Variation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// A single evaluation, as telemetry reports it. A record so that two identical evaluations compare
// equal and collapse into one aggregated entry with a count.
public record EvaluatedConfigEvent(
    String contextId,
    String contextKind,
    String key,
    ConfigType type,
    TelemetryValue defaultValue,
    String requestedType,
    TelemetryValue evaluatedValue,
    String evaluatedValueId,
    boolean usedDefault,
    EvaluationReason reason,
    String ruleId,
    String shareId,
    String variationId) {

  public static EvaluatedConfigEvent of(
      ConfigEvaluation evaluation,
      Object defaultValue,
      ConfigType type,
      String contextId,
      String contextKind,
      String ruleId,
      String shareId) {
    Variation variation = evaluation.variation();
    return new EvaluatedConfigEvent(
        contextId,
        contextKind,
        evaluation.key(),
        type,
        TelemetryValue.of(defaultValue, null, type),
        requestedTypeOf(defaultValue),
        TelemetryValue.of(evaluation.value(), evaluation.valueId(), type),
        evaluation.valueId(),
        evaluation.isDefault(),
        evaluation.reason(),
        ruleId,
        shareId,
        variation == null ? null : variation.id());
  }

  public EvaluatedConfigEvent compacted() {
    return new EvaluatedConfigEvent(
        contextId,
        contextKind,
        key,
        type,
        defaultValue.compacted(),
        requestedType,
        evaluatedValue.compacted(),
        evaluatedValueId,
        usedDefault,
        reason,
        ruleId,
        shareId,
        variationId);
  }

  public Map<String, Object> toWire() {
    Map<String, Object> wire = new LinkedHashMap<>();
    if (contextId != null) {
      wire.put("contextId", contextId);
    }
    if (contextKind != null) {
      wire.put("contextKind", contextKind);
    }
    wire.put("key", key);
    if (type != null) {
      wire.put("type", type.wireName());
    }
    wire.put("defaultValue", defaultValue.toWire());
    wire.put("requestedType", requestedType);
    wire.put("evaluatedValue", evaluatedValue.toWire());
    if (evaluatedValueId != null) {
      wire.put("evaluatedValueId", evaluatedValueId);
    }
    wire.put("usedDefault", usedDefault);
    wire.put("evaluationReason", reason.wireName());
    if (ruleId != null) {
      wire.put("ruleId", ruleId);
    }
    if (shareId != null) {
      wire.put("shareId", shareId);
    }
    if (variationId != null) {
      wire.put("variationId", variationId);
    }
    return wire;
  }

  // The type the caller asked the config to be returned as, named the way Java names it -- the
  // JavaScript SDK reports string/number/Object for the same three. Collections are reported by
  // the interface rather than by whichever implementation the caller happened to pass, so that
  // one call site does not split into HashMap and LinkedHashMap on the dashboard.
  static String requestedTypeOf(Object defaultValue) {
    if (defaultValue instanceof Map) {
      return "Map";
    }
    if (defaultValue instanceof List) {
      return "List";
    }
    return defaultValue == null ? "null" : defaultValue.getClass().getSimpleName();
  }
}
