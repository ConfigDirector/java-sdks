package com.configdirector.internal.telemetry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InvalidContextSubmissions {

  public static final int DISTINCT_KIND_SAMPLE_LIMIT = 20;

  private final Map<String, Long> countsByKindSample = new LinkedHashMap<>();
  private long countBeyondSampleLimit;
  private Instant startTime;

  public synchronized InvalidContextRecording record(String kindSample) {
    if (startTime == null) {
      startTime = Instant.now();
    }
    Long count = countsByKindSample.get(kindSample);
    if (count != null) {
      countsByKindSample.put(kindSample, count + 1);
      return InvalidContextRecording.KNOWN_KIND_SAMPLE;
    }
    if (countsByKindSample.size() < DISTINCT_KIND_SAMPLE_LIMIT) {
      countsByKindSample.put(kindSample, 1L);
      return InvalidContextRecording.NEW_KIND_SAMPLE;
    }
    countBeyondSampleLimit++;
    return countBeyondSampleLimit == 1
        ? InvalidContextRecording.FIRST_BEYOND_SAMPLE_LIMIT
        : InvalidContextRecording.BEYOND_SAMPLE_LIMIT;
  }

  public synchronized List<InvalidContextEntry> takeSnapshot() {
    Instant endTime = Instant.now();
    Instant start = startTime == null ? endTime : startTime;
    List<InvalidContextEntry> entries = new ArrayList<>(countsByKindSample.size() + 1);
    countsByKindSample.forEach(
        (kindSample, count) -> entries.add(new InvalidContextEntry(start, endTime, count, kindSample)));
    if (countBeyondSampleLimit > 0) {
      entries.add(new InvalidContextEntry(start, endTime, countBeyondSampleLimit, null));
    }
    clear();
    return List.copyOf(entries);
  }

  public synchronized void clear() {
    countsByKindSample.clear();
    countBeyondSampleLimit = 0;
    startTime = null;
  }
}
