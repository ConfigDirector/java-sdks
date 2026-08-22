package com.configdirector.internal.evaluation;

import com.configdirector.Context;
import java.util.List;
import java.util.Map;

public final class ConditionEvaluator {

  // Stands in for a resolved value where there is none. An enum rather than a bare Object so the
  // identity comparisons below read as deliberate; no trait value can ever be one of these.
  private enum Unresolved {
    // The context does not carry the attribute. Compared as "", so a negative operator such as
    // "does NOT equal" can still match.
    ABSENT,

    // This SDK version does not know the attribute. Unlike an absent value it is not compared at
    // all -- there is nothing sensible to compare it against.
    UNKNOWN_ATTRIBUTE
  }

  public boolean evaluate(Condition condition, EvaluationContext context) {
    EvaluationContext resolved = context == null ? EvaluationContext.empty() : context;
    Object value = resolve(condition, resolved);
    if (value == Unresolved.UNKNOWN_ATTRIBUTE) {
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
      default -> Unresolved.UNKNOWN_ATTRIBUTE;
    };
  }

  private static Object resolveTrait(Condition condition, Map<String, Object> traits) {
    List<String> path = condition.traitPath();
    if (path == null) {
      return Unresolved.ABSENT;
    }
    return orAbsent(JsonPointer.findByPath(path, traits));
  }

  private static Object orAbsent(Object value) {
    return value == null ? Unresolved.ABSENT : value;
  }

  private static Object unwrap(Object value) {
    return value == Unresolved.ABSENT ? null : value;
  }

  private static String render(Object value) {
    if (value == Unresolved.ABSENT || value == null) {
      return "";
    }
    // Lists and maps have no text form, so they render empty rather than as an object address.
    return JsonValues.isScalar(value) ? JsonValues.toJsonString(value) : "";
  }
}
