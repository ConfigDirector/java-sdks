package com.configdirector.testing;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.internal.client.InMemoryConnection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Entry point to the testing tools.
 *
 * <pre>{@code
 * try (TestClient testClient = ConfigDirectorTesting.createTestClient(Map.of("new-checkout", true))) {
 *   CheckoutService service = new CheckoutService(testClient.client());
 *   testClient.client().initialize();
 *
 *   assertThat(service.isNewCheckoutEnabled("user-123")).isTrue();
 *
 *   testClient.setValue("new-checkout", false);
 *   assertThat(service.isNewCheckoutEnabled("user-123")).isFalse();
 * }
 * }</pre>
 */
public final class ConfigDirectorTesting {

  private ConfigDirectorTesting() {}

  /**
   * A test client with no values.
   *
   * @return a test client whose client has not been initialized yet
   */
  public static TestClient createTestClient() {
    return createTestClient(Map.of(), options -> {});
  }

  /**
   * A test client serving {@code values}.
   *
   * @param values the values to serve, keyed by config key; see {@link TestClient#setValue(String,
   *     Object)} for the types and how each becomes a config
   * @return a test client whose client has not been initialized yet
   * @throws com.configdirector.ConfigDirectorValidationException if a value cannot be encoded
   */
  public static TestClient createTestClient(Map<String, ?> values) {
    return createTestClient(values, options -> {});
  }

  /**
   * A test client serving {@code values}, with the settings {@code configure} adjusts.
   *
   * @param values the values to serve, keyed by config key
   * @param configure receives the settings to adjust before the client is built
   * @return a test client whose client has not been initialized yet
   * @throws com.configdirector.ConfigDirectorValidationException if a value cannot be encoded
   * @throws IllegalStateException if this artifact's version differs from the SDK's, which can
   *     only work by accident because it relies on the SDK's internals
   */
  public static TestClient createTestClient(Map<String, ?> values, Consumer<TestClientOptions> configure) {
    Objects.requireNonNull(values, "values");
    Objects.requireNonNull(configure, "configure");
    SdkVersionCheck.verify(
        ConfigDirectorClient.class.getPackage().getImplementationVersion(),
        ConfigDirectorTesting.class.getPackage().getImplementationVersion());
    TestClientOptions options = new TestClientOptions();
    configure.accept(options);
    return new TestClient(new InMemoryConnection(values, options.timeout(), options.logger()));
  }
}
