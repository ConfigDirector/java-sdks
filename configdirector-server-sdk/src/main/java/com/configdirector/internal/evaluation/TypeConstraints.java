package com.configdirector.internal.evaluation;

// What the dashboard declared about a config's type. The evaluator does not read these; they
// travel with the config for the value parsing that follows.
public sealed interface TypeConstraints permits NumericTypeConstraints, EnumTypeConstraints {}
