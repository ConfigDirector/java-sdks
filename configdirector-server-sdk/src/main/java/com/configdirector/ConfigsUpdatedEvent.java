package com.configdirector;

import java.util.List;

/** The keys the update carried, sorted. */
public record ConfigsUpdatedEvent(List<String> keys) {

  public ConfigsUpdatedEvent {
    keys = keys == null ? List.of() : List.copyOf(keys);
  }
}
