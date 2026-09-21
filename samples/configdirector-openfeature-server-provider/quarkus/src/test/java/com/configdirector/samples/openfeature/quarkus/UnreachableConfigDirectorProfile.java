package com.configdirector.samples.openfeature.quarkus;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class UnreachableConfigDirectorProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "configdirector.server-key", "fake-sample-key",
        "configdirector.mode", "polling",
        "configdirector.timeout", "1s");
  }
}
