package com.configdirector;

/**
 * The result of evaluating a single config key.
 *
 * <p>{@code isDefault} is true when the caller's default was returned rather than a value from the
 * server, and {@code reason} says why. {@code valueId} and {@code context} may be null.
 */
public record ConfigEvaluation(
    String key,
    Object value,
    boolean isDefault,
    EvaluationReason reason,
    String valueId,
    Context context) {}
