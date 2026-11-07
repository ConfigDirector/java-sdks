package com.configdirector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a tracked event carries besides its name: a number it measures and details about it.
 *
 * <pre>{@code
 * client.track(
 *     "checkout-completed",
 *     context,
 *     TrackOptions.builder().value(49.9).details(Map.of("plan", "pro")).build());
 * }</pre>
 */
public final class TrackOptions {

  private final Number value;
  private final Map<String, Object> details;

  private TrackOptions(Builder builder) {
    this.value = builder.value;
    this.details = builder.details;
  }

  /**
   * Starts options carrying neither a value nor details.
   *
   * @return a builder to add them to
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The number the event measures.
   *
   * @return the value, or null when none was set
   */
  public Number value() {
    return value;
  }

  /**
   * The details of the event.
   *
   * @return the details, unmodifiable, or null when none were set
   */
  public Map<String, Object> details() {
    return details;
  }

  /** Collects a tracked event's value and details. Every setter returns this, so calls chain. */
  public static final class Builder {

    private Number value;
    private Map<String, Object> details;

    private Builder() {}

    /**
     * A number the event measures, such as an amount of money, a count or a duration.
     * ConfigDirector stores it as an exact decimal, and an {@link Integer}, a {@link Long}, a
     * {@link java.math.BigInteger} or a {@link java.math.BigDecimal} is sent with every digit. A
     * value that is not a finite number is left out and the event is still reported. ConfigDirector
     * stores an event without its value when the value is written in more than 64 characters or
     * has a decimal exponent beyond 400.
     *
     * @param value the number, or null for none
     * @return this builder, so calls chain
     */
    public Builder value(Number value) {
      this.value = value;
      return this;
    }

    /**
     * Details of the event, such as the plan a customer chose. Values are {@link String}, {@link
     * Number}, {@link Boolean}, {@link Map} with {@link String} keys, {@link java.util.Collection},
     * or null. An {@link java.time.Instant}, {@link java.util.Date}, {@link
     * java.time.OffsetDateTime} or {@link java.time.ZonedDateTime}, at any depth, is sent as ISO
     * 8601 text in UTC, such as {@code 2026-12-01T00:00:00Z}. Details with more than 20 top-level
     * keys, larger than 1 KB as JSON, or holding any other type of value, such as a {@link
     * java.time.LocalDate}, are left out and the event is still reported.
     *
     * @param details the details, or null for none
     * @return this builder, so calls chain
     */
    public Builder details(Map<String, Object> details) {
      this.details =
          details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options as configured
     */
    public TrackOptions build() {
      return new TrackOptions(this);
    }
  }
}
