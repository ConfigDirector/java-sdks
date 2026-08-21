package com.configdirector.internal.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FakeEventReporter implements EventReporter {

  private final List<EventReport> reports = Collections.synchronizedList(new ArrayList<>());

  private volatile ReporterResponse response = ReporterResponse.SUCCEEDED;
  private volatile RuntimeException failure;

  @Override
  public ReporterResponse report(EventReport report) {
    reports.add(report);
    RuntimeException thrown = failure;
    if (thrown != null) {
      throw thrown;
    }
    return response;
  }

  void respondWith(ReporterResponse response) {
    this.response = response;
  }

  void throwOnReport(RuntimeException failure) {
    this.failure = failure;
  }

  List<EventReport> reports() {
    synchronized (reports) {
      return new ArrayList<>(reports);
    }
  }

  int reportCount() {
    return reports().size();
  }

  EventReport lastReport() {
    List<EventReport> snapshot = reports();
    return snapshot.isEmpty() ? null : snapshot.get(snapshot.size() - 1);
  }

  List<AggregatedEvent> evaluations() {
    return reports().stream().flatMap(report -> report.evaluations().stream()).toList();
  }
}
