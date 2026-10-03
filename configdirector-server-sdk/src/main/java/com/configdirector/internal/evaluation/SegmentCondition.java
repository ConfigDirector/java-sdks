package com.configdirector.internal.evaluation;

import java.util.Objects;

public record SegmentCondition(String id, String operator, String segmentId) implements Condition {

  public SegmentCondition {
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(segmentId, "segmentId");
  }
}
