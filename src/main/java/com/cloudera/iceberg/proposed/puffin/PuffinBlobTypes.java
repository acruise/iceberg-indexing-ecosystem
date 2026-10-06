package com.cloudera.iceberg.proposed.puffin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.puffin.BlobMetadata;
import org.apache.iceberg.puffin.PuffinReader;
import org.apache.iceberg.util.Pair;

/** The set of Puffin blob types a process understands, keyed by type identifier (Sections 9.1 and 9.2). */
public final class PuffinBlobTypes {
  private static final BuildConfigBlobType BUILD_CONFIG = new BuildConfigBlobType();

  private final Map<String, PuffinBlobType<?>> byType;

  private PuffinBlobTypes(Map<String, PuffinBlobType<?>> byType) {
    this.byType = Map.copyOf(byType);
  }

  /** Types registered with {@link ServiceLoader} and visible to the context class loader. */
  public static PuffinBlobTypes load() {
    return load(Thread.currentThread().getContextClassLoader());
  }

  @SuppressWarnings("rawtypes")
  public static PuffinBlobTypes load(ClassLoader classLoader) {
    List<PuffinBlobType<?>> found = new ArrayList<>();
    for (PuffinBlobType t : ServiceLoader.load(PuffinBlobType.class, classLoader)) {
      found.add(t);
    }
    return of(found.toArray(new PuffinBlobType<?>[0]));
  }

  /**
   * @throws IllegalArgumentException if two types claim the same identifier, since a silent choice would make the
   *     meaning of a payload depend on class-path order, or if a mergeable type's descriptor does not declare merge
   */
  public static PuffinBlobTypes of(PuffinBlobType<?>... types) {
    Map<String, List<PuffinBlobType<?>>> grouped =
        Arrays.stream(types).collect(Collectors.groupingBy(PuffinBlobType::type, LinkedHashMap::new, Collectors.toList()));
    String duplicates =
        grouped.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(e -> e.getKey() + ": " + e.getValue().stream().map(t -> t.getClass().getName()).collect(Collectors.joining(", ")))
            .collect(Collectors.joining("; "));
    if (!duplicates.isEmpty()) {
      throw new IllegalArgumentException("several Puffin blob types claim the same identifier: " + duplicates);
    }
    for (PuffinBlobType<?> t : types) {
      boolean declaresMerge = t.descriptor().compaction() == BlobTypeDescriptor.Compaction.MERGE;
      if (declaresMerge != (t instanceof MergeablePuffinBlobType<?>)) {
        throw new IllegalArgumentException(
            t.type() + " must implement MergeablePuffinBlobType exactly when its descriptor declares merge");
      }
    }
    Map<String, PuffinBlobType<?>> byType = new HashMap<>();
    grouped.forEach((k, v) -> byType.put(k, v.get(0)));
    return new PuffinBlobTypes(byType);
  }

  public PuffinBlobTypes withAdditional(PuffinBlobType<?>... types) {
    return of(Stream.concat(byType.values().stream(), Arrays.stream(types)).toArray(PuffinBlobType<?>[]::new));
  }

  public Set<String> types() {
    return byType.keySet();
  }

  public Optional<PuffinBlobType<?>> forType(String type) {
    return Optional.ofNullable(byType.get(type));
  }

