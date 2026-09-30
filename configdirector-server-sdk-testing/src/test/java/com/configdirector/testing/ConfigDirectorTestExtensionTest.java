package com.configdirector.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConfigType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ConfigDirectorTestExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@BooleanConfigValue(key = "new-checkout", value = true)
@StringConfigValue(key = "greeting", value = "hello")
class ConfigDirectorTestExtensionTest {

  private static final AtomicReference<ConfigDirectorClient> clientOfTheFirstTest = new AtomicReference<>();

  @Test
  @Order(1)
  @IntegerConfigValue(key = "max-items", value = 20)
  @IntegerConfigValue(key = "big", value = 3_000_000_000L)
  @FloatConfigValue(key = "ratio", value = 2.5)
  @JsonConfigValue(key = "theme", value = "{\"color\": \"blue\", \"sizes\": [1, 2]}")
  @JsonConfigValue(key = "tags", value = "[\"a\", \"b\"]")
  void s28_seeds_one_value_of_each_type_from_annotations(TestClient testClient) {
    clientOfTheFirstTest.set(testClient.client());
    ConfigDirectorClient client = testClient.client();
    client.initialize();

    assertThat(client.isReady()).isTrue();
    assertThat(client.getBoolean("new-checkout", false)).isTrue();
    assertThat(client.getString("greeting", "x")).isEqualTo("hello");
    assertThat(client.getInteger("max-items", 0)).isEqualTo(20);
    assertThat(client.getValue("big", 0L)).isEqualTo(3_000_000_000L);
    assertThat(client.getDouble("ratio", 0.0)).isEqualTo(2.5);
    assertThat(client.getJsonObject("theme", Map.of()))
        .containsEntry("color", "blue")
        .containsEntry("sizes", List.of(1L, 2L));
    assertThat(client.getJsonArray("tags", List.of())).containsExactly("a", "b");
    assertThat(client.getAllConfigs().get("theme").type()).isEqualTo(ConfigType.JSON);
    assertThat(client.getAllConfigs().get("theme").value()).isEqualTo("{\"color\":\"blue\",\"sizes\":[1,2]}");
    assertThat(client.getAllConfigs().get("big").type()).isEqualTo(ConfigType.INTEGER);
    assertThat(client.getAllConfigs().get("ratio").type()).isEqualTo(ConfigType.FLOAT);

    testClient.setValue("max-items", 25);

    assertThat(client.getInteger("max-items", 0)).isEqualTo(25);
  }

  @Test
  @Order(2)
  @BooleanConfigValue(key = "new-checkout", value = false)
  void s28_a_second_test_gets_its_own_client_with_only_its_own_values(ConfigDirectorClient client) {
    assertThat(clientOfTheFirstTest.get().isClosed()).isTrue();
    assertThat(client).isNotSameAs(clientOfTheFirstTest.get());
    client.initialize();

    assertThat(client.getBoolean("new-checkout", true)).isFalse();
    assertThat(client.getString("greeting", "x")).isEqualTo("hello");
    assertThat(client.getInteger("max-items", 7)).isEqualTo(7);
    assertThat(client.getAllConfigs()).containsOnlyKeys("new-checkout", "greeting");
  }

  @Test
  @Order(3)
  void resolves_both_parameter_types_to_the_same_client(TestClient testClient, ConfigDirectorClient client) {
    assertThat(client).isSameAs(testClient.client());
    assertThat(client.isReady()).isFalse();
  }

  @Test
  void invalid_json_text_on_an_annotation_is_rejected_when_the_client_is_seeded() throws Exception {
    Method annotated = Examples.class.getDeclaredMethod("withInvalidJson");
    try (TestClient testClient = ConfigDirectorTesting.createTestClient()) {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> AnnotatedConfigValues.seed(testClient, Examples.class, annotated))
          .withMessageContaining("theme");
    }
  }

  @Test
  void method_annotations_are_applied_after_class_annotations() throws Exception {
    Method annotated = Examples.class.getDeclaredMethod("overriding");
    try (TestClient testClient = ConfigDirectorTesting.createTestClient()) {
      AnnotatedConfigValues.seed(testClient, Examples.class, annotated);
      testClient.client().initialize();

      assertThat(testClient.client().getInteger("max-items", 0)).isEqualTo(30);
      assertThat(testClient.client().getString("greeting", "x")).isEqualTo("hi");
    }
  }

  @IntegerConfigValue(key = "max-items", value = 20)
  @StringConfigValue(key = "greeting", value = "hi")
  @SuppressWarnings("UnusedMethod")
  private static final class Examples {

    @JsonConfigValue(key = "theme", value = "{color: blue}")
    void withInvalidJson() {}

    @IntegerConfigValue(key = "max-items", value = 30)
    void overriding() {}
  }
}
