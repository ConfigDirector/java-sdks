package com.configdirector.internal.telemetry;

import com.configdirector.internal.transport.HttpClient;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;

public record TelemetryCollectorOptions(
    String serverSdkKey,
    String baseUrl,
    Logger logger,
    // Owned by the client and shared with the transport: both talk to the same host.
    HttpClient http,
    int eventQueueLimit,
    Duration flushInterval,
    Duration initialFlushDelay) {

  public TelemetryCollectorOptions {
    Objects.requireNonNull(serverSdkKey, "serverSdkKey");
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(logger, "logger");
    Objects.requireNonNull(http, "http");
    Objects.requireNonNull(flushInterval, "flushInterval");
    Objects.requireNonNull(initialFlushDelay, "initialFlushDelay");
  }
}
