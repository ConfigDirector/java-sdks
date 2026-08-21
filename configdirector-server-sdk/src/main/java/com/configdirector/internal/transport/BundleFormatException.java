package com.configdirector.internal.transport;

import com.configdirector.ConfigDirectorException;

import java.io.Serial;

public class BundleFormatException extends ConfigDirectorException {

  @Serial
  private static final long serialVersionUID = 1L;

  public BundleFormatException(String message) {
    super(message);
  }

  public BundleFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
