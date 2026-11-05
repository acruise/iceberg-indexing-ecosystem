package com.cloudera.iceberg.proposed.puffin;

import java.util.Set;

/**
 * Blob property keys reserved by the proposal (Sections 4.2 and 7.2): un-namespaced, and with the same meaning for every
 * blob type that uses them.
 */
public final class BlobProperties {
  /** The location of the one data file a data-file-scoped blob describes, as written in the table's manifests. */
  public static final String REFERENCED_DATA_FILE = "referenced-data-file";

  /** The digest of the blob's build configuration, e.g. {@code sha-256:3a7b...}. Authoritative when present. */
  public static final String CONFIG_DIGEST = "config-digest";

  /** A human-oriented name for the build configuration; optional, and always paired with {@link #CONFIG_VERSION}. */
  public static final String CONFIG_ID = "config-id";

  public static final String CONFIG_VERSION = "config-version";

  /** The name of the store, declared in table properties, from which to resolve the configuration. */
  public static final String CONFIG_STORE = "config-store";

  public static final Set<String> RESERVED =
      Set.of(REFERENCED_DATA_FILE, CONFIG_DIGEST, CONFIG_ID, CONFIG_VERSION, CONFIG_STORE);

  private BlobProperties() {}
}
