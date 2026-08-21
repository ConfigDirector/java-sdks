package com.configdirector.internal.eventsource;

import com.configdirector.ConfigDirectorException;

public class EventSourceException extends ConfigDirectorException {

  private static final long serialVersionUID = 1L;

  public EventSourceException(String message) {
    super(message);
  }

  public EventSourceException(String message, Throwable cause) {
    super(message, cause);
  }
}
