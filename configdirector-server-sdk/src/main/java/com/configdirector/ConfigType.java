package com.configdirector;

import java.util.Locale;

/** The type a config was declared with in the ConfigDirector dashboard. */
public enum ConfigType {
  CUSTOM,
  BOOLEAN,
  STRING,
  INTEGER,
  FLOAT,
  ENUM,
  URL,
  JSON;

  /** Returns null for a type this SDK version does not know about, rather than throwing. */
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

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
