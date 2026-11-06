package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigState;
import com.configdirector.ConfigType;
import com.configdirector.Context;
import com.configdirector.EvaluationReason;
import com.configdirector.Variation;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// Covers what the cross-SDK contract deliberately leaves out: rule ordering, percentage
// bucketing, and falling through to the default value. Condition semantics are ConformanceTest's.
class ConfigEvaluatorTest {

  private static final String CONFIG_ID = "config-1";

  private static final List<ConfigVariation> VARIATIONS =
      List.of(
          new ConfigVariation("control-id", "Start", "control"),
          new ConfigVariation("treatment-id", "Try", "treatment"),
          new ConfigVariation("unnamed-id", "Go", null));

  private final ConfigEvaluator evaluator = new ConfigEvaluator();

  private static Config config(TargetingRules target) {
    return new Config(CONFIG_ID, "my-key", ConfigType.STRING, target, List.of(), null);
  }

  private static Config experiment(Rule... rules) {
    return new Config(
        CONFIG_ID,
        "my-key",
        ConfigType.STRING,
        new TargetingRules("Start", "v-start", "control-id", List.of(rules)),
        VARIATIONS,
        null);
  }

  private static Condition matching(String identifier) {
    return new AttributeCondition("c", "identifier", "=", "text", List.of(identifier), null);
  }

  private static Condition never() {
    return new AttributeCondition("c", "identifier", "=", "text", List.of("nobody"), null);
  }

  private static ConditionalRule valueRule(
      String id, Integer order, List<Condition> conditions, Object value) {
    return new ConditionalRule(id, order, conditions, "value", value, id + "-vid", null, List.of());
  }

  private static EvaluationContext contextFor(String identifier) {
    return new EvaluationContext(Context.builder().id(identifier).build(), null);
  }

  private ConfigState evaluate(TargetingRules target, String identifier) {
    return evaluator.evaluate(config(target), contextFor(identifier));
  }

  @Nested
  @DisplayName("falling through")
  class Defaults {

    @Test
    void returns_the_default_when_there_are_no_rules() {
      ConfigState state =
          evaluate(new TargetingRules("fallback", "default-vid", null, List.of()), "u1");

      assertThat(state.value()).isEqualTo("fallback");
      assertThat(state.valueId()).isEqualTo("default-vid");
    }

    @Test
    void returns_the_default_when_no_rule_matches() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              "default-vid",
              null,
              List.of(valueRule("r1", 1, List.of(never()), "matched")));