  /**
   * The one type whose decoded representation is {@code cls}.
   *
   * @throws IllegalArgumentException if no type, or more than one, decodes to {@code cls}
   */
  @SuppressWarnings("unchecked")
  public <T> PuffinBlobType<T> forClass(Class<T> cls) {
    List<PuffinBlobType<?>> matches = byType.values().stream().filter(t -> t.javaClass() == cls).toList();
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          (matches.isEmpty() ? "no Puffin blob type for " : "several Puffin blob types for ") + cls.getName());
    }
    return (PuffinBlobType<T>) matches.get(0);
  }

  public record DecodedBlob(BlobMetadata metadata, Object value, Optional<BuildConfig> config) {
    public <T> T valueAs(Class<T> cls) {
      return cls.cast(value);
    }
  }

  /** A blob the reader planned without, and why. */
  public record SkippedBlob(BlobMetadata metadata, String reason) {}

  public record ReadResult(List<DecodedBlob> decoded, List<SkippedBlob> skipped) {
    public <T> List<T> values(Class<T> cls) {
      return decoded.stream().map(DecodedBlob::value).filter(cls::isInstance).map(cls::cast).toList();
    }
  }

  /**
   * Decodes every blob in a Puffin file that this process can use, applying the reader obligations of Section 7.4.
   * In-band configurations are read first and are not themselves reported. A blob is skipped, without its payload
   * being fetched where possible, when its type is unknown, its configuration is revoked, its type requires a
   * configuration that cannot be resolved, or its payload is malformed.
   *
   * @throws java.io.UncheckedIOException if the Puffin file cannot be read
   */
  public ReadResult read(PuffinReader reader, BuildConfigResolver resolver) {
    List<BlobMetadata> all;
    try {
      all = reader.fileMetadata().blobs();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<SkippedBlob> skipped = new ArrayList<>();

    Map<String, BuildConfig> inBandConfigs = new HashMap<>();
    List<BlobMetadata> configBlobs = all.stream().filter(b -> BuildConfigBlobType.TYPE.equals(b.type())).toList();
    for (Pair<BlobMetadata, ByteBuffer> p : reader.readAll(configBlobs)) {
      try {
        BuildConfig c = BUILD_CONFIG.deserialize(p.first(), p.second(), Optional.empty());
        inBandConfigs.put(c.digest(), c);
      } catch (MalformedBlobException e) {
        skipped.add(new SkippedBlob(p.first(), e.getMessage()));
      }
    }
    BuildConfigStore inBand = digest -> Optional.ofNullable(inBandConfigs.get(digest));

    Map<Long, Optional<BuildConfig>> toDecode = new HashMap<>(); // by offset, unique within a file
    for (BlobMetadata b : all) {
      if (BuildConfigBlobType.TYPE.equals(b.type())) {
        continue;
      }
      PuffinBlobType<?> type = byType.get(b.type());
      if (type == null) {
        skipped.add(new SkippedBlob(b, "unknown blob type " + b.type()));
        continue;
      }
      boolean required = type.descriptor().config().requiresConfig();
      switch (resolver.resolve(b.properties(), inBand)) {
        case BuildConfigResolver.Resolved r -> toDecode.put(b.offset(), Optional.of(r.config()));
        case BuildConfigResolver.Revoked r -> skipped.add(new SkippedBlob(b, "configuration " + r.digest() + " is revoked"));
        case BuildConfigResolver.NoReference n -> {
          if (required) {
            skipped.add(new SkippedBlob(b, b.type() + " requires a build configuration and the blob records none"));
          } else {
            toDecode.put(b.offset(), Optional.empty());
          }
        }
        case BuildConfigResolver.Unresolved u -> {
          if (required) {
            skipped.add(new SkippedBlob(b, u.reason()));
          } else {
            toDecode.put(b.offset(), Optional.empty());
          }
        }
      }
    }

    List<BlobMetadata> wanted = all.stream().filter(b -> toDecode.containsKey(b.offset())).toList();
    List<DecodedBlob> decoded = new ArrayList<>();
    for (Pair<BlobMetadata, ByteBuffer> p : reader.readAll(wanted)) {
      BlobMetadata b = p.first();
      Optional<BuildConfig> config = toDecode.get(b.offset());
      try {
        Object value = byType.get(b.type()).deserialize(b, p.second(), config);
        decoded.add(new DecodedBlob(b, value, config));
      } catch (MalformedBlobException e) {
        skipped.add(new SkippedBlob(b, e.getMessage()));
      }
    }
    return new ReadResult(decoded, skipped);
  }
}
