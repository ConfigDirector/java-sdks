package com.configdirector.samples.quarkus;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** One-time mode with a short timeout, so the suite does not wait on a server that is not there. */
public class UnreachableConfigDirectorProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "configdirector.server-key", "fake-sample-key",
        "configdirector.mode", "one-time",
        "configdirector.timeout", "1s");
  }
}
