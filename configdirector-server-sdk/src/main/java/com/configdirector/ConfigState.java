package com.configdirector;

/**
 * The raw, evaluated state of a single config, before type parsing.
 *
 * @param id the config's identifier in ConfigDirector
 * @param key the config's key
 * @param type the type the config was declared with, or null when the SDK does not recognize it
 * @param value the selected value rendered as text, or null when the config had no default
 *     targeting rule value to fall back to
 * @param valueId the server's identifier for whichever value was selected, carried for telemetry
 * @param variation the variation that was selected, or null when the config has no variations
 * @param ruleId the identifier of the targeting rule whose percentage rollout selected the value,
 *     or null when no percentage rollout did
 * @param shareId the identifier of the percentage rollout's share that selected the value, or null
 *     when no percentage rollout did
 */
public record ConfigState(
    String id,
    String key,
    ConfigType type,
    String value,
    String valueId,
    Variation variation,
    String ruleId,
    String shareId) {

  /**
   * The state of a config that selected no variation and no percentage rollout.
   *
   * @param id the config's identifier in ConfigDirector
   * @param key the config's key
   * @param type the type the config was declared with, or null when the SDK does not recognize it
   * @param value the selected value rendered as text, or null when the config had no default
   *     targeting rule value to fall back to
   * @param valueId the server's identifier for whichever value was selected
   */
  public ConfigState(String id, String key, ConfigType type, String value, String valueId) {
    this(id, key, type, value, valueId, null, null, null);
  }
}
