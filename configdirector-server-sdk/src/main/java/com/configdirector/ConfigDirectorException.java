package com.configdirector;

import java.io.Serial;

/** Base class for every exception thrown by the ConfigDirector SDK. */
public class ConfigDirectorException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  public ConfigDirectorException(String message) {
    super(message);
  }

  public ConfigDirectorException(String message, Throwable cause) {
    super(message, cause);
  }
}
