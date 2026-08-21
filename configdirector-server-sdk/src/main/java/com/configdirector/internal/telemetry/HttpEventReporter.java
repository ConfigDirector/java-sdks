package com.configdirector.internal.telemetry;

import com.configdirector.Context;
import com.configdirector.internal.SdkIdentity;
import com.configdirector.internal.transport.ConfigDirectorConnectionException;
import com.configdirector.internal.transport.HttpClient;
import com.configdirector.internal.transport.HttpResponse;
import com.configdirector.internal.transport.Transports;
import com.configdirector.internal.transport.UnusableUrlException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

public final class HttpEventReporter implements EventReporter {

  // Telemetry is best-effort background work, so it waits a good deal less than the transport does
  // before giving up and letting the next flush carry the events instead.
  public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private static final String PATH = "server/telemetry/v1";

  private final String serverSdkKey;
  private final String url;
  private final Logger logger;
  private final HttpClient http;
  private final Duration timeout;

  private volatile boolean sendRequests = true;

  public HttpEventReporter(String serverSdkKey, String baseUrl, Logger logger, HttpClient http) {
    this(serverSdkKey, baseUrl, logger, http, REQUEST_TIMEOUT);
  }

  public HttpEventReporter(
      String serverSdkKey, String baseUrl, Logger logger, HttpClient http, Duration timeout) {
    this.serverSdkKey = serverSdkKey;
    this.url = Transports.resolve(baseUrl, PATH);
    this.logger = logger;
    this.http = http;
    this.timeout = timeout;
  }

  @Override
  public ReporterResponse report(EventReport report) {
    if (!sendRequests) {
      return ReporterResponse.FATAL;
    }
    if (report.isEmpty()) {
      return ReporterResponse.SUCCEEDED;
    }

    ReporterResponse response =
        send(TelemetryJson.stringify(payload(report)).getBytes(StandardCharsets.UTF_8));
    if (response.fatal()) {
      sendRequests = false;
    }
    return response;
  }

  private Map<String, Object> payload(EventReport report) {
    List<Object> evaluations = new ArrayList<>(report.evaluations().size());
    report.evaluations().forEach(evaluation -> evaluations.add(evaluation.toWire()));
    List<Object> contexts = new ArrayList<>(report.contexts().size());
    report.contexts().forEach(context -> contexts.add(contextToWire(context)));

    Map<String, Object> metaContext = new LinkedHashMap<>();
    metaContext.put("sdkName", SdkIdentity.NAME);
    metaContext.put("sdkVersion", SdkIdentity.version());

    Map<String, Object> droppedEvents = new LinkedHashMap<>();
    droppedEvents.put("evaluatedConfig", report.droppedEvaluations());
    droppedEvents.put("capturedContexts", report.droppedContexts());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("serverSdkKey", serverSdkKey);
    payload.put("metaContext", metaContext);
    payload.put("discreteEvents", Map.of("capturedContexts", contexts));
    payload.put("aggregatedEvents", Map.of("evaluatedConfig", evaluations));
    payload.put("droppedEvents", droppedEvents);
    return payload;
  }

  // Only identified, non-anonymous contexts are ever captured, so anonymous is left out rather
  // than sent as a constant false.
  private static Map<String, Object> contextToWire(Context context) {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("id", context.id());
    if (context.name() != null) {
      wire.put("name", context.name());
    }
    if (context.traits() != null) {
      wire.put("traits", context.traits());
    }
    return wire;
  }

  private ReporterResponse send(byte[] body) {
    HttpResponse response;
    try {
      response = http.post(url, body, Transports.REQUEST_HEADERS, timeout);
    } catch (UnusableUrlException unusable) {
      logger.warn(
          "[EventReporter] The telemetry URL '{}' is unusable: {}. No more telemetry data will be sent.",
          url,
          unusable.getMessage());
      return ReporterResponse.FATAL;
    } catch (ConfigDirectorConnectionException error) {
      logger.warn("[EventReporter] Error attempting to send telemetry data", error);
      return ReporterResponse.FAILED;
    }

    if (Transports.isFatalStatus(response.status())) {
      logger.warn(
          "[EventReporter] Received a fatal status response ({}) from the telemetry endpoint. "
              + "No more telemetry data will be sent.",
          response.status());
      return ReporterResponse.FATAL;
    }
    if (response.ok()) {
      logger.debug("[EventReporter] Telemetry report successfully sent.");
      return ReporterResponse.SUCCEEDED;
    }

    logger.warn(
        "[EventReporter] The telemetry endpoint responded with status {}. The events in this "
            + "report were discarded.",
        response.status());
    return ReporterResponse.FAILED;
  }
}
