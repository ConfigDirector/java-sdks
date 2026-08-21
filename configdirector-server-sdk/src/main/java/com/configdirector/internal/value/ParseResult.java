package com.configdirector.internal.value;

import com.configdirector.EvaluationReason;

public record ParseResult(Object value, EvaluationReason reason, boolean usedDefault, String valueId) {}
