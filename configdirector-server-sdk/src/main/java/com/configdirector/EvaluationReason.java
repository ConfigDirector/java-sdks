package com.configdirector;

import java.util.Locale;

/** Why an evaluation produced the value that it did. */
public enum EvaluationReason {

  /** A value from ConfigDirector was found and returned. */
  FOUND_MATCH,

  /**
   * A percentage rollout was skipped because the context has no {@linkplain Context#id() id} to
   * place it in a share, so the default targeting rule value was returned. {@linkplain
   * ConfigEvaluation#isDefault() isDefault} is false, since that value came from ConfigDirector.
   */
  ROLLOUT_SKIPPED_NO_IDENTIFIER,

  /** The SDK holds no config by that key, so the in-code default value was returned. */
  CONFIG_STATE_MISSING,

  /** No config state has arrived yet, so the in-code default value was returned. */
  CLIENT_NOT_READY,

  /** The config matched but carries no value, so the in-code default value was returned. */
  VALUE_MISSING,

  /** The value would not parse as the number the in-code default value asked for. */
  INVALID_NUMBER,

  /** The value would not parse as the JSON shape the in-code default value asked for. */
  INVALID_JSON,

  /** The value is neither {@code true} nor {@code false}. */
  INVALID_BOOLEAN,

  /**
   * The config holds a type the in-code default value did not ask for, so that value was returned.
   */
  TYPE_MISMATCH;

  /**
   * How ConfigDirector spells this reason on the wire.
   *
   * @return the kebab-case form of the name, such as {@code "found-match"}
   */
  public String wireName() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
