package com.configdirector.internal.telemetry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// One evaluation and how many times it happened during the window the snapshot covers.
public record AggregatedEvent(Instant startTime, Instant endTime, int count, EvaluatedConfigEvent event) {

  // Identical evaluations collapse into a single entry with a count, which is what keeps a report
  // small for an application that evaluates the same config thousands of times a second.
  public static List<AggregatedEvent> aggregate(EventQueue.Snapshot snapshot) {
    Map<EvaluatedConfigEvent, Integer> counts = new LinkedHashMap<>();
    for (EvaluatedConfigEvent event : snapshot.events()) {
      counts.merge(event, 1, Integer::sum);
    }

    List<AggregatedEvent> aggregated = new ArrayList<>(counts.size());
    counts.forEach(
        (event, count) ->
            aggregated.add(
                new AggregatedEvent(snapshot.startTime(), snapshot.endTime(), count, event)));
    return aggregated;
  }

  public Map<String, Object> toWire() {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("startTime", Timestamps.format(startTime));
    wire.put("endTime", Timestamps.format(endTime));
    wire.put("count", count);
    wire.put("event", event.toWire());
    return wire;
  }
}
