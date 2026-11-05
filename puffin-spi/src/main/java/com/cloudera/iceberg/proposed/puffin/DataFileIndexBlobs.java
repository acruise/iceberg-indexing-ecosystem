package com.cloudera.iceberg.proposed.puffin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.puffin.Blob;
import org.apache.iceberg.puffin.BlobMetadata;
import org.apache.iceberg.puffin.PuffinCompressionCodec;

/**
 * Writes blobs bound to one data file (Section 6.1): snapshot id and sequence number -1, valid for as long as the data
 * file named in {@code referenced-data-file} is live, with the indexed column's field id as the blob's input field.
 */
public final class DataFileIndexBlobs {
  private DataFileIndexBlobs() {}

  public static <T> Blob blob(
      PuffinBlobTypes types, T value, Class<T> valueClass, String dataFileLocation, int fieldId, Optional<ConfigReference> config) {
    return blob(types, value, valueClass, dataFileLocation, fieldId, config, null, Map.of());
  }

  /**
   * @param compression null for an uncompressed payload. Iceberg 1.12.0's Puffin writer implements zstd but not LZ4.
   * @param properties type-specific properties; reserved keys are set from the other arguments and may not appear here
   * @throws IllegalArgumentException if the type is not data-file-scoped, it requires a configuration and none is
   *     given, or {@code properties} contains a reserved key
   */
  public static <T> Blob blob(
      PuffinBlobTypes types,
      T value,
      Class<T> valueClass,
      String dataFileLocation,
      int fieldId,
      Optional<ConfigReference> config,
      PuffinCompressionCodec compression,
      Map<String, String> properties) {
    PuffinBlobType<T> type = types.forClass(valueClass);
    BlobTypeDescriptor d = type.descriptor();
    if (d.scope() != BlobTypeDescriptor.Scope.DATA_FILE) {
      throw new IllegalArgumentException(d.type() + " is not data-file-scoped");
    }
    if (d.config().requiresConfig() && config.isEmpty()) {
      throw new IllegalArgumentException(d.type() + " requires a build configuration reference");
    }
    for (String k : properties.keySet()) {
      if (BlobProperties.RESERVED.contains(k)) {
        throw new IllegalArgumentException("reserved property " + k + " is set by the writer");
      }
    }
    Map<String, String> props = new LinkedHashMap<>(properties);
    props.put(BlobProperties.REFERENCED_DATA_FILE, dataFileLocation);
    config.ifPresent(c -> props.putAll(c.toProperties()));
    return new Blob(type.type(), List.of(fieldId), -1L, -1L, type.serialize(value), compression, props);
  }

  public static Optional<String> referencedDataFile(BlobMetadata metadata) {
    return Optional.ofNullable(metadata.properties()).map(p -> p.get(BlobProperties.REFERENCED_DATA_FILE));
  }

  public static Optional<Integer> fieldId(BlobMetadata metadata) {
    return metadata.inputFields().stream().findFirst();
  }
}
