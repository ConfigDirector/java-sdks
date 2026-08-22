package com.configdirector;

/**
 * The result of evaluating a single config key.
 *
 * @param key the config that was evaluated
 * @param value what the evaluation returned, in the type the caller's default asked for
 * @param isDefault true when the caller's default was returned rather than a value from the server
 * @param reason why the evaluation produced the value that it did
 * @param valueId identifies the value that was returned: the server's identifier for it, or one
 *     derived from the value itself when it came from the caller's default
 * @param context the context the config was evaluated against, or null
 */
public record ConfigEvaluation(
    String key,
    Object value,
    boolean isDefault,
    EvaluationReason reason,
    String valueId,
    Context context) {}
