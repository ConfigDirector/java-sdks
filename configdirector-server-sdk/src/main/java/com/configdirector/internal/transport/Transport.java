package com.configdirector.internal.transport;

import java.time.Duration;

public interface Transport extends AutoCloseable {

  // Blocks until config state has arrived, the connection has failed unrecoverably, or the
  // timeout elapses -- whichever comes first. Throws only on an unrecoverable failure; a
  // transient one leaves the transport retrying in the background.
  void connect(Duration timeout);

  boolean isConnected();

  @Override
  void close();
}
