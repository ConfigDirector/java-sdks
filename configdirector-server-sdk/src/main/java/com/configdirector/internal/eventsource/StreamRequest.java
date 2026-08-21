package com.configdirector.internal.eventsource;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

// connectTimeout covers establishing the connection only; readTimeout is how long an open stream
// may stay silent before it counts as dead. Duration.ZERO means no limit for either. body is a
// carrier: it is neither copied nor part of this record's equality.
// Suppressed rather than fixed: this is a parameter object handed straight to a transport, its
// equality is never used, and the alternative is a handwritten class of pure boilerplate.
@SuppressWarnings("ArrayRecordComponent")
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
