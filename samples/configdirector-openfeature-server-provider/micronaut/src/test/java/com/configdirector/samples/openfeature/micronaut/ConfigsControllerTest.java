package com.configdirector.samples.openfeature.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Tests the app the way an application reading flags through OpenFeature tests itself: by
// swapping the provider for the OpenFeature SDK's own InMemoryProvider, so nothing from
// ConfigDirector is involved, no network is used, and no SDK key is needed. TestOpenFeatureFactory
// swaps it in.
//
// The application context outlives a test, so there is one provider for the class, and each test
// starts by resetting its flags. The test context never runs Application.main, so a developer's
// local .env cannot reach these tests.
@MicronautTest
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

  @Inject
  @Client("/")
  HttpClient http;

  @Inject InMemoryProvider provider;

  @Inject OpenFeatureAPI api;

  @Inject dev.openfeature.sdk.Client client;

  @BeforeEach
  void seedTheFlags() {
    provider.updateFlags(SAMPLE_FLAGS);
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
    provider.updateFlag("integer-config", Flag.builder().variant("seven", 7).defaultVariant("seven").build());

    assertThat(get("/configs")).containsEntry("integer-config", 7);
  }

  @Test
  void the_query_string_becomes_the_evaluation_context() {
    provider.updateFlag(
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
    assertThat(api.getProviderMetadata().getName()).isEqualTo(provider.getMetadata().getName());
    assertThat(client.getMetadata()).isNotNull();
  }

  @Test
  void an_unknown_path_explains_where_to_go() {
    assertThat(get("/nope")).containsEntry("error", "Not found. Try GET /configs");
  }
}
