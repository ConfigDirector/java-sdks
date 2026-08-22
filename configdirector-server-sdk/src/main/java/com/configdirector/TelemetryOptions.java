package com.configdirector;

import java.time.Duration;
import java.util.Objects;

/**
 * Telemetry tuning.
 *
 * <p>It is unlikely these need adjusting. If your application performs a large number of
 * evaluations per second, they trade memory footprint against how often telemetry requests are
 * made.
 *
 * <p>Keep in mind that ConfigDirector relies on these telemetry events to power insights and
 * features related to the configs being used.
 */
public final class TelemetryOptions {

  /** The default queue limit, split between evaluations and the contexts they were made against. */
  public static final int DEFAULT_EVENT_QUEUE_LIMIT = 5_000;

  /** The smallest queue limit accepted. */
  public static final int MIN_EVENT_QUEUE_LIMIT = 100;

  /** The largest queue limit accepted. */
  public static final int MAX_EVENT_QUEUE_LIMIT = 100_000;

  /** How often events are reported unless configured otherwise. */
  public static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(30);

  private static final TelemetryOptions DEFAULTS = builder().build();

  private final int eventQueueLimit;
  private final Duration flushInterval;

  private TelemetryOptions(Builder builder) {
    this.eventQueueLimit = builder.eventQueueLimit;
    this.flushInterval = builder.flushInterval;
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
   * @return a queue limit of {@value #DEFAULT_EVENT_QUEUE_LIMIT} and a 30 second flush interval
   */
  public static TelemetryOptions defaults() {
    return DEFAULTS;
  }

  /**
   * How many events are held between flushes.
   *
   * @return the queue limit
   */
  public int eventQueueLimit() {
    return eventQueueLimit;
  }

  /**
   * How often events are reported.
   *
   * @return the flush interval
   */
  public Duration flushInterval() {
    return flushInterval;
  }

  /** Collects telemetry settings. Every setter returns this, so calls chain. */
  public static final class Builder {

    private int eventQueueLimit = DEFAULT_EVENT_QUEUE_LIMIT;
    private Duration flushInterval = DEFAULT_FLUSH_INTERVAL;

    private Builder() {}

    /**
     * The size limit of the telemetry event queues. When the limit is reached before events are
     * flushed to the network, the oldest are dropped.
     *
     * <p>ConfigDirector keeps a count of dropped events. If more than 50% of total events are
     * dropped, a notification alert is raised in the dashboard.
     *
     * <p>Between {@value #MIN_EVENT_QUEUE_LIMIT} and {@value #MAX_EVENT_QUEUE_LIMIT}. Defaults to
     * {@value #DEFAULT_EVENT_QUEUE_LIMIT}.
     *
     * @param eventQueueLimit how many events to hold between flushes
     * @return this builder, so calls chain
     */
    public Builder eventQueueLimit(int eventQueueLimit) {
      this.eventQueueLimit = eventQueueLimit;
      return this;
    }

    /**
     * How often events are flushed and sent over the network. Decrease it if your application
     * consistently captures a large number of events in short bursts, to keep the event queue
     * small. Defaults to 30 seconds.
     *
     * @param flushInterval how often to report
     * @return this builder, so calls chain
     */
    public Builder flushInterval(Duration flushInterval) {
      this.flushInterval = Objects.requireNonNull(flushInterval, "flushInterval");
      return this;
    }

    /**
     * Validates the settings and builds them.
     *
     * @return the settings as configured
     * @throws ConfigDirectorValidationException if a setting is out of range
     */
    public TelemetryOptions build() {
      if (eventQueueLimit < MIN_EVENT_QUEUE_LIMIT || eventQueueLimit > MAX_EVENT_QUEUE_LIMIT) {
        throw new ConfigDirectorValidationException(
            "Invalid telemetry event queue limit '"
                + eventQueueLimit
                + "'. It must be between "
                + MIN_EVENT_QUEUE_LIMIT
                + " and "
                + MAX_EVENT_QUEUE_LIMIT
                + ".");
      }
      if (flushInterval.isNegative() || flushInterval.isZero()) {
        throw new ConfigDirectorValidationException(
            "Invalid telemetry flush interval '" + flushInterval + "'. It must be a positive duration.");
      }
      return new TelemetryOptions(this);
    }
  }
}
