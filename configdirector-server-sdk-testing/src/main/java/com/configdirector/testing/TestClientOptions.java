package com.configdirector.testing;

import com.configdirector.ConfigDirector;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings for a test client, handed to the lambda {@link ConfigDirectorTesting#createTestClient(java.util.Map,
 * java.util.function.Consumer)} takes. Every setter returns this, so calls chain.
 */
public final class TestClientOptions {

  private Duration timeout;
  private Logger logger = LoggerFactory.getLogger(ConfigDirector.LOGGER_NAME);

  TestClientOptions() {}

  /**
   * The connection timeout of the SDK client, which bounds how long a held {@code initialize} waits.
   * Defaults to the SDK's production timeout.
   *
   * @param timeout the time a connection attempt may take, positive
   * @return these options, so calls chain
   */
  public TestClientOptions timeout(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    return this;
  }

  /**
   * Where the SDK client writes. Defaults to the SLF4J logger named {@value
   * ConfigDirector#LOGGER_NAME}, as for a production client.
   *
   * @param logger the logger to write to
   * @return these options, so calls chain
   */
  public TestClientOptions logger(Logger logger) {
    this.logger = Objects.requireNonNull(logger, "logger");
    return this;
  }

  Duration timeout() {
    return timeout;
  }

  Logger logger() {
    return logger;
  }
}
