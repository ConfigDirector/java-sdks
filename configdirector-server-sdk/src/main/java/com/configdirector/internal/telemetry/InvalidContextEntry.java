package com.configdirector.internal.telemetry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record InvalidContextEntry(Instant startTime, Instant endTime, long count, String kindSample) {

  private static final String REASON = "invalid-context-kind";

  public Map<String, Object> toWire() {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("reason", REASON);
    if (kindSample != null) {
      event.put("kindSample", kindSample);
    }

    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("startTime", Timestamps.format(startTime));
    wire.put("endTime", Timestamps.format(endTime));
    wire.put("count", count);
    wire.put("event", event);
    return wire;
  }
}
