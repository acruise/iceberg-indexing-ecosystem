package com.cloudera.acruise.progidx.ngram;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A Bloom filter over the character n-grams of one string column: a filter for equality, prefix and substring
 * predicates with no false negatives (proposal Section 7.6).
 *
 * <p>Each value is normalized, framed with start and end markers (the noncharacters U+FDD0 and U+FDD1), and split into
 * overlapping grams of {@code gram-length} code points; a framed value shorter than that is one gram. Grams are hashed
 * as UTF-8 with XXH64 under the configured seed, and set {@code hash-count} bits by double hashing.
 *
 * <p>Predicates come in two semantics. {@link Semantics#RAW} predicates are about the stored values themselves, and are
 * answered conservatively under any normalization. {@link Semantics#NORMALIZED} predicates are about normalized values,
 * which under {@link TextNormalization#NFKC_CASEFOLD} is Unicode caseless matching. If this process cannot evaluate the
 * filter's configuration (see {@link NgramBloomConfig#evaluableHere}), every probe answers true.
 */
public final class NgramBloom {
  public static final byte VERSION = 1;
  static final int START = 0xFDD0;
  static final int END = 0xFDD1;
  private static final int HEADER_BYTES = 6;

  public enum Semantics {
    RAW,
    NORMALIZED
  }

  private final NgramBloomConfig config;
  private final long[] words;
  private final boolean evaluable;

  private NgramBloom(NgramBloomConfig config, long[] words) {
    this.config = config;
    this.words = words;
    this.evaluable = config.evaluableHere();
  }

  /** Nulls are skipped: no predicate on a literal matches them. */
  public static NgramBloom build(NgramBloomConfig config, Iterable<String> values) {
    long[] hashes = new long[64];
    int n = 0;
    for (String v : values) {
      if (v == null) {
        continue;
      }
      int[] framed = frame(config.normalization().apply(v), true, true);
      for (long h : gramHashes(config, framed)) {
        if (n == hashes.length) {
          hashes = Arrays.copyOf(hashes, n * 2);
        }
        hashes[n++] = h;
      }
    }
    Arrays.sort(hashes, 0, n);
    int distinct = 0;
    for (int i = 0; i < n; i++) {
      if (i == 0 || hashes[i] != hashes[i - 1]) {
        hashes[distinct++] = hashes[i];
      }
    }
    long[] words = new long[config.bitsFor(distinct) / 64];
    for (int i = 0; i < distinct; i++) {
      set(words, config.hashCount(), hashes[i]);
    }
    return new NgramBloom(config, words);
  }

  public NgramBloomConfig config() {
    return config;
  }

  public int bits() {
    return words.length * 64;
  }

  public boolean evaluable() {
    return evaluable;
  }

  /** False only if no value equals {@code literal}, or, for NORMALIZED, no value normalizes to the same string. */
  public boolean mightEqual(String literal) {
    // v = lit implies N(v) = N(lit) for any N, so the two semantics need the same probe.
    return !evaluable || allPresent(frame(config.normalization().apply(literal), true, true), true);
  }

  /** False only if no value starts with {@code prefix} under the given semantics. */
  public boolean mightStartWith(String prefix, Semantics semantics) {
    if (!evaluable) {
      return true;
    }
    String probe = semantics == Semantics.RAW ? stableCore(prefix, false, true) : prefix;
    return probe == null || allPresent(frame(config.normalization().apply(probe), true, false), false);
  }

  /** False only if no value contains {@code fragment} under the given semantics. */
  public boolean mightContain(String fragment, Semantics semantics) {
    if (!evaluable) {
      return true;
    }
    String probe = semantics == Semantics.RAW ? stableCore(fragment, true, true) : fragment;
    return probe == null || allPresent(frame(config.normalization().apply(probe), false, false), false);
  }

  /**
   * The part of a pattern literal whose normalization cannot depend on the characters around it in a stored value.
   * Normalizing a fragment is not the same as taking a fragment of a normalized value: a combining mark after the
   * fragment can compose with its last character, and one at its start can compose with what precedes it. Trims each
   * open edge to the nearest normalization boundary; null if nothing survives.
   */
  private String stableCore(String literal, boolean openStart, boolean openEnd) {
    int[] cps = literal.codePoints().toArray();
    TextNormalization norm = config.normalization();
    int from = 0;
    if (openStart) {
      while (from < cps.length && !norm.hasBoundaryBefore(cps[from])) {
        from++;
      }
    }
    int to = cps.length - 1;
    if (openEnd) {
      while (to >= from && !norm.hasBoundaryAfter(cps[to])) {
        to--;
      }
    }
    return from > to ? null : new String(cps, from, to - from + 1);
  }

  /** The fraction of bits set. */
  public double fillRatio() {
    long set = 0;
    for (long w : words) {
      set += Long.bitCount(w);
    }
    return (double) set / bits();
  }

  /** The false-positive rate implied by the current fill, for a probe of one gram. */
  public double estimatedFalsePositiveRate() {
    return Math.pow(fillRatio(), config.hashCount());
  }

  /**
   * The filter for the union of the sources' values: bitwise OR, exact for filters of one configuration and size.
   *
   * @throws IllegalArgumentException if the sources differ in configuration or size
   */
  public static NgramBloom merge(List<NgramBloom> sources) {
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("nothing to merge");
    }
    NgramBloom first = sources.get(0);
    long[] words = first.words.clone();
    for (NgramBloom s : sources.subList(1, sources.size())) {
      if (!s.config.equals(first.config) || s.words.length != words.length) {
        throw new IllegalArgumentException("n-gram filters of different configurations or sizes cannot be merged");
      }
      for (int i = 0; i < words.length; i++) {
        words[i] |= s.words[i];
      }
    }
    return new NgramBloom(first.config, words);
  }

  /** Payload: version (1 byte), flags (1 byte, 0), word count (4 bytes), then big-endian 64-bit words. */
  public byte[] toBytes() {
    ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + words.length * 8);
    buf.put(VERSION).put((byte) 0).putInt(words.length);
    for (long w : words) {
      buf.putLong(w);
    }
    return buf.array();
  }

  /**
   * @throws IllegalArgumentException if the payload is malformed or inconsistent with {@code config}
   */
  public static NgramBloom fromBytes(ByteBuffer data, NgramBloomConfig config) {
    ByteBuffer buf = data.duplicate();
    if (buf.remaining() < HEADER_BYTES) {
      throw new IllegalArgumentException("truncated n-gram filter header");
    }
    byte version = buf.get();
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported n-gram filter version " + version);
    }
    if (buf.get() != 0) {
      throw new IllegalArgumentException("unknown n-gram filter flags");
    }
    int count = buf.getInt();
    if (count <= 0 || count > NgramBloomConfig.MAX_BITS / 64 || buf.remaining() != count * 8) {
      throw new IllegalArgumentException("n-gram filter length does not match its word count " + count);
    }
    if (config.bits().isPresent() && config.bits().getAsInt() != count * 64) {
      throw new IllegalArgumentException(
          "n-gram filter has " + count * 64 + " bits but its configuration fixes " + config.bits().getAsInt());
    }
    long[] words = new long[count];
    for (int i = 0; i < count; i++) {
      words[i] = buf.getLong();
    }
    return new NgramBloom(config, words);
  }

  /**
   * @param whole whether {@code framed} is a whole framed value, which a value shorter than a gram was hashed as. Any
   *     other probe shorter than a gram constrains nothing.
   */
  private boolean allPresent(int[] framed, boolean whole) {
    if (framed.length < config.gramLength() && !whole) {
      return true;
    }
    for (long h : gramHashes(config, framed)) {
      if (!test(h)) {
        return false;
      }
    }
    return true;
  }

  private boolean test(long hash) {
    int m = bits();
    int h1 = (int) hash;
    int h2 = (int) (hash >>> 32);
    for (int i = 0; i < config.hashCount(); i++) {
      int combined = h1 + i * h2;
      if (combined < 0) {
        combined = ~combined;
      }
      int bit = combined % m;
      if ((words[bit >>> 6] & (1L << bit)) == 0) {
        return false;
      }
    }
    return true;
  }

  private static void set(long[] words, int hashCount, long hash) {
    int m = words.length * 64;
    int h1 = (int) hash;
    int h2 = (int) (hash >>> 32);
    for (int i = 0; i < hashCount; i++) {
      int combined = h1 + i * h2;
      if (combined < 0) {
        combined = ~combined;
      }
      int bit = combined % m;
      words[bit >>> 6] |= 1L << bit;
    }
  }

  private static int[] frame(String normalized, boolean start, boolean end) {
    int[] cps = normalized.codePoints().toArray();
    int[] out = new int[cps.length + (start ? 1 : 0) + (end ? 1 : 0)];
    int p = 0;
    if (start) {
      out[p++] = START;
    }
    System.arraycopy(cps, 0, out, p, cps.length);
    if (end) {
      out[out.length - 1] = END;
    }
    return out;
  }

  /** Hashes of each window of gram-length code points; a sequence shorter than that is hashed whole. */
  private static long[] gramHashes(NgramBloomConfig config, int[] cps) {
    int n = config.gramLength();
    if (cps.length == 0) {
      return new long[0];
    }
    if (cps.length <= n) {
      return new long[] {hashGram(config, cps, 0, cps.length)};
    }
    long[] out = new long[cps.length - n + 1];
    for (int i = 0; i < out.length; i++) {
      out[i] = hashGram(config, cps, i, n);
    }
    return out;
  }

  private static long hashGram(NgramBloomConfig config, int[] cps, int from, int len) {
    byte[] utf8 = new String(cps, from, len).getBytes(StandardCharsets.UTF_8);
    return Xxh64.hash(utf8, config.seed());
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof NgramBloom that && config.equals(that.config) && Arrays.equals(words, that.words);
  }

  @Override
  public int hashCode() {
    return Objects.hash(config, Arrays.hashCode(words));
  }

  @Override
  public String toString() {
    return String.format(
        "NgramBloom(%s, n=%d, bits=%d, fill=%.3f)", config.normalization().json(), config.gramLength(), bits(), fillRatio());
  }
}
