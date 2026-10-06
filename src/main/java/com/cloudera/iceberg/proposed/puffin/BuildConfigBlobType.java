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
 * canonical form, stored in the same Puffin file as the blobs that refer to it.
 */
public final class BuildConfigBlobType implements PuffinBlobType<BuildConfig> {
  public static final String TYPE = "build-config-v1";

  private static final BlobTypeDescriptor DESCRIPTOR =
      BlobTypeDescriptor.builder(TYPE)
          .title("Build configuration")
          .definition("https://iceberg.apache.org/puffin-spec/#build-config-v1")
          .scope(BlobTypeDescriptor.Scope.PUFFIN_FILE)
          .inputFields(0)
          .property(BlobProperties.CONFIG_DIGEST, true, "the digest of the payload")
          .compaction(BlobTypeDescriptor.Compaction.RETAIN)
          .deletes(BlobTypeDescriptor.Deletes.CONSERVATIVE)
          .compression("none", "zstd")
          .build();

  @Override
  public BlobTypeDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public Class<BuildConfig> javaClass() {
    return BuildConfig.class;
  }

  @Override
  public ByteBuffer serialize(BuildConfig value) {
    return ByteBuffer.wrap(value.canonicalBytes());
  }

  /** The payload must be exactly the canonical form, and its digest must be the one the blob's properties record. */
  @Override
  public BuildConfig deserialize(BlobMetadata metadata, ByteBuffer data, Optional<BuildConfig> config) {
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
    BuildConfig parsed;
    try {
      parsed = BuildConfig.parse(json);
    } catch (IllegalArgumentException e) {
      throw new MalformedBlobException("build configuration is not a canonicalizable JSON object", e);
    }
    if (!parsed.canonicalJson().equals(json)) {
      throw new MalformedBlobException("build configuration payload is not in canonical form");
    }
    Map<String, String> props = metadata.properties();
    String recorded = props == null ? null : props.get(BlobProperties.CONFIG_DIGEST);
    if (!parsed.digest().equals(recorded)) {
      throw new MalformedBlobException("build configuration digest " + parsed.digest() + " recorded as " + recorded);
    }
    return parsed;
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
