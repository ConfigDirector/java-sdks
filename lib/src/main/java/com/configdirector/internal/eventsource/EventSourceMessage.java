package com.configdirector.internal.eventsource;

import java.util.Objects;

/**
 * {@code type} and {@code id} may be null. Per the spec {@code id} is the last one seen on the
 * stream, which is not necessarily a field of this event.
 */
public record EventSourceMessage(String data, String type, String id) {

  public EventSourceMessage {
    Objects.requireNonNull(data, "data");
  }
}
