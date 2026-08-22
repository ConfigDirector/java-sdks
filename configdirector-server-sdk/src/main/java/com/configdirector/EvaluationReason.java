package com.configdirector;

import java.util.Locale;

/** Why an evaluation produced the value that it did. */
public enum EvaluationReason {

  /** A value was found and returned. */
  FOUND_MATCH,

  /** The SDK holds no config by that key, so the default was returned. */
  CONFIG_STATE_MISSING,

  /** No config state has arrived yet, so the default was returned. */
  CLIENT_NOT_READY,

  /** The config matched but carries no value, so the default was returned. */
  VALUE_MISSING,

  /** The value would not parse as the number the default asked for. */
  INVALID_NUMBER,

  /** The value would not parse as the JSON shape the default asked for. */
  INVALID_JSON,

  /** The value is neither {@code true} nor {@code false}. */
  INVALID_BOOLEAN;

  /**
   * How ConfigDirector spells this reason on the wire.
   *
   * @return the kebab-case form of the name, such as {@code "found-match"}
   */
  public String wireName() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
