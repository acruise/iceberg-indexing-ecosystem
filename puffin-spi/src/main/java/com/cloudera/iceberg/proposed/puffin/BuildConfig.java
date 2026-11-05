package com.cloudera.iceberg.proposed.puffin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * The configuration under which a blob was built (Section 7): a {@link ConfigDocument} read under one blob type's
 * configuration schema, identified by the digest of Section 7.2,
 *
 * <pre>  config-digest = SHA-256( schema-digest bytes || canonical(document) )</pre>
 *
 * so that two parties holding the same document and the same schema agree on its identity without coordination, and so
 * that the same document read under two schemas, {@code {}} for every parameterless type or {@code {"seed": 0}} for
 * every type with a seed, has two identities rather than one. A store keyed by the digest therefore cannot return a
 * document written for another type, and a revocation by digest (Section 7.4) cannot cross a type boundary.
 *
 * <p>Immutable. Equality is equality of digests, and therefore of schema and canonical form together.
 */
public final class BuildConfig {
  public static final String DIGEST_ALGORITHM = Digests.ALGORITHM;

  private final ConfigDocument document;
  private final SchemaDigest schema;
  private final String digest;

  private BuildConfig(ConfigDocument document, SchemaDigest schema) {
    this.document = Objects.requireNonNull(document, "document");
    this.schema = Objects.requireNonNull(schema, "schema");
    this.digest = Digests.format(Digests.sha256(schema.rawBytes(), document.canonicalBytes()));
  }

  static BuildConfig of(ConfigDocument document, SchemaDigest schema) {
    return new BuildConfig(document, schema);
  }

  /**
   * @throws IllegalArgumentException if {@code json} is not a single JSON object, has duplicate member names, or holds
   *     a value that RFC 8785 cannot canonicalize exactly
   */
  public static BuildConfig parse(String json, SchemaDigest schema) {
    return new BuildConfig(ConfigDocument.parse(json), schema);
  }

  public static Builder builder(SchemaDigest schema) {
    return new Builder(schema);
  }

  public static boolean isDigest(String s) {
    return Digests.isDigest(s);
  }

  /** The {@code config-digest} of Section 7.2. */
  public String digest() {
    return digest;
  }

  /** The digest of the schema this document was read under, the descriptor's {@code schema-digest}. */
  public SchemaDigest schemaDigest() {
    return schema;
  }

  public ConfigDocument document() {
    return document;
  }

  public String canonicalJson() {
    return document.canonicalJson();
  }

  public byte[] canonicalBytes() {
    return document.canonicalBytes();
  }

  public boolean has(String key) {
    return document.member(key) != null;
  }

  /** A copy of the member's value, or empty if absent. */
  public Optional<JsonNode> get(String key) {
    return Optional.ofNullable(document.member(key)).map(JsonNode::deepCopy);
  }

  public Optional<String> string(String key) {
    JsonNode v = document.member(key);
    return v != null && v.isTextual() ? Optional.of(v.textValue()) : Optional.empty();
  }

  /** The member as an integer, if it is a number with no fractional part. */
  public OptionalLong integer(String key) {
    JsonNode v = document.member(key);
    if (v == null || !v.isNumber()) {
      return OptionalLong.empty();
    }
    double d = v.doubleValue();
    return d == Math.rint(d) ? OptionalLong.of((long) d) : OptionalLong.empty();
  }

  public OptionalDouble number(String key) {
    JsonNode v = document.member(key);
    return v != null && v.isNumber() ? OptionalDouble.of(v.doubleValue()) : OptionalDouble.empty();
  }

  /**
   * Whether both configurations specify {@code key} with canonically equal values. A key that either omits is a
   * disagreement, not vacuous agreement (Section 7.5). Configurations under different schemas never agree, since the
   * same key may mean different things under each.
   */
  public boolean agreesOn(BuildConfig other, String key) {
    if (!schema.equals(other.schema)) {
      return false;
    }
    JsonNode a = document.member(key);
    JsonNode b = other.document.member(key);
    return a != null && b != null && CanonicalJson.write(a).equals(CanonicalJson.write(b));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof BuildConfig that && digest.equals(that.digest);
  }

  @Override
  public int hashCode() {
    return digest.hashCode();
  }

  @Override
  public String toString() {
    return document.canonicalJson();
  }

  public static final class Builder {
    private final SchemaDigest schema;
    private final ObjectNode node = JsonNodeFactory.instance.objectNode();

    private Builder(SchemaDigest schema) {
      this.schema = Objects.requireNonNull(schema, "schema");
    }

    public Builder put(String key, String value) {
      node.put(key, value);
      return this;
    }

    public Builder put(String key, long value) {
      node.put(key, value);
      return this;
    }

    public Builder put(String key, double value) {
      node.put(key, value);
      return this;
    }

    public Builder put(String key, boolean value) {
      node.put(key, value);
      return this;
    }

    public Builder put(String key, JsonNode value) {
      node.set(key, value.deepCopy());
      return this;
    }

    /**
     * @throws IllegalArgumentException if a value cannot be canonicalized exactly
     */
    public BuildConfig build() {
      return new BuildConfig(ConfigDocument.of(node), schema);
    }
  }
}
