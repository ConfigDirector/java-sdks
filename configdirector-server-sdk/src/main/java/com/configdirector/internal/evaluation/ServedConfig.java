package com.configdirector.internal.evaluation;

import com.configdirector.ConfigState;
import com.configdirector.EvaluationReason;

public record ServedConfig(ConfigState state, EvaluationReason reason) {}
