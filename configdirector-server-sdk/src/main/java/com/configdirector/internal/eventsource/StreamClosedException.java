package com.configdirector.internal.eventsource;

import java.io.Serial;

public class StreamClosedException extends EventSourceException {

  @Serial
  private static final long serialVersionUID = 1L;

  public StreamClosedException(String message) {
    super(message);
  }

  public StreamClosedException(String message, Throwable cause) {
    super(message, cause);
  }
}
