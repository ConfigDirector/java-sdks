package com.configdirector.samples.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigDirectorClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

// Runs the app with no reachable ConfigDirector, which is the same path a production app takes
// when it cannot reach the service: it keeps serving, on the defaults it chose.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // Empty, so a developer's local .env cannot reach these tests.
      "spring.config.import=",
      "configdirector.server-key=fake-sample-key",
      "configdirector.mode=one-time",
      "configdirector.timeout=1s"
    })
class ConfigsControllerTest {

  @LocalServerPort private int port;

  @Autowired private ConfigDirectorClient client;

  @SuppressWarnings("unchecked")
  private Map<String, Object> get(String path) {
    return RestClient.create()
        .get()
        .uri("http://localhost:" + port + path)
        .retrieve()
        // An error status is a result here, not something to throw over.
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
