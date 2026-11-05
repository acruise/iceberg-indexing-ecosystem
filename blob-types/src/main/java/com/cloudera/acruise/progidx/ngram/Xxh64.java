package com.cloudera.acruise.progidx.ngram;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** XXH64, as specified by the xxHash project, so that any implementation can reproduce an n-gram filter's bits. */
public final class Xxh64 {
  private static final long P1 = 0x9E3779B185EBCA87L;
  private static final long P2 = 0xC2B2AE3D27D4EB4FL;
  private static final long P3 = 0x165667B19E3779F9L;
  private static final long P4 = 0x85EBCA77C2B2AE63L;
  private static final long P5 = 0x27D4EB2F165667C5L;
  private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
  private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

  private Xxh64() {}

  public static long hash(byte[] b, long seed) {
    return hash(b, 0, b.length, seed);
  }

  public static long hash(byte[] b, int off, int len, long seed) {
    int p = off;
    int end = off + len;
    long h;
    if (len >= 32) {
      long v1 = seed + P1 + P2;
      long v2 = seed + P2;
      long v3 = seed;
      long v4 = seed - P1;
      do {
        v1 = round(v1, (long) LONG.get(b, p));
        v2 = round(v2, (long) LONG.get(b, p + 8));
        v3 = round(v3, (long) LONG.get(b, p + 16));
        v4 = round(v4, (long) LONG.get(b, p + 24));
        p += 32;
      } while (p <= end - 32);
      h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
      h = mergeRound(h, v1);
      h = mergeRound(h, v2);
      h = mergeRound(h, v3);
      h = mergeRound(h, v4);
    } else {
      h = seed + P5;
    }
    h += len;
    while (p <= end - 8) {
      h ^= round(0, (long) LONG.get(b, p));
      h = Long.rotateLeft(h, 27) * P1 + P4;
      p += 8;
    }
    if (p <= end - 4) {
      h ^= ((int) INT.get(b, p) & 0xFFFFFFFFL) * P1;
      h = Long.rotateLeft(h, 23) * P2 + P3;
      p += 4;
    }
    while (p < end) {
      h ^= (b[p] & 0xFFL) * P5;
      h = Long.rotateLeft(h, 11) * P1;
      p++;
    }
    h ^= h >>> 33;
    h *= P2;
    h ^= h >>> 29;
    h *= P3;
    h ^= h >>> 32;
    return h;
  }

  private static long round(long acc, long input) {
    acc += input * P2;
    acc = Long.rotateLeft(acc, 31);
    return acc * P1;
  }

  private static long mergeRound(long acc, long val) {
    acc ^= round(0, val);
    return acc * P1 + P4;
  }
}
