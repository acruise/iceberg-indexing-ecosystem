package com.cloudera.iceberg.proposed.puffin;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves build configurations (Section 7.3). Out-of-band stores are declared in table properties as
 * {@code puffin.config-store.<name>.impl}, instantiated reflectively with a public no-argument constructor, and
 * initialized with the store's other {@code puffin.config-store.<name>.*} properties, prefix removed.
 *
 * <p>A store promises that the document for a digest, and the document for an identifier and version, never change,
 * and that it keeps every document some live blob refers to. Callers do not rely on the first promise: {@link
 * BuildConfigResolver} checks the digest of whatever a store returns.
 *
 * <p>Implementations must be safe for concurrent use. Resolution may throw {@link java.io.UncheckedIOException} when
 * the store cannot be reached; the resolver then treats the configuration as unresolved.
 */
public interface BuildConfigStore {
  default void initialize(String name, Map<String, String> properties) {}

  /** The document whose digest is {@code digest}, or empty if the store does not hold it. */
  Optional<BuildConfig> resolve(String digest);

  /** The document published under a name and version, or empty if the store does not hold it or keeps no names. */
  default Optional<BuildConfig> resolve(String configId, String configVersion) {
    return Optional.empty();
  }
}
