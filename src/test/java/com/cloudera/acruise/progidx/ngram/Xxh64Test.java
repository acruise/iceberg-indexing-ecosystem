package com.cloudera.acruise.progidx.ngram;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Expected values from the reference implementation (python-xxhash). */
class Xxh64Test {
  private static long h(String s, long seed) {
    return Xxh64.hash(s.getBytes(StandardCharsets.UTF_8), seed);
  }

  @Test
  void shortInputs() {
    assertThat(h("", 0)).isEqualTo(0xef46db3751d8e999L);
    assertThat(h("a", 0)).isEqualTo(0xd24ec4f1a98c6e5bL);
    assertThat(h("abc", 0)).isEqualTo(0x44bc2cf5ad770999L);
    assertThat(h("abc", 42)).isEqualTo(0x13c1d910702770e6L);
    assertThat(h("﷐ab", 0)).isEqualTo(0xe9f5f6033017aa2aL);
  }

  @Test
  void stripedInputs() {
    assertThat(h("Nobody inspects the spammish repetition", 0)).isEqualTo(0xfbcea83c8a378bf1L);
    byte[] hundred = new byte[100];
    for (int i = 0; i < hundred.length; i++) {
      hundred[i] = (byte) i;
    }
    assertThat(Xxh64.hash(hundred, 7)).isEqualTo(0x80653e7e9b887cddL);
  }

  @Test
  void respectsOffsetAndLength() {
    byte[] padded = "xxabcxx".getBytes(StandardCharsets.UTF_8);
    assertThat(Xxh64.hash(padded, 2, 3, 0)).isEqualTo(0x44bc2cf5ad770999L);
  }
}
