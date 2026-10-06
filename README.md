# progressive-indexing

This repository explores *progressive indexing* for open table formats, Apache Iceberg in particular: deferring the construction of secondary indexes from write time to background workers that choose which data files to index, and with which index kinds, according to how the table is being read and how soon each file is likely to be rewritten by compaction.

## Motivation

A data lake has to favor write throughput, which rules out building heavy indexes synchronously with ingest, and yet the read side still benefits from indexes finer-grained than the file-level min/max statistics that every lake format records for free. Write-time indexing is also expensive precisely where it does least good, since most freshly written files are small and will be consumed by the next round of compaction long before an index on them has paid for itself. A background indexer can instead spend its budget on files that are likely to be queried and unlikely to be compacted away soon, and it can carry an index forward through compaction when the index kind permits composition (two presence bitmaps merge by OR, for example, without rereading any data).

A second argument concerns the workload rather than the cost. An index chosen at write time is a bet on which columns and which predicate shapes queries will use, and workloads drift: a new column becomes hot, a column queried by equality starts being queried by range, or an incident sends forensic lookups against old data on a column nobody had indexed. Deferring the choice, and revisiting it as access statistics change, lets the index set follow the workload rather than a forecast of it. The simulator in this repository models both arguments, and its reports separate the cost of indexing from how well each strategy recovers after the workload shifts.

## Contents

The code falls into four packages. Everything proposed for inclusion in Iceberg is written in Java, in a package under `com.cloudera.iceberg.proposed`, and this project's own code lives under `com.cloudera.acruise.progidx`, following Iceberg's own conventions, and the simulator and the f16 filter, which are this project's own, are written in Scala 2.13.

`com.cloudera.acruise.progidx.f16presence` contains `F16Presence`, a mergeable presence filter that records which half-precision (f16) codes occur in a column. Values are mapped through an order-preserving quantizer (`F16Code`), so the filter answers both equality and range predicates without false negatives; it occupies at most 8 KiB, stored as a sorted array while sparse and as a 65,536-bit bitmap once dense, and two filters merge by OR. Its precision is that of f16 (roughly 0.1% relative, with every magnitude above 65504 collapsing to infinity), so columns of large-magnitude values such as timestamps or identifiers would need a different monotone quantizer.

`com.cloudera.acruise.progidx.ngram` contains, in Java, `NgramBloom`, the character n-gram Bloom filter used as the worked example of build configurations in the proposal (Section 7.6). It normalizes values with ICU4J (none, NFC, NFKC, or NFKC_Casefold for caseless matching), frames them with start and end markers, and hashes overlapping grams with XXH64. It answers equality, prefix and substring predicates either about the stored values or about their normalized forms; for the former it trims pattern literals to normalization boundaries, since a combining mark in a stored value can compose across the edge of a literal. Its configuration (normalization, Unicode version, gram length, hash, seed, hash count, and either a fixed bit count or a target false-positive rate) is recorded with every blob, and a filter whose normalization depends on Unicode data other than the reader's is never used to exclude a file.

`com.cloudera.iceberg.proposed.puffin` is the Java prototype of the interfaces the proposal offers to Iceberg, named so that moving it upstream amounts to replacing the `com.cloudera.iceberg.proposed` prefix with `org.apache.iceberg`. `PuffinBlobType` encodes and decodes one blob type and exposes its descriptor; implementations are discovered through `java.util.ServiceLoader` (see `src/main/resources/META-INF/services/com.cloudera.iceberg.proposed.puffin.PuffinBlobType`), and `MergeablePuffinBlobType` adds composition for compaction. `BuildConfig` identifies a build configuration by the SHA-256 digest of its RFC 8785 canonical JSON; blobs refer to it through reserved properties, and `BuildConfigResolver` resolves those references from `build-config-v1` blobs in the same Puffin file or from stores declared in table properties, verifying digests and honoring revocation. `PuffinBlobTypes.read` decodes the blobs of a Puffin file that a reader can safely use and reports the rest with reasons, and `DataFileIndexBlobs` writes blobs bound to a single data file, following the convention Iceberg already uses for deletion vectors (`snapshot-id` and `sequence-number` of -1, with the data file named in the `referenced-data-file` property). Its dependencies are `iceberg-core` 1.12.0 and the Jackson it brings; ICU4J is needed only by the n-gram filter.

