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

  public static Builder builder() {
    return new Builder();
  }

  public static ConnectionOptions defaults() {
    return DEFAULTS;
  }

  public ConnectionMode mode() {
    return mode;
  }

  public Duration pollingInterval() {
    return pollingInterval;
  }

  public Duration timeout() {
    return timeout;
  }

  /** May be null, in which case the SDK talks to the ConfigDirector service directly. */
  public String url() {
    return url;
  }

  public static final class Builder {

    private ConnectionMode mode = ConnectionMode.STREAMING;
    private Duration pollingInterval = Duration.ofSeconds(60);
    private Duration timeout = Duration.ofSeconds(3);
    private String url;

    private Builder() {}

    public Builder mode(ConnectionMode mode) {
      this.mode = Objects.requireNonNull(mode, "mode");
      return this;
    }

    /** Used only in polling mode. */
    public Builder pollingInterval(Duration pollingInterval) {
      this.pollingInterval = Objects.requireNonNull(pollingInterval, "pollingInterval");
      return this;
    }

    public Builder timeout(Duration timeout) {
      this.timeout = Objects.requireNonNull(timeout, "timeout");
      return this;
    }

    public Builder url(String url) {
      this.url = url;
      return this;
    }

    public ConnectionOptions build() {
      return new ConnectionOptions(this);
    }
  }
}
