package com.configdirector.internal.telemetry;

import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;

public final class TrackedEventRecordingTelemetryCollector implements TelemetryCollector {

  private final Logger logger;
  private final Consumer<TrackedEventAdmission.Admitted> onTrackedEvent;

  public TrackedEventRecordingTelemetryCollector(
      Logger logger, Consumer<TrackedEventAdmission.Admitted> onTrackedEvent) {
    this.logger = logger;
    this.onTrackedEvent = onTrackedEvent;
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
      onTrackedEvent.accept(admitted);
    }
  }

  @Override
  public void close(Duration timeout) {}

  @Override
  public void close() {}
}
