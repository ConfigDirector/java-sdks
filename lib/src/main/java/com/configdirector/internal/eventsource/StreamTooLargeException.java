package com.configdirector.internal.eventsource;

public class StreamTooLargeException extends EventSourceException {

  private static final long serialVersionUID = 1L;

  public StreamTooLargeException(String message) {
    super(message);
  }
}
