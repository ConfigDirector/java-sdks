package com.configdirector.internal.evaluation;

import java.util.List;

public record PercentageRule(String id, Integer order, List<Percentage> percentages) implements Rule {

  public PercentageRule {
    percentages = percentages == null ? List.of() : List.copyOf(percentages);
  }
}
