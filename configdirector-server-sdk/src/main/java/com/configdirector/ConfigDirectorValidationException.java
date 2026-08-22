package com.configdirector;

import java.io.Serial;

/** Thrown when an argument has an unusable value, such as an empty config key. */
public class ConfigDirectorValidationException extends ConfigDirectorException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Builds a validation failure.
   *
   * @param message which argument was rejected, and what it should have been
   */
  public ConfigDirectorValidationException(String message) {
    super(message);
  }
}
