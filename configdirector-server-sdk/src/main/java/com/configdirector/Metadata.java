package com.configdirector;

/**
 * Metadata about the calling application, referenceable from targeting rules.
 *
 * @param appName the application's name, or null
 * @param appVersion the version it is running, or null
 */
public record Metadata(String appName, String appVersion) {

  /**
   * Metadata with neither field set.
   *
   * @return the shared empty metadata
   */
  public static Metadata empty() {
    return EMPTY;
  }

  private static final Metadata EMPTY = new Metadata(null, null);
}
