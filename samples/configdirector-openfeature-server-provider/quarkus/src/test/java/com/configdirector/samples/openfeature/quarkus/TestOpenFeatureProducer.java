package com.configdirector.samples.openfeature.quarkus;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.HashMap;

/**
 * Swaps the application's OpenFeature registration for the OpenFeature SDK's own in-memory
 * provider. InMemoryProviderProfile excludes the application's producer class, so the {@code
 * Client} it produced comes from here as well.
 */
@ApplicationScoped
public class TestOpenFeatureProducer {

  @Produces
  @Singleton
  public InMemoryProvider inMemoryProvider() {
    return new InMemoryProvider(new HashMap<>());
  }

  @Produces
  @Singleton
  @Mock
  public OpenFeatureAPI openFeatureApi(InMemoryProvider provider) {
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(provider);
    return api;
  }

  @Produces
  @Singleton
  public Client openFeatureClient(OpenFeatureAPI api) {
    return api.getClient();
  }

  public void shutdown(@Disposes OpenFeatureAPI api) {
    api.shutdown();
  }
}
