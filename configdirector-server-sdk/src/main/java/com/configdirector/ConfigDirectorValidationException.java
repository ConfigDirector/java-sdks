package com.configdirector;

import java.io.Serial;

/** Thrown when an argument has an unusable value, such as an empty config key. */
public class ConfigDirectorValidationException extends ConfigDirectorException {

  @Serial
  private static final long serialVersionUID = 1L;

  public ConfigDirectorValidationException(String message) {
    super(message);
  }
}
