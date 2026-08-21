package com.configdirector.internal.eventsource;

public class StreamStalledException extends EventSourceException {

  private static final long serialVersionUID = 1L;

  public StreamStalledException(String message, Throwable cause) {
    super(message, cause);
  }
}
