package com.configdirector.samples.openfeature.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openfeature.sdk.OpenFeatureAPI;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@MicronautTest
@Property(name = "configdirector.server-key", value = "fake-sample-key")
@Property(name = "configdirector.mode", value = "polling")
@Property(name = "configdirector.timeout", value = "1s")
class ConfigsControllerTest {

  @Inject
  @Client("/")
  HttpClient http;

  @Inject OpenFeatureAPI api;

  @Inject dev.openfeature.sdk.Client client;

  @SuppressWarnings("unchecked")
  private Map<String, Object> get(String path) {
    try {
      return http.toBlocking().retrieve(HttpRequest.GET(path), Map.class);
    } catch (HttpClientResponseException e) {
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

    assertThat(body).containsKey("temporary-feature-flag").hasSize(5);
  }

  @Test
  void the_configdirector_provider_is_registered_with_open_feature() {
    assertThat(api.getProviderMetadata().getName()).isEqualTo("ConfigDirectorProvider");
    assertThat(client.getMetadata()).isNotNull();
  }

  @Test
  void an_unknown_path_explains_where_to_go() {
    assertThat(get("/nope")).containsEntry("error", "Not found. Try GET /configs");
  }
}
