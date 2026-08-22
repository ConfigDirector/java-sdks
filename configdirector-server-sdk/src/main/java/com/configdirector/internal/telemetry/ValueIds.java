package com.configdirector.internal.telemetry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// Identifies a config value by a digest of its text, so that the same value reported by two SDKs
// is counted once. Every SDK has to spell these identically: the same bytes of digest, the same
// base62 alphabet, the same zero padding.
public final class ValueIds {

  // The number of base62 digits the leading 128 bits of the digest produce: ceil(128 / log2(62)).
  public static final int VALUE_ID_LENGTH = 22;

  private static final String BASE62 =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final long RADIX = 62;

  private ValueIds() {}

  public static String generate(String value) {
    byte[] digest = sha256().digest(value.getBytes(StandardCharsets.UTF_8));
    return toBase62(bigEndianLong(digest, 0), bigEndianLong(digest, Long.BYTES));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      // Every Java runtime is required to ship SHA-256.
      throw new IllegalStateException("SHA-256 is unavailable on this runtime", impossible);
    }
  }

  // The leading DIGEST_BYTES of the digest, read as one unsigned big-endian number split across
  // two longs.
  private static long bigEndianLong(byte[] digest, int offset) {
    long value = 0;
    for (int index = 0; index < Long.BYTES; index++) {
      value = (value << Byte.SIZE) | (digest[offset + index] & 0xFFL);
    }
    return value;
  }

  // Hand-rolled because BigInteger.toString stops at radix 36, and because the encoding has to be
  // fixed-width and zero-padded rather than dropping leading zeros. Done in longs rather than
  // BigInteger: 62^22 covers 128 bits, so the width is known up front and no digit needs an
  // allocation. Every value under CONFIG_VALUE_MAX_LENGTH that misses a server-sent ID is hashed
  // here on the caller's thread.
  private static String toBase62(long high, long low) {
    char[] digits = new char[VALUE_ID_LENGTH];
    long remainingHigh = high;
    long remainingLow = low;

    for (int index = VALUE_ID_LENGTH - 1; index >= 0; index--) {
      // Long division by RADIX over 128 bits, a 32-bit limb at a time. A carry is under RADIX, so
      // shifting it up by 32 and adding a limb stays well inside a signed long.
      long quotientHigh = Long.divideUnsigned(remainingHigh, RADIX);
      long carry = Long.remainderUnsigned(remainingHigh, RADIX);

      long upper = (carry << Integer.SIZE) | (remainingLow >>> Integer.SIZE);
      long quotientUpper = upper / RADIX;
      carry = upper % RADIX;

      long lower = (carry << Integer.SIZE) | (remainingLow & 0xFFFFFFFFL);
      long quotientLower = lower / RADIX;
      carry = lower % RADIX;

      digits[index] = BASE62.charAt((int) carry);
      remainingHigh = quotientHigh;
      remainingLow = (quotientUpper << Integer.SIZE) | quotientLower;
    }
    return new String(digits);
  }
}
