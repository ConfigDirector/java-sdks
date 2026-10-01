package com.configdirector.samples.micronaut;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.testing.ConfigDirectorTesting;
import com.configdirector.testing.TestClient;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;

/**
 * Swaps the application's client for the client of a test client from the SDK's testing artifact:
 * the real client over an in-memory connection the tests control. {@code @Replaces} removes the
 * application's own bean, which is therefore never built and never connects.
 */
@Factory
public class TestConfigDirectorFactory {

  @Singleton
  @Bean(preDestroy = "close")
  TestClient testClient() {
    return ConfigDirectorTesting.createTestClient();
  }

  /** Initializes the client where the application's factory method would have. */
  @Singleton
  @Replaces(bean = ConfigDirectorClient.class, factory = ConfigDirectorFactory.class)
  ConfigDirectorClient configDirectorClient(TestClient testClient) {
    testClient.client().initialize();
    return testClient.client();
  }
}
