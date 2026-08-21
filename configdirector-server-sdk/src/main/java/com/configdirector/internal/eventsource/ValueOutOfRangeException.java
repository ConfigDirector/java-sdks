package com.configdirector.internal.eventsource;

public class ValueOutOfRangeException extends EventSourceException {

  private static final long serialVersionUID = 1L;

  public ValueOutOfRangeException(String message) {
    super(message);
  }
}
