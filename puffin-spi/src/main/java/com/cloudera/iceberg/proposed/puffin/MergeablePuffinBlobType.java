package com.cloudera.iceberg.proposed.puffin;

import java.util.List;
import java.util.Optional;

/**
 * A blob type whose descriptor declares {@code compaction: merge}: the blob for a rewrite's output can be composed from
 * its sources' blobs without reading data (Section 8.3).
 */
public interface MergeablePuffinBlobType<T> extends PuffinBlobType<T> {
  /**
   * The writer's view of one output: the configuration the composed blob will carry, which the sources agree on in
   * every key the descriptor lists under {@code composition}.
   */
  record MergeContext(Optional<BuildConfig> config) {}

  /**
   * Whether composing is worthwhile, given that it is permitted. Returning false makes the writer rebuild the output's
   * blob from data instead, e.g. when the composed blob would be too unselective to be worth storing.
   */
  default boolean shouldMerge(List<T> sources, MergeContext context) {
    return true;
  }

  /**
   * Composes the blobs of a rewrite's sources into the blob for one output. Exact or conservative, as the descriptor's
   * {@code merge-precision} declares.
   *
   * @throws IllegalArgumentException if the sources cannot be composed, which the composition check of the descriptor
   *     should have prevented
   */
  T merge(List<T> sources, MergeContext context);
}
