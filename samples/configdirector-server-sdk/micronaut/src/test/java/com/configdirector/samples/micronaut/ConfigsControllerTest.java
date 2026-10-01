package com.configdirector.samples.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigEvaluatedEvent;
import com.configdirector.Context;
import com.configdirector.Subscription;
import com.configdirector.testing.TestClient;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Tests the app the way an application using the SDK tests itself: with a test client from the
// SDK's testing artifact, the real client over an in-memory connection the test controls, so no
// network is involved and no SDK key is needed. TestConfigDirectorFactory swaps it in.
//
// The application context outlives a test, so there is one test client for the class, and each
// test starts by resetting its values. The test context never runs Application.main, so
// DotEnvPropertySource is not in play and a developer's local .env cannot reach these tests.
@MicronautTest
class ConfigsControllerTest {

  private static final Map<String, Object> SAMPLE_VALUES =
      Map.of(
          "temporary-feature-flag", false,
          "permanent-kill-switch", true,
          "integer-config", 42,
          "day-of-the-week-config", "Tuesday",
          "json-value-config", Map.of("greeting", "hello"));

  @Inject
  @Client("/")
  HttpClient http;

  @Inject TestClient testClient;

  @Inject ConfigDirectorClient client;

  @BeforeEach
  void seedTheValues() {
    testClient.replaceValues(SAMPLE_VALUES);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> get(String path) {
    try {
      return http.toBlocking().retrieve(HttpRequest.GET(path), Map.class);
    } catch (HttpClientResponseException e) {
      // An error status is a result here, not something to throw over.
      return e.getResponse().getBody(Map.class).orElseThrow();
    }
  }

  @Test
  void serves_the_values_configdirector_holds() {
    assertThat(get("/configs")).containsExactlyInAnyOrderEntriesOf(SAMPLE_VALUES);
  }

  @Test
  void a_changed_value_is_served_on_the_next_request() {
    testClient.setValue("integer-config", 7);

    assertThat(get("/configs")).containsEntry("integer-config", 7);
  }

  @Test
  void a_removed_config_falls_back_to_the_default() {
    testClient.removeValue("day-of-the-week-config");

    assertThat(get("/configs")).containsEntry("day-of-the-week-config", "Friday");
  }

  @Test
  void serves_the_defaults_for_configs_configdirector_does_not_hold() {
    testClient.replaceValues(Map.of());

    assertThat(get("/configs"))
        .containsEntry("temporary-feature-flag", true)
        .containsEntry("permanent-kill-switch", false)
        .containsEntry("integer-config", 10)
        .containsEntry("day-of-the-week-config", "Friday")
        .containsEntry("json-value-config", Map.of());
  }

  @Test
  void the_query_string_becomes_the_evaluation_context() throws Exception {
    List<ConfigEvaluatedEvent> evaluated = new ArrayList<>();
    try (Subscription subscription = client.onConfigEvaluated(evaluated::add)) {
      get("/configs?id=user-123&name=Ada&anonymous=true&plan=pro");
    }

    assertThat(evaluated).hasSize(5);
    Context context = evaluated.get(0).evaluation().context();
    assertThat(evaluated).allSatisfy(event -> assertThat(event.evaluation().context()).isEqualTo(context));
    assertThat(context.id()).isEqualTo("user-123");
    assertThat(context.name()).isEqualTo("Ada");
    assertThat(context.anonymous()).isTrue();
    assertThat(context.traits()).containsExactlyEntriesOf(Map.of("plan", "pro"));
  }

  @Test
  void the_client_is_a_single_shared_instance() {
    // The whole point of the sample: one client for the process, injected everywhere.
    assertThat(client).isSameAs(testClient.client());
    assertThat(client.isClosed()).isFalse();
  }

  @Test
  void an_unknown_path_explains_where_to_go() {
    assertThat(get("/nope")).containsEntry("error", "Not found. Try GET /configs");
  }
}
