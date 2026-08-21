package com.configdirector.internal.telemetry;

// The outcome of reporting one batch. A fatal outcome ends collection for the life of the client:
// the request itself is wrong, so repeating it unchanged would only fail the same way.
public record ReporterResponse(boolean success, boolean fatal) {

  public static final ReporterResponse SUCCEEDED = new ReporterResponse(true, false);
  public static final ReporterResponse FAILED = new ReporterResponse(false, false);
  public static final ReporterResponse FATAL = new ReporterResponse(false, true);
}
