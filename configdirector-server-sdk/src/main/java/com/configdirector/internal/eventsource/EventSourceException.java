package com.configdirector.internal.eventsource;

import com.configdirector.ConfigDirectorException;

import java.io.Serial;

public class EventSourceException extends ConfigDirectorException {

  @Serial
  private static final long serialVersionUID = 1L;

  public EventSourceException(String message) {
    super(message);
  }

  public EventSourceException(String message, Throwable cause) {
    super(message, cause);
  }
}
