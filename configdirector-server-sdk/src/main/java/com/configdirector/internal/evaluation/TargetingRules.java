package com.configdirector.internal.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record TargetingRules(String defaultValue, String defaultValueId, List<Rule> rules) {

  private static final Comparator<Rule> BY_ORDER =
      Comparator.comparing(Rule::order, Comparator.nullsLast(Comparator.naturalOrder()));

  // Ordered once here rather than on every evaluation: the list cannot change after parsing, and
  // evaluation runs on the caller's thread for every config read. A stable sort, so rules sharing
  // an order keep the sequence the server sent them in.
  public TargetingRules {
    if (rules == null) {
      rules = List.of();
    } else {
      List<Rule> ordered = new ArrayList<>(rules);
      ordered.sort(BY_ORDER);
      rules = List.copyOf(ordered);
    }
  }
}
