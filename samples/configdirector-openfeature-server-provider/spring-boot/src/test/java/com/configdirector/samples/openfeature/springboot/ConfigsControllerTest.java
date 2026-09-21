package com.configdirector.samples.openfeature.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.config.import=",
      "configdirector.server-key=fake-sample-key",
      "configdirector.mode=polling",
      "configdirector.timeout=1s"
    })
class ConfigsControllerTest {

  @LocalServerPort private int port;

  @Autowired private OpenFeatureAPI api;

  @Autowired private Client client;

  @SuppressWarnings("unchecked")
  private Map<String, Object> get(String path) {
    return RestClient.create()
        .get()
        .uri("http://localhost:" + port + path)
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .body(Map.class);
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
