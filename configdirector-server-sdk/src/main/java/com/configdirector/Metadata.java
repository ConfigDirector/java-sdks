package com.configdirector;

/**
 * Metadata about the calling application, referenceable from targeting rules. Both fields may be
 * null.
 */
public record Metadata(String appName, String appVersion) {

  private static final Metadata EMPTY = new Metadata(null, null);

  public static Metadata empty() {
    return EMPTY;
  }
}
