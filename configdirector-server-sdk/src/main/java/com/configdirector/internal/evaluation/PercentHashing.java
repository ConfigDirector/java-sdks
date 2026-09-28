package com.configdirector.internal.evaluation;

import java.nio.charset.StandardCharsets;

final class PercentHashing {

  private static final long SEED = 0x397832987L;

  // The values a percentage can take: 0.0 through 99.9, in tenths.
  private static final int BUCKETS = 1_000;

  static final double PERCENTAGE_WITHOUT_IDENTIFIER = 0.0;

  private PercentHashing() {}

  static double assignPercentage(String configId, String contextIdentifier) {
    byte[] value = (contextIdentifier + "-" + configId).getBytes(StandardCharsets.UTF_8);
    return Long.remainderUnsigned(RapidHash.hash(value, SEED), BUCKETS) / 10.0;
  }
}
