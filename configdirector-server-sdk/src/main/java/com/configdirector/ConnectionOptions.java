package com.configdirector;

import com.configdirector.internal.PollingIntervals;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How the client connects to ConfigDirector.
 *
 * <p>{@code timeout} applies to initialization. While streaming, initialization may still succeed
 * after it elapses as long as nothing unrecoverable happened; without streaming, a timed-out
 * initialization is not retried. {@code url} is only needed when routing through a proxy.
 *
 * <p>The timeout and URL are checked when the settings are built, so an unusable one is reported
 * where it was written rather than as a client that quietly never updates. The polling interval is
 * kept as configured: a value below the minimum is raised to the minimum by the client, which logs
 * a warning. In {@link ConnectionMode#OFFLINE} mode the client never connects, so none of the
 * three is checked or used; the file or the values are checked when the client initializes.
 */
public final class ConnectionOptions {

  // The longest timeout OkHttp accepts, which is what the timeout is ultimately handed to. Past
  // this it rejects the request outright, and initialization reports a client that never becomes
  // ready rather than one that waited too long.
  private static final Duration LONGEST_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

  // The longest interval the polling thread can wait out, since it waits in nanoseconds.
  private static final Duration LONGEST_INTERVAL = Duration.ofNanos(Long.MAX_VALUE);

  private static final ConnectionOptions DEFAULTS = builder().build();

  private final ConnectionMode mode;
  private final Duration pollingInterval;
  private final Duration timeout;
  private final String url;
  private final Path file;
  private final Map<String, Object> values;

  private ConnectionOptions(Builder builder) {
    this.mode = builder.mode;
    this.pollingInterval = builder.pollingInterval;
    this.timeout = builder.timeout;
    this.url = builder.url;
    this.file = builder.file;
    this.values =
        builder.values == null
            ? null
            : Collections.unmodifiableMap(new LinkedHashMap<>(builder.values));
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
   * How long the client waits between polls, as configured. Defaults to 5 minutes. A value below
   * the 60 second minimum is raised to the minimum and a warning is logged when a polling client is
   * built.
   *
   * @return the polling interval as configured
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

  /**
   * The JSON file an offline client serves its values from.
   *
   * @return the file as given, or null when none was given
   */
  public Path file() {
    return file;
  }

  /**
   * The values an offline client serves, keyed by config key.
   *
   * @return an unmodifiable copy of the values as given, or null when none were given
   */
  public Map<String, Object> values() {
    return values;
  }

  /** Collects connection settings. Every setter returns this, so calls chain. */
  public static final class Builder {

    private ConnectionMode mode = ConnectionMode.STREAMING;
    private Duration pollingInterval = PollingIntervals.DEFAULT;
    private Duration timeout = Duration.ofSeconds(3);
    private String url;
    private Path file;
    private Map<String, ?> values;

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
     * How long to wait between polls. Used only in polling mode; defaults to 5 minutes, and the
     * minimum is 60 seconds. A value below the minimum is raised to the minimum and a warning is
     * logged.
     *
     * @param pollingInterval the interval to wait
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
     * A JSON file holding one value per config key, for an offline client to serve. Used only in
     * {@link ConnectionMode#OFFLINE} mode, and read when the client initializes. A relative path
     * resolves against the working directory. Each value takes its config type from the JSON:
     * {@code true} or {@code false} is a boolean, a whole number an integer, any other number a
     * float, a string a string, and an object or array a JSON config.
     *
     * @param file the file to read, or null for none
     * @return this builder, so calls chain
     */
    public Builder file(Path file) {
      this.file = file;
      return this;
    }

    /**
     * The values for an offline client to serve, keyed by config key. Used only in {@link
     * ConnectionMode#OFFLINE} mode. The map is copied when the settings are built, and a Map or
     * List inside it is read when the client initializes. A Boolean is a boolean config, an
     * Integer or a Long an integer, a Float or a Double a float, a String a string, and a Map with
     * String keys or a List a JSON config.
     *
     * @param values the values to serve, or null for none
     * @return this builder, so calls chain
     */
    public Builder values(Map<String, ?> values) {
      this.values = values;
      return this;
    }

    /**
     * Builds the settings.
     *
     * @return the settings as configured
     * @throws ConfigDirectorValidationException if the timeout is not positive, the timeout or the
     *     polling interval is longer than can be waited on, or the URL is not absolute or names no
     *     host. None of these is checked in {@link ConnectionMode#OFFLINE} mode, which ignores them
     */
    public ConnectionOptions build() {
      if (mode != ConnectionMode.OFFLINE) {
        requirePositive(timeout, "timeout");
        requireAtMost(pollingInterval, LONGEST_INTERVAL, "pollingInterval", "the SDK can wait for");
        requireAtMost(timeout, LONGEST_TIMEOUT, "timeout", "the HTTP client accepts");
        requireUsableUrl(url);
      }
      return new ConnectionOptions(this);
    }

    private static void requirePositive(Duration value, String name) {
      if (value.isNegative() || value.isZero()) {
        throw new ConfigDirectorValidationException(
            "Invalid " + name + " '" + value + "'. It must be a positive duration.");
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
