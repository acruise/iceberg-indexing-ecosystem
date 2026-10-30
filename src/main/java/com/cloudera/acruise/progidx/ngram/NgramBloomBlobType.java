package com.cloudera.acruise.progidx.ngram;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import org.apache.iceberg.puffin.BlobMetadata;
import com.cloudera.iceberg.proposed.puffin.BlobTypeDescriptor;
import com.cloudera.iceberg.proposed.puffin.BlobTypeDescriptor.ConfigSpec;
import com.cloudera.iceberg.proposed.puffin.BuildConfig;
import com.cloudera.iceberg.proposed.puffin.MalformedBlobException;
import com.cloudera.iceberg.proposed.puffin.MergeablePuffinBlobType;

/** Puffin blobs of type {@code com.example.ngram-bloom-v1}, the descriptor of proposal Section 7.6. */
public final class NgramBloomBlobType implements MergeablePuffinBlobType<NgramBloom> {
  public static final String TYPE = "com.example.ngram-bloom-v1";

  /** Above this estimated false-positive rate a composed filter is not worth keeping, and the writer rebuilds. */
  public static final double MAX_MERGED_FPP = 0.05;

  private static final List<String> EVALUATION =
      List.of("normalization", "unicode-version", "gram-length", "hash", "seed", "hash-count");

  private static final BlobTypeDescriptor DESCRIPTOR =
      BlobTypeDescriptor.builder(TYPE)
          .title("Character n-gram Bloom filter")
          .definition("https://example.com/specs/ngram-bloom-v1")
          .scope(BlobTypeDescriptor.Scope.DATA_FILE)
          .inputFields(1, "string")
          .merge(BlobTypeDescriptor.MergePrecision.EXACT)
          .deletes(BlobTypeDescriptor.Deletes.CONSERVATIVE)
          .compression("none", "zstd")
          .config(
              ConfigSpec.of(NgramBloomConfig.SCHEMA_URL, NgramBloomConfig.SCHEMA_DIGEST, EVALUATION, concat(EVALUATION, "bits")))
          .build();

  @Override
  public BlobTypeDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public Class<NgramBloom> javaClass() {
    return NgramBloom.class;
  }

  @Override
  public ByteBuffer serialize(NgramBloom value) {
    return ByteBuffer.wrap(value.toBytes());
  }

  @Override
  public NgramBloom deserialize(BlobMetadata metadata, ByteBuffer data, Optional<BuildConfig> config) {
    BuildConfig c = config.orElseThrow(() -> new IllegalArgumentException(TYPE + " requires a build configuration"));
    if (metadata.inputFields().size() != 1) {
      throw new MalformedBlobException(TYPE + " records exactly one input field, not " + metadata.inputFields().size());
    }
    NgramBloomConfig parsed;
    try {
      parsed = NgramBloomConfig.from(c);
    } catch (IllegalArgumentException e) {
      throw new MalformedBlobException("configuration " + c.digest() + " is not a valid " + TYPE + " configuration", e);
    }
    try {
      return NgramBloom.fromBytes(data, parsed);
    } catch (IllegalArgumentException e) {
      throw new MalformedBlobException(e.getMessage(), e);
    }
  }

  @Override
  public boolean shouldMerge(List<NgramBloom> sources, MergeContext context) {
    return NgramBloom.merge(sources).estimatedFalsePositiveRate() <= MAX_MERGED_FPP;
  }

  @Override
  public NgramBloom merge(List<NgramBloom> sources, MergeContext context) {
    return NgramBloom.merge(sources);
  }

  private static List<String> concat(List<String> a, String b) {
    return java.util.stream.Stream.concat(a.stream(), java.util.stream.Stream.of(b)).toList();
  }
}
