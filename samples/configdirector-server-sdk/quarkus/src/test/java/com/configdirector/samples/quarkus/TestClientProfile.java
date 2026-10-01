package com.configdirector.samples.quarkus;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Keeps the application's own producer out of the test container. {@code @Startup} on it would
 * otherwise build and initialize the real client at startup even though injection points receive
 * the test producer's client.
 */
public class TestClientProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("quarkus.arc.exclude-types", ConfigDirectorProducer.class.getName());
  }
}
