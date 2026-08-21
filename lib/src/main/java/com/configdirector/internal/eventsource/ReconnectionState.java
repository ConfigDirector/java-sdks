package com.configdirector.internal.eventsource;

import java.time.Duration;
import java.util.Objects;

/**
 * {@code status} is null when the connection failed before a response arrived; {@code error} is
 * null when the server simply answered with a status the stream could not continue from.
 */
public record ReconnectionState(
    int attempt, Duration serverReconnectDelay, Integer status, Throwable error) {

  public ReconnectionState {
    Objects.requireNonNull(serverReconnectDelay, "serverReconnectDelay");
  }
}
