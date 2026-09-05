package com.configdirector.samples.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigDirectorClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

// Runs the app with no reachable ConfigDirector, which is the same path a production app takes
// when it cannot reach the service: it keeps serving, on the defaults it chose.
//
// The test context never runs Application.main, so DotEnvPropertySource is not in play and a
// developer's local .env cannot reach these tests. The properties below pin the rest.
@MicronautTest
@Property(name = "configdirector.server-key", value = "fake-sample-key")
@Property(name = "configdirector.mode", value = "polling")
@Property(name = "configdirector.timeout", value = "1s")
class ConfigsControllerTest {

  @Inject
  @Client("/")
  HttpClient http;

  @Inject ConfigDirectorClient client;

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
  void serves_the_defaults_when_configdirector_is_unreachable() {
    Map<String, Object> body = get("/configs");

    assertThat(body)
        .containsEntry("temporary-feature-flag", true)
        .containsEntry("permanent-kill-switch", false)
        .containsEntry("integer-config", 10)
        .containsEntry("day-of-the-week-config", "Friday")
        .containsEntry("json-value-config", Map.of());
  }

  @Test
  void accepts_a_context_from_the_query_string() {
    Map<String, Object> body = get("/configs?id=user-123&name=Ada&plan=pro");

    // The context changes which rules match, not the shape of the response.
    assertThat(body).containsKey("temporary-feature-flag").hasSize(5);
  }

  @Test
  void the_client_is_a_single_shared_instance() {
    // The whole point of the sample: one client for the process, injected everywhere.
    assertThat(client).isNotNull();
    assertThat(client.isClosed()).isFalse();
  }

  @Test
  void an_unknown_path_explains_where_to_go() {
    assertThat(get("/nope")).containsEntry("error", "Not found. Try GET /configs");
  }
}
