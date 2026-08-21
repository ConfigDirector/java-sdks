package com.configdirector.internal.telemetry;

@FunctionalInterface
public interface EventReporter {

  ReporterResponse report(EventReport report);
}
