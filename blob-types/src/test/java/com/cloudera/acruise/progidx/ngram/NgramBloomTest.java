package com.cloudera.acruise.progidx.ngram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.cloudera.acruise.progidx.ngram.NgramBloom.Semantics;

class NgramBloomTest {
  private static final List<String> SKUS = List.of("ABC-100", "ABD-200", "XYZ-999", "Straße", "café", "e");

  private static NgramBloomConfig fixed(TextNormalization n) {
    return NgramBloomConfig.fixed(n, 3, 4, 8192);
  }

  /** Values mixing ASCII, precomposed and decomposed accents, compatibility forms and case. */
  private static List<String> randomValues(Random rng, int count) {
    String[] pieces = {"a", "B", "c", "é", "é", "́", "ﬁ", "ß", "SS", "Σ", "ς", "-", "1", "ẞ"};
    List<String> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      StringBuilder sb = new StringBuilder();
      int len = 1 + rng.nextInt(8);
      for (int j = 0; j < len; j++) {
        sb.append(pieces[rng.nextInt(pieces.length)]);
      }
      out.add(sb.toString());
    }
    return out;
  }

  @ParameterizedTest
  @EnumSource(TextNormalization.class)
  void rawPredicatesHaveNoFalseNegatives(TextNormalization normalization) {
    Random rng = new Random(normalization.ordinal());
    List<String> values = randomValues(rng, 200);
    NgramBloom f = NgramBloom.build(fixed(normalization), values);
    for (String v : values) {
      assertThat(f.mightEqual(v)).as("equal %s", v).isTrue();
      int[] cps = v.codePoints().toArray();
      for (int from = 0; from < cps.length; from++) {
        for (int to = from + 1; to <= cps.length; to++) {
          String sub = new String(cps, from, to - from);
          assertThat(f.mightContain(sub, Semantics.RAW)).as("%s contains %s", v, sub).isTrue();
          if (from == 0) {
            assertThat(f.mightStartWith(sub, Semantics.RAW)).as("%s starts with %s", v, sub).isTrue();
          }
        }
      }
    }
  }

  @Test
  void normalizedPredicatesAreCaselessUnderCasefolding() {
    NgramBloom f = NgramBloom.build(fixed(TextNormalization.NFKC_CASEFOLD), SKUS);
    assertThat(f.mightEqual("abc-100")).isTrue();
    assertThat(f.mightEqual("STRASSE")).isTrue(); // full case folding maps ß to ss
    assertThat(f.mightStartWith("xyz", Semantics.NORMALIZED)).isTrue();
    assertThat(f.mightContain("BD-2", Semantics.NORMALIZED)).isTrue();
    assertThat(f.mightContain("café", Semantics.NORMALIZED)).isTrue(); // NFKC composes the stored e + U+0301

    NgramBloom exact = NgramBloom.build(fixed(TextNormalization.NONE), SKUS);
    assertThat(exact.mightEqual("ABC-100")).isTrue();
    assertThat(exact.mightEqual("abc-100")).isFalse();
  }

  @Test
  void excludesWhatIsAbsent() {
    NgramBloom f = NgramBloom.build(fixed(TextNormalization.NFKC_CASEFOLD), SKUS);
    assertThat(f.mightEqual("QQQ-123")).isFalse();
    assertThat(f.mightEqual("ABC-10")).isFalse(); // the end marker makes equality stricter than containment
    assertThat(f.mightContain("ABC-10", Semantics.RAW)).isTrue();
    assertThat(f.mightStartWith("BC-", Semantics.RAW)).isFalse();
    assertThat(f.mightContain("BC-", Semantics.RAW)).isTrue();
    assertThat(f.mightEqual("e")).isTrue(); // shorter than a gram once framed: hashed whole
    assertThat(f.mightEqual("f")).isFalse();
  }

  /**
   * The hazard of proposal Section 7.6: the stored "cafe" + U+0301 normalizes to "café", whose grams lack "afe". A probe
   * of every gram of the normalized literal "cafe" would exclude a file that contains it.
   */
  @Test
  void rawProbesStopAtNormalizationBoundaries() {
    NgramBloom f = NgramBloom.build(fixed(TextNormalization.NFC), List.of("café"));
    assertThat(f.mightContain("cafe", Semantics.NORMALIZED)).isFalse(); // correct: "café" does not contain "cafe"
    assertThat(f.mightContain("cafe", Semantics.RAW)).isTrue(); // correct: the stored value does
    assertThat(f.mightStartWith("cafe", Semantics.RAW)).isTrue();
  }

  @Test
  void unicodeVersionMismatchMakesNormalizingFiltersInert() {
    NgramBloomConfig other =
        new NgramBloomConfig(TextNormalization.NFKC_CASEFOLD, "1.1", 3, 0, 4, OptionalInt.of(8192), OptionalDouble.empty());
    NgramBloom f = NgramBloom.build(other, SKUS);
    assertThat(f.evaluable()).isFalse();
    assertThat(f.mightEqual("QQQ-123")).isTrue();

    NgramBloomConfig raw = new NgramBloomConfig(TextNormalization.NONE, "1.1", 3, 0, 4, OptionalInt.of(8192), OptionalDouble.empty());
    assertThat(NgramBloom.build(raw, SKUS).mightEqual("QQQ-123")).isFalse();
  }

  @Test
  void mergeIsExact() {
    NgramBloomConfig c = fixed(TextNormalization.NFKC_CASEFOLD);
    List<String> a = SKUS.subList(0, 3);
    List<String> b = SKUS.subList(3, SKUS.size());
    assertThat(NgramBloom.merge(List.of(NgramBloom.build(c, a), NgramBloom.build(c, b)))).isEqualTo(NgramBloom.build(c, SKUS));
  }

  @Test
  void adaptiveSizingFollowsDistinctGrams() {
    NgramBloomConfig c = NgramBloomConfig.adaptive(TextNormalization.NONE, 3, 4, 0.01);
    NgramBloom small = NgramBloom.build(c, SKUS);
    NgramBloom large = NgramBloom.build(c, randomValues(new Random(1), 5000));
    assertThat(large.bits()).isGreaterThan(small.bits());
    assertThat(large.estimatedFalsePositiveRate()).isLessThan(0.02);
    assertThatThrownBy(() -> NgramBloom.merge(List.of(small, large))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void payloadRoundTripsAndIsCheckedAgainstItsConfiguration() {
    NgramBloomConfig c = fixed(TextNormalization.NFC);
    NgramBloom f = NgramBloom.build(c, SKUS);
    assertThat(NgramBloom.fromBytes(ByteBuffer.wrap(f.toBytes()), c)).isEqualTo(f);

    NgramBloomConfig bigger = NgramBloomConfig.fixed(TextNormalization.NFC, 3, 4, 16384);
    assertThatThrownBy(() -> NgramBloom.fromBytes(ByteBuffer.wrap(f.toBytes()), bigger)).hasMessageContaining("fixes 16384");
    byte[] truncated = java.util.Arrays.copyOf(f.toBytes(), 100);
    assertThatThrownBy(() -> NgramBloom.fromBytes(ByteBuffer.wrap(truncated), c)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void configurationRoundTripsThroughItsDocument() {
    NgramBloomConfig c = NgramBloomConfig.adaptive(TextNormalization.NFKC_CASEFOLD, 4, 3, 0.001);
    assertThat(NgramBloomConfig.from(c.toBuildConfig())).isEqualTo(c);
    assertThat(c.toBuildConfig().string("hash")).contains("xxh64");
  }
}
