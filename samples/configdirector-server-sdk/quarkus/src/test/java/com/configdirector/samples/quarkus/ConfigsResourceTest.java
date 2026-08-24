package com.configdirector.samples.quarkus;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigDirectorClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

// Runs the app with no reachable ConfigDirector, which is the same path a production app takes
// when it cannot reach the service: it keeps serving, on the defaults it chose.
//
// The profile below pins the settings, and the test task runs from a directory with no .env in
// it, so a developer's local file cannot reach these tests either.
@QuarkusTest
@TestProfile(UnreachableConfigDirectorProfile.class)
class ConfigsResourceTest {

  @Inject ConfigDirectorClient client;

  private Map<String, Object> get(String path) {
    // An error status is a result here, not something to throw over.
    return given().when().get(path).then().extract().body().jsonPath().getMap("$");
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
