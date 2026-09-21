package com.configdirector.samples.openfeature.quarkus;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "configdirector")
public interface SampleConfig {

  @WithDefault("fake-sample-key")
  String serverKey();

  Optional<String> baseUrl();

  @WithDefault("streaming")
  String mode();

  @WithDefault("3s")
  Duration timeout();

  @WithDefault("INFO")
  String logLevel();
}
