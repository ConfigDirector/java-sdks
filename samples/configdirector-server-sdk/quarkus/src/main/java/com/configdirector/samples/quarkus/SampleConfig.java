package com.configdirector.samples.quarkus;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/**
 * Bound from {@code configdirector.*}, so a real deployment supplies these as environment
 * variables rather than editing code.
 *
 * <p>An interface rather than a mutable bean: Quarkus generates the implementation at build time
 * and hands out an immutable instance, so there are no setters for anything to call after
 * startup. MicroProfile Config's environment mapping means {@code CONFIGDIRECTOR_SERVER_KEY} in
 * the environment -- or in {@code .env} -- lands on {@link #serverKey()} with nothing declared in
 * application.properties beyond the default.
 *
 * <p>Every key under the prefix has to appear here. Quarkus validates a mapped prefix and fails
 * startup on a property it cannot place, which is why {@link #logLevel()} is on this interface
 * even though only application.properties reads it.
 */
@ConfigMapping(prefix = "configdirector")
public interface SampleConfig {

  /** Your server SDK key. A secret: supply it as an environment variable, never in source. */
  @WithDefault("fake-sample-key")
  String serverKey();

  /** Only needed when routing through a proxy to reach ConfigDirector. */
  Optional<String> baseUrl();

  @WithDefault("streaming")
  String mode();

  @WithDefault("3s")
  Duration timeout();

  @WithDefault("INFO")
  String logLevel();
}
