package com.configdirector;

import java.util.List;

/**
 * Emitted when new config state arrives from the server.
 *
 * @param keys the keys the update carried, and the keys of every config whose targeting rules use a
 *     segment the update carried, sorted. Never null; an unmodifiable copy
 * @param removedKeys the keys a full update no longer carried, so the client stopped serving them,
 *     sorted. Empty when nothing was removed, and always empty for a delta update. Never null; an
 *     unmodifiable copy
 */
public record ConfigsUpdatedEvent(List<String> keys, List<String> removedKeys) {

  /** Copies both lists, so a later change to the caller's lists cannot alter the event. */
  public ConfigsUpdatedEvent {
    keys = keys == null ? List.of() : List.copyOf(keys);
    removedKeys = removedKeys == null ? List.of() : List.copyOf(removedKeys);
  }

  /**
   * An update that removed nothing.
   *
   * @param keys the keys the update carried, and the keys of every config whose targeting rules use
   *     a segment the update carried
   */
  public ConfigsUpdatedEvent(List<String> keys) {
    this(keys, List.of());
  }
}
