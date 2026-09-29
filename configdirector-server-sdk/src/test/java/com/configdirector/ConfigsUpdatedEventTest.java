package com.configdirector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigsUpdatedEventTest {

  @Test
  void the_keys_only_constructor_removes_nothing() {
    ConfigsUpdatedEvent event = new ConfigsUpdatedEvent(List.of("a"));

    assertThat(event.keys()).containsExactly("a");
    assertThat(event.removedKeys()).isEmpty();
  }

  @Test
  void null_lists_become_empty() {
    ConfigsUpdatedEvent event = new ConfigsUpdatedEvent(null, null);

    assertThat(event.keys()).isEmpty();
    assertThat(event.removedKeys()).isEmpty();
  }

  @Test
  void a_later_change_to_the_callers_lists_does_not_alter_the_event() {
    List<String> keys = new ArrayList<>(List.of("a"));
    List<String> removedKeys = new ArrayList<>(List.of("b"));
    ConfigsUpdatedEvent event = new ConfigsUpdatedEvent(keys, removedKeys);

    keys.add("c");
    removedKeys.add("d");

    assertThat(event.keys()).containsExactly("a");
    assertThat(event.removedKeys()).containsExactly("b");
  }
}
