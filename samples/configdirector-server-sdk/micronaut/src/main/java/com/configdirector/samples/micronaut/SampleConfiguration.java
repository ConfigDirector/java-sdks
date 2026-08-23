package com.configdirector.samples.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.time.Duration;

/**
 * Bound from {@code configdirector.*}, so a real deployment supplies these as environment
 * variables rather than editing code.
 *
 * <p>Micronaut derives the property name from each setter, and its environment property source
 * already understands the {@code SCREAMING_SNAKE_CASE} spelling of that name. So
 * {@code CONFIGDIRECTOR_SERVER_KEY} in the environment lands on {@link #setServerKey} with nothing
 * declared in application.properties beyond the default.
 */
@ConfigurationProperties("configdirector")
public class SampleConfiguration {

  /** Your server SDK key. A secret: supply it as an environment variable, never in source. */
  private String serverKey = "fake-sample-key";

  /** Only needed when routing through a proxy to reach ConfigDirector. */
  private String baseUrl;

  private String mode = "streaming";

  private Duration timeout = Duration.ofSeconds(3);

  public String getServerKey() {
    return serverKey;
  }

  public void setServerKey(String serverKey) {
    this.serverKey = serverKey;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }
}
