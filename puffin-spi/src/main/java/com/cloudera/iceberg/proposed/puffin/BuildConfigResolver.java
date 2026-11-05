package com.cloudera.iceberg.proposed.puffin;

import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves blobs' configuration references against the stores a table declares, and applies revocation (Sections
 * 7.3 and 7.4).
 *
 * <p>Table properties:
 *
 * <ul>
 *   <li>{@code puffin.config-store.<name>.impl}: a {@link BuildConfigStore} class, with any further
 *       {@code puffin.config-store.<name>.*} properties passed to its {@code initialize}
 *   <li>{@code puffin.config-store.default}: the store used when a blob names none and its Puffin file holds no
 *       matching in-band configuration
 *   <li>{@code puffin.config.revoked}: comma-separated digests whose blobs readers must not use
 * </ul>
 */
public final class BuildConfigResolver {
  public static final String STORE_PREFIX = "puffin.config-store.";
  public static final String DEFAULT_STORE = STORE_PREFIX + "default";
  public static final String REVOKED = "puffin.config.revoked";
  private static final Pattern STORE_PROPERTY = Pattern.compile(Pattern.quote(STORE_PREFIX) + "([a-z0-9-]+)\\.(.+)");

  private final Map<String, BuildConfigStore> stores;
  private final Optional<String> defaultStore;
  private final Set<String> revoked;

  /** The outcome of resolving one blob's reference. */
  public sealed interface Resolution {}

  /** The blob records no configuration. */
  public record NoReference() implements Resolution {}

  public record Resolved(BuildConfig config) implements Resolution {}

  public record Unresolved(String reason) implements Resolution {}

  public record Revoked(String digest) implements Resolution {}

  public BuildConfigResolver(Map<String, BuildConfigStore> stores, Optional<String> defaultStore, Set<String> revoked) {
    this.stores = Map.copyOf(stores);
    this.defaultStore = defaultStore;
    this.revoked = Set.copyOf(revoked);
    defaultStore.ifPresent(
        d -> {
          if (!this.stores.containsKey(d)) {
            throw new IllegalArgumentException("default configuration store " + d + " is not declared");
          }
        });
  }

  /** In-band configurations only, nothing revoked. */
  public static BuildConfigResolver inBandOnly() {
    return new BuildConfigResolver(Map.of(), Optional.empty(), Set.of());
  }

  /**
   * @throws IllegalArgumentException if a declared store cannot be instantiated or initialized, or the default store is
   *     not declared
   */
  public static BuildConfigResolver fromTableProperties(Map<String, String> properties, ClassLoader loader) {
    Map<String, Map<String, String>> byStore = new LinkedHashMap<>();
    properties.forEach(
        (k, v) -> {
          Matcher m = STORE_PROPERTY.matcher(k);
          if (m.matches()) {
            byStore.computeIfAbsent(m.group(1), n -> new HashMap<>()).put(m.group(2), v);
          }
        });
    Map<String, BuildConfigStore> stores = new LinkedHashMap<>();
    byStore.forEach(
        (name, props) -> {
          String impl = props.get("impl");
          if (impl == null) {
            throw new IllegalArgumentException("configuration store " + name + " has no impl property");
          }
          BuildConfigStore store = instantiate(name, impl, loader);
          Map<String, String> rest = new HashMap<>(props);
          rest.remove("impl");
          store.initialize(name, rest);
          stores.put(name, store);
        });
    Set<String> revoked =
        Arrays.stream(properties.getOrDefault(REVOKED, "").split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    return new BuildConfigResolver(stores, Optional.ofNullable(properties.get(DEFAULT_STORE)), revoked);
  }

  public boolean isRevoked(String digest) {
    return revoked.contains(digest);
  }

  /**
   * Resolves the reference in {@code blobProperties} for a blob whose type declares the configuration schema
   * {@code schema}. A blob that names a store is resolved there; otherwise the in-band store is tried first, then the
   * table's default. Whatever a store returns is read under {@code schema} and checked against the recorded digest,
   * which a document written for another type cannot pass (Section 7.2). A reference on a blob whose type declares no
   * schema is unresolvable, since nothing could verify it. Never throws: store failures and malformed references
   * become {@link Unresolved}.
   */
  public Resolution resolve(Map<String, String> blobProperties, BuildConfigStore inBand, Optional<SchemaDigest> schema) {
    Optional<ConfigReference> ref;
    try {
      ref = ConfigReference.fromProperties(blobProperties);
    } catch (IllegalArgumentException e) {
      return new Unresolved(e.getMessage());
    }
    if (ref.isEmpty()) {
      return new NoReference();
    }
    ConfigReference r = ref.get();
    if (revoked.contains(r.digest())) {
      return new Revoked(r.digest());
    }
    if (schema.isEmpty()) {
      return new Unresolved("the blob's type declares no configuration schema, so " + r.digest() + " cannot be verified");
    }
    SchemaDigest s = schema.get();
    if (r.store().isPresent()) {
      BuildConfigStore named = stores.get(r.store().get());
      if (named == null) {
        return new Unresolved("configuration store " + r.store().get() + " is not declared");
      }
      return lookUp(named, r.store().get(), r, s);
    }
    Resolution local = lookUp(inBand, "in-band", r, s);
    if (local instanceof Resolved || defaultStore.isEmpty()) {
      return local;
    }
    return lookUp(stores.get(defaultStore.get()), defaultStore.get(), r, s);
  }

  private static Resolution lookUp(BuildConfigStore store, String storeName, ConfigReference r, SchemaDigest schema) {
    Optional<ConfigDocument> found;
    try {
      found = store.resolve(r.digest());
      if (found.isEmpty() && r.id().isPresent()) {
        found = store.resolve(r.id().get(), r.version().get());
      }
    } catch (UncheckedIOException | IllegalArgumentException e) {
      return new Unresolved("configuration store " + storeName + " failed: " + e.getMessage());
    }
    if (found.isEmpty()) {
      return new Unresolved("configuration " + r.digest() + " not found in store " + storeName);
    }
    BuildConfig config = found.get().under(schema);
    if (!config.digest().equals(r.digest())) {
      return new Unresolved(
          "configuration store " + storeName + " returned " + config.digest() + " for " + r.digest());
    }
    return new Resolved(config);
  }

  private static BuildConfigStore instantiate(String name, String impl, ClassLoader loader) {
    try {
      Class<?> cls = Class.forName(impl, true, loader);
      return (BuildConfigStore) cls.getDeclaredConstructor().newInstance();
    } catch (ClassNotFoundException
        | ClassCastException
        | NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException e) {
      throw new IllegalArgumentException("cannot instantiate configuration store " + name + " (" + impl + ")", e);
    }
  }
}
