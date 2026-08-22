package com.configdirector;

import java.util.Locale;

/** The type a config was declared with in the ConfigDirector dashboard. */
public enum ConfigType {

  /** A value the dashboard applies no type constraint to. */
  CUSTOM,

  /** {@code true} or {@code false}. */
  BOOLEAN,

  /** Free text. */
  STRING,

  /** A whole number. */
  INTEGER,

  /** A number that may have a fractional part. */
  FLOAT,

  /** Text restricted to a set of allowed values. */
  ENUM,

  /** Text constrained to a URL. */
  URL,

  /** A JSON document. */
  JSON;

  /**
   * Reads the type as ConfigDirector spells it on the wire.
   *
   * @param name the wire name, such as {@code "json"}
   * @return the matching type, or null for a name this SDK version does not know about. Never
   *     throws: a type added to ConfigDirector after this SDK was released must not break an
   *     evaluation
   */
  public static ConfigType fromWireName(String name) {
    if (name == null) {
      return null;
    }
    try {
      return valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  /**
   * How ConfigDirector spells this type on the wire.
   *
   * @return the lowercase form of the name
   */
  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
