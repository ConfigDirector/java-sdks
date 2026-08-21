package com.configdirector.internal.transport;

import com.configdirector.ConfigDirectorException;

import java.io.Serial;

// status is null when the failure happened before any response arrived.
public class ConfigDirectorConnectionException extends ConfigDirectorException {

  @Serial
  private static final long serialVersionUID = 1L;

  private final Integer status;

  public ConfigDirectorConnectionException(String message) {
    this(message, null, null);
  }

  public ConfigDirectorConnectionException(String message, Integer status) {
    this(message, status, null);
  }

  public ConfigDirectorConnectionException(String message, Integer status, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  public Integer status() {
    return status;
  }
}
