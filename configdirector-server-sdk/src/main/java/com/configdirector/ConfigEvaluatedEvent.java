package com.configdirector;

/**
 * Emitted every time a config is evaluated, including evaluations that returned the caller's
 * default.
 *
 * @param evaluation what was asked for and what came back
 */
public record ConfigEvaluatedEvent(ConfigEvaluation evaluation) {}
