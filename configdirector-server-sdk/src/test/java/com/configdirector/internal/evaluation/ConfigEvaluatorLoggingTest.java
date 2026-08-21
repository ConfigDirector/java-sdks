package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.configdirector.ConfigType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

// A rule the evaluator cannot make sense of is skipped rather than thrown, so the log line is the
// only signal an operator ever gets that a config is misbehaving.
class ConfigEvaluatorLoggingTest {

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("evaluator-under-test");
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  private static Config configWith(Rule rule) {
    return new Config(
        "config-1",
        "my-key",
        ConfigType.STRING,
        new TargetingRules("fallback", null, List.of(rule)),
        List.of(),
        null);
  }

  @Test
  void names_the_rule_and_the_config_when_a_rule_cannot_be_evaluated() {
    Condition broken = new Condition("c", "identifier", "=", null, List.of("u1"), null);
    ConditionalRule rule =
        new ConditionalRule("broken-rule", 1, List.of(broken), "value", "v", null, List.of());

    new ConfigEvaluator(logger).evaluate(configWith(rule), EvaluationContext.empty());

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage()).contains("broken-rule").contains("my-key");
    assertThat(event.getThrowableProxy()).isNotNull();
  }

  @Test
  void says_nothing_when_every_rule_evaluates_cleanly() {
    Condition matches = new Condition("c", "identifier", "=", "text", List.of("u1"), null);
    ConditionalRule rule =
        new ConditionalRule("good-rule", 1, List.of(matches), "value", "v", null, List.of());

    new ConfigEvaluator(logger).evaluate(configWith(rule), EvaluationContext.empty());

    assertThat(appender.list).isEmpty();
  }
}
