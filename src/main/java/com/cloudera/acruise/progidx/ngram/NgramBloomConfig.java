package com.cloudera.acruise.progidx.ngram;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import com.cloudera.iceberg.proposed.puffin.BuildConfig;

/**
 * The build configuration of an n-gram Bloom filter (proposal Section 7.6). Every parameter but the sizing is needed to
 * evaluate predicates; the sizing must also agree for filters to be merged.
 *
 * <p>Sizing is either fixed ({@code bits}), which makes filters of the same configuration mergeable by OR, or adaptive
 * ({@code target-fpp}), which sizes each file's filter from its distinct-gram count and makes filters non-composable.
 */
public record NgramBloomConfig(
    TextNormalization normalization,
    String unicodeVersion,
    int gramLength,
    long seed,
    int hashCount,
    OptionalInt bits,
    OptionalDouble targetFpp) {

  public static final String HASH = "xxh64";
  public static final int MAX_BITS = 1 << 30;

  public NgramBloomConfig {
    if (gramLength < 2 || gramLength > 8) {
      throw new IllegalArgumentException("gram-length must be in [2, 8]: " + gramLength);
    }
    if (hashCount < 1 || hashCount > 16) {
      throw new IllegalArgumentException("hash-count must be in [1, 16]: " + hashCount);
    }
    if (bits.isPresent() == targetFpp.isPresent()) {
      throw new IllegalArgumentException("exactly one of bits and target-fpp must be given");
    }
    bits.ifPresent(
        b -> {
          if (b < 64 || b > MAX_BITS || b % 64 != 0) {
            throw new IllegalArgumentException("bits must be a multiple of 64 in [64, 2^30]: " + b);
          }
        });
    targetFpp.ifPresent(
        p -> {
          if (!(p > 0 && p < 1)) {
            throw new IllegalArgumentException("target-fpp must be in (0, 1): " + p);
          }
        });
  }

  /** Fixed sizing, built under this process's Unicode version, seed 0. */
  public static NgramBloomConfig fixed(TextNormalization normalization, int gramLength, int hashCount, int bits) {
    return new NgramBloomConfig(
        normalization, TextNormalization.runtimeUnicodeVersion(), gramLength, 0L, hashCount, OptionalInt.of(bits), OptionalDouble.empty());
  }

  /** Adaptive sizing, built under this process's Unicode version, seed 0. */
  public static NgramBloomConfig adaptive(TextNormalization normalization, int gramLength, int hashCount, double targetFpp) {
    return new NgramBloomConfig(
        normalization, TextNormalization.runtimeUnicodeVersion(), gramLength, 0L, hashCount, OptionalInt.empty(), OptionalDouble.of(targetFpp));
  }

  /**
   * @throws IllegalArgumentException if {@code config} does not satisfy this type's configuration schema
   */
  public static NgramBloomConfig from(BuildConfig config) {
    String hash = config.string("hash").orElseThrow(() -> missing("hash"));
    if (!HASH.equals(hash)) {
      throw new IllegalArgumentException("unsupported hash: " + hash);
    }
    return new NgramBloomConfig(
        TextNormalization.fromJson(config.string("normalization").orElseThrow(() -> missing("normalization"))),
        config.string("unicode-version").orElseThrow(() -> missing("unicode-version")),
        intValue(config, "gram-length"),
        config.integer("seed").orElseThrow(() -> missing("seed")),
        intValue(config, "hash-count"),
        config.has("bits") ? OptionalInt.of(intValue(config, "bits")) : OptionalInt.empty(),
        config.number("target-fpp"));
  }

  public BuildConfig toBuildConfig() {
    BuildConfig.Builder b =
        BuildConfig.builder()
            .put("normalization", normalization.json())
            .put("unicode-version", unicodeVersion)
            .put("gram-length", gramLength)
            .put("hash", HASH)
            .put("seed", seed)
            .put("hash-count", hashCount);
    bits.ifPresent(v -> b.put("bits", v));
    targetFpp.ifPresent(v -> b.put("target-fpp", v));
    return b.build();
  }

  /**
   * Whether this process can evaluate predicates against a filter built under this configuration. Normalization
   * depends on Unicode data, and a reader whose data differs from the writer's may normalize a literal differently and
   * exclude a matching file, so only an exact version match is accepted.
   */
  public boolean evaluableHere() {
    return normalization == TextNormalization.NONE || unicodeVersion.equals(TextNormalization.runtimeUnicodeVersion());
  }

  /** The filter size for a file with {@code distinctGrams} distinct grams. */
  public int bitsFor(long distinctGrams) {
    if (bits.isPresent()) {
      return bits.getAsInt();
    }
    // For k fixed hash functions, p = (1 - e^(-kn/m))^k, so m = -kn / ln(1 - p^(1/k)).
    double m = -hashCount * Math.max(1, distinctGrams) / Math.log(1 - Math.pow(targetFpp.getAsDouble(), 1.0 / hashCount));
    long rounded = ((long) Math.ceil(m / 64)) * 64;
    return (int) Math.min(MAX_BITS, Math.max(64, rounded));
  }

  private static int intValue(BuildConfig config, String key) {
    long v = config.integer(key).orElseThrow(() -> missing(key));
    if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(key + " out of range: " + v);
    }
    return (int) v;
  }

  private static IllegalArgumentException missing(String key) {
    return new IllegalArgumentException("missing or mistyped configuration key: " + key);
  }
}
