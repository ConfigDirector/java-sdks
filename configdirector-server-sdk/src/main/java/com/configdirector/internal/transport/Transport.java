package com.configdirector.internal.transport;

import java.time.Duration;

public interface Transport extends AutoCloseable {

  Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  // Blocks until config state has arrived, the connection has failed unrecoverably, or the
  // timeout elapses -- whichever comes first. Throws only on an unrecoverable failure; a
  // transient one leaves the transport retrying in the background.
  void connect(Duration timeout);

  boolean isConnected();

  // Waits up to `timeout` for a request already on the wire; Duration.ZERO does not wait.
  void close(Duration timeout);

  @Override
  default void close() {
    close(CLOSE_TIMEOUT);
  }
}
