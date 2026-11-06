package com.configdirector.internal.evaluation;

import com.configdirector.ConfigState;
import com.configdirector.EvaluationReason;
import com.configdirector.Variation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigEvaluator {

  private static final RuleServing NOT_MATCHED = new RuleServing.NotMatched();
  private static final RuleServing ROLLOUT_SKIPPED_NO_IDENTIFIER =
      new RuleServing.RolloutSkippedNoIdentifier();
  private static final RuleServing.ServedValue NO_VALUE =
      new RuleServing.ServedValue(null, null, null, null, null);

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
    return evaluateWithReason(config, context, segments).state();
  }

  public ServedConfig evaluateWithReason(
      Config config, EvaluationContext context, Map<String, Segment> segments) {
    TargetingRules target = config.target();
    if (target == null) {
      return servedConfig(config, NO_VALUE, EvaluationReason.FOUND_MATCH);
    }

    // Already ordered by TargetingRules, which sorts once at parse time.
    for (Rule rule : target.rules()) {
      RuleServing serving = evaluateRule(rule, config, context, segments);
      if (serving instanceof RuleServing.NotMatched) {
        continue;
      }
      if (serving instanceof RuleServing.ServedValue servedValue) {
        return servedConfig(config, servedValue, EvaluationReason.FOUND_MATCH);
      }
      if (serving instanceof RuleServing.RolloutSkippedNoIdentifier) {
        return servedConfig(
            config,
            defaultTargetingRuleValue(target),
            EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER);
      }
    }
    return servedConfig(config, defaultTargetingRuleValue(target), EvaluationReason.FOUND_MATCH);
  }

  private static RuleServing.ServedValue defaultTargetingRuleValue(TargetingRules target) {
    return new RuleServing.ServedValue(
        target.defaultValue(), target.defaultValueId(), target.defaultVariationId(), null, null);
  }

  private static ServedConfig servedConfig(
      Config config, RuleServing.ServedValue servedValue, EvaluationReason reason) {
    return new ServedConfig(
        new ConfigState(
            config.id(),
            config.key(),
            config.type(),
            servedValue.value(),
            servedValue.valueId(),
            variationWithId(config, servedValue.variationId()),
            servedValue.ruleId(),
            servedValue.shareId()),
        reason);
  }

  private static Variation variationWithId(Config config, String variationId) {
    if (variationId == null) {
      return null;
    }
    for (ConfigVariation variation : config.variations()) {
      if (variationId.equals(variation.id())) {
        return new Variation(variationId, variation.name());
      }
    }
    return null;
  }

  private RuleServing evaluateRule(
      Rule rule, Config config, EvaluationContext context, Map<String, Segment> segments) {
    try {
      if (rule instanceof PercentageRule percentageRule) {
        return evaluatePercentage(
            percentageRule.id(), percentageRule.percentages(), config, context);
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
    return NOT_MATCHED;
  }

  private RuleServing evaluateConditionalRule(
      ConditionalRule rule, Config config, EvaluationContext context, Map<String, Segment> segments) {
    for (Condition condition : rule.conditions()) {
      if (!conditionHolds(condition, context, segments)) {
        return NOT_MATCHED;
      }
    }
    if ("value".equals(rule.target()) && rule.value() != null) {
      return new RuleServing.ServedValue(
          JsonValues.toJsonString(rule.value()), rule.valueId(), rule.variationId(), null, null);
    }
    if ("percentage".equals(rule.target())) {
      return evaluatePercentage(rule.id(), rule.percentages(), config, context);
    }
    return NOT_MATCHED;
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

  private RuleServing evaluatePercentage(
      String ruleId, List<Percentage> percentages, Config config, EvaluationContext context) {
    EvaluationContext evaluationContext = context == null ? EvaluationContext.empty() : context;
    String identifier = evaluationContext.contextOrEmpty().id();
    if (identifier == null) {
      return ROLLOUT_SKIPPED_NO_IDENTIFIER;
    }

    double assigned =
        PercentHashing.assignPercentage(
            config.id(), identifier, evaluationContext.effectiveKind());

    // A bucket spans [total, total + percentage). Strict, so a context landing exactly on a
    // boundary belongs to the bucket that starts there -- which is what keeps a 0% bucket
    // unreachable and each bucket's share exact. See SEMANTICS.md 7.1 in targeting-rules-contract.
    Percentage selectedPercentage = null;
    double total = 0.0;
    for (Percentage percentage : percentages) {
      if (assigned < percentage.percentage() + total) {
        selectedPercentage = percentage;
        break;
      }
      total += percentage.percentage();
    }

    if (selectedPercentage == null || selectedPercentage.value() == null) {
      return NOT_MATCHED;
    }
    return new RuleServing.ServedValue(
        JsonValues.toJsonString(selectedPercentage.value()),
        selectedPercentage.valueId(),
        selectedPercentage.variationId(),
        ruleId,
        selectedPercentage.id());
  }

  private sealed interface RuleServing {

    record ServedValue(
        String value, String valueId, String variationId, String ruleId, String shareId)
        implements RuleServing {}

    record NotMatched() implements RuleServing {}

    record RolloutSkippedNoIdentifier() implements RuleServing {}
  }
}
