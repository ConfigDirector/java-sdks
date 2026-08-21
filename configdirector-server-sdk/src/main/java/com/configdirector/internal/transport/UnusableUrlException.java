package com.configdirector.internal.transport;

import com.configdirector.ConfigDirectorException;

import java.io.Serial;

public class UnusableUrlException extends ConfigDirectorException {

  @Serial
  private static final long serialVersionUID = 1L;

  public UnusableUrlException(String message, Throwable cause) {
    super(message, cause);
  }
}
