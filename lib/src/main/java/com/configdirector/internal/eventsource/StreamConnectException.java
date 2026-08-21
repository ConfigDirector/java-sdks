package com.configdirector.internal.eventsource;

public class StreamConnectException extends EventSourceException {

  private static final long serialVersionUID = 1L;

  public StreamConnectException(String message, Throwable cause) {
    super(message, cause);
  }
}