`com.cloudera.acruise.progidx.sim` is a discrete-time simulator of one table under continuous ingest, size-tiered compaction with a final settle step per time partition, retention, and a query workload with optional scheduled shifts. It compares six index schedulers:

| Scheduler | Strategy |
|---|---|
| `none` | No indexes; min/max pruning only. |
| `eager-at-write` | Index every written file, synchronously, on the write path. |
| `generation>=1` | Index in the background once a file has survived at least one compaction. |
| `finalized-only` | Index only files that compaction will never touch again. |
| `survival-weighted` | Rank candidate (file, column, kind) tasks by expected pruning benefit over the file's predicted remaining life, net of build and commit cost, and spend the background budget in that order. |
| `hybrid` | A cheap mergeable index (f16 by default) at write time on every column that receives queries, as a hedge against drift, with survival-weighted background indexing on top. |

Two index kinds are modeled: a Bloom filter, which serves equality predicates only, and the f16 presence filter, which serves equality and range predicates and is mergeable through compaction. Built indexes become visible to queries only when a batch of them is flushed into a Puffin file, which models the metadata commit, and the simulator accounts separately for that commit cost, for work lost when a file dies before its index is finished, and for the rewriting of Puffin files whose blobs have mostly gone dead.

## Building and running

The build uses sbt 2.0 and Java 21; `.sdkmanrc` pins the JDK for SDKMAN users. Scala suites use MUnit and Java suites use JUnit with AssertJ, and `sbt testFull` runs both.

```
sbt testFull          # run every suite (sbt 2's `test` runs only suites affected by changes)
sbt run               # run both scenarios at the default background budget of 4000 per tick
sbt "run 1000 4000"   # sweep several background budgets
```

`sbt run` simulates two scenarios, each with Bloom filters alone, f16 alone, and both together. The *steady* scenario rotates column popularity daily. The *drift* scenario disables rotation and instead applies three scheduled shifts over three simulated days: `order_id` becomes a hot equality column, `status` switches from equality to range predicates, and a six-hour burst of lookups on `trace_id` (a column that had never been queried) targets data one to two days old. A complete run takes several minutes per budget.

For each configuration the report gives, per scheduler, total query cost relative to min/max pruning alone, the build work spent in the background and on the write path, roll-forward and Puffin rewrite work, and the number of index commits. It then classifies the rows that min/max could not prune, although nothing in them matched the predicate, into four shares: *pruned* (an index excluded the file), *fp* (an applicable index was probed but could not exclude it), *mismatch* (indexes existed on the column but none could evaluate the predicate's shape) and *missing* (no visible index on the column). The drift scenario adds a timeline of the pruned share in six-hour buckets and, for each shift, the pruned share in its first six hours and the time until half of the prunable rows are being pruned.

## Documents

[`docs/proposals/puffin-blob-types-and-compaction.md`](docs/proposals/puffin-blob-types-and-compaction.md) is a discussion draft for the Iceberg community. It proposes a namespace and registry for Puffin blob type identifiers, a machine-readable descriptor for each type, a general notion of data-file-scoped blobs, references from each blob to the configuration under which it was built (resolvable from a store inside the Puffin file or outside the table), candidate specification text for how compaction treats them, and a Java service-provider interface of which `com.cloudera.iceberg.proposed.puffin` is the prototype.

## Status

This is research code. The simulator's cost model is deliberately coarse (one tick stands for roughly a minute and rows are counted in thousands), its purpose is to compare strategies under the same workload rather than to predict absolute costs, and nothing here has been run against a real Iceberg table beyond the Puffin round-trip tests.
