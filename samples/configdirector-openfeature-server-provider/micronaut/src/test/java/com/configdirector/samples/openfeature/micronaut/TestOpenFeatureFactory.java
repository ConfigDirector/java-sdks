package com.configdirector.samples.openfeature.micronaut;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;
import java.util.HashMap;

/**
 * Swaps the application's OpenFeature registration for the OpenFeature SDK's own in-memory
 * provider. {@code @Replaces} removes the application's own bean, which is therefore never built
 * and never registers the ConfigDirector provider.
 */
@Factory
public class TestOpenFeatureFactory {

  @Singleton
  InMemoryProvider inMemoryProvider() {
    return new InMemoryProvider(new HashMap<>());
  }

  @Singleton
  @Bean(preDestroy = "shutdown")
  @Replaces(bean = OpenFeatureAPI.class, factory = OpenFeatureFactory.class)
  OpenFeatureAPI openFeatureApi(InMemoryProvider provider) {
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(provider);
    return api;
  }
}
