package com.cloudera.iceberg.proposed.puffin;

import java.util.Arrays;

/**
 * The digest of a blob type's configuration schema, the {@code schema-digest} member of a descriptor's {@code config}
 * (Section 7.5): SHA-256 over the RFC 8785 canonical form of the JSON Schema document at {@code config.schema}.
 *
 * <p>It is the domain-separation prefix of every configuration digest under the type (Section 7.2), so that the same
 * document read under two schemas has two identities. An implementation compiled against a type carries this value
 * (Section 9.4); {@link #of} is for the party that publishes the schema and for tests that check the compiled-in value
 * against the document.
 */
public final class SchemaDigest {
  private final byte[] bytes;

  private SchemaDigest(byte[] bytes) {
    this.bytes = bytes;
  }

  /**
   * The digest of a schema document.
   *
   * @throws IllegalArgumentException if {@code schemaJson} is not a single JSON object that RFC 8785 can canonicalize
   *     exactly
   */
  public static SchemaDigest of(String schemaJson) {
    return new SchemaDigest(Digests.sha256(ConfigDocument.parse(schemaJson).canonicalBytes()));
  }

  /**
   * A digest in the notation of Section 7.2, as published in a descriptor.
   *
   * @throws IllegalArgumentException if {@code s} is not a supported digest
   */
  public static SchemaDigest parse(String s) {
    return new SchemaDigest(Digests.parse(s));
  }

  /** The raw digest, which prefixes the canonical configuration document in a configuration digest's preimage. */
  public byte[] bytes() {
    return bytes.clone();
  }

  byte[] rawBytes() {
    return bytes;
  }

  /** The {@code sha-256:<hex>} form. */
  @Override
  public String toString() {
    return Digests.format(bytes);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SchemaDigest that && Arrays.equals(bytes, that.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }
}
