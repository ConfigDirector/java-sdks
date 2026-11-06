package com.configdirector.internal.value;

import com.configdirector.EvaluationReason;
import com.configdirector.Variation;

public record ParseResult(
    Object value,
    EvaluationReason reason,
    boolean usedDefault,
    String valueId,
    Variation variation,
    String ruleId,
    String shareId) {}
