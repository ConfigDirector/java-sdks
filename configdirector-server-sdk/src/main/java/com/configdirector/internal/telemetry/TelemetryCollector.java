package com.configdirector.internal.telemetry;

import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import java.time.Duration;

public interface TelemetryCollector extends AutoCloseable {

  void recordEvaluation(ConfigEvaluation evaluation, Object defaultValue, ConfigType type);

  void close(Duration timeout);

  @Override
  void close();
}
