package com.configdirector.internal.telemetry;

import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import java.time.Duration;

public final class DiscardingTelemetryCollector implements TelemetryCollector {

  public static final DiscardingTelemetryCollector INSTANCE = new DiscardingTelemetryCollector();

  private DiscardingTelemetryCollector() {}

  @Override
  public void recordEvaluation(ConfigEvaluation evaluation, Object defaultValue, ConfigType type) {}

  @Override
  public InvalidContextRecording recordInvalidContext(String kindSample) {
    return InvalidContextRecording.NEW_KIND_SAMPLE;
  }

  @Override
  public void close(Duration timeout) {}

  @Override
  public void close() {}
}
