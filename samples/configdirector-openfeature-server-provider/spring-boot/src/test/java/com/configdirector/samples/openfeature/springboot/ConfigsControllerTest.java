package com.configdirector.samples.openfeature.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.web.client.RestClient;

// Tests the app the way an application reading flags through OpenFeature tests itself: by
// swapping the provider for the OpenFeature SDK's own InMemoryProvider, so nothing from
// ConfigDirector is involved, no network is used, and no SDK key is needed.
//
// The application context outlives a test, so there is one provider for the class, and each test
// starts by resetting its flags. @TestBean swaps in the OpenFeatureAPI holding it for the
// application's own bean, which is therefore never built and never registers the ConfigDirector
// provider.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Empty, so a developer's local .env cannot reach these tests.
    properties = "spring.config.import=")
class ConfigsControllerTest {

  private static final Map<String, Flag<?>> SAMPLE_FLAGS =
      Map.of(
          "temporary-feature-flag",
          Flag.builder().variant("on", true).variant("off", false).defaultVariant("off").build(),
          "permanent-kill-switch",
          Flag.builder().variant("on", true).variant("off", false).defaultVariant("on").build(),
          "integer-config",
          Flag.builder().variant("forty-two", 42).defaultVariant("forty-two").build(),
          "day-of-the-week-config",
          Flag.builder().variant("tuesday", "Tuesday").defaultVariant("tuesday").build(),
          "json-value-config",
          Flag.builder()
              .variant("greeting", new Value(new ImmutableStructure(Map.of("greeting", new Value("hello")))))
              .defaultVariant("greeting")
              .build());

  private static final InMemoryProvider PROVIDER = new InMemoryProvider(new HashMap<>(SAMPLE_FLAGS));

  @TestBean private OpenFeatureAPI api;

  // What @TestBean calls in place of the application's bean method, which would register the
  // ConfigDirector provider.
  static OpenFeatureAPI api() {
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(PROVIDER);
    return api;
  }

  @Autowired private Client client;

  @LocalServerPort private int port;

  @BeforeEach
  void seedTheFlags() {
    PROVIDER.updateFlags(SAMPLE_FLAGS);
  }

  @AfterAll
  static void shutOpenFeatureDown() {
    OpenFeatureAPI.getInstance().shutdown();
  }

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
  void serves_the_values_the_provider_holds() {
    assertThat(get("/configs"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "temporary-feature-flag", false,
                "permanent-kill-switch", true,
                "integer-config", 42,
                "day-of-the-week-config", "Tuesday",
                "json-value-config", Map.of("greeting", "hello")));
  }

  @Test
  void a_changed_flag_is_served_on_the_next_request() {
    PROVIDER.updateFlag("integer-config", Flag.builder().variant("seven", 7).defaultVariant("seven").build());

    assertThat(get("/configs")).containsEntry("integer-config", 7);
  }

  @Test
  void the_query_string_becomes_the_evaluation_context() {
    PROVIDER.updateFlag(
        "day-of-the-week-config",
        Flag.<String>builder()
            .variant("described", "")
            .defaultVariant("described")
            .contextEvaluator(
                (flag, context) ->
                    context.getTargetingKey()
                        + "/"
                        + context.getValue("name").asString()
                        + "/"
                        + context.getValue("anonymous").asBoolean()
                        + "/"
                        + context.getValue("traits").asStructure().getValue("plan").asString())
            .build());

    assertThat(get("/configs?id=user-123&name=Ada&anonymous=true&plan=pro"))
        .containsEntry("day-of-the-week-config", "user-123/Ada/true/pro");
  }

  @Test
  void the_in_memory_provider_is_registered_with_open_feature() {
    assertThat(api.getProviderMetadata().getName()).isEqualTo(PROVIDER.getMetadata().getName());
    assertThat(client.getMetadata()).isNotNull();
  }

  @Test
  void an_unknown_path_explains_where_to_go() {
    assertThat(get("/nope")).containsEntry("error", "Not found. Try GET /configs");
  }
}
