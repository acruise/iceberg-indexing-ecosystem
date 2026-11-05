package com.cloudera.iceberg.proposed.puffin;

import java.nio.ByteBuffer;
import java.util.Optional;
import org.apache.iceberg.puffin.BlobMetadata;

/**
 * Encodes and decodes the payloads of one Puffin blob type (Section 9.1).
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader} from
 * {@code META-INF/services/com.cloudera.iceberg.proposed.puffin.PuffinBlobType}; see {@link PuffinBlobTypes}. They need a public no-argument
 * constructor and must be safe for concurrent use.
 *
 * @param <T> the decoded representation
 */
public interface PuffinBlobType<T> {
  /** The identifier written as the blob's type, e.g. {@code com.example.ngram-bloom-v1}. */
  default String type() {
    return descriptor().type();
  }

  /** The descriptor of Section 5, as published in the registry. Must return the same value on every call. */
  BlobTypeDescriptor descriptor();

  /** The decoded representation. */
  Class<T> javaClass();

  /**
   * Encodes a value as an uncompressed payload.
   *
   * @throws IllegalArgumentException if this type cannot represent {@code value}
   */
  ByteBuffer serialize(T value);

  /**
   * Decodes one payload of this type.
   *
   * <p>Implementations must not throw for any reason other than those listed below. Any other runtime exception is
   * treated as a bug in the implementation and propagates to the caller of {@link PuffinBlobTypes#read}.
   *
   * @param metadata the blob's footer entry, for its input fields and properties
   * @param data the decompressed payload, from its position to its limit. Implementations may read it but must not
   *     modify its contents, and must not retain it after returning, since the reader may reuse the buffer.
   * @param config the blob's build configuration, already verified against its {@code config-digest}. Empty when the
   *     blob records no configuration, or when its configuration could not be resolved and the descriptor declares no
   *     evaluation parameters.
   * @return the decoded value, never null
   * @throws MalformedBlobException if the payload or the blob's properties do not conform to the type's definition:
   *     for example a truncated payload, an unsupported payload format version, a configuration that does not satisfy
   *     the type's configuration schema, or a payload inconsistent with its configuration. {@link PuffinBlobTypes#read}
   *     reports such a blob as skipped and plans without it, so the failure costs coverage but never correctness.
   * @throws IllegalArgumentException if {@code config} is empty although the descriptor declares evaluation
   *     parameters. {@link PuffinBlobTypes#read} never makes such a call, so this indicates a caller error.
   */
  T deserialize(BlobMetadata metadata, ByteBuffer data, Optional<BuildConfig> config);
}
