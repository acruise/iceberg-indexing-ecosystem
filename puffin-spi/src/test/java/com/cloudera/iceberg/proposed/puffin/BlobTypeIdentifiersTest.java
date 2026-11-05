package com.cloudera.iceberg.proposed.puffin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The examples of proposal Section 4.1. */
class BlobTypeIdentifiersTest {
  @Test
  void wellFormed() {
    assertThat(BlobTypeIdentifiers.namespace("apache-datasketches-theta-v1")).isEmpty();
    assertThat(BlobTypeIdentifiers.namespace("deletion-vector-v1")).isEmpty();
    assertThat(BlobTypeIdentifiers.namespace("build-config-v1")).isEmpty();
    assertThat(BlobTypeIdentifiers.namespace("com.example.f16-presence-v1")).contains("com.example");
    assertThat(BlobTypeIdentifiers.majorVersion("com.example.f16-presence-v12")).isEqualTo(12);
    // Well formed, though probably not what its author meant: the name is "presence".
    assertThat(BlobTypeIdentifiers.namespace("com.example.f16.presence-v1")).contains("com.example.f16");
  }

  @Test
  void malformed() {
    assertThat(BlobTypeIdentifiers.isValid("com.example.f16-presence")).isFalse();
    assertThat(BlobTypeIdentifiers.isValid("com.example.f16-presence-v0")).isFalse();
    assertThat(BlobTypeIdentifiers.isValid("com.example.f16-presence-v01")).isFalse();
    assertThat(BlobTypeIdentifiers.isValid("com.example.F16-Presence-v1")).isFalse();
    assertThat(BlobTypeIdentifiers.isValid("com.example.f16_presence-v1")).isFalse();
    assertThat(BlobTypeIdentifiers.isValid(".f16-presence-v1")).isFalse();
    assertThat(BlobTypeIdentifiers.isValid(null)).isFalse();
  }
}