      assertThat(evaluate(target, "u1").value()).isEqualTo("fallback");
    }

    @Test
    void carries_the_config_identity_through() {
      ConfigState state = evaluate(new TargetingRules("fallback", null, null, List.of()), "u1");

      assertThat(state.id()).isEqualTo(CONFIG_ID);
      assertThat(state.key()).isEqualTo("my-key");
      assertThat(state.type()).isEqualTo(ConfigType.STRING);
    }

    @Test
    void a_rule_with_no_conditions_applies_to_every_context() {
      TargetingRules target =
          new TargetingRules(
              "fallback", null, null, List.of(valueRule("r1", 1, List.of(), "matched")));

      assertThat(evaluate(target, "u1").value()).isEqualTo("matched");
      assertThat(evaluator.evaluate(config(target), null).value()).isEqualTo("matched");
    }

    @Test
    void a_null_target_yields_no_value() {
      ConfigState state = evaluator.evaluate(config(null), contextFor("u1"));

      assertThat(state.value()).isNull();
      assertThat(state.valueId()).isNull();
    }
  }

  @Nested
  @DisplayName("a rule with several conditions")
  class SeveralConditions {

    private static Condition planIs(String plan) {
      return new AttributeCondition("c2", "traits", "=", "text", List.of(plan), "/plan");
    }

    private static TargetingRules requiring(Condition... conditions) {
      return new TargetingRules(
          "fallback",
          "default-vid",
          null,
          List.of(valueRule("r1", 1, List.of(conditions), "matched")));
    }

    private ConfigState evaluate(TargetingRules target, String identifier, String plan) {
      return evaluator.evaluate(
          config(target),
          new EvaluationContext(Context.builder().id(identifier).trait("plan", plan).build(), null));
    }

    @Test
    void matches_when_every_condition_matches() {
      TargetingRules target = requiring(matching("u1"), planIs("pro"));

      assertThat(evaluate(target, "u1", "pro").value()).isEqualTo("matched");
    }

    @Test
    void does_not_match_when_only_the_first_condition_matches() {
      TargetingRules target = requiring(matching("u1"), planIs("pro"));

      assertThat(evaluate(target, "u1", "free").value()).isEqualTo("fallback");
    }

    @Test
    void does_not_match_when_only_the_second_condition_matches() {
      TargetingRules target = requiring(matching("u1"), planIs("pro"));

      assertThat(evaluate(target, "u2", "pro").value()).isEqualTo("fallback");
    }

    @Test
    void does_not_match_when_no_condition_matches() {
      TargetingRules target = requiring(matching("u1"), planIs("pro"));

      assertThat(evaluate(target, "u2", "free").value()).isEqualTo("fallback");
    }

    @Test
    void never_matches_when_its_conditions_cannot_all_hold() {
      TargetingRules target = requiring(matching("u1"), matching("u2"));

      assertThat(ConfigEvaluatorTest.this.evaluate(target, "u1").value()).isEqualTo("fallback");
      assertThat(ConfigEvaluatorTest.this.evaluate(target, "u2").value()).isEqualTo("fallback");
    }
  }

  @Nested
  @DisplayName("rule ordering")
  class Ordering {

    @Test
    void the_lowest_order_wins() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(
                  valueRule("late", 10, List.of(matching("u1")), "late"),
                  valueRule("early", 1, List.of(matching("u1")), "early")));

      assertThat(evaluate(target, "u1").value()).isEqualTo("early");
    }

    @Test
    void a_rule_without_an_order_evaluates_last() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(
                  valueRule("unordered", null, List.of(matching("u1")), "unordered"),
                  valueRule("ordered", 5, List.of(matching("u1")), "ordered")));

      assertThat(evaluate(target, "u1").value()).isEqualTo("ordered");
    }

    @Test
    void rules_sharing_an_order_keep_the_order_the_server_sent() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(
                  valueRule("first", 1, List.of(matching("u1")), "first"),
                  valueRule("second", 1, List.of(matching("u1")), "second")));

      assertThat(evaluate(target, "u1").value()).isEqualTo("first");
    }

    @Test
    void an_earlier_rule_that_does_not_match_falls_through_to_a_later_one() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(
                  valueRule("early", 1, List.of(never()), "early"),
                  valueRule("late", 2, List.of(matching("u1")), "late")));

      assertThat(evaluate(target, "u1").value()).isEqualTo("late");
    }
  }

  @Nested
  @DisplayName("value rendering")
  class Rendering {

    @Test
    void renders_a_boolean_the_way_json_spells_it() {
      TargetingRules target =
          new TargetingRules(
              "f", null, null, List.of(valueRule("r", 1, List.of(matching("u1")), true)));

      assertThat(evaluate(target, "u1").value()).isEqualTo("true");
    }

    @Test
    void renders_a_whole_number_without_a_decimal_point() {
      TargetingRules target =
          new TargetingRules(
              "f", null, null, List.of(valueRule("r", 1, List.of(matching("u1")), 26.0)));

      assertThat(evaluate(target, "u1").value()).isEqualTo("26");
    }

    @Test
    void keeps_a_fractional_number() {
      TargetingRules target =
          new TargetingRules(
              "f", null, null, List.of(valueRule("r", 1, List.of(matching("u1")), 26.5)));

      assertThat(evaluate(target, "u1").value()).isEqualTo("26.5");
    }

    @Test
    void reports_the_matched_rule_value_id() {
      TargetingRules target =
          new TargetingRules(
              "f", null, null, List.of(valueRule("r", 1, List.of(matching("u1")), "v")));

      assertThat(evaluate(target, "u1").valueId()).isEqualTo("r-vid");
    }

    @Test
    void a_rule_with_a_null_value_does_not_match() {
      TargetingRules target =
          new TargetingRules(
              "f", null, null, List.of(valueRule("r", 1, List.of(matching("u1")), null)));

      assertThat(evaluate(target, "u1").value()).isEqualTo("f");
    }
  }

  @Nested
  @DisplayName("percentage bucketing")
  class Bucketing {

    private static PercentageRule rule(List<Percentage> percentages) {
      return new PercentageRule("p", 1, percentages);
    }

    @Test
    void a_single_full_bucket_takes_everyone() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(rule(List.of(new Percentage("b", 100.0, "on", "on-vid", null)))));

      for (int i = 0; i < 200; i++) {
        assertThat(evaluate(target, "user-" + i).value()).isEqualTo("on");
      }
    }

    // A bucket spans [start, end), so a 0% bucket is empty and unreachable no matter who asks.
    // With an inclusive boundary it would take one of the 1000 reachable hash values, and a
    // variation turned down to 0% would still serve roughly 0.1% of traffic.
    @Test
    void a_zero_percent_bucket_is_unreachable_for_every_identifier() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(
                  rule(
                      List.of(
                          new Percentage("none", 0.0, "never", null, null),
                          new Percentage("all", 100.0, "always", null, null)))));

      for (int i = 0; i < 4_000; i++) {
        assertThat(evaluate(target, "user-" + i).value()).isEqualTo("always");
      }
    }

    @Test
    void a_context_landing_on_a_boundary_takes_the_bucket_that_starts_there() {
      // Only 1000 values are reachable, so an identifier landing exactly on 50.0 exists.
      String onBoundary = null;
      for (int i = 0; i < 100_000 && onBoundary == null; i++) {
        if (PercentHashing.assignPercentage(CONFIG_ID, "u" + i, "user") == 50.0) {
          onBoundary = "u" + i;
        }
      }
      assertThat(onBoundary).as("an identifier hashing to exactly 50.0").isNotNull();

      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(
                  rule(
                      List.of(
                          new Percentage("a", 50.0, "a", null, null),
                          new Percentage("b", 50.0, "b", null, null)))));

      assertThat(evaluate(target, onBoundary).value()).isEqualTo("b");
    }

    @Test
    void an_empty_leading_bucket_is_skipped() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(
                  rule(
                      List.of(
                          new Percentage("none", 0.0, "never", null, null),
                          new Percentage("all", 100.0, "always", "always-vid", null)))));

      ConfigState state = evaluate(target, "user-1");
      assertThat(state.value()).isEqualTo("always");
      assertThat(state.valueId()).isEqualTo("always-vid");
    }

    @Test
    void buckets_that_do_not_cover_the_range_fall_through_to_the_default() {
      // The assigned percentage is above the only bucket for this identifier, so nothing matches.
      double assigned = PercentHashing.assignPercentage(CONFIG_ID, "user-1", "user");
      double belowAssigned = Math.max(0.0, assigned - 0.1);
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(rule(List.of(new Percentage("small", belowAssigned, "on", null, null)))));

      assertThat(evaluate(target, "user-1").value()).isEqualTo("fallback");
    }

    @Test
    void the_same_identifier_always_lands_in_the_same_bucket() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(
                  rule(
                      List.of(
                          new Percentage("a", 50.0, "a", null, null),
                          new Percentage("b", 50.0, "b", null, null)))));

      String first = evaluate(target, "stable-user").value();
      for (int i = 0; i < 50; i++) {
        assertThat(evaluate(target, "stable-user").value()).isEqualTo(first);
      }
    }

    @Test
    void a_fifty_fifty_split_lands_roughly_evenly() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(
                  rule(
                      List.of(
                          new Percentage("a", 50.0, "a", null, null),
                          new Percentage("b", 50.0, "b", null, null)))));

      long inA =
          IntStream.range(0, 4_000)
              .filter(i -> "a".equals(evaluate(target, "user-" + i).value()))
              .count();

      assertThat(inA).isBetween(1_800L, 2_200L);
    }

    @Test
    void a_conditional_rule_can_target_a_percentage() {
      ConditionalRule rule =
          new ConditionalRule(
              "r",
              1,
              List.of(matching("u1")),
              "percentage",
              null,
              null,
              null,
              List.of(new Percentage("all", 100.0, "on", "on-vid", null)));
      TargetingRules target = new TargetingRules("fallback", null, null, List.of(rule));

      assertThat(evaluate(target, "u1").value()).isEqualTo("on");
      // A context the condition does not select never reaches the buckets.
      assertThat(evaluate(target, "someone-else").value()).isEqualTo("fallback");
    }
  }

  @Nested
  @DisplayName("the variation served")
  class VariationServed {

    private static final Config SPLIT_AT_FIFTY_NINE =
        new Config(
            "11111111-1111-4111-8111-111111111111",
            "my-key",
            ConfigType.STRING,
            new TargetingRules(
                "Start",
                "v-start",
                "control-id",
                List.of(
                    new PercentageRule(
                        "rollout-rule",
                        1,
                        List.of(
                            new Percentage("control-share", 59.0, "Start", "v-start", "control-id"),
                            new Percentage(
                                "treatment-share", 41.0, "Try", "v-try", "treatment-id"))))),
            VARIATIONS,
            null);

    private static ConditionalRule ruleServing(String variationId, Condition... conditions) {
      return new ConditionalRule(
          "conditional-rule", 1, List.of(conditions), "value", "Go", "v-go", variationId, List.of());
    }

    private static Percentage shareOfEveryone(String variationId) {
      return new Percentage("everyone-share", 100.0, "Try", "v-try", variationId);
    }

    private ConfigState evaluateFor(String identifier, Rule... rules) {
      return evaluator.evaluate(experiment(rules), contextFor(identifier));
    }

    @Test
    void a_percentage_rule_carries_its_rule_share_and_variation() {
      ConfigState state =
          evaluateFor(
              "u1", new PercentageRule("rollout-rule", 1, List.of(shareOfEveryone("treatment-id"))));

      assertThat(state.value()).isEqualTo("Try");
      assertThat(state.variation()).isEqualTo(new Variation("treatment-id", "treatment"));
      assertThat(state.ruleId()).isEqualTo("rollout-rule");
      assertThat(state.shareId()).isEqualTo("everyone-share");
    }

    @Test
    void a_context_placed_in_the_first_share_gets_that_shares_id_and_variation() {
      ConfigState state = evaluator.evaluate(SPLIT_AT_FIFTY_NINE, contextFor("user-1"));

      assertThat(state.shareId()).isEqualTo("control-share");
      assertThat(state.variation()).isEqualTo(new Variation("control-id", "control"));
    }

    @Test
    void a_context_placed_in_the_second_share_gets_that_shares_id_and_variation() {
      ConfigState state =
          evaluator.evaluate(
              SPLIT_AT_FIFTY_NINE,
              new EvaluationContext(Context.builder().id("user-1").kind("device").build(), null));

      assertThat(state.shareId()).isEqualTo("treatment-share");
      assertThat(state.variation()).isEqualTo(new Variation("treatment-id", "treatment"));
    }

    @Test
    void a_conditional_rules_rollout_carries_that_rules_id() {
      ConfigState state =
          evaluateFor(
              "u1",
              new ConditionalRule(
                  "conditional-rollout-rule",
                  1,
                  List.of(matching("u1")),
                  "percentage",
                  null,
                  null,
                  null,
                  List.of(shareOfEveryone("treatment-id"))));

      assertThat(state.variation()).isEqualTo(new Variation("treatment-id", "treatment"));
      assertThat(state.ruleId()).isEqualTo("conditional-rollout-rule");
      assertThat(state.shareId()).isEqualTo("everyone-share");
    }

    @Test
    void a_conditional_rules_single_value_carries_its_variation_and_no_rule_or_share() {
      ConfigState state = evaluateFor("u1", ruleServing("treatment-id"));

      assertThat(state.value()).isEqualTo("Go");
      assertThat(state.variation()).isEqualTo(new Variation("treatment-id", "treatment"));
      assertThat(state.ruleId()).isNull();
      assertThat(state.shareId()).isNull();
    }

    @Test
    void the_default_targeting_rule_value_carries_its_variation_and_no_rule_or_share() {
      ConfigState state = evaluateFor("u1", ruleServing("treatment-id", never()));

      assertThat(state.value()).isEqualTo("Start");
      assertThat(state.variation()).isEqualTo(new Variation("control-id", "control"));
      assertThat(state.ruleId()).isNull();
      assertThat(state.shareId()).isNull();
    }

    @Test
    void an_unnamed_variation_has_its_id_and_no_name() {
      ConfigState state = evaluateFor("u1", ruleServing("unnamed-id"));

      assertThat(state.variation()).isEqualTo(new Variation("unnamed-id", null));
    }

    @Test
    void a_variation_id_the_config_does_not_list_serves_no_variation() {
      ConfigState state = evaluateFor("u1", ruleServing("unknown-id"));

      assertThat(state.value()).isEqualTo("Go");
      assertThat(state.variation()).isNull();
    }

    @Test
    void a_flag_rollout_carries_its_rule_and_share_and_no_variation() {
      TargetingRules target =
          new TargetingRules(
              "false",
              "v-false",
              null,
              List.of(
                  new PercentageRule(
                      "flag-rollout-rule",
                      1,
                      List.of(new Percentage("on-share", 100.0, "true", "v-true", null)))));

      ConfigState state = evaluator.evaluate(config(target), contextFor("u1"));

      assertThat(state.value()).isEqualTo("true");
      assertThat(state.variation()).isNull();
      assertThat(state.ruleId()).isEqualTo("flag-rollout-rule");
      assertThat(state.shareId()).isEqualTo("on-share");
    }

    @Test
    void a_flag_default_targeting_rule_value_serves_no_variation() {
      ConfigState state =
          evaluator.evaluate(
              config(new TargetingRules("true", "v-true", null, List.of())), contextFor("u1"));

      assertThat(state.variation()).isNull();
      assertThat(state.ruleId()).isNull();
      assertThat(state.shareId()).isNull();
    }
  }

  @Nested
  @DisplayName("a percentage rollout without an identifier")
  class RolloutWithoutAnIdentifier {

    private static final PercentageRule EVERYONE_GETS_THE_TREATMENT =
        new PercentageRule(
            "rollout-rule",
            2,
            List.of(new Percentage("everyone-share", 100.0, "Try", "v-try", "treatment-id")));

    private static final ConditionalRule LATER_RULE_FOR_EVERYONE =
        new ConditionalRule(
            "later-rule", 3, List.of(), "value", "Later", "v-later", null, List.of());

    private ServedConfig evaluateWithoutAnIdentifier(Config config) {
      return evaluator.evaluateWithReason(
          config, new EvaluationContext(Context.builder().name("Ada").build(), null), Map.of());
    }

    @Test
    void a_percentage_rule_serves_the_default_targeting_rule_value_with_the_skipped_reason() {
      ServedConfig served = evaluateWithoutAnIdentifier(experiment(EVERYONE_GETS_THE_TREATMENT));

      assertThat(served.reason()).isEqualTo(EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER);
      assertThat(served.state().value()).isEqualTo("Start");
      assertThat(served.state().valueId()).isEqualTo("v-start");
      assertThat(served.state().variation()).isEqualTo(new Variation("control-id", "control"));
      assertThat(served.state().ruleId()).isNull();
      assertThat(served.state().shareId()).isNull();
    }

    @Test
    void a_matched_conditional_rule_that_serves_a_rollout_is_skipped_too() {
      ConditionalRule conditionalRollout =
          new ConditionalRule(
              "conditional-rollout-rule",
              1,
              List.of(new AttributeCondition("c", "name", "=", "text", List.of("Ada"), null)),
              "percentage",
              null,
              null,
              null,
              EVERYONE_GETS_THE_TREATMENT.percentages());

      ServedConfig served = evaluateWithoutAnIdentifier(experiment(conditionalRollout));

      assertThat(served.reason()).isEqualTo(EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER);
      assertThat(served.state().value()).isEqualTo("Start");
    }

    @Test
    void no_evaluation_context_at_all_skips_the_rollout() {
      ServedConfig served =
          evaluator.evaluateWithReason(experiment(EVERYONE_GETS_THE_TREATMENT), null, Map.of());

      assertThat(served.reason()).isEqualTo(EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER);
      assertThat(served.state().value()).isEqualTo("Start");
    }

    @Test
    void an_evaluation_context_without_a_context_skips_the_rollout() {
      ServedConfig served =
          evaluator.evaluateWithReason(
              experiment(EVERYONE_GETS_THE_TREATMENT),
              new EvaluationContext(null, null),
              Map.of());

      assertThat(served.reason()).isEqualTo(EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER);
      assertThat(served.state().value()).isEqualTo("Start");
    }

    @Test
    void the_rules_after_a_skipped_rollout_are_not_tried() {
      ServedConfig served =
          evaluateWithoutAnIdentifier(
              experiment(EVERYONE_GETS_THE_TREATMENT, LATER_RULE_FOR_EVERYONE));

      assertThat(served.reason()).isEqualTo(EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER);
      assertThat(served.state().value()).isEqualTo("Start");
    }

    @Test
    void a_rule_before_the_rollout_that_serves_a_single_value_still_applies() {
      ConditionalRule earlierRule =
          new ConditionalRule(
              "earlier-rule", 1, List.of(), "value", "Earlier", "v-earlier", null, List.of());

      ServedConfig served =
          evaluateWithoutAnIdentifier(experiment(earlierRule, EVERYONE_GETS_THE_TREATMENT));

      assertThat(served.reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
      assertThat(served.state().value()).isEqualTo("Earlier");
    }

    @Test
    void a_conditional_rollout_whose_conditions_do_not_match_is_not_reached() {
      ConditionalRule conditionalRollout =
          new ConditionalRule(
              "conditional-rollout-rule",
              1,
              List.of(never()),
              "percentage",
              null,
              null,
              null,
              EVERYONE_GETS_THE_TREATMENT.percentages());

      ServedConfig served =
          evaluateWithoutAnIdentifier(experiment(conditionalRollout, LATER_RULE_FOR_EVERYONE));

      assertThat(served.reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
      assertThat(served.state().value()).isEqualTo("Later");
    }

    @Test
    void a_rollout_rule_with_an_empty_share_list_is_skipped_too() {
      PercentageRule withoutShares = new PercentageRule("no-shares-rule", 1, List.of());

      ServedConfig served =
          evaluateWithoutAnIdentifier(experiment(withoutShares, LATER_RULE_FOR_EVERYONE));

      assertThat(served.reason()).isEqualTo(EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER);
      assertThat(served.state().value()).isEqualTo("Start");
    }

    @Test
    void the_default_targeting_rule_value_reached_without_a_rollout_is_a_found_match() {
      ServedConfig served = evaluateWithoutAnIdentifier(experiment());

      assertThat(served.reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
      assertThat(served.state().value()).isEqualTo("Start");
    }

    @Test
    void a_context_with_an_identifier_is_served_by_the_rollout() {
      ServedConfig served =
          evaluator.evaluateWithReason(
              experiment(EVERYONE_GETS_THE_TREATMENT), contextFor("u1"), Map.of());

      assertThat(served.reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
      assertThat(served.state().value()).isEqualTo("Try");
    }
  }

  @Nested
  @DisplayName("percentage rollouts by kind")
  class PercentagesByKind {

    private static final String KIND_CONFIG_ID = "11111111-1111-4111-8111-111111111111";

    private final TargetingRules splitAtFiftyNine =
        new TargetingRules(
            "fallback",
            null,
            null,
            List.of(
                new PercentageRule(
                    "p",
                    1,
                    List.of(
                        new Percentage("low", 59.0, "low", null, null),
                        new Percentage("high", 41.0, "high", null, null)))));

    private ConfigState evaluateFor(Context context) {
      return evaluator.evaluate(
          new Config(KIND_CONFIG_ID, "flag", ConfigType.STRING, splitAtFiftyNine, List.of(), null),
          new EvaluationContext(context, null));
    }

    @Test
    void a_user_keeps_the_bucket_it_had_before_kinds_existed() {
      assertThat(evaluateFor(Context.builder().id("user-1").build()).value()).isEqualTo("low");
      assertThat(evaluateFor(Context.builder().id("user-1").kind("user").build()).value())
          .isEqualTo("low");
    }

    @Test
    void another_kind_with_the_same_identifier_is_assigned_its_own_bucket() {
      assertThat(evaluateFor(Context.builder().id("user-1").kind("device").build()).value())
          .isEqualTo("high");
    }

    @Test
    void a_context_of_any_kind_without_an_identifier_skips_the_rollout() {
      assertThat(evaluateFor(Context.builder().kind("device").build()).value())
          .isEqualTo("fallback");
    }
  }

  @Nested
  @DisplayName("malformed rules")
  class Malformed {

    @Test
    void a_rule_that_throws_is_skipped_rather_than_breaking_the_evaluation() {
      // A missing targetType is malformed wire data, and throws when it is switched on.
      Condition broken = new AttributeCondition("c", "identifier", "=", null, List.of("u1"), null);
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              null,
              List.of(
                  valueRule("broken", 1, List.of(broken), "boom"),
                  valueRule("good", 2, List.of(matching("u1")), "good")));

      assertThat(evaluate(target, "u1").value()).isEqualTo("good");
    }
  }
}
