package com.configdirector;

import java.io.Serial;

/** Base class for every exception thrown by the ConfigDirector SDK. */
public class ConfigDirectorException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Builds an exception with no underlying cause.
   *
   * @param message what went wrong
   */
  public ConfigDirectorException(String message) {
    super(message);
  }

  /**
   * Builds an exception that wraps the failure underneath it.
   *
   * @param message what went wrong
   * @param cause the failure underneath, such as the {@link java.io.IOException} that ended a
   *     request
   */
  public ConfigDirectorException(String message, Throwable cause) {
    super(message, cause);
  }
}
