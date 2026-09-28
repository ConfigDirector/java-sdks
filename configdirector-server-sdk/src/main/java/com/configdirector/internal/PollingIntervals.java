package com.configdirector.internal;

import java.time.Duration;

public final class PollingIntervals {

  public static final Duration DEFAULT = Duration.ofMinutes(5);

  public static final Duration MINIMUM = Duration.ofSeconds(60);

  private PollingIntervals() {}
}
