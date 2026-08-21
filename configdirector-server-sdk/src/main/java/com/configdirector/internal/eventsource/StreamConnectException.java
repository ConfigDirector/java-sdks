package com.configdirector.internal.eventsource;

import java.io.Serial;

public class StreamConnectException extends EventSourceException {

  @Serial
  private static final long serialVersionUID = 1L;

  public StreamConnectException(String message, Throwable cause) {
    super(message, cause);
  }
}
