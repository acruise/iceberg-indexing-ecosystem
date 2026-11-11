# How Other Table Formats Provide What the Puffin Proposals Offer

*For each benefit that the [proposal for pluggable Puffin blob types](puffin-blob-types-and-compaction.md) and its companion notes aim to provide, what Delta Lake, Apache Hudi, Apache Paimon, Lance, DuckLake and Hive already do. The document stands on its own, restating each benefit briefly. A companion document, [how other table formats handle the problems in Iceberg's contracts](../contracts/prior-art.md), covers the problems in the contracts note, and Section 13 of the proposal describes related systems outside the table formats (Pinot, Lucene, ClickHouse, PostgreSQL and the managed warehouses).*

**Status:** draft, November 11, 2026. Every claim is cited to a primary source pinned to a commit read on that date (listed under "Sources" at the end). A claim marked *protocol* is normative text in the system's specification; *implementation* means the reference implementation or its documentation only; *proposal* means an RFC or open pull request, with its status.

## The benefits

The proposals aim to make it possible for parties outside the Iceberg project to add auxiliary structures, indexes chief among them, that engines can discover and use safely and that maintenance tools which did not write them can keep. That aim decomposes into thirteen benefits:

1. **B1. Namespaced, versioned identifiers** that third parties can mint without collision, with a registry.
2. **B2. A machine-readable descriptor per type**, declaring scope, what a reader may conclude, and behavior under deletes and compaction.
3. **B3. Safe ignorance.** A reader that does not understand a structure may ignore it without risk to correctness.
4. **B4. Recorded build configuration**, verifiable by readers, with revocation of configurations later found defective.
5. **B5. Binding to a data file**, so that an index stays valid across every snapshot in which its file is live.
6. **B6. Late, partial indexing.** Indexes built after the data, by any process, with readers that know what is covered.
7. **B7. A defined lifecycle under compaction**: drop, rebuild or merge, atomic with the rewrite and reported.
8. **B8. Maintenance that knows about index files**, and a way to fence maintenance that does not.
9. **B9. Run-time pluggable implementations.**
10. **B10. Indexes over expressions.**
11. **B11. A distinction between locating and excluding indexes**, with validity checks before a locating index is trusted.
12. **B12. Workload telemetry** that informs which indexes to build.
13. **B13. An extension contract carried in the format**, so that implementations in several languages can share it.

## Summary

| Benefit | Delta | Hudi | Paimon | Lance | DuckLake | Hive |
|---|---|---|---|---|---|---|
| B1 Namespaced identifiers | partial | partial | — | protocol | — | — |
| B2 Machine-readable descriptor | — | partial | — | partial | — | — |
| B3 Safe ignorance | protocol | — | protocol | protocol | n/a | — |
| B4 Recorded, verifiable configuration | — | partial | partial | partial | — | partial |
| B5 Binding to a data file | — | protocol | protocol | partial | partial | — |
| B6 Late, partial indexing | proposal | protocol | implementation | protocol | — | removed |
| B7 Lifecycle under compaction | proposal | protocol | implementation | protocol | — | removed |
| B8 Index-aware maintenance, fencing | protocol | protocol | protocol | implementation | — | — |
| B9 Run-time pluggability | — | partial | implementation | — | — | removed |
| B10 Indexes over expressions | partial | protocol | partial | partial | — | — |
| B11 Locating against excluding | proposal | partial | implementation | protocol | — | — |
| B12 Workload telemetry | vendor | — | — | — | — | — |
| B13 Contract in the format | partial | partial | — | partial | — | — |

"Removed" marks a feature Hive had and withdrew in release 3.0. "Vendor" marks a feature available only in a commercial distribution. A dash means nothing was found, which is not proof that nothing exists.

The table supports three observations, and the sections below give the evidence.

- **Late, partial indexing is established practice.** Paimon builds file indexes for existing files without rewriting them, Hudi indexes asynchronously while writers continue, and Lance lets index segments cover any subset of fragments and splits each query into an indexed part and a scan. The operating model of the proposal's Section 2.3 needs no defending on feasibility.
- **Binding indexes to files, and defining what compaction does to them, is solved per system and per index kind.** Paimon attaches a file's index to its manifest entry; Hudi and Lance update their indexes within the commit that rewrites the data. Each rule is written into the system for its own index kinds, which is the situation the proposal's per-type declaration of compaction behavior is meant to end.
- **Nobody offers an open, declared, cross-language extension point for index types.** The closest are Lance's namespaced type URLs and Delta's naming convention for metadata domains, and neither comes with a machine-readable descriptor, recorded build configuration or revocation. Hudi's index types are a closed list, Paimon's are flat strings resolved arbitrarily when they collide, and Lance's plugins are compiled-in Rust. The proposal's Sections 4, 5 and 7 have no complete precedent among table formats.

## B1. Namespaced, versioned identifiers

- **Lance** (protocol). Index types are identified by the protobuf type URL of their details message, such as `/lance.table.BTreeIndexDetails`, which is namespaced by package, and configuration keys prefixed `lance.` are reserved ([`table.proto`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/protos/table.proto)). The implementation then derives a short plugin name from the last segment alone, lowercased, and rejects collisions ([`registry.rs`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/rust/lance-index/src/registry.rs)), so the namespace does not survive into the registry.
- **Delta** (partial, protocol). Metadata domains prefixed `delta.` are reserved, and others are "encouraged to use a naming convention … (e.g. `com.databricks.*` or `org.apache.*`)" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#domain-metadata)). Table feature names are flat, with a `-dev` suffix for experimental ones until their RFC is accepted ([RFC process](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/protocol_rfcs/README.md)).
- **Hudi** (partial). Index types are a closed list. Record mergers are identified by UUIDs stored in table configuration, so that a table names its merger independently of any language's class names ([record merger](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/docs/record_merger.md)).
- **Paimon.** File index, global index and format identifiers are flat strings, and when two providers register one name the loader warns "choose one of them" and picks arbitrarily ([`FileIndexerFactoryUtils.java`](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/paimon-common/src/main/java/org/apache/paimon/fileindex/FileIndexerFactoryUtils.java)).

**For the proposal.** Paimon's arbitrary resolution of duplicate names is the collision that Section 4 exists to prevent, observed in a shipping system. Delta's naming convention and Lance's package-qualified URLs are the nearest precedents for the identifier grammar.

## B2. A machine-readable descriptor per type

No system publishes a machine-readable declaration of an index type's behavior.

- **Lance** (partial). Each index type has a specification page and a details message, both for human readers.
- **Hudi** (partial, protocol). Each index *instance* has a JSON definition giving `indexType`, `indexFunction`, `version`, `sourceFields` and `indexOptions`, and the specification warns that `indexFunction` on non-expression indexes depends "on the code path that registered the definition … Use `indexType` to identify an index" ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)). The definition describes an instance, not a type.

