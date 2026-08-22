package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.configdirector.ConfigType;
import com.configdirector.Context;
import com.configdirector.Metadata;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EvaluationTypesTest {

  @Nested
  @DisplayName("Condition")
  class Conditions {

    @Test
    void requires_an_operator_because_every_comparison_lowercases_it() {
      assertThatNullPointerException()
          .isThrownBy(() -> new Condition("c", "identifier", null, "text", List.of(), null))
          .withMessageContaining("operator");
    }

    @Test
    void a_null_target_list_becomes_an_empty_one() {
      Condition condition = new Condition("c", "identifier", "=", "text", null, null);

      assertThat(condition.targetValues()).isEmpty();
    }

    @Test
    void the_target_list_is_copied_and_unmodifiable() {
      List<String> targets = new ArrayList<>(List.of("a"));
      Condition condition = new Condition("c", "identifier", "=", "text", targets, null);

      targets.add("b");

      assertThat(condition.targetValues()).containsExactly("a");
      assertThatExceptionOfType(UnsupportedOperationException.class)
          .isThrownBy(() -> condition.targetValues().add("c"));
    }
  }

  @Nested
  @DisplayName("rules")
  class Rules {

    @Test
    void a_conditional_rule_normalizes_its_null_lists() {
      ConditionalRule rule = new ConditionalRule("r", 1, null, "value", "v", null, null);

      assertThat(rule.conditions()).isEmpty();
      assertThat(rule.percentages()).isEmpty();
    }

    @Test
    void a_percentage_rule_normalizes_its_null_list() {
      assertThat(new PercentageRule("p", 1, null).percentages()).isEmpty();
    }

    @Test
    void targeting_rules_normalize_a_null_rule_list() {
      assertThat(new TargetingRules("fallback", null, null).rules()).isEmpty();
    }

    @Test
    void targeting_rules_come_back_ordered() {
      // Sorted here rather than on every evaluation, so the evaluator can walk the list as it is.
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              List.of(
                  new PercentageRule("third", 9, List.of()),
                  new PercentageRule("last", null, List.of()),
                  new PercentageRule("first", 1, List.of())));

      assertThat(target.rules()).extracting(Rule::id).containsExactly("first", "third", "last");
    }

    @Test
    void targeting_rules_keep_the_order_the_server_sent_for_rules_that_share_one() {
      TargetingRules target =
          new TargetingRules(
              "fallback",
              null,
              List.of(
                  new PercentageRule("a", 2, List.of()),
                  new PercentageRule("b", 2, List.of()),
                  new PercentageRule("c", 1, List.of())));

      assertThat(target.rules()).extracting(Rule::id).containsExactly("c", "a", "b");
    }

    @Test
    void a_condition_splits_its_trait_pointer_once() {
      // Derived rather than supplied: a bundle carries the pointer, not the path.
      Condition condition =
          new Condition("c", "traits", "equals", "text", List.of("pro"), "/billing/x~1y");

      assertThat(condition.traitPath()).containsExactly("billing", "x/y");
    }

    @Test
    void a_condition_on_something_other_than_a_trait_has_no_path() {
      assertThat(new Condition("c", "identifier", "equals", "text", List.of("a"), null).traitPath())
          .isNull();
    }

    @Test
    void a_config_normalizes_a_null_variation_list() {
      Config config = new Config("id", "key", ConfigType.STRING, null, null, null);

      assertThat(config.variations()).isEmpty();
    }

    @Test
    void both_rule_kinds_are_rules() {
      assertThat(new PercentageRule("p", 3, List.of())).isInstanceOf(Rule.class);
      assertThat(new ConditionalRule("c", 3, List.of(), "value", null, null, List.of()))
          .isInstanceOf(Rule.class);
    }
  }

  @Nested
  @DisplayName("EvaluationContext")
  class Contexts {

    @Test
    void substitutes_empties_for_absent_parts() {
      EvaluationContext context = new EvaluationContext(null, null);

      assertThat(context.contextOrEmpty()).isSameAs(Context.empty());
      assertThat(context.metadataOrEmpty()).isSameAs(Metadata.empty());
    }

    @Test
    void passes_through_what_it_was_given() {
      Context subject = Context.builder().id("u1").build();
      Metadata metadata = new Metadata("app", "1.0.0");

      EvaluationContext context = new EvaluationContext(subject, metadata);

      assertThat(context.contextOrEmpty()).isSameAs(subject);
      assertThat(context.metadataOrEmpty()).isSameAs(metadata);
    }

    @Test
    void the_shared_empty_carries_nothing() {
      assertThat(EvaluationContext.empty().context()).isNull();
      assertThat(EvaluationContext.empty().metadata()).isNull();
    }
  }
}
