package com.configdirector.internal.telemetry;

import java.util.List;

record ReportableOption<T>(T reported, List<TrackedEventWarning> warnings) {

  static <T> ReportableOption<T> notGiven() {
    return new ReportableOption<>(null, List.of());
  }

  static <T> ReportableOption<T> reportable(T reported) {
    return new ReportableOption<>(reported, List.of());
  }

  static <T> ReportableOption<T> leftOut(TrackedEventWarning warning) {
    return new ReportableOption<>(null, List.of(warning));
  }
}
