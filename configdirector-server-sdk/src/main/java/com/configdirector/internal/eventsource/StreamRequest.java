package com.configdirector.internal.eventsource;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * {@code connectTimeout} covers establishing the connection only; {@code readTimeout} is how long
 * an open stream may stay silent before it counts as dead. {@link Duration#ZERO} means no limit for
 * either. {@code body} is not copied.
 */
public record StreamRequest(
    String url,
    String method,
    Map<String, String> headers,
    byte[] body,
    Duration connectTimeout,
    Duration readTimeout,
    boolean followRedirects) {

  public StreamRequest {
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(readTimeout, "readTimeout");
    headers = Map.copyOf(headers);
  }
}
