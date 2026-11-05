package com.cloudera.iceberg.proposed.puffin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A blob's reference to its build configuration, as recorded in the reserved properties of Section 7.2. The digest is
 * the configuration's identity; the identifier and version are names a store may also know it by.
 */
public record ConfigReference(String digest, Optional<String> id, Optional<String> version, Optional<String> store) {
  public ConfigReference {
    if (!BuildConfig.isDigest(digest)) {
      throw new IllegalArgumentException("not a supported configuration digest: " + digest);
    }
    if (id.isPresent() != version.isPresent()) {
      throw new IllegalArgumentException("config-id and config-version must be given together");
    }
  }

  public static ConfigReference of(BuildConfig config) {
    return new ConfigReference(config.digest(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  public ConfigReference named(String configId, String configVersion) {
    return new ConfigReference(digest, Optional.of(configId), Optional.of(configVersion), store);
  }

  public ConfigReference inStore(String storeName) {
    return new ConfigReference(digest, id, version, Optional.of(storeName));
  }

  public Map<String, String> toProperties() {
    Map<String, String> props = new LinkedHashMap<>();
    props.put(BlobProperties.CONFIG_DIGEST, digest);
    id.ifPresent(v -> props.put(BlobProperties.CONFIG_ID, v));
    version.ifPresent(v -> props.put(BlobProperties.CONFIG_VERSION, v));
    store.ifPresent(v -> props.put(BlobProperties.CONFIG_STORE, v));
    return props;
  }

  /**
   * The reference recorded in a blob's properties, or empty if it records none.
   *
   * @throws IllegalArgumentException if configuration properties are present but do not form a valid reference, for
   *     instance a {@code config-id} without a {@code config-digest}
   */
  public static Optional<ConfigReference> fromProperties(Map<String, String> properties) {
    if (properties == null) {
      return Optional.empty();
    }
    String digest = properties.get(BlobProperties.CONFIG_DIGEST);
    Optional<String> id = Optional.ofNullable(properties.get(BlobProperties.CONFIG_ID));
    Optional<String> version = Optional.ofNullable(properties.get(BlobProperties.CONFIG_VERSION));
    Optional<String> store = Optional.ofNullable(properties.get(BlobProperties.CONFIG_STORE));
    if (digest == null) {
      if (id.isPresent() || version.isPresent() || store.isPresent()) {
        throw new IllegalArgumentException("configuration reference without " + BlobProperties.CONFIG_DIGEST);
      }
      return Optional.empty();
    }
    return Optional.of(new ConfigReference(digest, id, version, store));
  }
}
