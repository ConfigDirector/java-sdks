package com.configdirector.internal.evaluation;

// A rule kind this SDK version does not know about never reaches the evaluator: parsing skips it
// rather than producing a variant nothing can handle.
public sealed interface Rule permits ConditionalRule, PercentageRule {

  String id();

  // Null sorts last, after every rule the server did order.
  Integer order();
}
