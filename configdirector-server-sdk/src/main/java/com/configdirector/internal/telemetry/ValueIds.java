package com.configdirector.internal.telemetry;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

// Identifies a config value by a digest of its text, so that the same value reported by two SDKs
// is counted once. Every SDK has to spell these identically: the same bytes of digest, the same
// base62 alphabet, the same zero padding.
public final class ValueIds {

  // The number of base62 digits DIGEST_BYTES produce: ceil(128 / log2(62)).
  public static final int VALUE_ID_LENGTH = 22;

  private static final int DIGEST_BYTES = 16;
  private static final String BASE62 =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final BigInteger RADIX = BigInteger.valueOf(62);

  private ValueIds() {}

  public static String generate(String value) {
    byte[] digest = sha256().digest(value.getBytes(StandardCharsets.UTF_8));
    return toBase62(new BigInteger(1, Arrays.copyOf(digest, DIGEST_BYTES)));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      // Every Java runtime is required to ship SHA-256.
      throw new IllegalStateException("SHA-256 is unavailable on this runtime", impossible);
    }
  }

  // Hand-rolled because BigInteger.toString stops at radix 36, and because the encoding has to be
  // fixed-width and zero-padded rather than dropping leading zeros.
  private static String toBase62(BigInteger number) {
    StringBuilder digits = new StringBuilder(VALUE_ID_LENGTH);
    BigInteger remaining = number;
    while (remaining.signum() > 0) {
      BigInteger[] divided = remaining.divideAndRemainder(RADIX);
      digits.append(BASE62.charAt(divided[1].intValue()));
      remaining = divided[0];
    }
    while (digits.length() < VALUE_ID_LENGTH) {
      digits.append('0');
    }
    return digits.reverse().toString();
  }
}
