package com.configdirector.samples.quarkus;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.testing.ConfigDirectorTesting;
import com.configdirector.testing.TestClient;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Swaps the application's client for the client of a test client from the SDK's testing artifact:
 * the real client over an in-memory connection the tests control. {@code @Mock} makes this
 * producer the one injection points receive instead of the application's own.
 */
@ApplicationScoped
public class TestConfigDirectorProducer {

  @Produces
  @Singleton
  public TestClient testClient() {
    return ConfigDirectorTesting.createTestClient();
  }

  public void close(@Disposes TestClient testClient) {
    testClient.close();
  }

  /** Initializes the client where the application's producer would have. */
  @Produces
  @Singleton
  @Mock
  public ConfigDirectorClient configDirectorClient(TestClient testClient) {
    testClient.client().initialize();
    return testClient.client();
  }
}
