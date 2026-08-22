package com.configdirector.internal.evaluation;

import com.configdirector.ConfigState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigEvaluator {

  private static final Comparator<Rule> BY_ORDER =
      Comparator.comparing(Rule::order, Comparator.nullsLast(Comparator.naturalOrder()));

  private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();
  private final Logger logger;

  public ConfigEvaluator() {
    this(LoggerFactory.getLogger(ConfigEvaluator.class));
  }

  public ConfigEvaluator(Logger logger) {
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  public ConfigState evaluate(Config config, EvaluationContext context) {
    Selection selected = selectValue(config, context);
    return new ConfigState(
        config.id(), config.key(), config.type(), selected.value(), selected.valueId());
  }

  // Value and value id travel together because which rule produced the value is the only thing
  // that says which id belongs to it.
  private Selection selectValue(Config config, EvaluationContext context) {
    TargetingRules target = config.target();
    if (target == null) {
      return new Selection(null, null);
    }

    // A stable sort, so rules sharing an order keep the sequence the server sent them in.
    List<Rule> rules = new ArrayList<>(target.rules());
    rules.sort(BY_ORDER);

    for (Rule rule : rules) {
      Selection selected = evaluateRule(rule, config, context);
      if (selected != null) {
        return selected;
      }
    }
    return new Selection(target.defaultValue(), target.defaultValueId());
  }

  private Selection evaluateRule(Rule rule, Config config, EvaluationContext context) {
    try {
      if (rule instanceof PercentageRule percentageRule) {
        return evaluatePercentage(percentageRule.percentages(), config, context);
      }
      if (rule instanceof ConditionalRule conditionalRule) {
        return evaluateConditionalRule(conditionalRule, config, context);
      }
    } catch (Exception error) {
      // Malformed rule data must not break the evaluation of the rest of the config.
      logger.warn(
          "[ConfigEvaluator] There was an error while evaluating targeting rule {} for {}."
              + " The rule will be disregarded.",
          rule.id(),
          config.key(),
          error);
    }
    return null;
  }

  private Selection evaluateConditionalRule(
      ConditionalRule rule, Config config, EvaluationContext context) {
    boolean anyConditionMatched =
        rule.conditions().stream()
            .anyMatch(condition -> conditionEvaluator.evaluate(condition, context));
    if (!anyConditionMatched) {
      return null;
    }
    if ("value".equals(rule.target()) && rule.value() != null) {
      return new Selection(JsonValues.toJsonString(rule.value()), rule.valueId());
    }
    if ("percentage".equals(rule.target())) {
      return evaluatePercentage(rule.percentages(), config, context);
    }
    return null;
  }

  private Selection evaluatePercentage(
      List<Percentage> percentages, Config config, EvaluationContext context) {
    String identifier = context == null ? null : context.contextOrEmpty().id();
    if (identifier == null) {
      // An anonymous caller still gets a bucket, just not a stable one.
      identifier = UUID.randomUUID().toString();
    }

    double assigned = PercentHashing.assignPercentage(config.id(), identifier);

    // A bucket spans [total, total + percentage). Strict, so a context landing exactly on a
    // boundary belongs to the bucket that starts there -- which is what keeps a 0% bucket
    // unreachable and each bucket's share exact. See SEMANTICS.md 7.1 in targeting-rules-contract.
    Percentage bucket = null;
    double total = 0.0;
    for (Percentage percentage : percentages) {
      if (assigned < percentage.percentage() + total) {
        bucket = percentage;
        break;
      }
      total += percentage.percentage();
    }

    if (bucket != null && bucket.value() != null) {
      return new Selection(JsonValues.toJsonString(bucket.value()), bucket.valueId());
    }
    return null;
  }

  // What a rule selected, or what the config fell back to. Null stands for "this rule did not
  // match", which is why the type carries no flag of its own.
  private record Selection(String value, String valueId) {}
}
