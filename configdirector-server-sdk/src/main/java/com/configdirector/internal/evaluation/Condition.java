package com.configdirector.internal.evaluation;

import java.util.List;
import java.util.Objects;

// trait is a JSON Pointer into the context's traits, and is null for every other attribute.
// traitPath is that pointer already split, so that resolving a trait costs no parsing on the
// caller's thread. Nothing outside this record supplies it: the six-argument form below leaves it
// to be derived, and is how a bundle and the tests build a condition.
public record Condition(
    String id,
    String attribute,
    String operator,
    String targetType,
    List<String> targetValues,
    String trait,
    List<String> traitPath) {

  public Condition {
    Objects.requireNonNull(operator, "operator");
    targetValues = targetValues == null ? List.of() : List.copyOf(targetValues);
    traitPath = traitPath == null ? JsonPointer.parse(trait) : List.copyOf(traitPath);
  }

  public Condition(
      String id,
      String attribute,
      String operator,
      String targetType,
      List<String> targetValues,
      String trait) {
    this(id, attribute, operator, targetType, targetValues, trait, null);
  }
}
