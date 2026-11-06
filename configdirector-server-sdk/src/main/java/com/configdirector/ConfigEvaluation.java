package com.configdirector;

/**
 * The result of evaluating a single config key.
 *
 * @param key the config that was evaluated
 * @param value what the evaluation returned, in the type the in-code default value asked for
 * @param isDefault true when the in-code default value was returned rather than a value from
 *     ConfigDirector
 * @param reason why the evaluation produced the value that it did
 * @param valueId identifies the value that was returned: the server's identifier for it, or one
 *     derived from the value itself when it came from the in-code default value or from offline
 *     values
 * @param context the context the config was evaluated against, or null when none was supplied or
 *     the one supplied was ignored for its invalid kind
 * @param variation the variation that was served, or null when the config has no variations or the
 *     in-code default value was returned
 */
public record ConfigEvaluation(
    String key,
    Object value,
    boolean isDefault,
    EvaluationReason reason,
    String valueId,
    Context context,
    Variation variation) {

  /**
   * An evaluation that served no variation.
   *
   * @param key the config that was evaluated
   * @param value what the evaluation returned, in the type the in-code default value asked for
   * @param isDefault true when the in-code default value was returned rather than a value from
   *     ConfigDirector
   * @param reason why the evaluation produced the value that it did
   * @param valueId identifies the value that was returned
   * @param context the context the config was evaluated against, or null when none was supplied or
   *     the one supplied was ignored for its invalid kind
   */
  public ConfigEvaluation(
      String key,
      Object value,
      boolean isDefault,
      EvaluationReason reason,
      String valueId,
      Context context) {
    this(key, value, isDefault, reason, valueId, context, null);
  }
}
