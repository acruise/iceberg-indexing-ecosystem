package com.cloudera.iceberg.proposed.puffin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.puffin.Blob;
import org.apache.iceberg.puffin.BlobMetadata;
import org.apache.iceberg.puffin.Puffin;
import org.apache.iceberg.puffin.PuffinCompressionCodec;
import org.apache.iceberg.puffin.PuffinReader;
import org.apache.iceberg.puffin.PuffinWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.cloudera.acruise.progidx.f16presence.F16Presence;
import com.cloudera.acruise.progidx.f16presence.F16PresenceBlobType;
import com.cloudera.acruise.progidx.ngram.NgramBloom;
import com.cloudera.acruise.progidx.ngram.NgramBloomBlobType;
import com.cloudera.acruise.progidx.ngram.NgramBloomConfig;
import com.cloudera.acruise.progidx.ngram.TextNormalization;

class PuffinBlobTypesTest {
  private static final PuffinBlobTypes TYPES = PuffinBlobTypes.load();
  private static final String FILE_A = "s3://lake/t/data/a.parquet";
  private static final String FILE_B = "s3://lake/t/data/b.parquet";
  private static final NgramBloomConfig NGRAM = NgramBloomConfig.fixed(TextNormalization.NFKC_CASEFOLD, 3, 4, 8192);
  private static final BuildConfig NGRAM_CONFIG = NGRAM.toBuildConfig();
  private static final NgramBloom SKUS = NgramBloom.build(NGRAM, List.of("ABC-100", "ABD-200", "Straße"));

  @TempDir Path dir;

  private static F16Presence temperatures() {
    return F16Presence.ofFloats(new float[] {19.5f, 20.25f, 31.0f});
  }

  private Path write(Blob... blobs) throws IOException {
    Path file = Files.createTempFile(dir, "indexes", ".puffin");
    Files.delete(file);
    try (PuffinWriter w = Puffin.write(org.apache.iceberg.Files.localOutput(file.toFile())).createdBy("progidx test").build()) {
      for (Blob b : blobs) {
        w.add(b);
      }
    }
    return file;
  }

  private static PuffinBlobTypes.ReadResult read(Path file, BuildConfigResolver resolver) throws IOException {
    try (PuffinReader r = Puffin.read(org.apache.iceberg.Files.localInput(file.toFile())).build()) {
      return TYPES.read(r, resolver);
    }
  }

  private static Blob ngramBlob(ConfigReference ref) {
    return DataFileIndexBlobs.blob(TYPES, SKUS, NgramBloom.class, FILE_A, 7, Optional.of(ref));
  }

