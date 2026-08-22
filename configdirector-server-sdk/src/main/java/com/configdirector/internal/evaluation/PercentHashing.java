package com.configdirector.internal.evaluation;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

final class PercentHashing {

  private static final long SEED = 0x397832987L;

  // The values a percentage can take: 0.0 through 99.9, in tenths.
  private static final int BUCKETS = 1_000;

  private PercentHashing() {}

  static double assignPercentage(String configId, String contextIdentifier) {
    byte[] value = (contextIdentifier + "-" + configId).getBytes(StandardCharsets.UTF_8);
    return Long.remainderUnsigned(RapidHash.hash(value, SEED), BUCKETS) / 10.0;
  }

  // For a caller there is no identifier to hash: the same spread of values, drawn rather than
  // derived. A UUID would be the same arbitrary answer at the cost of a cryptographic draw, which
  // every thread in the application takes one lock to make.
  static double arbitraryPercentage() {
    return ThreadLocalRandom.current().nextInt(BUCKETS) / 10.0;
  }
}
