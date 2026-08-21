package com.configdirector.internal.evaluation;

import java.util.List;

public record ConditionalRule(
    String id,
    Integer order,
    List<Condition> conditions,
    String target,
    Object value,
    String valueId,
    List<Percentage> percentages)
    implements Rule {

  public ConditionalRule {
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
    percentages = percentages == null ? List.of() : List.copyOf(percentages);
  }
}