**For the proposal.** Section 5's descriptor has no precedent among table formats. The ClickHouse and Pinot entries in the proposal's Section 13 are the nearest outside them.

## B3. Safe ignorance

- **Delta** (protocol). "Readers are not required to support domain metadata", and a domain that "imposes any requirements on readers is a breaking change, and must be part of a reader-writer table feature" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#reader-requirements-for-domain-metadata)). The proposed record-level index is accordingly writer-only, and "readers are unaffected" ([RFC, delta-io/delta#7436](https://github.com/delta-io/delta/pull/7436)).
- **Lance** (protocol). "If the engine does not recognize the type, it should skip this index segment" and scan instead ([index specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/index/index.md)).
- **Paimon** (protocol). An unknown file index type fails by default, and `file-index.read.ignore-unknown-type` makes it skip the index, which then filters nothing ([file index specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/fileindex.mdx)).
- **Hudi.** An unknown metadata partition type throws (`MetadataPartitionType.java` at [Hudi master](https://github.com/apache/hudi/blob/9a7728c5862a8939f58f47c35abb1d838fc2dcb3/)), so unknown index types are not ignorable.

**For the proposal.** Delta's rule is the clearest statement of the principle the proposal's derivation rule enforces: anything a reader may ignore must carry no reader requirement, and anything that does must be declared as a feature. Paimon reaches safe ignorance only by configuration, which the proposal's Section 13.1 already discusses.

## B4. Recorded, verifiable build configuration

No system verifies a configuration by digest or revokes one.

- **Lance** (partial). Build parameters are stored in each index's details: metric, quantization and graph parameters for vector indexes, tokenizer configuration for full-text, rows per zone for zone maps ([`index.proto`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/protos/index.proto)). Nothing checks them.
- **Hudi** (partial). `indexOptions` are part of each index definition. Revocation means dropping the index; since 1.2.0, disabling an index in the write configuration drops it by default ([metadata indexing](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/docs/metadata_indexing.md)).
- **Paimon** (partial). A Bloom filter payload records only its number of hash functions and its bits, so the false positive rate and expected item count it was sized for are lost ([file index specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/fileindex.mdx)). Global indexes store tokenizer configuration with each index file.
- **Hive** (partial, removed). Index properties were stored in the metastore ([index design](https://hive.apache.org/development/designdocs/indexdev/)).

**For the proposal.** Section 7's configuration references, digests and revocation are without precedent here. Paimon's Bloom filter, which keeps the parameters needed to evaluate it and discards those needed to reason about it, illustrates the proposal's distinction between evaluation and provenance parameters.

## B5. Binding to a data file

- **Paimon** (protocol). A file's index is embedded in its manifest entry when small, or listed among the entry's associated files, so it lives exactly as long as the file's entry ([manifest specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/manifest.md)). This is the closest precedent for the proposal's Section 6.
- **Hudi** (protocol). Column statistics and Bloom filters in the metadata table are keyed by hashes of the partition and file name ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)), which binds them to a file by name.
- **Lance** (partial, protocol). Index segments record the fragments they cover in a `fragment_bitmap`, copied into the index metadata "since the old version may be deleted while the index is still in use", together with the dataset version they were built at ([`table.proto`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/protos/table.proto)). Coverage is bound to fragments, not to individual files.
- **DuckLake** (partial). The nearest slot is the per-file, per-column `extra_stats`, holding type-specific statistics as JSON ([file column statistics](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/specification/tables/ducklake_file_column_stats.md)). DuckLake lists indexes as unlikely to be supported, though its 2.0 roadmap mentions Bloom filters "If Time Permits".

## B6. Late, partial indexing

- **Paimon** (implementation). `rewrite_file_index` builds "indexes for existing data without rewriting the data files", committing a replacement manifest entry under the ordinary conflict checks, and a `file_indexes` system table shows coverage ([index procedures](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/spark/procedures/indexes.md); [system tables](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/system-tables.mdx)). Changing a table's index options "does not add indexes to existing files", so the procedure is the only route to coverage of old data.
- **Hudi** (protocol). An asynchronous indexer plans under the lock, builds while writers continue, and then "checks all commits that completed after the plan's base instant time … to confirm each one wrote the expected entries … if any did not, the indexer aborts gracefully" ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)). The Spark reader treats unindexed files as candidates (`SparkBaseIndexSupport.scala` at [Hudi master](https://github.com/apache/hudi/blob/9a7728c5862a8939f58f47c35abb1d838fc2dcb3/)).
- **Lance** (protocol). "Index segments together **do not** need to cover all fragments", engines split a query into an indexed part and a scan of the rest, and coverage is reported as a count of unindexed rows ([index specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/index/index.md); [indexing guide](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/guide/indexing.md)).
- **Delta** (proposal). The record-level index RFC, open since August 12, 2026. Delta's clustering already accepts partial coverage: a "Writer is not required to cluster a specific file at any specific moment" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#writer-requirements-for-clustered-table)).
- **Hive** (removed). `WITH DEFERRED REBUILD` created an index "initially empty", built later by an explicit rebuild ([index design](https://hive.apache.org/development/designdocs/indexdev/)).

**For the proposal.** Hudi's cross-check after an asynchronous build is a validation rule for metadata-only commits, the problem of the proposal's Section 8.6, solved by checking every commit since the plan.

## B7. A defined lifecycle under compaction

- **Hudi** (protocol). Table services, compaction and clustering included, update the metadata table and its indexes under the same lock as writers, before the completed instant is published ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Lance** (protocol). Compaction remaps every index by default. Alternatively a fragment reuse index publishes the mapping from old addresses to new "in the same commit" as the rewrite, and readers translate addresses at load ([fragment reuse index](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/index/system/frag_reuse.md)).
- **Paimon** (implementation). Compaction output is written by the ordinary writers, so it gets indexes for the table's current options: in the proposal's terms, every type is rebuilt and none is merged ([query performance](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/append-table/query-performance.md)). For primary-key global indexes, "Data-file and index-file changes are committed in the same snapshot", stale payloads are rejected, and a rebuild replaces the old group atomically ([global index](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/primary-key-table/global-index.mdx)).
- **Delta** (proposal). The record-level index RFC requires "the writer performing the rewrite must, in the same commit, emit index entries that re-point the affected keys".
- **Hive** (removed). "If data in the base table changes, then the REBUILD command must be used to bring the index up to date."

**For the proposal.** Every system that kept indexes through compaction made the index update atomic with the rewrite, which supports Section 8.5. None reports what a rewrite dropped, as Section 8.7 proposes.

## B8. Index-aware maintenance, and fencing

- **Delta** (protocol). The VACUUM protocol check makes "Older vacuum implementations … begin to fail" on tables with writer features they do not know, "eliminating the risk of older Vacuum implementations … accidentally deleting relevant files" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#vacuum-protocol-check)). The record-level index RFC encourages tables using it to require the check. This is the only fence found that blocks maintenance without blocking readers.
- **Paimon** (protocol). Associated files are explicit references, retained and cleaned with their manifest since September 2026 ([pull request 9886](https://github.com/apache/paimon/pull/9886)). An open pull request, [7715](https://github.com/apache/paimon/pull/7715), fixes a race in which concurrent expiration lets orphan cleanup delete live files.
- **Hudi** (protocol). The metadata table is itself a Hudi table with its own timeline, compaction and cleaning. A dependent secondary index left behind when a record-level index is dropped remains an open issue ([apache/hudi#17337](https://github.com/apache/hudi/issues/17337)).
- **Lance** (implementation). Index files are deleted only when no retained manifest references them ([`cleanup.rs`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/rust/lance/src/dataset/cleanup.rs)).

The [note on index reachability](index-reachability.md#other-table-formats) gives further detail and the history of failures in each system.

**For the proposal.** Delta's fence and Paimon's explicit references are the two halves of the remedy the decoupled index metadata note proposes for version 4: a list of discovery roots, and a way to refuse maintenance by implementations that do not read it.

## B9. Run-time pluggable implementations

- **Paimon** (implementation). File indexes, global indexes, formats and file systems are all discovered through `ServiceLoader`, including from the thread context class loader for job-scoped providers ([`FileIndexerFactoryUtils.java`](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/paimon-common/src/main/java/org/apache/paimon/fileindex/FileIndexerFactoryUtils.java)).
- **Hudi** (partial). The writer-side index, key generator, lock provider and record merger are configured by class name, but the metadata table's index types are fixed.
- **Lance.** Index plugins are compiled-in Rust, "not dynamic libraries", through "an unstable Rust extension interface" whose registration "is not persisted in the dataset" ([`registry.rs`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/rust/lance-index/src/registry.rs)).
- **Hive** (removed). Index handlers were named by class in `CREATE INDEX … AS`, implementing `HiveIndexHandler`.

## B10. Indexes over expressions

- **Hudi** (protocol). Expression indexes over `identity`, `year`, `month`, `day`, `hour`, `lower` and `from_unixtime` ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)), described in the proposal's Section 13.5.
- **Lance** (partial). A JSON index that indexes one path and records its decoded type ([JSON index](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/index/scalar/json.md)).
- **Paimon** (partial). Indexes on a map entry, declared as `<map-column>[<map-key>]`.
- **Delta** (partial). Generated columns let partition filters be derived from queries on the source column ([batch documentation](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/docs/src/content/docs/delta-batch.mdx)).

## B11. Locating against excluding

- **Lance** (protocol). The specification gives four validity rules for using an index, covering deleted rows, deleted fragments, fragments updated in place and overlays newer than the index, and states that zone maps "can definitively exclude zones but may include false positives" ([index specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/index/index.md)). No metadata classifies an index as one kind or the other.
- **Paimon** (implementation). For scalar global indexes, "partial coverage affects performance rather than result completeness", while fast vector and full-text search "can omit matching rows when coverage is incomplete", and "row-ID coverage alone does not prove that indexed values are current" ([managing global indexes](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/multimodal-table/global-index/manage-indexes.mdx)). An index's file selection "must retain every file that may match".
- **Delta** (proposal). The record-level index is locating, and writers "must **validate** each probed locator against the current snapshot … If validation fails, the writer must fall back".
- **Hudi** (partial). Bloom filters serve "presence checks", while the record-level index "maps individual record keys to the file group that currently holds the record".

**For the proposal.** Paimon's documentation states the distinction of Section 12.2 in nearly the same words, from operating experience: an excluding index that covers too little costs speed, and a locating one that covers too little costs rows.

## B12. Workload telemetry

Only a vendor feature was found. Databricks' automatic liquid clustering "analyzes the table's historical query workload" to choose clustering keys ([Databricks documentation](https://docs.databricks.com/aws/en/delta/clustering)). None of the open formats records or uses workload information.

## B13. A contract carried in the format

- **Delta** (partial). Every extension is a named feature specified in the protocol, and an RFC is accepted only after "some discussion and/or prototype … in Delta Kernel", which exists in Java and Rust ([RFC process](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/protocol_rfcs/README.md)).
- **Hudi** (partial). The merger UUIDs are designed to work across runtimes, and hudi-rs reads the built-in indexes, but there is no extension contract beyond them.
- **Lance** (partial). Index details are typed protobuf messages, language-neutral in principle, while the plugin interface is Rust-only.
- **Paimon.** The Python client reads embedded file indexes but does not evaluate them.

## The problems the proposals respond to

Two problems in the [register](../contracts/problems.md) concern indexes directly.

- **V13, maintenance that deletes indexes.** Delta's VACUUM protocol check and Paimon's explicit references are covered under B8. Both formats fixed the problem in their specifications, Paimon by a change merged two months before this document, which is evidence that the problem is real and that the fix is affordable.
- **V14, rewrites disabling a positive index.** Lance's fragment reuse index, Delta's proposed re-pointing within the rewrite commit, and Paimon's atomic rebuild of global index groups are three ways to keep a locating index usable across compaction. Each pays a cost per rewrite. The proposal avoids that cost for excluding indexes, which a rewrite cannot make wrong.

## Indexes that were withdrawn

Two systems have withdrawn or declined index support, and their reasons bear on the proposal.

- **Hive** removed indexes in release 3.0, arguing that "columnar file formats with their statistics achieve similar goals as Hive indexes" ([HIVE-18448](https://issues.apache.org/jira/browse/HIVE-18448)) and recommending materialized views. A proposal to reintroduce them, HIVE-21792, remains open.
- **DuckLake** lists indexes among features "Unlikely to Be Supported in the Future" ([unsupported features](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/duckdb/unsupported_features.md)).

Together with Databricks' deprecation of Delta's Bloom filter index, described in the proposal's Section 13.8, these are the strongest evidence against the proposal's premise. Each withdrawal concerned indexes chosen at write time, maintained by rebuild and without partial coverage, which the proposal does not depend on; but the argument from columnar statistics applies to any index, and the proposal should answer it with measurements.

## Caveats

- The Delta record-level index is an open RFC and may change.
- Lance was read from a shallow clone, so the release in which each passage first appeared is not known, and several features cited are reserved or marked unstable.
- Paimon's documentation was read from the repository, whose layout has moved; website addresses may differ.

## Sources

All sources were read on November 11, 2026.

- Delta Lake: [`PROTOCOL.md`](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md) and documentation at delta-io/delta `ecde102`; the record-level index RFC, [delta-io/delta#7436](https://github.com/delta-io/delta/pull/7436).
- Apache Hudi: the [1.x technical specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md) and documentation at asf-site `56ebc22` (table version 9, release 1.2.0); source at master `9a7728c`.
- Apache Paimon: specification, documentation and source at master `d98f087`.
- Lance: format specification, protobuf definitions and source at lance-format/lance `fbfea4f`.
- DuckLake: specification 1.1 and extension documentation at duckdb/ducklake-web `139cfaf`.
- Hive: the [index design document](https://hive.apache.org/development/designdocs/indexdev/), [HIVE-18448](https://issues.apache.org/jira/browse/HIVE-18448) and the [indexing manual](https://hive.apache.org/docs/latest/language/languagemanual-indexing/).
