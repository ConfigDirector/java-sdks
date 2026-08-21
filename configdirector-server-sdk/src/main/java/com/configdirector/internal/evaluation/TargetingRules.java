package com.configdirector.internal.evaluation;

import java.util.List;

public record TargetingRules(String defaultValue, String defaultValueId, List<Rule> rules) {

  public TargetingRules {
    rules = rules == null ? List.of() : List.copyOf(rules);
  }
}
