package com.configdirector.samples.openfeature.quarkus;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Keeps the application's own producer out of the test container. {@code @Startup} on it would
 * otherwise register the ConfigDirector provider at startup even though injection points receive
 * the test producer's beans.
 */
public class InMemoryProviderProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("quarkus.arc.exclude-types", OpenFeatureProducer.class.getName());
  }
}
