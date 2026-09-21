package com.configdirector.samples.openfeature.quarkus;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(UnreachableConfigDirectorProfile.class)
class ConfigsResourceTest {

  @Inject OpenFeatureAPI api;

  @Inject Client client;

  private Map<String, Object> get(String path) {
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
