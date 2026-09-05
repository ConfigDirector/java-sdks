package com.configdirector;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * How the client connects to ConfigDirector.
 *
 * <p>{@code timeout} applies to initialization. While streaming, initialization may still succeed
 * after it elapses as long as nothing unrecoverable happened; without streaming, a timed-out
 * initialization is not retried. {@code url} is only needed when routing through a proxy.
 *
 * <p>Settings are checked when they are built, so an unusable one is reported where it was written
 * rather than as a client that quietly never updates.
 */
public final class ConnectionOptions {

  // The longest timeout OkHttp accepts, which is what the timeout is ultimately handed to. Past
  // this it rejects the request outright, and initialization reports a client that never becomes
  // ready rather than one that waited too long.
  private static final Duration LONGEST_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

  private static final Duration SHORTEST_INTERVAL = Duration.ofSeconds(60);

  // The longest interval the polling thread can wait out, since it waits in nanoseconds.
  private static final Duration LONGEST_INTERVAL = Duration.ofNanos(Long.MAX_VALUE);

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
   * @return streaming, a 5 minute polling interval, and a 3 second initialization timeout
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
    private Duration pollingInterval = Duration.ofMinutes(5);
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
     * How long to wait between polls. Used only in polling mode; defaults to 5 minutes. Must be at
     * least 60 seconds.
     *
     * @param pollingInterval the interval to wait, at least 60 seconds
     * @return this builder, so calls chain
     */
    public Builder pollingInterval(Duration pollingInterval) {
      this.pollingInterval = Objects.requireNonNull(pollingInterval, "pollingInterval");
      return this;
    }

    /**
     * How long initialization waits for the first config state. Defaults to 3 seconds. Must be
     * positive.
     *
     * @param timeout the time to wait, positive
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
     * @throws ConfigDirectorValidationException if the polling interval is shorter than 60 seconds,
     *     the timeout is not positive, either is longer than can be waited on, or the URL is not
     *     absolute or names no host
     */
    public ConnectionOptions build() {
      requireAtLeast(pollingInterval, SHORTEST_INTERVAL, "pollingInterval");
      requirePositive(timeout, "timeout");
      requireAtMost(pollingInterval, LONGEST_INTERVAL, "pollingInterval", "the SDK can wait for");
      requireAtMost(timeout, LONGEST_TIMEOUT, "timeout", "the HTTP client accepts");
      requireUsableUrl(url);
      return new ConnectionOptions(this);
    }

    private static void requirePositive(Duration value, String name) {
      if (value.isNegative() || value.isZero()) {
        throw new ConfigDirectorValidationException(
            "Invalid " + name + " '" + value + "'. It must be a positive duration.");
      }
    }

    private static void requireAtLeast(Duration value, Duration floor, String name) {
      if (value.compareTo(floor) < 0) {
        throw new ConfigDirectorValidationException(
            "Invalid "
                + name
                + " '"
                + value
                + "'. It must be at least "
                + floor.toSeconds()
                + " seconds.");
      }
    }

    private static void requireAtMost(Duration value, Duration ceiling, String name, String why) {
      if (value.compareTo(ceiling) > 0) {
        throw new ConfigDirectorValidationException(
            "Invalid "
                + name
                + " '"
                + value
                + "'. It must be no longer than "
                + ceiling.toMillis()
                + "ms (about "
                + ceiling.toDays()
                + " days), which is the longest "
                + why
                + ".");
      }
    }

    // Blank stands for absent, the same as null: both mean the ConfigDirector service.
    private static void requireUsableUrl(String url) {
      if (url == null || url.isBlank()) {
        return;
      }
      URI parsed;
      try {
        parsed = URI.create(url.strip());
      } catch (IllegalArgumentException malformed) {
        throw new ConfigDirectorValidationException(
            "Invalid connection URL '" + url + "'. " + malformed.getMessage());
      }
      if (!parsed.isAbsolute() || parsed.getHost() == null) {
        throw new ConfigDirectorValidationException(
            "Invalid connection URL '" + url + "'. It must be absolute and name a host.");
      }
    }
  }
}
