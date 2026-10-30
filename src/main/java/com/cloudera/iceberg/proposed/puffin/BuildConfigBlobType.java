package com.cloudera.iceberg.proposed.puffin;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.puffin.Blob;
import org.apache.iceberg.puffin.BlobMetadata;

/**
 * The standard in-band configuration store (Section 7.3): a blob whose payload is a configuration document in RFC 8785
 * canonical form, stored in the same Puffin file as the blobs that refer to it, with the configuration's digest in its
 * {@code config-digest} property.
 *
 * <p>The payload is a document, not a configuration: its digest is defined over the schema of the type that refers to
 * it as well as over the document (Section 7.2), so the blob alone cannot be checked against its recorded digest.
 * {@link BuildConfigResolver} does that when a blob of a known type refers to it.
 */
public final class BuildConfigBlobType implements PuffinBlobType<ConfigDocument> {
  public static final String TYPE = "build-config-v1";

  private static final BlobTypeDescriptor DESCRIPTOR =
      BlobTypeDescriptor.builder(TYPE)
          .title("Build configuration")
          .definition("https://iceberg.apache.org/puffin-spec/#build-config-v1")
          .scope(BlobTypeDescriptor.Scope.PUFFIN_FILE)
          .inputFields(0)
          .property(BlobProperties.CONFIG_DIGEST, true, "the digest of the payload under the referring type's schema")
          .compaction(BlobTypeDescriptor.Compaction.RETAIN)
          .deletes(BlobTypeDescriptor.Deletes.CONSERVATIVE)
          .compression("none", "zstd")
          .build();

  @Override
  public BlobTypeDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public Class<ConfigDocument> javaClass() {
    return ConfigDocument.class;
  }

  @Override
  public ByteBuffer serialize(ConfigDocument value) {
    return ByteBuffer.wrap(value.canonicalBytes());
  }

  /** The payload must be exactly the canonical form, and the blob's properties must record a well-formed digest. */
  @Override
  public ConfigDocument deserialize(BlobMetadata metadata, ByteBuffer data, Optional<BuildConfig> config) {
    String json;
    try {
      json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(data.duplicate())
              .toString();
    } catch (CharacterCodingException e) {
      throw new MalformedBlobException("build configuration is not UTF-8", e);
    }
    ConfigDocument parsed;
    try {
      parsed = ConfigDocument.parse(json);
    } catch (IllegalArgumentException e) {
      throw new MalformedBlobException("build configuration is not a canonicalizable JSON object", e);
    }
    if (!parsed.canonicalJson().equals(json)) {
      throw new MalformedBlobException("build configuration payload is not in canonical form");
    }
    if (recordedDigest(metadata).isEmpty()) {
      throw new MalformedBlobException("build configuration blob records no well-formed " + BlobProperties.CONFIG_DIGEST);
    }
    return parsed;
  }

  /** The digest under which the blob publishes its payload, if the blob records a well-formed one. */
  public static Optional<String> recordedDigest(BlobMetadata metadata) {
    Map<String, String> props = metadata.properties();
    String recorded = props == null ? null : props.get(BlobProperties.CONFIG_DIGEST);
    return BuildConfig.isDigest(recorded) ? Optional.of(recorded) : Optional.empty();
  }

  /** The blob to add to a Puffin file so that blobs in the same file can resolve {@code config} in-band. */
  public static Blob blob(BuildConfig config) {
    return new Blob(
        TYPE,
        List.of(),
        -1L,
        -1L,
        ByteBuffer.wrap(config.canonicalBytes()),
        null,
        Map.of(BlobProperties.CONFIG_DIGEST, config.digest()));
  }
}
