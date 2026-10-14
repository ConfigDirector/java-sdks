package com.configdirector.internal.evaluation;

import com.configdirector.internal.ContextKinds;
import java.nio.charset.StandardCharsets;

final class PercentHashing {

  private static final long SEED = 0x397832987L;

  // The values a percentage can take: 0.0 through 99.9, in tenths.
  private static final int BUCKETS = 1_000;

  static final double PERCENTAGE_WITHOUT_IDENTIFIER = 0.0;

  private PercentHashing() {}

  static double assignPercentage(String configId, String contextIdentifier, String contextKind) {
    byte[] value = hashInput(configId, contextIdentifier, contextKind).getBytes(StandardCharsets.UTF_8);
    return Long.remainderUnsigned(RapidHash.hash(value, SEED), BUCKETS) / 10.0;
  }

  private static String hashInput(String configId, String contextIdentifier, String contextKind) {
    String userInput = contextIdentifier + "-" + configId;
    return ContextKinds.DEFAULT_KIND.equals(contextKind) ? userInput : userInput + ":" + contextKind;
  }
}
