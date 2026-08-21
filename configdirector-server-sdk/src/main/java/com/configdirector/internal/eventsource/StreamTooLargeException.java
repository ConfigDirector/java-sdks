package com.configdirector.internal.eventsource;

import java.io.Serial;

public class StreamTooLargeException extends EventSourceException {

  @Serial
  private static final long serialVersionUID = 1L;

  public StreamTooLargeException(String message) {
    super(message);
  }
}
