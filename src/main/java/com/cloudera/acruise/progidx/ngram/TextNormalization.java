package com.cloudera.acruise.progidx.ngram;

import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.util.VersionInfo;
import java.util.Arrays;
import java.util.Locale;

/**
 * How an n-gram filter normalizes text before splitting it into grams. Everything but {@link #NONE} depends on Unicode
 * data, which grows as characters are assigned, so filters record the Unicode version they were built with.
 */
public enum TextNormalization {
  NONE(null),
  NFC(Normalizer2.getNFCInstance()),
  NFKC(Normalizer2.getNFKCInstance()),
  /** Unicode NFKC_Casefold: compatibility normalization plus full case folding, for caseless matching. */
  NFKC_CASEFOLD(Normalizer2.getNFKCCasefoldInstance());

  private final Normalizer2 normalizer;

  TextNormalization(Normalizer2 normalizer) {
    this.normalizer = normalizer;
  }

  public String json() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  public static TextNormalization fromJson(String s) {
    return Arrays.stream(values())
        .filter(v -> v.json().equals(s))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown normalization: " + s));
  }

  public String apply(String s) {
    return normalizer == null ? s : normalizer.normalize(s);
  }

  /** Normalization never interacts with characters before {@code codePoint}, whatever they are. */
  public boolean hasBoundaryBefore(int codePoint) {
    return normalizer == null || normalizer.hasBoundaryBefore(codePoint);
  }

  /** Normalization never interacts with characters after {@code codePoint}, whatever they are. */
  public boolean hasBoundaryAfter(int codePoint) {
    return normalizer == null || normalizer.hasBoundaryAfter(codePoint);
  }

  /** The Unicode version of this process's normalization data, as major.minor. */
  public static String runtimeUnicodeVersion() {
    VersionInfo v = UCharacter.getUnicodeVersion();
    return v.getMajor() + "." + v.getMinor();
  }
}
