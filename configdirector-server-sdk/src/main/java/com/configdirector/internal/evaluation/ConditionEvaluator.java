package com.configdirector.internal.evaluation;

import com.configdirector.Context;
import java.util.Map;

public final class ConditionEvaluator {

  // An attribute the context does not carry. Compared as "", so a negative operator such as
  // "does NOT equal" can still match.
  private static final Object ABSENT = new Object();

  // An attribute this SDK version does not know about. Unlike an absent value it is not compared
  // at all -- there is nothing sensible to compare it against.
  private static final Object UNKNOWN_ATTRIBUTE = new Object();

  public boolean evaluate(Condition condition, EvaluationContext context) {
    EvaluationContext resolved = context == null ? EvaluationContext.empty() : context;
    Object value = resolve(condition, resolved);
    if (value == UNKNOWN_ATTRIBUTE) {
      return false;
    }

    return switch (condition.targetType()) {
      case "text" -> TextComparison.compare(render(value), condition.operator(), condition.targetValues());
      case "number" -> NumericComparison.compare(unwrap(value), condition.operator(), condition.targetValues());
      case "datetime" -> DateComparison.compare(render(value), condition.operator(), condition.targetValues());
      case "semver" -> SemverComparison.compare(render(value), condition.operator(), condition.targetValues());
      case "array" -> ArrayComparison.compare(unwrap(value), condition.operator(), condition.targetValues());
      default -> false;
    };
  }

  private static Object resolve(Condition condition, EvaluationContext context) {
    Context subject = context.contextOrEmpty();
    return switch (condition.attribute()) {
      case "identifier" -> orAbsent(subject.id());
      case "name" -> orAbsent(subject.name());
      case "appName" -> orAbsent(context.metadataOrEmpty().appName());
      case "appVersion" -> orAbsent(context.metadataOrEmpty().appVersion());
      case "traits" -> resolveTrait(condition, subject.traits());
      default -> UNKNOWN_ATTRIBUTE;
    };
  }

  private static Object resolveTrait(Condition condition, Map<String, Object> traits) {
    String trait = condition.trait();
    if (trait == null || trait.isEmpty()) {
      return ABSENT;
    }
    return orAbsent(JsonPointer.findByPointer(trait, traits));
  }

  private static Object orAbsent(Object value) {
    return value == null ? ABSENT : value;
  }

  private static Object unwrap(Object value) {
    return value == ABSENT ? null : value;
  }

  private static String render(Object value) {
    if (value == ABSENT || value == null) {
      return "";
    }
    // Lists and maps have no text form, so they render empty rather than as an object address.
    return JsonValues.isScalar(value) ? JsonValues.toJsonString(value) : "";
  }
}
