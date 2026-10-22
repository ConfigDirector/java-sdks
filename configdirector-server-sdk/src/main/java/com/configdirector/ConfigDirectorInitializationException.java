package com.configdirector;

import java.io.Serial;

/**
 * Thrown by {@link ConfigDirectorClient#initialize()} when the client cannot start. An offline
 * client throws it when its values cannot be loaded, so an application never runs on in-code
 * default values it did not ask for.
 */
public class ConfigDirectorInitializationException extends ConfigDirectorException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Builds an initialization failure with no underlying cause.
   *
   * @param message why the client could not start
   */
  public ConfigDirectorInitializationException(String message) {
    super(message);
  }

  /**
   * Builds an initialization failure that wraps the failure underneath it.
   *
   * @param message why the client could not start
   * @param cause the failure underneath, such as the {@link java.io.IOException} that ended reading
   *     a file
   */
  public ConfigDirectorInitializationException(String message, Throwable cause) {
    super(message, cause);
  }
}
