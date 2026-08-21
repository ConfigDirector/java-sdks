package com.configdirector.internal.evaluation;

import java.util.List;
import java.util.Objects;

// trait is a JSON Pointer into the context's traits, and is null for every other attribute.
public record Condition(
    String id,
    String attribute,
    String operator,
    String targetType,
    List<String> targetValues,
    String trait) {

  public Condition {
    Objects.requireNonNull(operator, "operator");
    targetValues = targetValues == null ? List.of() : List.copyOf(targetValues);
  }
}
