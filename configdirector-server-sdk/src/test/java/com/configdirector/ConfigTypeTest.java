package com.configdirector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTypeTest {

  @ParameterizedTest
  @EnumSource(ConfigType.class)
  void every_type_round_trips_through_its_wire_name(ConfigType type) {
    assertThat(ConfigType.fromWireName(type.wireName())).isEqualTo(type);
  }

  @Test
  void wire_names_are_lowercase() {
    assertThat(ConfigType.BOOLEAN.wireName()).isEqualTo("boolean");
    assertThat(ConfigType.JSON.wireName()).isEqualTo("json");
  }

  @Test
  void parsing_ignores_case() {
    assertThat(ConfigType.fromWireName("BOOLEAN")).isEqualTo(ConfigType.BOOLEAN);
    assertThat(ConfigType.fromWireName("Boolean")).isEqualTo(ConfigType.BOOLEAN);
  }

  @ParameterizedTest
  @ValueSource(strings = {"somethingNew", "", " boolean", "bool"})
  void an_unrecognized_name_is_null_rather_than_an_exception(String name) {
    assertThat(ConfigType.fromWireName(name)).isNull();
  }

  @Test
  void a_null_name_is_null() {
    assertThat(ConfigType.fromWireName(null)).isNull();
  }
}
