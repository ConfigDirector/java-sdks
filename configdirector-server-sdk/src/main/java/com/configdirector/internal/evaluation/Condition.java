package com.configdirector.internal.evaluation;

public sealed interface Condition permits AttributeCondition, SegmentCondition {

  String id();
}
