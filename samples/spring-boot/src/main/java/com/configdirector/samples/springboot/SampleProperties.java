package com.configdirector.samples.springboot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code configdirector.*} in application.properties, so a real deployment supplies
 * them as environment variables rather than editing code.
 */
@ConfigurationProperties(prefix = "configdirector")
public class SampleProperties {

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
