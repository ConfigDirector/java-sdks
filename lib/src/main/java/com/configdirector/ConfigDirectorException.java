package com.configdirector;

/** Base class for every exception thrown by the ConfigDirector SDK. */
public class ConfigDirectorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ConfigDirectorException(String message) {
    super(message);
  }

  public ConfigDirectorException(String message, Throwable cause) {
    super(message, cause);
  }
}
