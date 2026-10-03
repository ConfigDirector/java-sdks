package com.configdirector.internal.evaluation;

import java.util.List;
import java.util.Map;

final class SegmentEvaluator {

  private final ConditionEvaluator conditionEvaluator;

  SegmentEvaluator(ConditionEvaluator conditionEvaluator) {
    this.conditionEvaluator = conditionEvaluator;
  }

  boolean evaluate(
      SegmentCondition condition, Map<String, Segment> segments, EvaluationContext context) {
    Segment segment = segments.get(condition.segmentId());
    if (segment == null) {
      return false;
    }
    boolean member = contains(segment, context);
    return switch (condition.operator().toLowerCase(java.util.Locale.ROOT)) {
      case "in" -> member;
      case "not in" -> !member;
      default -> false;
    };
  }

  private boolean contains(Segment segment, EvaluationContext context) {
    for (List<AttributeCondition> group : segment.groups()) {
      if (groupMatches(group, context)) {
        return true;
      }
    }
    return false;
  }

  private boolean groupMatches(List<AttributeCondition> group, EvaluationContext context) {
    for (AttributeCondition condition : group) {
      if (!conditionEvaluator.evaluate(condition, context)) {
        return false;
      }
    }
    return true;
  }
}
