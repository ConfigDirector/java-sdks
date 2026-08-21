package com.configdirector.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import com.configdirector.Context;
import com.configdirector.EvaluationReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EvaluatedConfigEventTest {

  private static ConfigEvaluation evaluation(Object value, String valueId) {
    return new ConfigEvaluation("my-config", value, false, EvaluationReason.FOUND_MATCH, valueId, null);
  }

  private static EvaluatedConfigEvent event() {
    return EvaluatedConfigEvent.of(evaluation("hello", "server-id"), "fallback", ConfigType.STRING, null);
  }

  @Nested
  @DisplayName("capturing an evaluation")
  class Capturing {

    @Test
    void reports_both_values_and_the_type_that_was_asked_for() {
      EvaluatedConfigEvent captured = event();

      assertThat(captured.key()).isEqualTo("my-config");
      assertThat(captured.defaultValue().value()).isEqualTo("fallback");
      assertThat(captured.evaluatedValue().value()).isEqualTo("hello");
      assertThat(captured.requestedType()).isEqualTo("String");
      assertThat(captured.usedDefault()).isFalse();
      assertThat(captured.reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
    }

    @Test
    void only_the_evaluated_value_carries_the_server_value_id() {
      // The default came from the caller's code, so the server has never seen it.
      EvaluatedConfigEvent captured = event();

      assertThat(captured.defaultValue().valueId()).isNull();
      assertThat(captured.evaluatedValueId()).isEqualTo("server-id");
    }

    @Test
    void carries_the_context_it_was_told_about() {
      EvaluatedConfigEvent captured =
          EvaluatedConfigEvent.of(evaluation("hello", null), "fallback", ConfigType.STRING, "user-1");

      assertThat(captured.contextId()).isEqualTo("user-1");
    }
  }

  @Nested
  @DisplayName("the type the caller asked for")
  class RequestedType {

    static List<Arguments> defaults() {
      return List.of(
          Arguments.of("text", "String"),
          Arguments.of(true, "Boolean"),
          Arguments.of(26, "Integer"),
          Arguments.of(26L, "Long"),
          Arguments.of(1.5, "Double"),
          Arguments.of(1.5f, "Float"),
          Arguments.of(Map.of("a", 1), "Map"),
          Arguments.of(List.of(1, 2), "List"));
    }

    @ParameterizedTest
    @MethodSource("defaults")
    void is_named_the_way_java_names_it(Object defaultValue, String expected) {
      assertThat(EvaluatedConfigEvent.requestedTypeOf(defaultValue)).isEqualTo(expected);
    }

    @Test
    void names_a_collection_by_its_interface_rather_than_its_implementation() {
      // Otherwise one call site would split into HashMap and LinkedHashMap on the dashboard.
      assertThat(EvaluatedConfigEvent.requestedTypeOf(new java.util.TreeMap<>())).isEqualTo("Map");
      assertThat(EvaluatedConfigEvent.requestedTypeOf(new java.util.ArrayList<>())).isEqualTo("List");
    }
  }

  @Nested
  @DisplayName("aggregating")
  class Aggregating {

    @Test
    void identical_evaluations_compare_equal() {
      assertThat(event()).isEqualTo(event()).hasSameHashCodeAs(event());
    }

    @Test
    void an_evaluation_of_another_config_does_not() {
      EvaluatedConfigEvent other =
          EvaluatedConfigEvent.of(
              new ConfigEvaluation(
                  "other-config", "hello", false, EvaluationReason.FOUND_MATCH, "server-id", null),
              "fallback",
              ConfigType.STRING,
              null);

      assertThat(other).isNotEqualTo(event());
    }

    @Test
    void an_evaluation_for_another_context_does_not() {
      EvaluatedConfigEvent other =
          EvaluatedConfigEvent.of(evaluation("hello", "server-id"), "fallback", ConfigType.STRING, "user-1");

      assertThat(other).isNotEqualTo(event());
    }

    @Test
    void an_evaluation_that_produced_another_value_does_not() {
      EvaluatedConfigEvent other =
          EvaluatedConfigEvent.of(evaluation("goodbye", "other-id"), "fallback", ConfigType.STRING, null);

      assertThat(other).isNotEqualTo(event());
    }
  }

  @Nested
  @DisplayName("compacting")
  class Compacting {

    @Test
    void reduces_both_values() {
      String oversized = "x".repeat(TelemetryValue.CONFIG_VALUE_MAX_LENGTH + 1);
      EvaluatedConfigEvent captured =
          EvaluatedConfigEvent.of(evaluation(oversized, null), oversized, ConfigType.STRING, null);

      EvaluatedConfigEvent compacted = captured.compacted();

      assertThat(compacted.defaultValue().valueId()).isEqualTo(ValueIds.generate(oversized));
      assertThat(compacted.evaluatedValue().valueId()).isEqualTo(ValueIds.generate(oversized));
    }

    @Test
    void leaves_the_rest_of_the_event_alone() {
      EvaluatedConfigEvent compacted = event().compacted();

      assertThat(compacted.key()).isEqualTo("my-config");
      assertThat(compacted.requestedType()).isEqualTo("String");
      assertThat(compacted.evaluatedValueId()).isEqualTo("server-id");
      assertThat(compacted.reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
    }
  }

  @Nested
  @DisplayName("the wire form")
  class Wire {

    @Test
    void writes_the_field_names_the_server_reads() {
      Map<String, Object> wire =
          EvaluatedConfigEvent.of(evaluation("hello", "server-id"), "fallback", ConfigType.STRING, "user-1")
              .toWire();

      assertThat(wire)
          .containsEntry("contextId", "user-1")
          .containsEntry("key", "my-config")
          .containsEntry("type", "string")
          .containsEntry("defaultValue", Map.of("value", "fallback"))
          .containsEntry("requestedType", "String")
          .containsEntry("evaluatedValue", Map.of("value", "hello"))
          .containsEntry("evaluatedValueId", "server-id")
          .containsEntry("usedDefault", false)
          .containsEntry("evaluationReason", "found-match");
    }

    @Test
    void omits_the_context_and_the_type_when_there_are_none() {
      Map<String, Object> wire =
          EvaluatedConfigEvent.of(evaluation("hello", null), "fallback", null, null).toWire();

      assertThat(wire).doesNotContainKeys("contextId", "type", "evaluatedValueId");
    }

    @Test
    void spells_every_reason_the_way_the_other_sdks_do() {
      ConfigEvaluation usedDefault =
          new ConfigEvaluation(
              "my-config", "fallback", true, EvaluationReason.CONFIG_STATE_MISSING, null, null);

      Map<String, Object> wire =
          EvaluatedConfigEvent.of(usedDefault, "fallback", null, null).toWire();

      assertThat(wire)
          .containsEntry("evaluationReason", "config-state-missing")
          .containsEntry("usedDefault", true);
    }

    @Test
    void reports_a_json_value_by_the_id_the_server_sent() {
      Map<String, Object> wire =
          EvaluatedConfigEvent.of(
                  evaluation(Map.of("a", 1), "server-id"), Map.of("b", 2), ConfigType.JSON, null)
              .toWire();

      assertThat(wire)
          .containsEntry("evaluatedValue", Map.of("valueId", "server-id", "type", "json"))
          .containsEntry("defaultValue", Map.of("value", "{\"b\":2}", "type", "json"));
    }
  }

  @Nested
  @DisplayName("a context that must not be reported")
  class Contexts {

    @Test
    void is_never_taken_from_the_evaluation_itself() {
      // Whether a context may be identified is the collector's call, so the event only ever
      // carries the ID it was handed.
      Context anonymous = Context.builder().id("user-1").anonymous(true).build();
      ConfigEvaluation evaluated =
          new ConfigEvaluation(
              "my-config", "hello", false, EvaluationReason.FOUND_MATCH, null, anonymous);

      assertThat(EvaluatedConfigEvent.of(evaluated, "fallback", null, null).contextId()).isNull();
    }
  }
}
