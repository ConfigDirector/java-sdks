package com.configdirector;

/**
 * The result of evaluating a single config key.
 *
 * <p>{@code isDefault} is true when the caller's default was returned rather than a value from the
 * server, and {@code reason} says why. {@code valueId} identifies the value that was returned: the
 * server's identifier for it, or one derived from the value itself when it came from the caller's
 * default. {@code context} may be null.
 */
public record ConfigEvaluation(
    String key,
    Object value,
    boolean isDefault,
    EvaluationReason reason,
    String valueId,
    Context context) {}
