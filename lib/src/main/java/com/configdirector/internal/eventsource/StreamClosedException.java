package com.configdirector.internal.eventsource;

public class StreamClosedException extends EventSourceException {

  private static final long serialVersionUID = 1L;

  public StreamClosedException(String message) {
    super(message);
  }

  public StreamClosedException(String message, Throwable cause) {
    super(message, cause);
  }
}
