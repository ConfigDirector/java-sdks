package com.configdirector.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TelemetryJsonTest {

  private static Map<String, Object> map(Object... pairs) {
    Map<String, Object> members = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      members.put((String) pairs[index], pairs[index + 1]);
    }
    return members;
  }

  @Nested
  @DisplayName("serializing scalars")
  class Scalars {

    @Test
    void spells_them_the_way_json_stringify_does() {
      assertThat(TelemetryJson.stringify(null)).isEqualTo("null");
      assertThat(TelemetryJson.stringify(true)).isEqualTo("true");
      assertThat(TelemetryJson.stringify(false)).isEqualTo("false");
      assertThat(TelemetryJson.stringify(26)).isEqualTo("26");
      assertThat(TelemetryJson.stringify(26L)).isEqualTo("26");
      assertThat(TelemetryJson.stringify(1.5)).isEqualTo("1.5");
      assertThat(TelemetryJson.stringify("text")).isEqualTo("\"text\"");
    }

    @Test
    void writes_a_whole_float_without_a_fractional_part() {
      // JSON makes no int/float distinction, and every other SDK writes 26 here.
      assertThat(TelemetryJson.stringify(26.0)).isEqualTo("26");
      assertThat(TelemetryJson.stringify(26.0f)).isEqualTo("26");
    }

    @Test
    void writes_null_for_what_json_cannot_spell() {
      assertThat(TelemetryJson.stringify(Double.NaN)).isEqualTo("null");
      assertThat(TelemetryJson.stringify(Double.POSITIVE_INFINITY)).isEqualTo("null");
      assertThat(TelemetryJson.stringify(Double.NEGATIVE_INFINITY)).isEqualTo("null");
    }
  }

  @Nested
  @DisplayName("serializing strings")
  class Strings {

    @Test
    void escapes_what_json_requires() {
      assertThat(TelemetryJson.stringify("quote \" and \\ and \n"))
          .isEqualTo("\"quote \\\" and \\\\ and \\n\"");
    }

    @Test
    void escapes_control_characters_as_hex() {
      assertThat(TelemetryJson.stringify("\u0001")).isEqualTo("\"\\u0001\"");
      assertThat(TelemetryJson.stringify("\t\r\b\f")).isEqualTo("\"\\t\\r\\b\\f\"");
    }

    @Test
    void leaves_non_ascii_alone() {
      // Escaping it to \u00e9 would change the digest, and the other SDKs do not escape it.
      assertThat(TelemetryJson.stringify("café ☂")).isEqualTo("\"café ☂\"");
    }
  }

  @Nested
  @DisplayName("serializing documents")
  class Documents {

    @Test
    void writes_nothing_between_the_punctuation() {
      assertThat(TelemetryJson.stringify(map("a", 1, "b", 2))).isEqualTo("{\"a\":1,\"b\":2}");
      assertThat(TelemetryJson.stringify(List.of(1, "two", true))).isEqualTo("[1,\"two\",true]");
    }

    @Test
    void preserves_key_order_rather_than_sorting() {
      assertThat(TelemetryJson.stringify(map("b", 1, "a", 2))).isEqualTo("{\"b\":1,\"a\":2}");
    }

    @Test
    void writes_empty_containers() {
      assertThat(TelemetryJson.stringify(map())).isEqualTo("{}");
      assertThat(TelemetryJson.stringify(List.of())).isEqualTo("[]");
    }

    @Test
    void nests_to_any_depth() {
      Map<String, Object> nested = map("nested", map("list", Arrays.asList(1.0, map("deep", false))));

      assertThat(TelemetryJson.stringify(nested))
          .isEqualTo("{\"nested\":{\"list\":[1,{\"deep\":false}]}}");
    }

    @Test
    void writes_a_null_member() {
      List<Object> withNull = new ArrayList<>();
      withNull.add(null);

      assertThat(TelemetryJson.stringify(map("a", null))).isEqualTo("{\"a\":null}");
      assertThat(TelemetryJson.stringify(withNull)).isEqualTo("[null]");
    }

    @Test
    void writes_a_non_string_key_as_a_string() {
      assertThat(TelemetryJson.stringify(Map.of(1, "one"))).isEqualTo("{\"1\":\"one\"}");
    }

    @Test
    void falls_back_to_how_a_value_describes_itself() {
      // A trait built out of something JSON cannot represent is still worth counting.
      Object unrepresentable =
          new Object() {
            @Override
            public String toString() {
              return "not-json";
            }
          };

      assertThat(TelemetryJson.stringify(map("when", unrepresentable)))
          .isEqualTo("{\"when\":\"not-json\"}");
    }
  }
}
