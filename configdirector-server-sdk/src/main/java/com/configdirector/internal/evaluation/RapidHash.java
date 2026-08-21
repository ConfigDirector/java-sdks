package com.configdirector.internal.evaluation;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

// rapidhash v3.0, "fast" variant. Every SDK must produce the same 64-bit value for the same input
// or the same user would land in different percentage buckets depending on which SDK evaluated the
// config. Do not "clean up" the arithmetic here, every mask and shift is load-bearing.
// Results are unsigned values carried in a signed long; read them with the Long.*Unsigned helpers.
final class RapidHash {

  private static final VarHandle LONG_LE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
  private static final VarHandle INT_LE =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

  private static final long[] MIXING_CONSTANTS = {
    0x2D358DCCAA6C78A5L,
    0x8BB84B93962EACC9L,
    0x4B33A62ED433D4A3L,
    0x4D5A2DA51DE1AA47L,
    0xA0761D6478BD642FL,
    0xE7037ED1A0B428DBL,
    0x90ED1765281C388CL,
    0xAAAAAAAAAAAAAAAAL,
  };

  private RapidHash() {}

  static long hash(byte[] data, long seed) {
    int length = data.length;
    seed ^= mix(seed ^ MIXING_CONSTANTS[2], MIXING_CONSTANTS[1]);
    int i = length;

    long a;
    long b;
    int bi;

    if (length <= 16) {
      bi = length;
      if (length >= 4) {
        seed ^= length;
        if (length >= 8) {
          a = read64(data, 0);
          b = read64(data, length - 8);
        } else {
          a = read32(data, 0);
          b = read32(data, length - 4);
        }
      } else if (length > 0) {
        a = readSmall(data, length);
        b = data[length >> 1] & 0xFFL;
      } else {
        a = 0;
        b = 0;
      }
    } else {
      int p = 0;
      if (i > 112) {
        long see1 = seed;
        long see2 = seed;
        long see3 = seed;
        long see4 = seed;
        long see5 = seed;
        long see6 = seed;
        do {
          seed = mix(read64(data, p) ^ MIXING_CONSTANTS[0], read64(data, p + 8) ^ seed);
          see1 = mix(read64(data, p + 16) ^ MIXING_CONSTANTS[1], read64(data, p + 24) ^ see1);
          see2 = mix(read64(data, p + 32) ^ MIXING_CONSTANTS[2], read64(data, p + 40) ^ see2);
          see3 = mix(read64(data, p + 48) ^ MIXING_CONSTANTS[3], read64(data, p + 56) ^ see3);
          see4 = mix(read64(data, p + 64) ^ MIXING_CONSTANTS[4], read64(data, p + 72) ^ see4);
          see5 = mix(read64(data, p + 80) ^ MIXING_CONSTANTS[5], read64(data, p + 88) ^ see5);
          see6 = mix(read64(data, p + 96) ^ MIXING_CONSTANTS[6], read64(data, p + 104) ^ see6);
          p += 112;
          i -= 112;
        } while (i > 112);
        seed ^= see1;
        see2 ^= see3;
        see4 ^= see5;
        seed ^= see6;
        see2 ^= see4;
        seed ^= see2;
      }

      bi = i;
      if (i > 16) {
        seed = mix(read64(data, p) ^ MIXING_CONSTANTS[2], read64(data, p + 8) ^ seed);
        if (i > 32) {
          seed = mix(read64(data, p + 16) ^ MIXING_CONSTANTS[2], read64(data, p + 24) ^ seed);
          if (i > 48) {
            seed = mix(read64(data, p + 32) ^ MIXING_CONSTANTS[1], read64(data, p + 40) ^ seed);
            if (i > 64) {
              seed = mix(read64(data, p + 48) ^ MIXING_CONSTANTS[1], read64(data, p + 56) ^ seed);
              if (i > 80) {
                seed = mix(read64(data, p + 64) ^ MIXING_CONSTANTS[2], read64(data, p + 72) ^ seed);
                if (i > 96) {
                  seed = mix(read64(data, p + 80) ^ MIXING_CONSTANTS[1], read64(data, p + 88) ^ seed);
                }
              }
            }
          }
        }
      }

      a = read64(data, p + i - 16) ^ bi;
      b = read64(data, p + i - 8);
    }

    a ^= MIXING_CONSTANTS[1];
    b ^= seed;
    return epilogue(a, b, bi);
  }

  private static long mix(long a, long b) {
    return (a * b) ^ unsignedMultiplyHigh(a, b);
  }

  private static long epilogue(long a, long b, int i) {
    long low = a * b;
    long high = unsignedMultiplyHigh(a, b);
    long x = low ^ MIXING_CONSTANTS[7];
    long y = high ^ MIXING_CONSTANTS[1] ^ i;
    return (x * y) ^ unsignedMultiplyHigh(x, y);
  }

  // Math.unsignedMultiplyHigh would do this directly but arrived in Java 18.
  private static long unsignedMultiplyHigh(long x, long y) {
    return Math.multiplyHigh(x, y) + ((x >> 63) & y) + ((y >> 63) & x);
  }

  private static long read64(byte[] data, int offset) {
    return (long) LONG_LE.get(data, offset);
  }

  private static long read32(byte[] data, int offset) {
    return ((int) INT_LE.get(data, offset)) & 0xFFFFFFFFL;
  }

  private static long readSmall(byte[] data, int length) {
    long v = data[0] & 0xFFL;
    return (data[length - 1] & 0xFFL) | (((v << 5) & 0xFFL) << 40) | ((v >> 3) << 48);
  }
}
