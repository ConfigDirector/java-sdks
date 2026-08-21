package com.configdirector.internal.evaluation;

// Either bound may be null, meaning unbounded in that direction.
public record NumericTypeConstraints(Bound min, Bound max) implements TypeConstraints {

  // relation is one of ">", ">=", "<", "<=".
  public record Bound(String relation, double value) {}
}
