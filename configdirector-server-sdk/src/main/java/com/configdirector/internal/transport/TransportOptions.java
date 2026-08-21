package com.configdirector.internal.transport;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;

public record TransportOptions(
    String serverSdkKey,
    String baseUrl,
    Map<String, String> metaContext,
    Logger logger,
    Consumer<ConfigBundle> onBundle,
    // Owned by the client and shared with the telemetry reporter: both talk to the same host.
    HttpClient http,
    Duration pollingInterval) {

  public TransportOptions {
    Objects.requireNonNull(serverSdkKey, "serverSdkKey");
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(logger, "logger");
    Objects.requireNonNull(onBundle, "onBundle");
    Objects.requireNonNull(http, "http");
    Objects.requireNonNull(pollingInterval, "pollingInterval");
    metaContext = metaContext == null ? Map.of() : Map.copyOf(metaContext);
  }

  public TransportOptions withPollingInterval(Duration interval) {
    return new TransportOptions(
        serverSdkKey, baseUrl, metaContext, logger, onBundle, http, interval);
  }
}
