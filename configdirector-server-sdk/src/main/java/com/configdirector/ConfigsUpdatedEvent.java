package com.configdirector;

import java.util.List;

/**
 * Emitted when new config state arrives from the server.
 *
 * @param keys the keys the update carried, sorted. Never null; an unmodifiable copy
 */
public record ConfigsUpdatedEvent(List<String> keys) {

  /** Copies the keys, so a later change to the caller's list cannot alter the event. */
  public ConfigsUpdatedEvent {
    keys = keys == null ? List.of() : List.copyOf(keys);
  }
}
