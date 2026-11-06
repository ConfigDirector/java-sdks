package com.configdirector.internal.telemetry;

import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

public final class TrackedEventRecordingTelemetryCollector implements TelemetryCollector {

  private final Logger logger;
  private final List<TrackedEventAdmission.Admitted> trackedEvents = new ArrayList<>();

  public TrackedEventRecordingTelemetryCollector(Logger logger) {
    this.logger = logger;
  }

  @Override
  public void recordEvaluation(
      ConfigEvaluation evaluation,
      Object defaultValue,
      ConfigType type,
      String ruleId,
      String shareId) {}

  @Override
  public InvalidContextRecording recordInvalidContext(String kindSample) {
    return InvalidContextRecording.NEW_KIND_SAMPLE;
  }

  @Override
  public void recordTrackedEvent(TrackedEventAdmission admission) {
    admission.warnings().forEach(warning -> logger.warn("{}", warning.message()));
    if (admission instanceof TrackedEventAdmission.Admitted admitted) {
      synchronized (trackedEvents) {
        trackedEvents.add(admitted);
      }
    }
  }

  public List<TrackedEventAdmission.Admitted> trackedEvents() {
    synchronized (trackedEvents) {
      return List.copyOf(trackedEvents);
    }
  }

  public void clearTrackedEvents() {
    synchronized (trackedEvents) {
      trackedEvents.clear();
    }
  }

  @Override
  public void close(Duration timeout) {}

  @Override
  public void close() {}
}
