package com.configdirector.internal.telemetry;

@FunctionalInterface
public interface TelemetryCollectorFactory {

  TelemetryCollector create(TelemetryCollectorOptions options);
}
