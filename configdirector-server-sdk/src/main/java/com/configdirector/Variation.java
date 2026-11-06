package com.configdirector;

/**
 * One of the values a config can serve, such as the control or a treatment of an experiment.
 *
 * @param id the identifier ConfigDirector assigned to the variation, which stays the same when it
 *     is renamed
 * @param name the variation's name, or null when it has none
 */
public record Variation(String id, String name) {}
