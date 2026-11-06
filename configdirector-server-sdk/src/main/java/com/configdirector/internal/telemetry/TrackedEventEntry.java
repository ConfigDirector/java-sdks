package com.configdirector.internal.telemetry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record TrackedEventEntry(
    String name,
    String contextKind,
    String contextIdentifier,
    Number value,
    Map<String, Object> details,
    Instant happenedAt) {

  Map<String, Object> toWire() {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("name", name);
    if (contextKind != null) {
      wire.put("contextKind", contextKind);
    }
    if (contextIdentifier != null) {
      wire.put("contextIdentifier", contextIdentifier);
    }
    if (value != null) {
      wire.put("value", value);
    }
    if (details != null) {
      wire.put("details", details);
    }
    wire.put("happenedAt", Timestamps.format(happenedAt));
    return wire;
  }
}
