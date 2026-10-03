package com.configdirector.internal.evaluation;

import java.util.List;

public record Segment(List<List<AttributeCondition>> groups) {

  public Segment {
    groups = groups == null ? List.of() : groups.stream().map(List::copyOf).toList();
  }
}
