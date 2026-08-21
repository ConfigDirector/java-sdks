package com.configdirector.internal.telemetry;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class Timestamps {

  // RFC 3339 in UTC, to the millisecond: the spelling the other SDKs send and the server parses.
  // Instant.toString would drop the fraction on a whole second, which not every parser accepts.
  private static final DateTimeFormatter RFC_3339 =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private Timestamps() {}

  static String format(Instant moment) {
    return RFC_3339.format(moment);
  }
}
