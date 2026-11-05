package com.cloudera.iceberg.proposed.puffin;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves build configurations (Section 7.3). Out-of-band stores are declared in table properties as
 * {@code puffin.config-store.<name>.impl}, instantiated reflectively with a public no-argument constructor, and
 * initialized with the store's other {@code puffin.config-store.<name>.*} properties, prefix removed.
 *
 * <p>A store holds documents, not configurations: it knows nothing of the schemas they are read under, and the digest
 * it is keyed by is the one of Section 7.2, taken over the requesting type's schema digest and the document together.
 * A store promises that the document for a digest, and the document for an identifier and version, never change, and
 * that it keeps every document some live blob refers to. Callers do not rely on the first promise: {@link
 * BuildConfigResolver} reads whatever a store returns under the requesting type's schema and checks the digest.
 *
 * <p>Implementations must be safe for concurrent use. Resolution may throw {@link java.io.UncheckedIOException} when
 * the store cannot be reached; the resolver then treats the configuration as unresolved.
 */
public interface BuildConfigStore {
  default void initialize(String name, Map<String, String> properties) {}

  /** The document published under {@code digest}, or empty if the store does not hold it. */
  Optional<ConfigDocument> resolve(String digest);

  /** The document published under a name and version, or empty if the store does not hold it or keeps no names. */
  default Optional<ConfigDocument> resolve(String configId, String configVersion) {
    return Optional.empty();
  }
}
