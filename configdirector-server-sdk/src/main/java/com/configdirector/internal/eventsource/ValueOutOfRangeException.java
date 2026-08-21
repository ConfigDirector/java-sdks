package com.configdirector.internal.eventsource;

import java.io.Serial;

public class ValueOutOfRangeException extends EventSourceException {

  @Serial
  private static final long serialVersionUID = 1L;

  public ValueOutOfRangeException(String message) {
    super(message);
  }
}
