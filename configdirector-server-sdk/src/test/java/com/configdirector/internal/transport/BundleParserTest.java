package com.configdirector.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.configdirector.ConfigType;
import com.configdirector.internal.evaluation.Condition;
import com.configdirector.internal.evaluation.ConditionalRule;
import com.configdirector.internal.evaluation.Config;
import com.configdirector.internal.evaluation.EnumTypeConstraints;
import com.configdirector.internal.evaluation.NumericTypeConstraints;
import com.configdirector.internal.evaluation.PercentageRule;
import com.configdirector.internal.evaluation.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class BundleParserTest {

  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(BundleParserTest.class);

  private static ConfigBundle parse(String payload) {
    return BundleParser.parse(payload, LOGGER);
  }

  private static Config onlyConfig(String payload) {
    ConfigBundle bundle = parse(payload);
    assertThat(bundle.configs()).hasSize(1);
    return bundle.configs().values().iterator().next();
  }

  @Nested
  @DisplayName("the envelope")
  class Envelope {

    @Test
    void reads_the_identifying_fields() {
      ConfigBundle bundle =
          parse(
              """
              {"kind":"delta","environmentId":"env-1","projectId":"proj-1",
               "timestamp":"2026-01-01T00:00:00Z","configs":{}}
              """);

      assertThat(bundle.kind()).isEqualTo(ConfigBundle.BundleKind.DELTA);
      assertThat(bundle.environmentId()).isEqualTo("env-1");
      assertThat(bundle.projectId()).isEqualTo("proj-1");
      assertThat(bundle.timestamp()).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    void anything_that_is_not_a_delta_is_a_full_bundle() {
      assertThat(parse("{\"configs\":{}}").kind()).isEqualTo(ConfigBundle.BundleKind.FULL);
      assertThat(parse("{\"kind\":\"full\",\"configs\":{}}").kind())
          .isEqualTo(ConfigBundle.BundleKind.FULL);
      assertThat(parse("{\"kind\":\"something-new\",\"configs\":{}}").kind())
          .isEqualTo(ConfigBundle.BundleKind.FULL);
    }

    @Test
    void absent_optional_fields_are_null() {
      ConfigBundle bundle = parse("{\"configs\":{}}");

      assertThat(bundle.environmentId()).isNull();
      assertThat(bundle.projectId()).isNull();
      assertThat(bundle.timestamp()).isNull();
    }

    @Test
    void an_explicitly_empty_configs_object_yields_an_empty_bundle() {
      assertThat(parse("{\"configs\":{}}").configs()).isEmpty();
    }

    // Without a configs object there is nothing to apply. Treating one as an empty bundle would
    // let any other message the server sends -- a heartbeat, an error frame -- wipe config state.
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"configs\":null}", "{\"configs\":[]}", "{\"type\":\"heartbeat\"}"})
    void a_payload_carrying_no_configs_object_is_rejected(String payload) {
      assertThatExceptionOfType(NotAConfigBundleException.class).isThrownBy(() -> parse(payload));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"text\"", "42", "null", "true"})
    void a_payload_that_is_not_an_object_is_rejected(String payload) {
      assertThatExceptionOfType(BundleFormatException.class).isThrownBy(() -> parse(payload));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"configs\":}", "not json at all"})
    void malformed_json_is_rejected(String payload) {
      assertThatExceptionOfType(BundleFormatException.class).isThrownBy(() -> parse(payload));
    }

    @Test
    void the_configs_map_is_unmodifiable() {
      ConfigBundle bundle = parse("{\"configs\":{}}");

      assertThatExceptionOfType(UnsupportedOperationException.class)
          .isThrownBy(() -> bundle.configs().put("k", null));
    }
  }

  @Nested
  @DisplayName("a config")
  class Configs {

    private static final String SIMPLE =
        """
        {"configs":{"my-key":{
          "id":"c1","key":"my-key","type":"boolean",
          "target":{"defaultValue":"false","defaultValueId":"dv1","rules":[]}
        }}}
        """;

    @Test
    void reads_its_identity_and_default() {
      Config config = onlyConfig(SIMPLE);

      assertThat(config.id()).isEqualTo("c1");
      assertThat(config.key()).isEqualTo("my-key");
      assertThat(config.type()).isEqualTo(ConfigType.BOOLEAN);
      assertThat(config.target().defaultValue()).isEqualTo("false");
      assertThat(config.target().defaultValueId()).isEqualTo("dv1");
    }

    @Test
    void is_keyed_by_the_map_key_the_server_used() {
      assertThat(parse(SIMPLE).configs()).containsOnlyKeys("my-key");
    }

    @Test
    void an_unknown_type_is_null_rather_than_fatal() {
      Config config =
          onlyConfig(
              """
              {"configs":{"k":{"id":"c1","key":"k","type":"something-new",
               "target":{"defaultValue":"x","rules":[]}}}}
              """);

      assertThat(config.type()).isNull();
    }

    @Test
    void one_unreadable_config_does_not_cost_the_others() {
      ConfigBundle bundle =
          parse(
              """
              {"configs":{
                "good":{"id":"c1","key":"good","type":"string","target":{"defaultValue":"a","rules":[]}},
                "broken":{"key":"broken"},
                "alsoGood":{"id":"c2","key":"alsoGood","type":"string","target":{"defaultValue":"b","rules":[]}}
              }}
              """);

      assertThat(bundle.configs()).containsOnlyKeys("good", "alsoGood");
    }

    @Test
    void a_config_with_no_target_is_skipped() {
      assertThat(parse("{\"configs\":{\"k\":{\"id\":\"c1\",\"key\":\"k\",\"type\":\"string\"}}}").configs())
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("rules")
  class Rules {

    @Test
    void reads_a_conditional_rule_with_its_conditions() {
      Config config =
          onlyConfig(
              """
              {"configs":{"k":{"id":"c1","key":"k","type":"string","target":{
                "defaultValue":"off","rules":[{
                  "id":"r1","type":"conditional","order":2,"target":"value",
                  "value":"on","valueId":"v1",
                  "conditions":[{"id":"cond1","attribute":"identifier","operator":"=",
                                 "targetType":"text","targetValues":["u1"],"trait":null}]
                }]}}}}
              """);

      Rule rule = config.target().rules().get(0);
      assertThat(rule).isInstanceOf(ConditionalRule.class);
      ConditionalRule conditional = (ConditionalRule) rule;
      assertThat(conditional.id()).isEqualTo("r1");
      assertThat(conditional.order()).isEqualTo(2);
      assertThat(conditional.target()).isEqualTo("value");
      assertThat(conditional.value()).isEqualTo("on");
      assertThat(conditional.valueId()).isEqualTo("v1");

      Condition condition = conditional.conditions().get(0);
      assertThat(condition.attribute()).isEqualTo("identifier");
      assertThat(condition.targetValues()).containsExactly("u1");
      assertThat(condition.trait()).isNull();
    }

    @Test
    void reads_a_percentage_rule_with_its_buckets() {
      Config config =
          onlyConfig(
              """
              {"configs":{"k":{"id":"c1","key":"k","type":"string","target":{
                "defaultValue":"off","rules":[{
                  "id":"r1","type":"percentage","order":1,
                  "percentages":[{"id":"b1","percentage":25.5,"value":"on","valueId":"v1"},
                                 {"id":"b2","percentage":74.5,"value":"off","valueId":"v2"}]
                }]}}}}
              """);

      Rule rule = config.target().rules().get(0);
      assertThat(rule).isInstanceOf(PercentageRule.class);
      PercentageRule percentage = (PercentageRule) rule;
      assertThat(percentage.percentages()).hasSize(2);
      assertThat(percentage.percentages().get(0).percentage()).isEqualTo(25.5);
      assertThat(percentage.percentages().get(0).value()).isEqualTo("on");
      assertThat(percentage.percentages().get(0).valueId()).isEqualTo("v1");
    }

    @Test
    void a_rule_kind_this_version_predates_is_carried_as_conditional() {
      // Kept rather than dropped, so the reason it never matches stays visible in the evaluator.
      Config config =
          onlyConfig(
              """
              {"configs":{"k":{"id":"c1","key":"k","type":"string","target":{
                "defaultValue":"off","rules":[{"id":"r1","type":"something-new","order":1}]}}}}
              """);

      assertThat(config.target().rules().get(0)).isInstanceOf(ConditionalRule.class);
    }

    @Test
    void a_rule_without_an_order_keeps_a_null_order() {
      Config config =
          onlyConfig(
              """
              {"configs":{"k":{"id":"c1","key":"k","type":"string","target":{
                "defaultValue":"off","rules":[{"id":"r1","type":"conditional"}]}}}}
              """);

      assertThat(config.target().rules().get(0).order()).isNull();
    }

    @Test
    void the_target_defaults_to_value() {
      Config config =
          onlyConfig(
              """
              {"configs":{"k":{"id":"c1","key":"k","type":"string","target":{
                "defaultValue":"off","rules":[{"id":"r1","type":"conditional"}]}}}}
              """);

      assertThat(((ConditionalRule) config.target().rules().get(0)).target()).isEqualTo("value");
    }
  }

  @Nested
  @DisplayName("value rendering")
  class Values {

    private static Object ruleValue(String json) {
      Config config =
          onlyConfig(
              "{\"configs\":{\"k\":{\"id\":\"c1\",\"key\":\"k\",\"type\":\"string\","
                  + "\"target\":{\"defaultValue\":\"off\",\"rules\":[{\"id\":\"r1\","
                  + "\"type\":\"conditional\",\"value\":"
                  + json
                  + "}]}}}}");
      return ((ConditionalRule) config.target().rules().get(0)).value();
    }

    @Test
    void scalars_keep_their_json_type() {
      assertThat(ruleValue("\"text\"")).isEqualTo("text");
      assertThat(ruleValue("true")).isEqualTo(true);
      assertThat(ruleValue("null")).isNull();
    }

    @Test
    void a_whole_number_stays_whole() {
      // Not 26.0, which would render as "26.0" where the other SDKs say "26".
      assertThat(ruleValue("26")).isEqualTo(26L);
      assertThat(ruleValue("26.5")).isEqualTo(26.5);
    }

    @Test
    void a_structured_value_becomes_the_json_text_it_was_sent_as() {
      assertThat(ruleValue("{\"a\":1}")).isEqualTo("{\"a\":1}");
      assertThat(ruleValue("[1,2]")).isEqualTo("[1,2]");
    }

    @Test
    void target_values_render_the_way_every_sdk_renders_them() {
      Config config =
          onlyConfig(
              """
              {"configs":{"k":{"id":"c1","key":"k","type":"string","target":{
                "defaultValue":"off","rules":[{"id":"r1","type":"conditional","conditions":[
                  {"id":"c","attribute":"identifier","operator":"=","targetType":"text",
                   "targetValues":["text", 26, 26.5, true, null]}]}]}}}}
              """);

      Condition condition = ((ConditionalRule) config.target().rules().get(0)).conditions().get(0);
      assertThat(condition.targetValues()).containsExactly("text", "26", "26.5", "true", "");
    }

    @Test
    void a_missing_default_value_becomes_the_empty_string() {
      Config config =
          onlyConfig("{\"configs\":{\"k\":{\"id\":\"c1\",\"key\":\"k\",\"type\":\"string\",\"target\":{}}}}");

      assertThat(config.target().defaultValue()).isEmpty();
    }
  }

  @Nested
  @DisplayName("type constraints")
  class Constraints {

    private static Object constraintsOf(String json) {
      return onlyConfig(
              "{\"configs\":{\"k\":{\"id\":\"c1\",\"key\":\"k\",\"type\":\"string\","
                  + "\"target\":{\"defaultValue\":\"x\",\"rules\":[]},\"typeConstraints\":"
                  + json
                  + "}}}")
          .typeConstraints();
    }

    @Test
    void reads_numeric_bounds_with_their_relations() {
      NumericTypeConstraints constraints =
          (NumericTypeConstraints)
              constraintsOf("{\"min\":{\"relation\":\">=\",\"value\":1},\"max\":{\"relation\":\"<\",\"value\":10}}");

      assertThat(constraints.min().relation()).isEqualTo(">=");
      assertThat(constraints.min().value()).isEqualTo(1.0);
      assertThat(constraints.max().relation()).isEqualTo("<");
      assertThat(constraints.max().value()).isEqualTo(10.0);
    }

    @Test
    void an_absent_bound_is_null() {
      NumericTypeConstraints constraints =
          (NumericTypeConstraints) constraintsOf("{\"min\":{\"relation\":\">\",\"value\":1}}");

      assertThat(constraints.max()).isNull();
    }

    @Test
    void reads_enum_constraints() {
      EnumTypeConstraints constraints =
          (EnumTypeConstraints) constraintsOf("{\"valueType\":\"number\",\"values\":[1,2,3]}");

      assertThat(constraints.valueType()).isEqualTo("number");
      assertThat(constraints.values()).containsExactly("1", "2", "3");
    }

    @Test
    void an_unrecognised_value_type_falls_back_to_string() {
      EnumTypeConstraints constraints =
          (EnumTypeConstraints) constraintsOf("{\"valueType\":\"other\",\"values\":[]}");

      assertThat(constraints.valueType()).isEqualTo("string");
    }

    @Test
    void absent_or_unusable_constraints_are_null() {
      assertThat(constraintsOf("null")).isNull();
      assertThat(constraintsOf("[]")).isNull();
      assertThat(constraintsOf("\"text\"")).isNull();
    }
  }
}
