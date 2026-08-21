package com.configdirector.internal.transport;

import java.time.Duration;

// One fetch and no polling thread, which a zero interval already means.
public final class OneTimeTransport extends PollingTransport {

  public OneTimeTransport(TransportOptions options) {
    super(options.withPollingInterval(Duration.ZERO));
  }
}
