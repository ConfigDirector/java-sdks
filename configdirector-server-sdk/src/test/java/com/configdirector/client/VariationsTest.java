package com.configdirector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConfigEvaluatedEvent;
import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigState;
import com.configdirector.ConnectionMode;
import com.configdirector.Context;
import com.configdirector.EvaluationReason;
import com.configdirector.Variation;
import com.configdirector.testing.TestHttpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class VariationsTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static final String CONFIG_KEY = "checkout-cta-copy";
  private static final String FLAG_KEY = "new-checkout";
  private static final String IN_CODE_DEFAULT_VALUE = "in-code default";
  private static final String DEFAULT_TARGETING_RULE_VALUE = "Start free trial";
  private static final String TREATMENT_VALUE = "Try it free";

  private static final String CONTROL_ID = "77777777-7777-4777-8777-777777777777";
  private static final String TREATMENT_ID = "88888888-8888-4888-8888-888888888888";
  private static final String UNNAMED_ID = "99999999-9999-4999-8999-999999999999";
  private static final String ROLLOUT_RULE_ID = "22222222-2222-4222-8222-222222222222";
  private static final String TREATMENT_SHARE_ID = "66666666-6666-4666-8666-666666666666";
  private static final String SECOND_TREATMENT_SHARE_ID = "66666666-6666-4666-8666-666666666667";

  private static final Variation CONTROL = new Variation(CONTROL_ID, "control");
  private static final Variation TREATMENT = new Variation(TREATMENT_ID, "treatment");

  private static final Context PRO_USER =
      Context.builder().id("user-1").trait("plan", "pro").build();
  private static final Context PRO_CONTEXT_WITHOUT_AN_ID =
      Context.builder().trait("plan", "pro").build();

  private static final String EXPERIMENT =
      experimentWithRollout(treatmentShare(TREATMENT_SHARE_ID, 100));

  private static final String FLAG =
      """
      {"id":"00000000-0000-0000-0000-000000000002","key":"new-checkout","type":"boolean",
       "variations":[],
       "target":{"defaultValue":"true","defaultValueId":"value-id-true","rules":[]}}
      """;

  private final List<String> telemetryBodies = Collections.synchronizedList(new ArrayList<>());

  private ConfigDirectorClient client;
  private TestHttpServer server;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
  }

  private static String planIs(String plan) {
    return "{\"id\":\"44444444-4444-4444-8444-444444444444\",\"kind\":\"attribute\","
        + "\"attribute\":\"traits\",\"trait\":\"/plan\",\"operator\":\"is one of\","
        + "\"targetType\":\"text\",\"targetValues\":[\""
        + plan
        + "\"]}";
  }

  private static String treatmentShare(String id, int percentage) {
    return "{\"id\":\""
        + id
        + "\",\"percentage\":"
        + percentage
        + ",\"value\":\"Try it free\",\"valueId\":\"value-id-treatment\",\"variationId\":\""
        + TREATMENT_ID
        + "\"}";
  }

  private static String experimentWithRollout(String... shares) {
    return "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"key\":\"checkout-cta-copy\","
        + "\"type\":\"string\",\"variations\":["
        + "{\"id\":\""
        + CONTROL_ID
        + "\",\"name\":\"control\",\"value\":\"Start free trial\"},"
        + "{\"id\":\""
        + TREATMENT_ID
        + "\",\"name\":\"treatment\",\"value\":\"Try it free\"},"
        + "{\"id\":\""
        + UNNAMED_ID
        + "\",\"name\":null,\"value\":\"Get started\"}],"
        + "\"target\":{\"defaultValue\":\"Start free trial\","
        + "\"defaultValueId\":\"value-id-control\","
        + "\"defaultVariationId\":\""
        + CONTROL_ID
        + "\",\"rules\":["
        + "{\"id\":\"33333333-3333-4333-8333-333333333333\",\"order\":0,\"type\":\"conditional\","
        + "\"target\":\"value\",\"value\":\"Get started\",\"valueId\":\"value-id-get-started\","
        + "\"variationId\":\""
        + UNNAMED_ID
        + "\",\"conditions\":["
        + planIs("enterprise")
        + "]},"
        + "{\"id\":\""
        + ROLLOUT_RULE_ID
        + "\",\"order\":1,\"type\":\"conditional\",\"target\":\"percentage\",\"conditions\":["
        + planIs("pro")
        + "],\"percentages\":["
        + String.join(",", shares)
        + "]}]}}";
  }

  /** The experiment with a rule after its rollout that serves pro plans "Get started". */
  private static String experimentWithARuleAfterTheRollout() {
    String proPlansGetStarted =
        "{\"id\":\"33333333-3333-4333-8333-333333333335\",\"order\":2,\"type\":\"conditional\","
            + "\"target\":\"value\",\"value\":\"Get started\",\"valueId\":\"value-id-get-started\","
            + "\"variationId\":\""
            + UNNAMED_ID
            + "\",\"conditions\":["
            + planIs("pro")
            + "]}";
    String rulesClosing = "]}}";
    return EXPERIMENT.substring(0, EXPERIMENT.length() - rulesClosing.length())
        + ","
        + proPlansGetStarted
        + rulesClosing;
  }

  private static String bundleOf(String experiment) {
    return "{\"timestamp\":\"t1\",\"configs\":{\"checkout-cta-copy\":"
        + experiment
        + ",\"new-checkout\":"
        + FLAG
        + "}}";
  }

  private ConfigDirectorClient readyClient() {
    return readyClient(EXPERIMENT);
  }

  private ConfigDirectorClient readyClient(String experiment) {
    startedClient(experiment);
    client.initialize();
    return client;
  }

  private ConfigDirectorClient startedClient(String experiment) {
    String bundle = bundleOf(experiment);
    server =
        start(
            session -> {
              if (session.path().endsWith("/server/telemetry/v1")) {
                telemetryBodies.add(session.bodyAsString());
                session.respond(200, "Content-Length: 0", "Connection: close");
              } else {
                byte[] body = bundle.getBytes(StandardCharsets.UTF_8);
                session.respond(
                    200,
                    "Content-Type: application/json",
                    "Content-Length: " + body.length,
                    "Connection: close");
                session.send(bundle);
              }
              session.close();
            });
    String url = server.url("/");
    client =
        ConfigDirector.client(
            "sdk-key",
            options ->
                options.connection(
                    connection ->
                        connection.mode(ConnectionMode.POLLING).url(url).timeout(TIMEOUT)));
    return client;
  }

  private static TestHttpServer start(Consumer<TestHttpServer.Session> handler) {
    try {
      return TestHttpServer.start(handler);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private JsonArray evaluationEntriesSentOnClosing() {
    client.close();
    await().atMost(TIMEOUT).until(() -> !telemetryBodies.isEmpty());
    JsonObject report;
    synchronized (telemetryBodies) {
      report = JsonParser.parseString(telemetryBodies.get(0)).getAsJsonObject();
    }
    return report.getAsJsonObject("aggregatedEvents").getAsJsonArray("evaluatedConfig");
  }

  private static JsonObject eventOf(JsonArray entries, int index) {
    return entries.get(index).getAsJsonObject().getAsJsonObject("event");
  }

  @Nested
  @DisplayName("evaluate")
  class Evaluate {

    @Test
    void returns_the_variation_of_the_share_a_percentage_rollout_served() {
      ConfigEvaluation evaluation =
          readyClient().evaluate(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_USER);

      assertThat(evaluation.key()).isEqualTo(CONFIG_KEY);
      assertThat(evaluation.value()).isEqualTo(TREATMENT_VALUE);
      assertThat(evaluation.valueId()).isEqualTo("value-id-treatment");
      assertThat(evaluation.isDefault()).isFalse();
      assertThat(evaluation.reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
      assertThat(evaluation.context()).isEqualTo(PRO_USER);
      assertThat(evaluation.variation()).isEqualTo(TREATMENT);
    }

    @Test
    void returns_the_unnamed_variation_of_a_conditional_rules_value_with_its_id_and_no_name() {
      Context enterpriseUser = Context.builder().id("user-1").trait("plan", "enterprise").build();

      ConfigEvaluation evaluation =
          readyClient().evaluate(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, enterpriseUser);

      assertThat(evaluation.value()).isEqualTo("Get started");
      assertThat(evaluation.variation()).isEqualTo(new Variation(UNNAMED_ID, null));
    }

    @Test
    void returns_the_variation_of_the_default_targeting_rule_value_when_no_context_is_supplied() {
      ConfigEvaluation evaluation = readyClient().evaluate(CONFIG_KEY, IN_CODE_DEFAULT_VALUE);

      assertThat(evaluation.value()).isEqualTo(DEFAULT_TARGETING_RULE_VALUE);
      assertThat(evaluation.reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
      assertThat(evaluation.context()).isNull();
      assertThat(evaluation.variation()).isEqualTo(CONTROL);
    }

    @Test
    void serves_a_context_without_an_id_that_reaches_a_rollout_the_default_targeting_rule_value() {
      ConfigEvaluation evaluation =
          readyClient().evaluate(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_CONTEXT_WITHOUT_AN_ID);

      assertThat(evaluation.value()).isEqualTo(DEFAULT_TARGETING_RULE_VALUE);
      assertThat(evaluation.valueId()).isEqualTo("value-id-control");
      assertThat(evaluation.isDefault()).isFalse();
      assertThat(evaluation.reason()).isEqualTo(EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER);
      assertThat(evaluation.variation()).isEqualTo(CONTROL);
    }

    @Test
    void returns_no_variation_for_a_flag_whose_config_has_no_variations() {
      ConfigEvaluation evaluation = readyClient().evaluate(FLAG_KEY, false, PRO_USER);

      assertThat(evaluation.value()).isEqualTo(true);
      assertThat(evaluation.valueId()).isEqualTo("value-id-true");
      assertThat(evaluation.variation()).isNull();
    }

    @Test
    void returns_no_variation_with_the_in_code_default_value_when_the_value_cannot_be_read() {
      ConfigEvaluation evaluation = readyClient().evaluate(CONFIG_KEY, 7, PRO_USER);

      assertThat(evaluation.value()).isEqualTo(7);
      assertThat(evaluation.isDefault()).isTrue();
      assertThat(evaluation.reason()).isEqualTo(EvaluationReason.INVALID_NUMBER);
      assertThat(evaluation.variation()).isNull();
    }

    @Test
    void keeps_the_reason_of_the_in_code_default_value_for_a_context_without_an_id_at_a_rollout() {
      ConfigEvaluation evaluation =
          readyClient().evaluate(CONFIG_KEY, 7, PRO_CONTEXT_WITHOUT_AN_ID);

      assertThat(evaluation.value()).isEqualTo(7);
      assertThat(evaluation.isDefault()).isTrue();
      assertThat(evaluation.reason()).isEqualTo(EvaluationReason.INVALID_NUMBER);
    }

    @Test
    void returns_the_in_code_default_value_for_a_key_that_does_not_exist() {
      ConfigEvaluation evaluation =
          readyClient().evaluate("no-such-config", IN_CODE_DEFAULT_VALUE, PRO_USER);

      assertThat(evaluation.value()).isEqualTo(IN_CODE_DEFAULT_VALUE);
      assertThat(evaluation.isDefault()).isTrue();
      assertThat(evaluation.reason()).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING);
      assertThat(evaluation.variation()).isNull();
    }

    @Test
    void returns_the_in_code_default_value_before_the_client_is_ready() {
      ConfigEvaluation evaluation =
          startedClient(EXPERIMENT).evaluate(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_USER);

      assertThat(evaluation.value()).isEqualTo(IN_CODE_DEFAULT_VALUE);
      assertThat(evaluation.isDefault()).isTrue();
      assertThat(evaluation.reason()).isEqualTo(EvaluationReason.CLIENT_NOT_READY);
      assertThat(evaluation.variation()).isNull();
    }

    @Test
    void serves_a_context_without_an_id_the_rule_after_a_skipped_rollout() {
      ConfigDirectorClient ready = readyClient(experimentWithARuleAfterTheRollout());

      ConfigEvaluation evaluation =
          ready.evaluate(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_CONTEXT_WITHOUT_AN_ID);

      assertThat(evaluation.value()).isEqualTo("Get started");
      assertThat(evaluation.reason()).isEqualTo(EvaluationReason.FOUND_MATCH);
      assertThat(evaluation.isDefault()).isFalse();
      assertThat(evaluation.variation()).isEqualTo(new Variation(UNNAMED_ID, null));
    }

    @Test
    void returns_the_value_get_value_returns() {
      ConfigDirectorClient ready = readyClient();

      String value = ready.getValue(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_USER);

      assertThat(ready.evaluate(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_USER).value())
          .isEqualTo(value);
    }

    @Test
    void rejects_a_blank_config_key() {
      ConfigDirectorClient ready = readyClient();

      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> ready.evaluate(" ", IN_CODE_DEFAULT_VALUE));
    }

    @Test
    void rejects_a_null_in_code_default_value() {
      ConfigDirectorClient ready = readyClient();

      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> ready.evaluate(CONFIG_KEY, null));
    }

    @Test
    void emits_the_evaluation_it_returns_on_config_evaluated() {
      ConfigDirectorClient ready = readyClient();
      List<ConfigEvaluatedEvent> events = new ArrayList<>();
      ready.onConfigEvaluated(events::add);

      ConfigEvaluation evaluation = ready.evaluate(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_USER);

      assertThat(events).extracting(ConfigEvaluatedEvent::evaluation).containsExactly(evaluation);
    }

    @Test
    void is_counted_as_one_evaluation_in_telemetry() {
      readyClient().evaluate(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_USER);

      JsonArray entries = evaluationEntriesSentOnClosing();

      assertThat(entries).hasSize(1);
      assertThat(entries.get(0).getAsJsonObject().get("count").getAsInt()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("configEvaluated")
  class ConfigEvaluated {

    private final List<ConfigEvaluation> evaluations = new ArrayList<>();

    private void recordEvaluations() {
      client.onConfigEvaluated(event -> evaluations.add(event.evaluation()));
    }

    @Test
    void carries_the_variation_of_the_value_a_getter_served() {
      readyClient();
      recordEvaluations();

      client.getString(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_USER);

      assertThat(evaluations)
          .singleElement()
          .extracting(ConfigEvaluation::variation)
          .isEqualTo(TREATMENT);
    }

    @Test
    void carries_the_reason_for_a_skipped_rollout() {
      readyClient();
      recordEvaluations();

      client.getString(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_CONTEXT_WITHOUT_AN_ID);

      assertThat(evaluations)
          .singleElement()
          .satisfies(
              evaluation -> {
                assertThat(evaluation.reason())
                    .isEqualTo(EvaluationReason.ROLLOUT_SKIPPED_NO_IDENTIFIER);
                assertThat(evaluation.isDefault()).isFalse();
                assertThat(evaluation.variation()).isEqualTo(CONTROL);
              });
    }

    @Test
    void carries_the_variation_of_a_value_delivered_to_a_watcher() {
      startedClient(EXPERIMENT);
      recordEvaluations();
      client.watchString(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, value -> {}, PRO_USER);

      client.initialize();

      assertThat(evaluations)
          .singleElement()
          .extracting(ConfigEvaluation::variation)
          .isEqualTo(TREATMENT);
    }
  }

  @Nested
  @DisplayName("a watch")
  class Watch {

    @Test
    void gives_a_context_without_an_id_that_reaches_a_rollout_the_default_targeting_rule_value() {
      startedClient(EXPERIMENT);
      List<String> values = new ArrayList<>();
      client.watchString(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, values::add, PRO_CONTEXT_WITHOUT_AN_ID);

      client.initialize();

      assertThat(values).containsExactly(DEFAULT_TARGETING_RULE_VALUE);
    }
  }

  @Nested
  @DisplayName("evaluation events")
  class EvaluationEvents {

    @Test
    void report_the_rule_share_and_variation_of_a_value_a_percentage_rollout_served() {
      readyClient().getString(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_USER);

      JsonObject event = eventOf(evaluationEntriesSentOnClosing(), 0);

      assertThat(event.get("ruleId").getAsString()).isEqualTo(ROLLOUT_RULE_ID);
      assertThat(event.get("shareId").getAsString()).isEqualTo(TREATMENT_SHARE_ID);
      assertThat(event.get("variationId").getAsString()).isEqualTo(TREATMENT_ID);
      assertThat(event.get("evaluationReason").getAsString()).isEqualTo("found-match");
    }

    @Test
    void report_the_variation_and_no_rule_or_share_for_a_conditional_rules_value() {
      Context enterpriseUser = Context.builder().id("user-1").trait("plan", "enterprise").build();
      readyClient().getString(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, enterpriseUser);

      JsonObject event = eventOf(evaluationEntriesSentOnClosing(), 0);

      assertThat(event.get("variationId").getAsString()).isEqualTo(UNNAMED_ID);
      assertThat(event.has("ruleId")).isFalse();
      assertThat(event.has("shareId")).isFalse();
    }

    @Test
    void report_a_skipped_rollout_with_its_reason_its_variation_and_no_rule_or_share() {
      readyClient().getString(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, PRO_CONTEXT_WITHOUT_AN_ID);

      JsonObject event = eventOf(evaluationEntriesSentOnClosing(), 0);

      assertThat(event.get("evaluationReason").getAsString())
          .isEqualTo("rollout-skipped-no-identifier");
      assertThat(event.get("usedDefault").getAsBoolean()).isFalse();
      assertThat(event.get("variationId").getAsString()).isEqualTo(CONTROL_ID);
      assertThat(event.has("ruleId")).isFalse();
      assertThat(event.has("shareId")).isFalse();
    }

    @Test
    void report_no_rule_share_or_variation_for_a_flag_whose_config_has_no_variations() {
      readyClient().getBoolean(FLAG_KEY, false, PRO_USER);

      JsonObject event = eventOf(evaluationEntriesSentOnClosing(), 0);

      assertThat(event.get("usedDefault").getAsBoolean()).isFalse();
      assertThat(event.has("ruleId")).isFalse();
      assertThat(event.has("shareId")).isFalse();
      assertThat(event.has("variationId")).isFalse();
    }

    @Test
    void report_no_rule_share_or_variation_when_the_in_code_default_value_is_returned() {
      readyClient().getInteger(CONFIG_KEY, 7, PRO_USER);

      JsonObject event = eventOf(evaluationEntriesSentOnClosing(), 0);

      assertThat(event.get("usedDefault").getAsBoolean()).isTrue();
      assertThat(event.get("evaluationReason").getAsString()).isEqualTo("invalid-number");
      assertThat(event.has("ruleId")).isFalse();
      assertThat(event.has("shareId")).isFalse();
      assertThat(event.has("variationId")).isFalse();
    }

    @Test
    void count_evaluations_of_one_value_served_by_two_shares_separately() {
      readyClient(
          experimentWithRollout(
              treatmentShare(TREATMENT_SHARE_ID, 50),
              treatmentShare(SECOND_TREATMENT_SHARE_ID, 50)));

      client.getString(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, anonymousProContext("user-1"));
      client.getString(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, anonymousProContext("user-3"));
      client.getString(CONFIG_KEY, IN_CODE_DEFAULT_VALUE, anonymousProContext("user-3"));

      JsonArray entries = evaluationEntriesSentOnClosing();

      assertThat(entries).hasSize(2);
      assertThat(entries.get(0).getAsJsonObject().get("count").getAsInt()).isEqualTo(1);
      assertThat(eventOf(entries, 0).get("shareId").getAsString()).isEqualTo(TREATMENT_SHARE_ID);
      assertThat(entries.get(1).getAsJsonObject().get("count").getAsInt()).isEqualTo(2);
      assertThat(eventOf(entries, 1).get("shareId").getAsString())
          .isEqualTo(SECOND_TREATMENT_SHARE_ID);
    }

    private static Context anonymousProContext(String id) {
      return Context.builder().id(id).anonymous(true).trait("plan", "pro").build();
    }
  }

  @Nested
  @DisplayName("getAllConfigs")
  class GetAllConfigs {

    @Test
    void returns_the_variation_rule_and_share_of_a_value_a_percentage_rollout_served() {
      ConfigState state = readyClient().getAllConfigs(PRO_USER).get(CONFIG_KEY);

      assertThat(state.value()).isEqualTo(TREATMENT_VALUE);
      assertThat(state.variation()).isEqualTo(TREATMENT);
      assertThat(state.ruleId()).isEqualTo(ROLLOUT_RULE_ID);
      assertThat(state.shareId()).isEqualTo(TREATMENT_SHARE_ID);
    }

    @Test
    void returns_the_default_rule_variation_and_no_rule_or_share_for_a_context_without_an_id() {
      ConfigState state = readyClient().getAllConfigs(PRO_CONTEXT_WITHOUT_AN_ID).get(CONFIG_KEY);

      assertThat(state.value()).isEqualTo(DEFAULT_TARGETING_RULE_VALUE);
      assertThat(state.variation()).isEqualTo(CONTROL);
      assertThat(state.ruleId()).isNull();
      assertThat(state.shareId()).isNull();
    }

    @Test
    void returns_no_variation_rule_or_share_for_a_flag() {
      ConfigState state = readyClient().getAllConfigs(PRO_USER).get(FLAG_KEY);

      assertThat(state.value()).isEqualTo("true");
      assertThat(state.variation()).isNull();
      assertThat(state.ruleId()).isNull();
      assertThat(state.shareId()).isNull();
    }
  }
}
