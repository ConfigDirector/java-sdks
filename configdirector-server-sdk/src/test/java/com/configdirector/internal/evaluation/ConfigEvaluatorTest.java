package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigState;
import com.configdirector.ConfigType;
import com.configdirector.Context;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// Covers what the cross-SDK contract deliberately leaves out: rule ordering, percentage
// bucketing, and falling through to the default value. Condition semantics are ConformanceTest's.
class ConfigEvaluatorTest {

  private static final String CONFIG_ID = "config-1";

  private final ConfigEvaluator evaluator = new ConfigEvaluator();

  private static Config config(TargetingRules target) {
    return new Config(CONFIG_ID, "my-key", ConfigType.STRING, target, List.of(), null);
  }

  private static Condition matching(String identifier) {
    return new Condition("c", "identifier", "=", "text", List.of(identifier), null);
  }

  private static Condition never() {
    return new Condition("c", "identifier", "=", "text", List.of("nobody"), null);
  }

  private static ConditionalRule valueRule(
      String id, Integer order, List<Condition> conditions, Object value) {
    return new ConditionalRule(id, order, conditions, "value", value, id + "-vid", List.of());
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
      ConfigState state = evaluate(new TargetingRules("fallback", "default-vid", List.of()), "u1");

      assertThat(state.value()).isEqualTo("fallback");
      assertThat(state.valueId()).isEqualTo("default-vid");
    }

    @Test
    void returns_the_default_when_no_rule_matches() {
      TargetingRules target =
          new TargetingRules(
              "fallback", "default-vid", List.of(valueRule("r1", 1, List.of(never()), "matched")));

      assertThat(evaluate(target, "u1").value()).isEqualTo("fallback");
    }

    @Test
    void carries_the_config_identity_through() {
      ConfigState state = evaluate(new TargetingRules("fallback", null, List.of()), "u1");

      assertThat(state.id()).isEqualTo(CONFIG_ID);
      assertThat(state.key()).isEqualTo("my-key");
      assertThat(state.type()).isEqualTo(ConfigType.STRING);
    }

    @Test
    void a_rule_with_no_conditions_never_matches() {
      TargetingRules target =
          new TargetingRules("fallback", null, List.of(valueRule("r1", 1, List.of(), "matched")));

      assertThat(evaluate(target, "u1").value()).isEqualTo("fallback");
    }

    @Test
    void a_null_target_yields_no_value() {
      ConfigState state = evaluator.evaluate(config(null), contextFor("u1"));

      assertThat(state.value()).isNull();
      assertThat(state.valueId()).isNull();
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
          new TargetingRules("f", null, List.of(valueRule("r", 1, List.of(matching("u1")), true)));

      assertThat(evaluate(target, "u1").value()).isEqualTo("true");
    }

    @Test
    void renders_a_whole_number_without_a_decimal_point() {
      TargetingRules target =
          new TargetingRules("f", null, List.of(valueRule("r", 1, List.of(matching("u1")), 26.0)));

      assertThat(evaluate(target, "u1").value()).isEqualTo("26");
    }

    @Test
    void keeps_a_fractional_number() {
      TargetingRules target =
          new TargetingRules("f", null, List.of(valueRule("r", 1, List.of(matching("u1")), 26.5)));

      assertThat(evaluate(target, "u1").value()).isEqualTo("26.5");
    }

    @Test
    void reports_the_matched_rule_value_id() {
      TargetingRules target =
          new TargetingRules("f", null, List.of(valueRule("r", 1, List.of(matching("u1")), "v")));

      assertThat(evaluate(target, "u1").valueId()).isEqualTo("r-vid");
    }

    @Test
    void a_rule_with_a_null_value_does_not_match() {
      TargetingRules target =
          new TargetingRules("f", null, List.of(valueRule("r", 1, List.of(matching("u1")), null)));

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
              "fallback", null, List.of(rule(List.of(new Percentage("b", 100.0, "on", "on-vid")))));

      for (int i = 0; i < 200; i++) {
        assertThat(evaluate(target, "user-" + i).value()).isEqualTo("on");
      }
    }

    @Test
    void an_empty_leading_bucket_is_skipped() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              List.of(
                  rule(
                      List.of(
                          new Percentage("none", 0.0, "never", null),
                          new Percentage("all", 100.0, "always", "always-vid")))));

      ConfigState state = evaluate(target, "user-1");
      assertThat(state.value()).isEqualTo("always");
      assertThat(state.valueId()).isEqualTo("always-vid");
    }

    @Test
    void buckets_that_do_not_cover_the_range_fall_through_to_the_default() {
      // The assigned percentage is above the only bucket for this identifier, so nothing matches.
      double assigned = PercentHashing.assignPercentage(CONFIG_ID, "user-1");
      double belowAssigned = Math.max(0.0, assigned - 0.1);
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              List.of(rule(List.of(new Percentage("small", belowAssigned, "on", null)))));

      assertThat(evaluate(target, "user-1").value()).isEqualTo("fallback");
    }

    @Test
    void the_same_identifier_always_lands_in_the_same_bucket() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              List.of(
                  rule(
                      List.of(
                          new Percentage("a", 50.0, "a", null), new Percentage("b", 50.0, "b", null)))));

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
              List.of(
                  rule(
                      List.of(
                          new Percentage("a", 50.0, "a", null), new Percentage("b", 50.0, "b", null)))));

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
              List.of(new Percentage("all", 100.0, "on", "on-vid")));
      TargetingRules target = new TargetingRules("fallback", null, List.of(rule));

      assertThat(evaluate(target, "u1").value()).isEqualTo("on");
      // A context the condition does not select never reaches the buckets.
      assertThat(evaluate(target, "someone-else").value()).isEqualTo("fallback");
    }

    @Test
    void an_anonymous_context_still_gets_a_bucket() {
      TargetingRules target =
          new TargetingRules(
              "fallback", null, List.of(rule(List.of(new Percentage("b", 100.0, "on", null)))));

      ConfigState state = evaluator.evaluate(config(target), new EvaluationContext(null, null));

      assertThat(state.value()).isEqualTo("on");
    }
  }

  @Nested
  @DisplayName("malformed rules")
  class Malformed {

    @Test
    void a_rule_that_throws_is_skipped_rather_than_breaking_the_evaluation() {
      // A missing targetType is malformed wire data, and throws when it is switched on.
      Condition broken = new Condition("c", "identifier", "=", null, List.of("u1"), null);
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              List.of(
                  valueRule("broken", 1, List.of(broken), "boom"),
                  valueRule("good", 2, List.of(matching("u1")), "good")));

      assertThat(evaluate(target, "u1").value()).isEqualTo("good");
    }
  }
}
