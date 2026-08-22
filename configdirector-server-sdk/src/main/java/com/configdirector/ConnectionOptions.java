package com.configdirector;

import java.time.Duration;
import java.util.Objects;

/**
 * How the client connects to ConfigDirector.
 *
 * <p>{@code timeout} applies to initialization. While streaming, initialization may still succeed
 * after it elapses as long as nothing unrecoverable happened; without streaming, a timed-out
 * initialization is not retried. {@code url} is only needed when routing through a proxy.
 */
public final class ConnectionOptions {

  private static final ConnectionOptions DEFAULTS = builder().build();

  private final ConnectionMode mode;
  private final Duration pollingInterval;
  private final Duration timeout;
  private final String url;

  private ConnectionOptions(Builder builder) {
    this.mode = builder.mode;
    this.pollingInterval = builder.pollingInterval;
    this.timeout = builder.timeout;
    this.url = builder.url;
  }

  /**
   * Starts from the defaults.
   *
   * @return a builder to adjust
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The settings a client uses when none are supplied.
   *
   * @return streaming, a 60 second polling interval, and a 3 second initialization timeout
   */
  public static ConnectionOptions defaults() {
    return DEFAULTS;
  }

  /**
   * How the client keeps its config state current.
   *
   * @return the connection mode
   */
  public ConnectionMode mode() {
    return mode;
  }

  /**
   * How long the client waits between polls.
   *
   * @return the polling interval
   */
  public Duration pollingInterval() {
    return pollingInterval;
  }

  /**
   * How long initialization waits for the first config state.
   *
   * @return the initialization timeout
   */
  public Duration timeout() {
    return timeout;
  }

  /**
   * Where the client connects.
   *
   * @return the base URL, or null to talk to the ConfigDirector service directly
   */
  public String url() {
    return url;
  }

  /** Collects connection settings. Every setter returns this, so calls chain. */
  public static final class Builder {

    private ConnectionMode mode = ConnectionMode.STREAMING;
    private Duration pollingInterval = Duration.ofSeconds(60);
    private Duration timeout = Duration.ofSeconds(3);
    private String url;

    private Builder() {}

    /**
     * How the client keeps its config state current. Defaults to
     * {@link ConnectionMode#STREAMING}.
     *
     * @param mode the mode to connect in
     * @return this builder, so calls chain
     */
    public Builder mode(ConnectionMode mode) {
      this.mode = Objects.requireNonNull(mode, "mode");
      return this;
    }

    /**
     * How long to wait between polls. Used only in polling mode; defaults to 60 seconds.
     *
     * @param pollingInterval the interval to wait
     * @return this builder, so calls chain
     */
    public Builder pollingInterval(Duration pollingInterval) {
      this.pollingInterval = Objects.requireNonNull(pollingInterval, "pollingInterval");
      return this;
    }

    /**
     * How long initialization waits for the first config state. Defaults to 3 seconds.
     *
     * @param timeout the time to wait
     * @return this builder, so calls chain
     */
    public Builder timeout(Duration timeout) {
      this.timeout = Objects.requireNonNull(timeout, "timeout");
      return this;
    }

    /**
     * The base URL to connect to. Only needed when routing through a proxy.
     *
     * @param url an absolute URL naming a host, or null for the ConfigDirector service
     * @return this builder, so calls chain
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Builds the settings.
     *
     * @return the settings as configured
     */
    public ConnectionOptions build() {
      return new ConnectionOptions(this);
    }
  }
}
