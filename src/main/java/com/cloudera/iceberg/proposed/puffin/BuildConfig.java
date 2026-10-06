package com.cloudera.iceberg.proposed.puffin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * The configuration under which a blob was built (Section 7): a JSON object identified by the SHA-256 digest of its
 * RFC 8785 canonical form, so that two parties holding the same document agree on its identity without coordination.
 *
 * <p>Immutable. Equality is equality of digests, and therefore of canonical forms.
 */
public final class BuildConfig {
  public static final String DIGEST_ALGORITHM = "sha-256";
  private static final Pattern DIGEST_SYNTAX = Pattern.compile("sha-256:[0-9a-f]{64}");

  private final JsonNode document;
  private final String canonical;
  private final String digest;

  private BuildConfig(JsonNode document) {
    if (!document.isObject()) {
      throw new IllegalArgumentException("a build configuration must be a JSON object");
    }
    this.document = document.deepCopy();
    this.canonical = CanonicalJson.write(this.document);
    this.digest = digestOf(canonical);
  }

  /**
   * @throws IllegalArgumentException if {@code json} is not a single JSON object, has duplicate member names, or holds
   *     a value that RFC 8785 cannot canonicalize exactly
   */
  public static BuildConfig parse(String json) {
    return new BuildConfig(CanonicalJson.parse(json));
  }

  public static Builder builder() {
    return new Builder();
  }

  public static boolean isDigest(String s) {
    return s != null && DIGEST_SYNTAX.matcher(s).matches();
  }

  public String digest() {
    return digest;
  }

  public String canonicalJson() {
    return canonical;
  }

  public byte[] canonicalBytes() {
    return canonical.getBytes(StandardCharsets.UTF_8);
  }

  public boolean has(String key) {
    return document.has(key);
  }

  /** A copy of the member's value, or empty if absent. */
  public Optional<JsonNode> get(String key) {
    return Optional.ofNullable(document.get(key)).map(JsonNode::deepCopy);
  }

  public Optional<String> string(String key) {
    JsonNode v = document.get(key);
    return v != null && v.isTextual() ? Optional.of(v.textValue()) : Optional.empty();
  }

  /** The member as an integer, if it is a number with no fractional part. */
  public OptionalLong integer(String key) {
    JsonNode v = document.get(key);
    if (v == null || !v.isNumber()) {
      return OptionalLong.empty();
    }
    double d = v.doubleValue();
    return d == Math.rint(d) ? OptionalLong.of((long) d) : OptionalLong.empty();
  }

  public OptionalDouble number(String key) {
    JsonNode v = document.get(key);
    return v != null && v.isNumber() ? OptionalDouble.of(v.doubleValue()) : OptionalDouble.empty();
  }

  /**
   * Whether both configurations specify {@code key} with canonically equal values. A key that either omits is a
   * disagreement, not vacuous agreement (Section 7.5).
   */
  public boolean agreesOn(BuildConfig other, String key) {
    JsonNode a = document.get(key);
    JsonNode b = other.document.get(key);
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
    return canonical;
  }

  private static String digestOf(String canonical) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return DIGEST_ALGORITHM + ":" + java.util.HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java platform must support SHA-256", e);
    }
  }

  public static final class Builder {
    private final ObjectNode node = JsonNodeFactory.instance.objectNode();

    private Builder() {}

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
      return new BuildConfig(node);
    }
  }
}
