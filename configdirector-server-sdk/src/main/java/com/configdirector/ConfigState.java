package com.configdirector;

/**
 * The raw, evaluated state of a single config, before type parsing.
 *
 * @param id the config's identifier in ConfigDirector
 * @param key the config's key
 * @param type the type the config was declared with, or null when the SDK does not recognize it
 * @param value the selected value rendered as text, or null when the config had no default to fall
 *     back to
 * @param valueId the server's identifier for whichever value was selected, carried for telemetry
 */
public record ConfigState(String id, String key, ConfigType type, String value, String valueId) {}
