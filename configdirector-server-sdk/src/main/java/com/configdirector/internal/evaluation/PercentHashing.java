package com.configdirector.internal.evaluation;

import java.nio.charset.StandardCharsets;

final class PercentHashing {

  private static final long SEED = 0x397832987L;

  private PercentHashing() {}

  static double assignPercentage(String configId, String contextIdentifier) {
    byte[] value = (contextIdentifier + "-" + configId).getBytes(StandardCharsets.UTF_8);
    return Long.remainderUnsigned(RapidHash.hash(value, SEED), 1_000) / 10.0;
  }
}
