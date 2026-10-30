package com.cloudera.iceberg.proposed.puffin;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;

/**
 * A configuration document: a JSON object in its RFC 8785 canonical form, with no identity of its own. What a
 * configuration store holds and returns (Section 7.3) is a document; what a blob refers to is a {@link BuildConfig},
 * the document read under one type's schema, because a document means nothing apart from the schema it is read under
 * and its digest is defined over both (Section 7.2).
 *
 * <p>Immutable. Equality is equality of canonical forms.
 */
public final class ConfigDocument {
  private final JsonNode node;
  private final String canonical;

  private ConfigDocument(JsonNode node) {
    if (!node.isObject()) {
      throw new IllegalArgumentException("a configuration document must be a JSON object");
    }
    this.node = node.deepCopy();
    this.canonical = CanonicalJson.write(this.node);
  }

  /**
   * @throws IllegalArgumentException if {@code json} is not a single JSON object, has duplicate member names, or holds
   *     a value that RFC 8785 cannot canonicalize exactly
   */
  public static ConfigDocument parse(String json) {
    return new ConfigDocument(CanonicalJson.parse(json));
  }

  static ConfigDocument of(JsonNode node) {
    return new ConfigDocument(node);
  }

  /** This document read under the schema whose digest is {@code schema}. */
  public BuildConfig under(SchemaDigest schema) {
    return BuildConfig.of(this, schema);
  }

  public String canonicalJson() {
    return canonical;
  }

  public byte[] canonicalBytes() {
    return canonical.getBytes(StandardCharsets.UTF_8);
  }

  /** A copy of the parsed object. */
  JsonNode node() {
    return node.deepCopy();
  }

  JsonNode member(String key) {
    return node.get(key);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ConfigDocument that && canonical.equals(that.canonical);
  }

  @Override
  public int hashCode() {
    return canonical.hashCode();
  }

  @Override
  public String toString() {
    return canonical;
  }
}
