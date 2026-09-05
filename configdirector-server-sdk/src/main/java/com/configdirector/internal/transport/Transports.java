package com.configdirector.internal.transport;

import com.configdirector.internal.SdkIdentity;
import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Transports {

  // Every request identifies the SDK by name and version. Left to itself OkHttp sends
  // "okhttp/5.x", which bot-protection layers in front of the API reject before the request ever
  // reaches the origin -- surfacing as a 403 that looks exactly like a rejected SDK key.
  public static final Map<String, String> REQUEST_HEADERS =
      Map.of("Content-Type", "application/json", "User-Agent", SdkIdentity.userAgent());

  private Transports() {}

  public static Transport create(ConnectionMode mode, TransportOptions options) {
    return switch (mode) {
      case ONE_TIME -> new OneTimeTransport(options);
      case POLLING -> new PollingTransport(options);
      case STREAMING -> new StreamingTransport(options);
    };
  }

  public static String resolve(String baseUrl, String path) {
    // The trailing slash is what keeps resolve from treating the last segment of a proxy base URL
    // as a file name and dropping it.
    String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    return URI.create(base).resolve(path).toString();
  }

  // Null-valued entries are omitted rather than sent as JSON null.
  public static byte[] jsonBody(Map<String, Object> payload) {
    JsonObject body = new JsonObject();
    payload.forEach(
        (key, value) -> {
          if (value instanceof Map<?, ?> nested) {
            JsonObject object = new JsonObject();
            nested.forEach((name, entry) -> object.addProperty(String.valueOf(name), String.valueOf(entry)));
            body.add(key, object);
          } else if (value != null) {
            body.addProperty(key, String.valueOf(value));
          }
        });
    return body.toString().getBytes(StandardCharsets.UTF_8);
  }

  // A 4xx means the request itself is wrong -- a revoked SDK key, a bad URL -- and repeating it
  // unchanged will only fail the same way.
  public static boolean isFatalStatus(Integer status) {
    return status != null && status >= 400 && status < 500;
  }

  public static ConfigDirectorConnectionException fatalStatusError(Integer status, String detail) {
    String headline = "Connection failed with status: " + (status == null ? "unknown" : status);
    String body = detail == null || detail.isBlank() ? "" : " (" + detail.strip() + ")";
    return new ConfigDirectorConnectionException(
        headline + body + ". This is an unrecoverable error, retry attempts will be ignored.",
        status);
  }

  static Map<String, Object> requestPayload(
      TransportOptions options, String lastUpdateTimestamp, String sessionId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("serverSdkKey", options.serverSdkKey());
    payload.put("metaContext", options.metaContext());
    payload.put("lastUpdateTimestamp", lastUpdateTimestamp);
    payload.put("sessionId", sessionId);
    return payload;
  }
}
