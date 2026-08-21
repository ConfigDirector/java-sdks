package com.configdirector.internal.evaluation;

import java.util.List;

// valueType is "string" or "number".
public record EnumTypeConstraints(String valueType, List<String> values) implements TypeConstraints {

  public EnumTypeConstraints {
    values = values == null ? List.of() : List.copyOf(values);
  }
}
