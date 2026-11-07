package com.configdirector.testing;

import com.configdirector.Context;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An event the code under test tracked, as a test client recorded it.
 *
 * @param name the name the event was tracked with
 * @param context the context the event was tracked for, or null when there was none or its kind was
 *     invalid
 * @param value the value the event was tracked with, or null when there was none or it was not a
 *     finite number. A {@code Float} is recorded as the {@code Double} with the same digits
 * @param details the details the event was tracked with, an unmodifiable copy, or null when there
 *     were none or they were outside the limits. A date is recorded as the ISO 8601 text the SDK
 *     sends
 */
public record TrackedEvent(String name, Context context, Number value, Map<String, Object> details) {

  /** Copies the details, so a later change to the caller's map cannot alter the event. */
  public TrackedEvent {
    details = details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }
}
