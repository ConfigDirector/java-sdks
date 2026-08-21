package com.configdirector.internal.eventsource;

import java.io.Serial;

public class StreamStalledException extends EventSourceException {

  @Serial
  private static final long serialVersionUID = 1L;

  public StreamStalledException(String message, Throwable cause) {
    super(message, cause);
  }
}
