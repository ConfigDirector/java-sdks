package com.configdirector.internal.evaluation;

import com.configdirector.ConfigState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigEvaluator {

  private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();
  private final SegmentEvaluator segmentEvaluator = new SegmentEvaluator(conditionEvaluator);
  private final Logger logger;

  public ConfigEvaluator() {
    this(LoggerFactory.getLogger(ConfigEvaluator.class));
  }

  public ConfigEvaluator(Logger logger) {
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  public ConfigState evaluate(Config config, EvaluationContext context) {
    return evaluate(config, context, Map.of());
  }

  public ConfigState evaluate(
      Config config, EvaluationContext context, Map<String, Segment> segments) {
    Selection selected = selectValue(config, context, segments);
    return new ConfigState(
        config.id(), config.key(), config.type(), selected.value(), selected.valueId());
  }

  // Value and value id travel together because which rule produced the value is the only thing
  // that says which id belongs to it.
  private Selection selectValue(
      Config config, EvaluationContext context, Map<String, Segment> segments) {
    TargetingRules target = config.target();
    if (target == null) {
      return new Selection(null, null);
    }

    // Already ordered by TargetingRules, which sorts once at parse time.
    for (Rule rule : target.rules()) {
      Selection selected = evaluateRule(rule, config, context, segments);
      if (selected != null) {
        return selected;
      }
    }
    return new Selection(target.defaultValue(), target.defaultValueId());
  }

  private Selection evaluateRule(
      Rule rule, Config config, EvaluationContext context, Map<String, Segment> segments) {
    try {
      if (rule instanceof PercentageRule percentageRule) {
        return evaluatePercentage(percentageRule.percentages(), config, context);
      }
      if (rule instanceof ConditionalRule conditionalRule) {
        return evaluateConditionalRule(conditionalRule, config, context, segments);
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
      ConditionalRule rule, Config config, EvaluationContext context, Map<String, Segment> segments) {
    for (Condition condition : rule.conditions()) {
      if (!conditionHolds(condition, context, segments)) {
        return null;
      }
    }
    if ("value".equals(rule.target()) && rule.value() != null) {
      return new Selection(JsonValues.toJsonString(rule.value()), rule.valueId());
    }
    if ("percentage".equals(rule.target())) {
      return evaluatePercentage(rule.percentages(), config, context);
    }
    return null;
  }

  private boolean conditionHolds(
      Condition condition, EvaluationContext context, Map<String, Segment> segments) {
    if (condition instanceof AttributeCondition attributeCondition) {
      return conditionEvaluator.evaluate(attributeCondition, context);
    }
    if (condition instanceof SegmentCondition segmentCondition) {
      return segmentEvaluator.evaluate(segmentCondition, segments, context);
    }
    return false;
  }

  private Selection evaluatePercentage(
      List<Percentage> percentages, Config config, EvaluationContext context) {
    String identifier = context == null ? null : context.contextOrEmpty().id();

    double assigned =
        identifier == null
            ? PercentHashing.PERCENTAGE_WITHOUT_IDENTIFIER
            : PercentHashing.assignPercentage(config.id(), identifier);

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
