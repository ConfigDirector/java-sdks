package com.configdirector;

/**
 * The raw, evaluated state of a single config, before type parsing.
 *
 * <p>{@code value} is the selected value rendered as text, or null when the config had no default
 * to fall back to. {@code valueId} is the server's identifier for whichever value was selected,
 * carried for telemetry.
 */
public record ConfigState(String id, String key, ConfigType type, String value, String valueId) {}
