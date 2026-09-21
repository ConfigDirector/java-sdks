package com.configdirector.openfeature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigDirectorProviderTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private FakeConfigDirectorServer server;
  private OpenFeatureAPI api;

  @BeforeEach
  void setUp() {
    server = FakeConfigDirectorServer.start();
    api = OpenFeatureAPI.createIsolated();
  }

  @AfterEach
  void tearDown() {
    api.shutdown();
    server.close();
  }

  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String bundleOf(String... configs) {
    return "{\"timestamp\":\"t1\",\"configs\":{" + String.join(",", configs) + "}}";
  }

  private static String deltaOf(String... configs) {
    return "{\"kind\":\"delta\",\"timestamp\":\"t2\",\"configs\":{" + String.join(",", configs) + "}}";
  }

  private static String config(String key, String type, String value) {
    return config(key, type, value, "");
  }

  private static String config(String key, String type, String value, String rules) {
    return quote(key)
        + ":{\"id\":"
        + quote("id-" + key)
        + ",\"key\":"
        + quote(key)
        + ",\"type\":"
        + quote(type)
        + ",\"target\":{\"defaultValue\":"
        + quote(value)
        + ",\"defaultValueId\":"
        + quote("dv-" + key)
        + ",\"rules\":["
        + rules
        + "]}}";
  }

  private static String rule(String attribute, String trait, String target, String value) {
    return "{\"id\":\"r1\",\"type\":\"conditional\",\"order\":1,\"target\":\"value\",\"value\":"
        + quote(value)
        + ",\"valueId\":\"rule-value\",\"conditions\":[{\"id\":\"c1\",\"attribute\":"
        + quote(attribute)
        + ",\"operator\":\"=\",\"targetType\":\"text\",\"targetValues\":["
        + quote(target)
        + "],\"trait\":"
        + (trait == null ? "null" : quote(trait))
        + "}]}";
  }

  private ConfigDirectorProvider provider(Duration timeout) {
    String url = server.url();
    return new ConfigDirectorProvider(
        "sdk-key", options -> options.connection(connection -> connection.url(url).timeout(timeout)));
  }

  private Client clientServing(String bundle) {
    server.send(bundle);
    api.setProviderAndWait(provider(TIMEOUT));
    return api.getClient();
  }

  @Test
  void resolves_each_flag_type_once_the_provider_is_ready() throws InterruptedException {
    Client client =
        clientServing(
            bundleOf(
                config("show-banner", "boolean", "true"),
                config("greeting", "string", "Bye"),
                config("max-items", "integer", "42"),
                config("rate", "float", "1.5"),
                config("settings", "json", "{\"theme\":\"dark\",\"sizes\":[1,2.5],\"extra\":null}"),
                config("tags", "json", "[\"a\",\"b\"]")));

    assertThat(client.getBooleanValue("show-banner", false)).isTrue();
    assertThat(client.getStringValue("greeting", "Hello")).isEqualTo("Bye");
    assertThat(client.getIntegerValue("max-items", 0)).isEqualTo(42);
    assertThat(client.getDoubleValue("rate", 0.0)).isEqualTo(1.5);

    Value settings = client.getObjectValue("settings", new Value(new ImmutableStructure()));
    assertThat(settings.asStructure().getValue("theme").asString()).isEqualTo("dark");
    assertThat(settings.asStructure().getValue("sizes").asList())
        .containsExactly(new Value(1), new Value(2.5));
    assertThat(settings.asStructure().getValue("extra").isNull()).isTrue();

    Value tags = client.getObjectValue("tags", new Value(List.<Value>of()));
    assertThat(tags.asList()).containsExactly(new Value("a"), new Value("b"));

    assertThat(server.nextStreamRequest()).contains("\"serverSdkKey\":\"sdk-key\"");
  }

  @Test
  void reports_a_match_with_the_value_id_as_the_variant() {
    Client client = clientServing(bundleOf(config("greeting", "string", "Bye")));

    FlagEvaluationDetails<String> details = client.getStringDetails("greeting", "Hello");

    assertThat(details.getValue()).isEqualTo("Bye");
    assertThat(details.getReason()).isEqualTo(Reason.TARGETING_MATCH.toString());
    assertThat(details.getVariant()).isEqualTo("dv-greeting");
    assertThat(details.getErrorCode()).isNull();
  }

  @Test
  void returns_the_default_with_flag_not_found_for_a_config_the_server_did_not_send() {
    Client client = clientServing(bundleOf());

    FlagEvaluationDetails<Boolean> details = client.getBooleanDetails("missing-flag", true);

    assertThat(details.getValue()).isTrue();
    assertThat(details.getReason()).isEqualTo(Reason.ERROR.toString());
    assertThat(details.getErrorCode()).isEqualTo(ErrorCode.FLAG_NOT_FOUND);
    assertThat(client.getStringValue("missing-flag", "fallback")).isEqualTo("fallback");
    assertThat(client.getIntegerValue("missing-flag", 7)).isEqualTo(7);
  }

  @Test
  void returns_the_default_with_type_mismatch_for_a_value_of_another_type() {
    Client client = clientServing(bundleOf(config("greeting", "string", "Bye")));

    FlagEvaluationDetails<Integer> number = client.getIntegerDetails("greeting", 7);
    FlagEvaluationDetails<Value> object =
        client.getObjectDetails("greeting", new Value(new ImmutableStructure()));

    assertThat(number.getValue()).isEqualTo(7);
    assertThat(number.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
    assertThat(number.getErrorMessage()).contains("greeting").contains("invalid-number");
    assertThat(object.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
  }

  @Test
  void returns_the_same_object_default_it_was_given() {
    server.send(bundleOf());
    ConfigDirectorProvider provider = provider(TIMEOUT);
    api.setProviderAndWait(provider);
    Value defaultValue =
        new Value(new ImmutableStructure(Map.of("since", new Value(Instant.parse("2026-01-01T00:00:00Z")))));

    assertThat(provider.getObjectEvaluation("missing-flag", defaultValue, null).getValue())
        .isSameAs(defaultValue);
  }

  @Test
  void resolves_long_values_beyond_the_range_a_double_holds_exactly() {
    Client client = clientServing(bundleOf(config("big", "integer", "9007199254740993")));

    assertThat(client.getLongValue("big", 0L)).isEqualTo(9007199254740993L);
  }

  @Test
  void keeps_a_whole_number_too_large_for_an_integer_as_a_long_inside_an_object() {
    Client client = clientServing(bundleOf(config("limits", "json", "{\"max\":9007199254740993}")));

    Value limits = client.getObjectValue("limits", new Value(new ImmutableStructure()));

    assertThat(limits.asStructure().getValue("max").asObject()).isEqualTo(9007199254740993L);
  }

  @Test
  void maps_the_targeting_key_name_and_traits_onto_the_context_rules_are_evaluated_against() {
    Client client =
        clientServing(
            bundleOf(
                config("by-id", "string", "default", rule("identifier", null, "user-1", "matched")),
                config("by-name", "string", "default", rule("name", null, "Ada Lovelace", "matched")),
                config("by-trait", "string", "default", rule("traits", "/region", "EU", "matched"))));

    EvaluationContext matching =
        new ImmutableContext(
            "user-1",
            Map.of(
                "name", new Value("Ada Lovelace"),
                "traits", new Value(new ImmutableStructure(Map.of("region", new Value("EU"))))));
    EvaluationContext other = new ImmutableContext("user-2");

    assertThat(client.getStringValue("by-id", "x", matching)).isEqualTo("matched");
    assertThat(client.getStringValue("by-name", "x", matching)).isEqualTo("matched");
    assertThat(client.getStringValue("by-trait", "x", matching)).isEqualTo("matched");
    assertThat(client.getStringDetails("by-id", "x", matching).getVariant()).isEqualTo("rule-value");
    assertThat(client.getStringValue("by-id", "x", other)).isEqualTo("default");
    assertThat(client.getStringValue("by-name", "x", other)).isEqualTo("default");
    assertThat(client.getStringValue("by-trait", "x", other)).isEqualTo("default");
  }

  @Test
  void falls_back_to_the_id_attribute_when_there_is_no_targeting_key() {
    Client client =
        clientServing(
            bundleOf(config("by-id", "string", "default", rule("identifier", null, "42", "matched"))));

    EvaluationContext withId = new ImmutableContext(Map.of("id", new Value(42)));
    EvaluationContext withBoth = new ImmutableContext("user-1", Map.of("id", new Value(42)));

    assertThat(client.getStringValue("by-id", "x", withId)).isEqualTo("matched");
    assertThat(client.getStringValue("by-id", "x", withBoth)).isEqualTo("default");
  }

  @Test
  void keeps_an_anonymous_context_out_of_the_reported_telemetry() throws InterruptedException {
    Client client = clientServing(bundleOf(config("greeting", "string", "Bye")));

    client.getStringValue("greeting", "x", new ImmutableContext("known-user"));
    client.getStringValue(
        "greeting", "x", new ImmutableContext("hidden-user", Map.of("anonymous", new Value(true))));
    api.shutdown();

    assertThat(server.nextTelemetryRequest()).contains("known-user").doesNotContain("hidden-user");
  }

  @Test
  void emits_configuration_changed_with_the_keys_an_update_carried() {
    List<EventDetails> changes = new CopyOnWriteArrayList<>();
    api.onProviderConfigurationChanged(changes::add);
    Client client = clientServing(bundleOf(config("greeting", "string", "Bye")));

    server.send(deltaOf(config("greeting", "string", "Ciao")));

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(changes)
                    .extracting(EventDetails::getFlagsChanged)
                    .contains(List.of("greeting")));
    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> assertThat(client.getStringValue("greeting", "Hello")).isEqualTo("Ciao"));
  }

  @Test
  void finishes_initializing_without_configs_and_becomes_ready_when_they_arrive() {
    List<EventDetails> ready = new CopyOnWriteArrayList<>();
    api.onProviderReady(ready::add);
    api.setProviderAndWait(provider(Duration.ofMillis(200)));
    Client client = api.getClient();
    await().atMost(TIMEOUT).until(() -> ready.size() == 1);

    FlagEvaluationDetails<Boolean> early = client.getBooleanDetails("show-banner", false);
    assertThat(early.getValue()).isFalse();
    assertThat(early.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_NOT_READY);

    server.send(bundleOf(config("show-banner", "boolean", "true")));

    await().atMost(TIMEOUT).until(() -> ready.size() == 2);
    assertThat(client.getBooleanValue("show-banner", false)).isTrue();
  }

  @Test
  void closes_the_client_when_open_feature_shuts_the_provider_down() {
    server.send(bundleOf(config("show-banner", "boolean", "true")));
    ConfigDirectorProvider provider = provider(TIMEOUT);
    api.setProviderAndWait(provider);
    assertThat(provider.getBooleanEvaluation("show-banner", false, null).getValue()).isTrue();

    api.shutdown();

    assertThat(provider.getBooleanEvaluation("show-banner", false, null).getValue()).isFalse();
  }

  @Test
  void names_itself_in_the_provider_metadata() {
    clientServing(bundleOf());

    assertThat(api.getProviderMetadata().getName()).isEqualTo("ConfigDirectorProvider");
  }
}
