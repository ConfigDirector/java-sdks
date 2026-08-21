package com.configdirector.internal.telemetry;

import com.configdirector.Context;
import java.util.List;

// Everything one flush has to say. Empty reports are not worth a request, which is what keeps an
// idle application from talking to the network every interval.
public record EventReport(
    List<AggregatedEvent> evaluations,
    int droppedEvaluations,
    List<Context> contexts,
    int droppedContexts) {

  public boolean isEmpty() {
    return evaluations.isEmpty() && contexts.isEmpty() && droppedEvaluations == 0 && droppedContexts == 0;
  }
}
