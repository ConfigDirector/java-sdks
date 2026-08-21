package com.configdirector.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TelemetryValueTest {

  private static final int MAX = TelemetryValue.CONFIG_VALUE_MAX_LENGTH;

  private static String oversized() {
    return "x".repeat(MAX + 1);
  }

  private static Map<String, Object> document() {
    Map<String, Object> members = new LinkedHashMap<>();
    members.put("b", 1);
    members.put("a", List.of(true));
    return members;
  }

  @Nested
  @DisplayName("capturing a value")
  class Capturing {

    @Test
    void reports_a_small_value_inline() {
      assertThat(TelemetryValue.of("hello", null, null))
          .isEqualTo(new TelemetryValue("hello", null, null));
    }

    @Test
    void spells_a_scalar_the_way_every_sdk_does() {
      assertThat(TelemetryValue.of(true, null, null).value()).isEqualTo("true");
      assertThat(TelemetryValue.of(26, null, null).value()).isEqualTo("26");
      assertThat(TelemetryValue.of(26.0, null, null).value()).isEqualTo("26");
      assertThat(TelemetryValue.of(1.5, null, null).value()).isEqualTo("1.5");
    }

    @Test
    void serializes_a_json_value_compactly() {
      assertThat(TelemetryValue.of(document(), null, ConfigType.JSON))
          .isEqualTo(new TelemetryValue("{\"b\":1,\"a\":[true]}", null, ConfigType.JSON));
    }

    @Test
    void treats_an_untyped_map_or_list_as_json() {
      // An evaluation that found no config state has no declared type to go on.
      assertThat(TelemetryValue.of(Map.of("a", 1), null, null).type()).isEqualTo(ConfigType.JSON);
      assertThat(TelemetryValue.of(List.of(1, 2), null, null).type()).isEqualTo(ConfigType.JSON);
    }

    @Test
    void does_not_treat_a_declared_string_as_json_however_it_reads() {
      assertThat(TelemetryValue.of("{\"a\":1}", null, ConfigType.STRING).type()).isNull();
    }

    @Test
    void prefers_the_value_id_the_server_sent_for_a_json_value() {
      assertThat(TelemetryValue.of(document(), "server-id", ConfigType.JSON))
          .isEqualTo(new TelemetryValue(null, "server-id", ConfigType.JSON));
    }

    @Test
    void reports_an_oversized_value_by_the_id_the_server_sent() {
      assertThat(TelemetryValue.of(oversized(), "server-id", null))
          .isEqualTo(new TelemetryValue(null, "server-id", null));
    }

    @Test
    void keeps_an_oversized_value_when_the_server_sent_no_id() {
      // It is compacted into an ID at flush time instead; the hashing does not belong on the
      // caller's thread.
      assertThat(TelemetryValue.of(oversized(), null, null))
          .isEqualTo(new TelemetryValue(oversized(), null, null));
    }
  }

  @Nested
  @DisplayName("compacting for the wire")
  class Compacting {

    @Test
    void leaves_a_small_value_inline() {
      assertThat(new TelemetryValue("hello", null, null).compacted())
          .isEqualTo(new TelemetryValue("hello", null, null));
    }

    @Test
    void keeps_a_value_of_exactly_the_maximum_length_inline() {
      String atLimit = "x".repeat(MAX);

      assertThat(new TelemetryValue(atLimit, null, null).compacted().value()).isEqualTo(atLimit);
    }

    @Test
    void replaces_an_oversized_value_with_its_id() {
      TelemetryValue compacted = new TelemetryValue(oversized(), null, null).compacted();

      assertThat(compacted.value()).isNull();
      assertThat(compacted.valueId()).isEqualTo(ValueIds.generate(oversized()));
    }

    @Test
    void replaces_every_json_document_with_its_id() {
      TelemetryValue compacted = new TelemetryValue("{\"a\":1}", null, ConfigType.JSON).compacted();

      assertThat(compacted.valueId()).isEqualTo(ValueIds.generate("{\"a\":1}"));
      assertThat(compacted.value()).isNull();
    }

    @Test
    void keeps_an_id_it_already_has() {
      assertThat(new TelemetryValue(null, "server-id", ConfigType.JSON).compacted())
          .isEqualTo(new TelemetryValue(null, "server-id", null));
    }

    @Test
    void drops_the_declared_type() {
      // The type is only there to decide how the value is reported; the server does not read it
      // back off the compacted value.
      assertThat(new TelemetryValue("hello", null, ConfigType.STRING).compacted().type()).isNull();
    }

    @Test
    void leaves_an_empty_value_alone() {
      assertThat(new TelemetryValue("", null, ConfigType.JSON).compacted())
          .isEqualTo(new TelemetryValue("", null, null));
    }
  }

  @Nested
  @DisplayName("the wire form")
  class Wire {

    @Test
    void names_the_fields_the_way_the_server_reads_them() {
      Map<String, Object> wire = new TelemetryValue("hello", "an-id", ConfigType.JSON).toWire();

      assertThat(wire).containsExactly(
          Map.entry("value", "hello"), Map.entry("valueId", "an-id"), Map.entry("type", "json"));
    }

    @Test
    void omits_what_was_not_set() {
      assertThat(new TelemetryValue("hello", null, null).toWire())
          .containsOnlyKeys("value");
    }
  }

  @Nested
  @DisplayName("rendering and identifying")
  class Rendering {

    @Test
    void renders_a_scalar_as_text_without_quoting_it() {
      assertThat(TelemetryValue.render("hello", ConfigType.STRING)).isEqualTo("hello");
      assertThat(TelemetryValue.render(true, ConfigType.BOOLEAN)).isEqualTo("true");
    }

    @Test
    void renders_a_document_as_compact_json() {
      assertThat(TelemetryValue.render(document(), ConfigType.JSON)).isEqualTo("{\"b\":1,\"a\":[true]}");
    }

    @Test
    void identifies_a_value_by_the_digest_of_how_it_renders() {
      assertThat(TelemetryValue.idFor("hello", ConfigType.STRING))
          .isEqualTo(ValueIds.generate("hello"));
      assertThat(TelemetryValue.idFor(document(), null))
          .isEqualTo(ValueIds.generate("{\"b\":1,\"a\":[true]}"));
    }
  }
}