  private static Blob foreignBlob() {
    return new Blob("org.elsewhere.secret-index-v7", List.of(3), 1L, 1L, ByteBuffer.wrap("opaque".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void serviceLoaderDiscoversEveryType() {
    assertThat(TYPES.types())
        .containsExactlyInAnyOrder(BuildConfigBlobType.TYPE, F16PresenceBlobType.Type(), NgramBloomBlobType.TYPE);
    assertThat(TYPES.forClass(NgramBloom.class).type()).isEqualTo(NgramBloomBlobType.TYPE);
  }

  @Test
  void twoTypesClaimingOneIdentifierAreRejected() {
    assertThatThrownBy(() -> PuffinBlobTypes.of(new NgramBloomBlobType(), new NgramBloomBlobType()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(NgramBloomBlobType.TYPE);
    assertThatThrownBy(() -> PuffinBlobTypes.of().forClass(String.class)).hasMessageContaining("java.lang.String");
  }

  @Test
  void descriptorMatchesTheProposal() {
    String proposal =
        """
        {
          "type": "com.example.ngram-bloom-v1",
          "title": "Character n-gram Bloom filter",
          "definition": "https://example.com/specs/ngram-bloom-v1",
          "scope": "data-file",
          "input-fields": { "count": 1, "types": ["string"] },
          "properties": {},
          "compaction": "merge",
          "merge-precision": "exact",
          "deletes": "conservative",
          "compression": ["none", "zstd"],
          "config": {
            "schema": "https://example.com/specs/ngram-bloom-v1/config.schema.json",
            "schema-digest": "sha-256:c0ffee1a9b8c7d6e5f40312233445566778899aabbccddeeff00112233445566",
            "evaluation": ["normalization", "unicode-version", "gram-length", "hash", "seed", "hash-count"],
            "composition": ["normalization", "unicode-version", "gram-length", "hash", "seed", "hash-count", "bits"]
          }
        }
        """;
    // The proposal's schema-digest is a placeholder; the implementation's is the digest of the schema it ships.
    String expected = proposal.replace("sha-256:c0ffee1a9b8c7d6e5f40312233445566778899aabbccddeeff00112233445566", NgramBloomConfig.SCHEMA_DIGEST.toString());
    String actual = new NgramBloomBlobType().descriptor().toJson().toString();
    assertThat(ConfigDocument.parse(actual)).isEqualTo(ConfigDocument.parse(expected));
  }

  @Test
  void theCompiledInSchemaDigestIsTheDigestOfTheShippedSchema() {
    // Section 9.4: an implementation knows the schema it was built against, by URL and digest.
    assertThat(SchemaDigest.of(NgramBloomConfig.schemaDocument())).isEqualTo(NgramBloomConfig.SCHEMA_DIGEST);
    assertThat(NGRAM_CONFIG.schemaDigest()).isEqualTo(NgramBloomConfig.SCHEMA_DIGEST);
    assertThat(ConfigDocument.parse(NgramBloomConfig.schemaDocument()).member("$id").textValue()).isEqualTo(NgramBloomConfig.SCHEMA_URL);
  }

  @Test
  void aDocumentPublishedForAnotherTypeIsNotBelieved() throws IOException {
    // The same document under another schema has another digest (Section 7.2), so a blob that records the other
    // type's digest finds the in-band document by that digest and fails verification under its own schema.
    SchemaDigest otherSchema = SchemaDigest.of("{\"type\":\"object\"}");
    BuildConfig foreign = NGRAM_CONFIG.document().under(otherSchema);
    assertThat(foreign.digest()).isNotEqualTo(NGRAM_CONFIG.digest());
    Blob mislabelled =
        new Blob(
            NgramBloomBlobType.TYPE,
            List.of(7),
            -1L,
            -1L,
            ByteBuffer.wrap(SKUS.toBytes()),
            null,
            Map.of(BlobProperties.REFERENCED_DATA_FILE, FILE_A, BlobProperties.CONFIG_DIGEST, foreign.digest()));
    PuffinBlobTypes.ReadResult result = read(write(BuildConfigBlobType.blob(foreign), mislabelled), BuildConfigResolver.inBandOnly());
    assertThat(result.decoded()).isEmpty();
    assertThat(result.skipped()).singleElement().satisfies(s -> assertThat(s.reason()).contains("returned " + NGRAM_CONFIG.digest()));
  }

  @Test
  void blobsResolveTheirConfigurationInBand() throws IOException {
    Path file =
        write(
            BuildConfigBlobType.blob(NGRAM_CONFIG),
            ngramBlob(ConfigReference.of(NGRAM_CONFIG)),
            foreignBlob(),
            DataFileIndexBlobs.blob(
                TYPES, temperatures(), F16Presence.class, FILE_B, 3, Optional.empty(), PuffinCompressionCodec.ZSTD, Map.of()));

    PuffinBlobTypes.ReadResult result = read(file, BuildConfigResolver.inBandOnly());

    assertThat(result.values(NgramBloom.class)).containsExactly(SKUS);
    assertThat(result.values(F16Presence.class)).containsExactly(temperatures());
    assertThat(result.skipped()).singleElement().satisfies(s -> assertThat(s.reason()).contains("unknown blob type"));

    PuffinBlobTypes.DecodedBlob ngram =
        result.decoded().stream().filter(d -> d.value() instanceof NgramBloom).findFirst().orElseThrow();
    assertThat(ngram.config()).contains(NGRAM_CONFIG);
    BlobMetadata md = ngram.metadata();
    assertThat(md.snapshotId()).isEqualTo(-1L);
    assertThat(md.sequenceNumber()).isEqualTo(-1L);
    assertThat(DataFileIndexBlobs.referencedDataFile(md)).contains(FILE_A);
    assertThat(DataFileIndexBlobs.fieldId(md)).contains(7);
    assertThat(md.properties()).containsEntry(BlobProperties.CONFIG_DIGEST, NGRAM_CONFIG.digest());
    assertThat(ngram.valueAs(NgramBloom.class).mightEqual("strasse")).isTrue();
  }

  @Test
  void anUnresolvableConfigurationCostsCoverageNotCorrectness() throws IOException {
    Path file =
        write(
            ngramBlob(ConfigReference.of(NGRAM_CONFIG)),
            DataFileIndexBlobs.blob(TYPES, temperatures(), F16Presence.class, FILE_B, 3, Optional.empty()));

    PuffinBlobTypes.ReadResult result = read(file, BuildConfigResolver.inBandOnly());

    assertThat(result.values(NgramBloom.class)).isEmpty();
    assertThat(result.values(F16Presence.class)).hasSize(1);
    assertThat(result.skipped()).singleElement().satisfies(s -> assertThat(s.reason()).contains("not found in store in-band"));
  }

  @Test
  void blobsResolveFromAnOutOfBandStoreDeclaredInTableProperties() throws IOException {
    Path storeDir = dir.resolve("configs");
    new DirectoryBuildConfigStore(storeDir).publish(NGRAM_CONFIG, "com.example.indexing.orders-sku-ngram", "2026-10-06.1");
    Map<String, String> tableProps =
        Map.of(
            "puffin.config-store.shared.impl", DirectoryBuildConfigStore.class.getName(),
            "puffin.config-store.shared.path", storeDir.toString(),
            "puffin.config-store.default", "shared");
    BuildConfigResolver resolver = BuildConfigResolver.fromTableProperties(tableProps, getClass().getClassLoader());

    ConfigReference named = ConfigReference.of(NGRAM_CONFIG).named("com.example.indexing.orders-sku-ngram", "2026-10-06.1");
    Path viaDefault = write(ngramBlob(named));
    Path viaName = write(ngramBlob(named.inStore("shared")));
    Path viaUndeclared = write(ngramBlob(named.inStore("elsewhere")));

    assertThat(read(viaDefault, resolver).values(NgramBloom.class)).containsExactly(SKUS);
    assertThat(read(viaName, resolver).values(NgramBloom.class)).containsExactly(SKUS);
    assertThat(read(viaUndeclared, resolver).skipped())
        .singleElement()
        .satisfies(s -> assertThat(s.reason()).contains("elsewhere is not declared"));
  }

  @Test
  void aStoreThatReturnsTheWrongDocumentIsNotBelieved() throws IOException {
    Path storeDir = dir.resolve("configs");
    new DirectoryBuildConfigStore(storeDir).publish(NGRAM_CONFIG);
    String hex = NGRAM_CONFIG.digest().substring("sha-256:".length());
    Files.writeString(storeDir.resolve("by-digest/sha-256/" + hex + ".json"), "{\"normalization\":\"none\"}");
    BuildConfigResolver resolver =
        new BuildConfigResolver(Map.of("shared", new DirectoryBuildConfigStore(storeDir)), Optional.of("shared"), java.util.Set.of());

    assertThat(read(write(ngramBlob(ConfigReference.of(NGRAM_CONFIG))), resolver).skipped())
        .singleElement()
        .satisfies(s -> assertThat(s.reason()).contains("returned sha-256:"));
  }

  @Test
  void revokedConfigurationsAreNotUsed() throws IOException {
    Path file = write(BuildConfigBlobType.blob(NGRAM_CONFIG), ngramBlob(ConfigReference.of(NGRAM_CONFIG)));
    BuildConfigResolver resolver =
        BuildConfigResolver.fromTableProperties(Map.of(BuildConfigResolver.REVOKED, " " + NGRAM_CONFIG.digest() + " "), getClass().getClassLoader());

    PuffinBlobTypes.ReadResult result = read(file, resolver);
    assertThat(result.decoded()).isEmpty();
    assertThat(result.skipped()).singleElement().satisfies(s -> assertThat(s.reason()).contains("revoked"));
  }

  @Test
  void aPayloadInconsistentWithItsConfigurationIsSkipped() throws IOException {
    BuildConfig bigger = NgramBloomConfig.fixed(TextNormalization.NFKC_CASEFOLD, 3, 4, 16384).toBuildConfig();
    Blob mislabelled =
        new Blob(
            NgramBloomBlobType.TYPE,
            List.of(7),
            -1L,
            -1L,
            ByteBuffer.wrap(SKUS.toBytes()),
            null,
            Map.of(BlobProperties.REFERENCED_DATA_FILE, FILE_A, BlobProperties.CONFIG_DIGEST, bigger.digest()));
    PuffinBlobTypes.ReadResult result = read(write(BuildConfigBlobType.blob(bigger), mislabelled), BuildConfigResolver.inBandOnly());
    assertThat(result.decoded()).isEmpty();
    assertThat(result.skipped()).singleElement().satisfies(s -> assertThat(s.reason()).contains("fixes 16384"));
  }

  @Test
  void writersAndCallersCannotOmitARequiredConfiguration() {
    assertThatThrownBy(() -> DataFileIndexBlobs.blob(TYPES, SKUS, NgramBloom.class, FILE_A, 7, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires a build configuration");
    assertThatThrownBy(
            () ->
                DataFileIndexBlobs.blob(
                    TYPES, SKUS, NgramBloom.class, FILE_A, 7, Optional.of(ConfigReference.of(NGRAM_CONFIG)), null,
                    Map.of(BlobProperties.CONFIG_DIGEST, "sha-256:00")))
        .hasMessageContaining("reserved property");
    assertThatThrownBy(() -> new NgramBloomBlobType().deserialize(null, ByteBuffer.wrap(SKUS.toBytes()), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPublishedNameCannotBeRepointed() {
    DirectoryBuildConfigStore store = new DirectoryBuildConfigStore(dir.resolve("configs"));
    store.publish(NGRAM_CONFIG, "com.example.indexing.orders-sku-ngram", "1");
    store.publish(NGRAM_CONFIG, "com.example.indexing.orders-sku-ngram", "1"); // idempotent
    BuildConfig other = NgramBloomConfig.fixed(TextNormalization.NONE, 3, 4, 8192).toBuildConfig();
    assertThatThrownBy(() -> store.publish(other, "com.example.indexing.orders-sku-ngram", "1"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.resolve("com.example.indexing.orders-sku-ngram", "1")).contains(NGRAM_CONFIG.document());
    assertThat(store.resolve(NGRAM_CONFIG.digest()).map(d -> d.under(NgramBloomConfig.SCHEMA_DIGEST))).contains(NGRAM_CONFIG);
  }

  @Test
  void mergeabilityFollowsTheDescriptorAndTheFill() {
    NgramBloomBlobType type = new NgramBloomBlobType();
    var configs = List.of(Optional.of(NGRAM_CONFIG), Optional.of(NgramBloomConfig.from(NGRAM_CONFIG).toBuildConfig()));
    assertThat(type.descriptor().config().composable(configs)).isTrue();

    BuildConfig adaptive = NgramBloomConfig.adaptive(TextNormalization.NFKC_CASEFOLD, 3, 4, 0.01).toBuildConfig();
    assertThat(type.descriptor().config().composable(List.of(Optional.of(adaptive), Optional.of(adaptive)))).isFalse();

    var context = new MergeablePuffinBlobType.MergeContext(Optional.of(NGRAM_CONFIG));
    assertThat(type.shouldMerge(List.of(SKUS, SKUS), context)).isTrue();
    java.util.Random rng = new java.util.Random(7);
    List<String> many =
        java.util.stream.IntStream.range(0, 5_000).mapToObj(i -> Long.toString(rng.nextLong() & Long.MAX_VALUE, 36)).toList();
    NgramBloom saturated = NgramBloom.build(NGRAM, many);
    assertThat(type.shouldMerge(List.of(saturated, SKUS), context)).isFalse();
  }
}
