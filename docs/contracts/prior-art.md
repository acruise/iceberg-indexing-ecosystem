# How Other Table Formats Handle the Problems in Iceberg's Contracts

*For each problem in the [register of problems in Iceberg's contracts](problems.md), what Delta Lake, Apache Hudi, Apache Paimon, Lance, DuckLake and Hive ACID transactional tables already do about it. The document stands on its own: each problem is restated briefly, and the register gives the full statement. A companion document, [how other systems provide what the Puffin proposals offer](../proposals/prior-art.md), does the same for the benefits of the ecosystem proposals.*

**Status:** draft, November 11, 2026. Every claim is cited to a primary source pinned to a commit read on that date (listed under "Sources" at the end). A claim marked *protocol* is normative text in the system's specification; *implementation* means the reference implementation or its documentation only; *proposal* means an RFC or open pull request, with its status. Where a source was read but its behavior was not tested, the entry says so.

## Summary

The table shows, for each problem, the strongest form in which each system addresses it. "Partial" means the system addresses part of the problem, "counterexample" that it shows the problem occurring, and "vendor" that the mechanism exists only in a commercial distribution. "N/a" means the problem does not arise, usually because a central database performs the commit. A dash means nothing was found, which is not proof that nothing exists.

| Problem | Delta | Hudi | Paimon | Lance | DuckLake | Hive ACID |
|---|---|---|---|---|---|---|
| E1 Retry conditions and row-level deletes | protocol | protocol | implementation | protocol | implementation | implementation |
| E2 Validation window | implementation | protocol | implementation | implementation | protocol | implementation |
| E3 Read base recorded, validation enforced | partial | partial | partial | protocol | — | implementation |
| E4 Commits of unknown outcome | protocol | protocol | protocol | implementation | — | implementation |
| E5 Path reuse | protocol | protocol | implementation | protocol | implementation | implementation |
| E6 Out-of-band mutation | partial | — | partial | — | — | — |
| E7 Atomic exclusive create | implementation | implementation | implementation | protocol | n/a | n/a |
| E8 Several names for one object | partial | — | — | — | protocol | — |
| E9 Orphan removal races and clocks | protocol | protocol | implementation | implementation | implementation | implementation |
| E10 Lifecycle, archival and credentials | vendor | — | implementation | protocol | partial | — |
| E11 Ignoring by default or by exception | protocol | protocol | partial | protocol | implementation | — |
| E12 Defaults a reader cannot supply | protocol | — | — | protocol | protocol | — |
| E13 Stable row positions | protocol | protocol | protocol | protocol | protocol | implementation |
| E14 Inconsistent split offsets | — | — | — | protocol | — | — |
| E15 Name mapping | protocol | implementation | protocol | protocol | protocol | — |
| E16 Unsupported features found mid-scan | protocol | implementation | implementation | partial | implementation | implementation |
| E17 Maintenance and unreferenced structures | protocol | protocol | protocol | implementation | implementation | implementation |
| E18 What a data file format owes | protocol | protocol | implementation | protocol | protocol | implementation |
| E19 Type mappings with ranges | protocol | — | protocol | — | partial | — |
| E20 Pushdown semantics per format | implementation | — | implementation | protocol | — | — |
| E21 Extension identifiers | protocol | partial | — | protocol | — | — |
| E22 Capabilities declared, not inferred | protocol | — | partial | protocol | — | implementation |
| E23 Pluggable implementations | implementation | implementation | implementation | implementation | — | implementation |
| E24 File-attached metadata across rewrites | protocol | protocol | implementation | partial | protocol | implementation |
| E25 Shared metadata replaced whole | protocol | counterexample | counterexample | protocol | protocol | — |
| E26 Statistics from defective writers | partial | partial | partial | implementation | protocol | implementation |
| E27 Byte meaning kept outside the bytes | protocol | partial | — | protocol | — | — |
| E28 Decodability known before opening | protocol | protocol | implementation | partial | implementation | — |
| V1, V2 Deprecated formats | implementation | protocol | protocol | protocol | partial | implementation |
| V7, V8 Defaults that are expressions | protocol | — | protocol | — | protocol | — |
| V9 Relocation | protocol | implementation | protocol | protocol | protocol | — |
| V10 Commits without a catalog server | protocol | protocol | implementation | protocol | n/a | n/a |
| V11 Admitting new formats | — | protocol | implementation | — | — | — |
| V12 Rewrite rules per kind of sidecar | partial | — | protocol | protocol | protocol | implementation |
| V15 Exact statistics answering aggregates | protocol | protocol | implementation | protocol | protocol | implementation |

Four patterns stand out, and the sections below give the detail.

- **Every other format that has row-level deletes conflicts them with compaction.** Delta does it through the protocol's rule that a deletion vector replaces its file, Hudi and Lance in their specifications, and Paimon, DuckLake and Hive in their implementations. Hive avoids the question by identifying rows logically, so that compaction never invalidates a delete.
- **Recording what a commit read is common outside Iceberg.** Lance records each transaction's `read_version` in the protocol, Delta's reference implementation records `readVersion` in commit information, and Hudi records each file's previous version. Hudi and Hive also make validation robust to expired history in ways Iceberg does not.
- **Several formats declare capabilities instead of deciding case by case.** Delta and Lance both have a uniform rule that a feature a reader or writer does not know causes it to refuse, with explicitly ignorable fields alongside. Delta goes furthest, distinguishing reader features, writer features and a fence for maintenance alone.
- **Nobody has solved out-of-band mutation of data files.** No format records a content hash for data files. Delta, Paimon and Lance protect deletion vectors with checksums, and Hudi protects its own log blocks, but a data file replaced at the same path and size goes undetected everywhere.

## Commits

### E1. Retry conditions and row-level deletes

*Iceberg's list of conditions a retried commit must verify predates deletion vectors, so a compaction racing a row-level delete can drop the delete and resurrect rows.*

- **Delta** (protocol). A deletion vector is written by replacing the logical file: it is "illegal for a `path` to occur in an `add` action that already occurs with a different `dvId`" unless the same commit removes it ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#add-file-and-remove-file)). Every deletion therefore removes the file's path, and the reference implementation fails any commit that removes a path another commit has also removed ([`ConflictChecker.scala`](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/spark/src/main/scala/org/apache/spark/sql/delta/ConflictChecker.scala)). The conflict rule itself is implementation, but the protocol rule makes it unavoidable.
- **Hudi** (protocol). A writer aborts on overlapping file groups, including "compactions planned or clustering actions completed on overlapping file groups", and may compare record keys. Clustering "Should abort if concurrent updates to the same file groups being clustered are detected" ([1.x specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Paimon** (implementation). Deletion vectors are paired with their data files before the deleted-files check, and for primary-key tables with deletion vectors the documentation concedes that "File-level conflict checks can miss conflicts between concurrent compactions", so Paimon rejects concurrent compactions of one bucket from different writers ([compaction documentation](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/primary-key-table/compaction.md); [`ConflictDetection.java`](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/paimon-core/src/main/java/org/apache/paimon/operation/commit/ConflictDetection.java)).
- **Lance** (protocol). The conflict matrix is defined per operation type. A rewrite and a delete that touch overlapping fragments are a *retryable* conflict, and two deletes have their deletion masks merged unless they modify the same rows ([transaction specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/table/transaction.md)).
- **DuckLake** (implementation). The extension treats "A snapshot tries to delete from a data file compacted by another snapshot" as a conflict ([conflict resolution](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/duckdb/advanced_features/conflict_resolution.md)). The specification's change vocabulary is table-granular only.
- **Hive ACID** (implementation). Compaction is excluded from write-set conflict checks ([`CommitTxnFunction.java`](https://github.com/apache/hive/blob/3802476e88f74e7202925fe46d68690178872456/standalone-metastore/metastore-server/src/main/java/org/apache/hadoop/hive/metastore/txn/jdbc/functions/CommitTxnFunction.java)). It can be, because deletes name rows by a logical identifier that major compaction copies unchanged (see E13). That compaction cannot invalidate a concurrent delete is our inference from the code.

**For Iceberg.** Delta's approach is notable for making the conflict a consequence of a structural rule, not of a listed condition. Hive's is the strongest: logical row identity makes the race harmless rather than detectable.

### E2. The validation window

*Iceberg does not say which commits a retry validates against, or what a writer should do when expiration has removed them.*

- **Hudi** (protocol). The window is "all actions that have completed with completion time greater than the snapshot write time", refreshed inside the lock ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)). Archival never removes history an in-flight writer needs: it retains everything from the earliest pending instant onward (`TimelineArchiverV2.java` at [Hudi master](https://github.com/apache/hudi/blob/9a7728c5862a8939f58f47c35abb1d838fc2dcb3/)).
- **Lance** (implementation). The committer loads every transaction between its read version and the latest, and if any have been cleaned up and the transaction touches existing fragments, it fails with a retryable error: "versions … were cleaned up, so … cannot safely check conflicts" ([`conflict_resolver/init.rs`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/rust/lance/src/io/commit/conflict_resolver/init.rs)).
- **DuckLake** (protocol). "Snapshot expiration keeps rows in `ducklake_snapshot_changes` after removing the corresponding rows from `ducklake_snapshot`", so the summaries validation needs outlive expiration ([specification](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/specification/tables/ducklake_snapshot_changes.md)).
- **Hive ACID** (implementation). A reader whose snapshot predates a compacted base fails with `ACID_NOT_ENOUGH_HISTORY` ([`AcidUtils.java`](https://github.com/apache/hive/blob/3802476e88f74e7202925fe46d68690178872456/ql/src/java/org/apache/hadoop/hive/ql/io/AcidUtils.java)).
- **Paimon** (implementation). An optional `commit.last-safe-snapshot` bounds the search and must stay unchanged across retries ([`CoreOptions.java`](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/paimon-api/src/main/java/org/apache/paimon/CoreOptions.java)).
- **Delta.** Nothing normative. The reference implementation checks every commit after the read version.

**For Iceberg.** Hudi's archival rule and DuckLake's retention of change summaries are two ways of making the window survive expiration. Lance's rule, to fail with a retryable error when history is missing, is the behavior [prose fix 2](prose-fixes.md#2-what-a-retry-validates-against) proposes.

### E3. Recording the read base, and who enforces validation

*An Iceberg snapshot records its parent, not the snapshot its writer read, and no component other than the writer validates.*

- **Lance** (protocol). Every transaction carries `read_version`, "The version of the dataset this transaction was built from", and the manifest points to the transaction that produced it ([`transaction.proto`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/protos/transaction.proto)). Validation is still the writer's.
- **Delta** (implementation). The reference implementation records `readVersion`, `isolationLevel` and `isBlindAppend` in `commitInfo` ([`actions.scala`](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/spark/src/main/scala/org/apache/spark/sql/delta/actions/actions.scala)), but the protocol leaves `commitInfo` free-form. The only normative conflict rule concerns domain metadata.
- **Hudi** (partial). Validation is writer-side within a lock. Each file's write statistics record `prevCommit`, "The previous version of the file", which is a per-file read base (`HoodieWriteStat.java` at [Hudi master](https://github.com/apache/hudi/blob/9a7728c5862a8939f58f47c35abb1d838fc2dcb3/)).
- **Paimon** (partial). The writer passes the base snapshot's UUID to the commit path, but the snapshot does not record it ([`FileStoreCommitImpl.java`](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/paimon-core/src/main/java/org/apache/paimon/operation/FileStoreCommitImpl.java)). Whether a REST catalog server validates it was not checked.
- **Hive ACID** (implementation). The metastore performs the write-write check inside `commitTxn`, and a reader's snapshot is a list "produced by the Hive metastore when a query starts" ([ORC ACID documentation](https://orc.apache.org/docs/acid.html)). Validation is central by construction.
- **DuckLake.** The catalog database enforces only a primary key on the snapshot id, and the logical check is client-side ([conflict resolution](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/duckdb/advanced_features/conflict_resolution.md)).

**For Iceberg.** Lance is the precedent for [version 4 ask 2](v4-asks.md#2-the-read-base-of-each-snapshot), in the protocol and with the same name.

### E4. Commits of unknown outcome

- **Delta** (protocol). The `txn` action lets external systems make writes "_idempotent_", and catalog-managed tables require a `txnId` in commit information, which the reference implementation compares to recognize its own commit ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#transaction-identifiers); [`OptimisticTransaction.scala`](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/spark/src/main/scala/org/apache/spark/sql/delta/OptimisticTransaction.scala)).
- **Paimon** (protocol). Every snapshot carries `commitUser` and `commitIdentifier`, and a retry scans intervening snapshots to "Resolve an uncertain successful commit" ([snapshot specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/snapshot.md)). The scan fails if the snapshot has already expired.
- **Hudi** (protocol). Actions "are idempotent, and could fail many times", and failed writes are rolled back after a heartbeat timeout ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Lance** (implementation). After a failed attempt the writer reads back the manifest and classifies the outcome, surfacing a distinct `CommitStatusUnknown` error and suppressing cleanup, since "Callers must not run destructive cleanup in this state" ([`commit.rs`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/rust/lance/src/io/commit.rs)).
- **Hive ACID** (implementation). Committing an already committed transaction succeeds, "assume that this is most likely due to retry logic" ([`CommitTxnFunction.java`](https://github.com/apache/hive/blob/3802476e88f74e7202925fe46d68690178872456/standalone-metastore/metastore-server/src/main/java/org/apache/hadoop/hive/metastore/txn/jdbc/functions/CommitTxnFunction.java)).

### V10. Commits without a catalog server

- **Delta** (protocol). The default commit is the creation of a numbered log file. The `catalogManaged` feature, accepted on February 17, 2026, makes "the catalog … the source of truth"; the earlier coordinated commits RFC was rejected on April 7, 2025 ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#catalog-managed-tables); [RFC index](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/protocol_rfcs/README.md)).
- **Lance** (protocol). Storage must offer rename-if-not-exists or put-if-not-exists, with an optional DynamoDB external manifest store ([transaction specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/table/transaction.md)).
- **Hudi** (protocol). A distributed lock and atomic publication of completed instants to the timeline ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Paimon** (implementation). Atomic rename or conditional creation, optionally with a Hive or JDBC lock ([concurrency control](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/concurrency-control.md)).

**For Iceberg.** Every other file-based format keeps a storage-only commit protocol, and Delta keeps one alongside its catalog-managed mode. Version 4 removing Iceberg's without a replacement would make it the exception.

## Identity and storage

### E5. Path reuse

- **Delta** (protocol). "Data files MUST be uniquely named and MUST NOT be overwritten" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#data-files)).
- **Hudi** (protocol). Base file names include a write token, "Monotonically increasing token for every attempt … helps uniquely identify the base file when there are failures and retries" ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Lance** (protocol). Data files take UUID-derived names, deletion files include an id "used to differentiate this file from others written by concurrent writers", and field and fragment ids are never reused ([layout specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/table/layout.md)).
- **Paimon, DuckLake** (implementation). File names embed a UUID ([`DataFilePathFactory.java`](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/paimon-core/src/main/java/org/apache/paimon/io/DataFilePathFactory.java); [paths](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/duckdb/usage/paths.md)).
- **Hive ACID** (implementation). Directories are named by monotonically allocated write ids, and an opt-in setting suffixes each table directory with its creation transaction id, so that a dropped and recreated table does not reuse paths ([`HiveConf.java`](https://github.com/apache/hive/blob/3802476e88f74e7202925fe46d68690178872456/common/src/java/org/apache/hadoop/hive/conf/HiveConf.java)).

**For Iceberg.** Delta's sentence is almost exactly [prose fix 3](prose-fixes.md#3-paths-are-not-reused). Hive's directory suffix addresses the drop-and-recreate case that motivates it.

### E6. Out-of-band mutation

No format records a content hash for data files. The protections that exist cover other structures.

- **Delta** (partial, protocol). Each deletion vector carries a "CRC-32 checksum of `bitmapData`", and `_last_checkpoint` an MD5 ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#deletion-vector-file-storage-format)). The optional version checksum file exists "to enable the detection of non-compliant modifications", but covers table-level aggregates only ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#version-checksum-file)).
- **Paimon** (partial, protocol). Deletion vectors carry a CRC32 each ([table index specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/tableindex.md)).
- **Hudi.** Only `hoodie.properties` and log blocks are checked ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Lance, DuckLake, Hive ACID.** Only sizes, where anything.

**For Iceberg.** [Version 4 ask 1](v4-asks.md#1-a-content-hash-on-tracked-files) would have no precedent among table formats, which is an argument both for its novelty and for asking why nobody has done it.

### E7. Atomic exclusive create

- **Lance** (protocol). The specification requires one of two atomic primitives, and the implementation uses conditional put on S3, GCS and Azure. It refuses Tencent COS for default commits because "COS silently ignores its put-if-not-exists header on buckets that have ever had versioning enabled" ([`lance-table` commit handlers](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/rust/lance-table/src/io/commit.rs)).
- **Delta** (implementation). The log store must provide mutual exclusion, and multi-cluster writes to S3 need a DynamoDB log store that "is only safe when all writers use this `LogStore` implementation" ([storage documentation](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/docs/src/content/docs/delta-storage.mdx)). delta-rs offers conditional put as an option.
- **Hudi** (implementation). RFC-91, completed, adds a lock based on S3 and GCS conditional writes; the file-system lock "is not supported with cloud storage like S3 or GCS" ([concurrency control](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/docs/concurrency_control.md)).
- **Paimon** (implementation). "Do not assume an object store's rename operation has HDFS semantics" ([concurrency control](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/concurrency-control.md)).

**For Iceberg.** Lance's refusal of a store whose conditional create is unreliable is a per-substrate guarantee table in miniature.

### E8. Several names for one object

- **DuckLake** (protocol). Every path carries `path_is_relative`, resolved through a chain from file to table to schema to data path, and paths are relative by default ([specification](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/specification/queries.md)).
- **Delta** (partial). Paths are RFC 2396 URIs to be decoded, relative or absolute, with no equality rule ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#add-file-and-remove-file)).

### E9. Orphan removal, races and clocks

- **Delta** (protocol). The comparison of clocks is explicit: a tombstone expires when "*current time* (according to the node performing the cleanup)" exceeds the threshold ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#add-file-and-remove-file)). The documentation warns that VACUUM can corrupt tables "when `VACUUM` deletes files that have not yet been committed" ([utilities](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/docs/src/content/docs/delta-utility/index.mdx)). In-commit timestamps make commit time monotonic.
- **Hudi** (protocol). Instant times must be monotonic across processes, by "implementing a TrueTime generator … or rely on system epoch times with assumed bounds on clock skews" ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Hive ACID** (implementation). The cleaner's safety is keyed to transaction ids rather than clocks: it waits until every reader that could see the old files has finished ([`CompactionCleaner.java`](https://github.com/apache/hive/blob/3802476e88f74e7202925fe46d68690178872456/ql/src/java/org/apache/hadoop/hive/ql/txn/compactor/handler/CompactionCleaner.java)).
- **Lance** (implementation). Unreferenced files are deleted only when at least seven days old, and manifests newer than the version cleanup read are never deleted ([`cleanup.rs`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/rust/lance/src/dataset/cleanup.rs)).
- **Paimon, DuckLake** (implementation). Age thresholds compare storage modification times with the local clock. DuckLake schedules replaced files for deletion separately, "because there might still be active queries" ([cleanup](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/duckdb/maintenance/cleanup_of_files.md)).

**For Iceberg.** Delta states the clock dependence instead of leaving it implicit. Hive shows that a central transaction service can remove it altogether.

### E10. Lifecycle, archival and credentials

- **Delta** (vendor only). Databricks' archival support makes queries that need archived files fail rather than return partial results ([Databricks documentation](https://docs.databricks.com/aws/en/optimizations/archive-delta)), which is the refusal [the register](problems.md#e10-lifecycle-policies-archival-classes-and-credential-scope) asks about. It depends entirely on Databricks compute.
- **Lance** (protocol). `base_paths` let fragments live in several buckets, for hot and cold tiering ([layout specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/table/layout.md)).
- **Paimon** (implementation). The REST catalog vends table-scoped storage credentials ([REST API](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/rest/rest-api.md)).
- **DuckLake** (partial). Per-file encryption keys live in the catalog, and the guide approximates access control with storage-prefix policies, warning that "This approach requires trusted writers" ([access control](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/duckdb/guides/access_control.md)).

### V9. Relocation

- **Delta** (protocol). The format is "Self describing … allows static tables to be copied or moved using standard filesystem tools", and deletion vectors distinguish relative from absolute storage ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#overview)). Shallow clones reference the source's data files, and VACUUM on the source breaks them ([utilities](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/docs/src/content/docs/delta-utility/index.mdx)).
- **Lance** (protocol). Relocation needs "only the `base_paths` field in the manifest" changed ([layout specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/table/layout.md)).
- **DuckLake, Paimon** (protocol). Relative paths with a configurable data path, and file names plus an optional external path ([paths](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/duckdb/usage/paths.md); [manifest specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/manifest.md)).

## Reading

### E11. Ignoring as the default or as the exception

- **Delta** (protocol). The rule is uniform in both directions. Readers and writers "must not ignore table features", while "unrecognized actions, fields, and/or metadata domains are never required … Clients must ignore such unrecognized fields" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#protocol-evolution)). A domain that imposes requirements on readers "must be part of a reader-writer table feature".
- **Lance** (protocol). Separate reader and writer feature bitmaps: "If either sees a flag they don't know, they should return an 'unsupported' error". An unknown transaction operation "conflicts with every concurrent transaction" and must not be re-encoded ([versioning](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/table/versioning.md)).
- **Hudi** (protocol). All or nothing by table version: an unknown version code fails at load with "upgrade your readers" ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Paimon** (partial). Case by case, as in Iceberg: unknown file index types fail by default, unknown sidecar dimensions are skipped, generated-column metadata may be ignored ([schema specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/schema.md)).

**For Iceberg.** Delta's two sentences are the default rule [prose fix 8](prose-fixes.md#8-ignoring-is-the-exception) proposes, together with its converse, and Delta enforces them through declared features instead of prose alone.

### E12. Defaults a reader cannot supply

- **Delta** (protocol). Defaults are write-only: "this metadata only applies for write operations, not read operations", and adding a column with a default is not allowed, so a reader never needs to supply one ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#default-columns)).
- **DuckLake** (protocol). Separate `initial_default` and operational `default_value`, as in Iceberg ([column specification](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/specification/tables/ducklake_column.md)).
- **Lance** (protocol). A column missing from a fragment reads as null; there is no other default mechanism.

**For Iceberg.** Delta avoids the read obligation by forbidding the feature that creates it, which is the first of the note's [three ways out](README.md#the-read-obligation-keeps-growing) taken to its limit.

### E13. Stable row positions

- **Delta** (protocol). Deletion vectors hold "the position (index) of a row in a parquet file starting from zero", and row tracking preserves row ids across rewrites through a materialized hidden column ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#row-tracking)).
- **Hudi** (protocol). A log block's position bitmap is anchored by the base file's instant time, "Used by the reader to validate that positions still refer to the same base file" ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Lance** (protocol). Row addresses change on compaction; an optional stable row id feature gives persistent identity, and a writer-only flag exists because "Older writers could treat its entries as stable row IDs and silently extend coverage" ([row id lineage](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/table/row_id_lineage.md)).
- **DuckLake** (protocol). "Every row has a unique row id that is maintained", stored or implicit, while delete files stay positional ([data file specification](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/specification/tables/ducklake_data_file.md)).
- **Hive ACID** (implementation). Rows are identified by `ROW__ID`, a triple preserved by major compaction and reassigned by Hive 4's rebalance compaction ([`RecordIdentifier.java`](https://github.com/apache/hive/blob/3802476e88f74e7202925fe46d68690178872456/ql/src/java/org/apache/hadoop/hive/ql/io/RecordIdentifier.java)).

**For Iceberg.** Hudi's anchor is the most direct precedent for binding positions to the file they were taken from, which is what [prose fix 9](prose-fixes.md#9-row-positions-are-fixed-when-a-data-file-is-written) and the content hash of [version 4 ask 1](v4-asks.md#1-a-content-hash-on-tracked-files) aim at.

### E14. Inconsistent split offsets

Only **Lance** avoids the problem, by having no row groups: "you can split the file at whatever row boundary you want" ([file format](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/file/index.md)). Nothing comparable was found elsewhere.

### E15. Name mapping

- **Delta** (protocol). In column mapping's id mode, "If a data file does not contain field ids, readers must refuse to read that file or return nulls for each column", and physical names "must be unique across all versions of the table" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#column-mapping)).
- **DuckLake** (protocol). Files without field ids are mapped through `ducklake_column_mapping` and `ducklake_name_mapping` tables in the catalog ([column mapping](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/specification/tables/ducklake_column_mapping.md)). Whether a mapping is bound per file was not established.
- **Lance, Paimon** (protocol). Field ids throughout. Lance makes ids non-reusable under a writer flag; Paimon's file index, by contrast, keys entries by column name and translates through ids ([Paimon file index](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/fileindex.mdx)).
- **Hudi** (implementation). Resolution is by name by default, with an experimental schema-on-read mode using column ids that "cannot be disabled again" ([schema evolution](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/docs/schema_evolution.md)).

**For Iceberg.** Delta refuses outright what Iceberg reinterprets through a mutable table property.

### E16 and E28. Discovering what a reader needs before reading

- **Delta** (protocol). Every capability is declared in the `protocol` action and checked when the snapshot loads; type changes must be validated "and fail" if unsupported; variant shredding is gated table-wide ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#table-features)). Readers "SHOULD support all codecs listed … regardless of the current property value".
- **DuckLake** (implementation). The data file format is checked while listing files, before any is opened ([`ducklake_metadata_manager.cpp`](https://github.com/duckdb/ducklake/blob/1adfc4cf19e1a09e2c482912b24ec39ae049acea/src/storage/ducklake_metadata_manager.cpp)).
- **Hudi** (protocol). Format by extension, mixed formats per file group only when a table option allows them ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Lance** (partial). Table features are checked at load, but encodings are self-describing per page, so an unknown one is found only when decoded. The specification records a case in which old readers "panic rather than returning an error" ([file versioning](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/file/versioning.md)).
- **Paimon, Hive ACID** (implementation). Format inferred from the file extension, and an unknown ACID metadata version fails at directory resolution.

**For Iceberg.** Delta's table-level declaration is [version 4 ask 3](v4-asks.md#3-declaring-what-a-snapshot-depends-on) at full strength. Lance's panic is the cautionary tale for extensions declared only inside the file.

### E17. Maintenance and structures outside metadata

- **Delta** (protocol). VACUUM removes files "from directories not managed by Delta Lake, ignoring directories beginning with `_`", and the VACUUM protocol check makes older implementations refuse tables whose writer features they do not know ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#vacuum-protocol-check)).
- **Paimon** (protocol). Associated files are listed explicitly in `_EXTRA_FILES`, retained and cleaned with their manifest, which pull request 9886 made maintenance follow in September 2026 ([manifest specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/manifest.md)). An open pull request, 7715, fixes a race in which concurrent expiration empties the set of used files.
- **Hudi** (protocol). Files not belonging to a completed action on the timeline are eliminated, and markers track in-flight writes.
- **Lance, DuckLake, Hive ACID** (implementation). Reachability from manifests or catalog state.

The [note on index reachability](../proposals/index-reachability.md#other-table-formats) covers this ground in more detail.

## Formats

### E18 and V11. What a data file format owes, and admitting new ones

- **Delta** (protocol). `format.provider` exists, but only Parquet tables can be created, and other formats are supported "for legacy reasons and to enable possible support for other formats in the future" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#format-specification)).
- **Hudi** (protocol). A closed list of base file formats that now includes Lance, with an explicit degradation: for Lance, "column stats and partition stats are not populated … readers should not rely on these indexes" ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Paimon** (implementation). The most open: a `FileFormatFactory` discovered through `ServiceLoader`, with registered factories for Avro, ORC, Parquet, CSV, JSON, Lance, Vortex and others. A format must supply a reader factory taking filters, a writer factory and field validation, while statistics extraction is optional ([`FileFormat.java`](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/paimon-common/src/main/java/org/apache/paimon/format/FileFormat.java)). The specification gives type mappings per format but no normative contract.
- **Lance, DuckLake** (protocol). One format each; Lance records the file format version per data file and requires a feature flag to mix versions.
- **Hive ACID** (implementation). The format contract is a pair of Java interfaces, `AcidInputFormat` and `AcidOutputFormat`, checked when a table is created ([`TransactionalValidationListener.java`](https://github.com/apache/hive/blob/3802476e88f74e7202925fe46d68690178872456/standalone-metastore/metastore-server/src/main/java/org/apache/hadoop/hive/metastore/TransactionalValidationListener.java)).

**For Iceberg.** Paimon has already admitted Vortex and Lance through an SPI without a specification change, which is both the opportunity of V11 and the risk of E21. Hudi's per-format declaration that some indexes will be missing is a degradation stated in the specification, the pattern the contracts note proposes.

### E19. Type mappings with ranges

- **Delta** (protocol). Primitive types state their ranges, type widening lists permitted changes and writers "must reject applying any unsupported type change" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#type-widening)).
- **Paimon** (protocol). Mappings are banded by precision, for instance Parquet timestamps by P ≤ 3, P ≤ 6 and P > 6, and some combinations are marked "unsupported" ([file format specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/fileformat.md)).
- **DuckLake** (partial). Registration accepts narrower physical types, such as `decimal(P',S')` with P' ≤ P.

**For Iceberg.** Paimon's banded tables are a working example of the parameterized mappings the note asks for, written in prose without a constraint language.

### E20. Pushdown semantics

- **Lance** (protocol). Zone maps "can definitively exclude zones but may include false positives that require rechecking", and their treatment of NaN is specified ([zone map index](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/index/scalar/zonemap.md)).
- **Delta** (implementation). Kernel defines one expression framework, and returns the "Subset of the given filter that is not guaranteed to be satisfied" ([Kernel documentation](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/docs/src/content/docs/delta-kernel-java.mdx)).
- **Paimon** (implementation). Filters reach format readers "on a best-effort basis".

### E27. Byte meaning outside the bytes

- **Delta** (protocol). `isAdjustedToUTC` "must be set to `true`" for timestamps and `false` for timestamps without zone, while legacy partition values are interpreted "using the time zone of the system which wrote to the table" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#partition-value-serialization)).
- **Hudi** (partial). `hoodie.table.timeline.timezone` is kept in table properties, outside the files ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Lance** (protocol). Encodings describe themselves within the file.

### V1 and V2. Deprecated formats

- **Delta** (implementation). `ALTER TABLE … DROP FEATURE` rewrites data files "as necessary to remove all traces of the table feature", and checkpoint protection hides the history from before the drop ([drop feature](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/docs/src/content/docs/delta-drop-feature.mdx)). The protocol's statement that features are never removed sits awkwardly with this.
- **Hudi** (protocol). Index layouts are versioned per index "without forcing a table-version upgrade", and a downgrade drops every index newer than the target supports ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Lance** (protocol). File format 0.1 is "no longer writable" but readable, with a table of the releases that read and write each version ([file versioning](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/file/versioning.md)).

No format records how much of a table remains in an old format, apart from Lance's per-file versions and Delta's count of deletion vectors in its checksum file.

**For Iceberg.** Delta's drop-feature operation is the operational form of "stop writing, then rewrite until nothing remains", with the bookkeeping that tells an operator when it is done.

## Extensions and the parties who add them

### E21 and E22. Identifiers and declared capabilities

- **Delta** (protocol). Metadata domains prefixed `delta.` are reserved, and others are "encouraged to use a naming convention … (e.g. `com.databricks.*` or `org.apache.*`)" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#domain-metadata)). Table feature names are flat and listed only in the specification; experimental ones carry a `-dev` suffix until accepted ([RFC process](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/protocol_rfcs/README.md)). Capabilities are declared, and a feature is distinguished as *supported* or *active*.
- **Lance** (protocol). Index types are identified by protobuf type URLs, which are namespaced by package, configuration keys prefixed `lance.` are reserved, and the specification forbids inferring capability "from the index type, the writer version, or the `covering_fields` metadata alone" ([index specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/index/index.md)).
- **Hudi** (partial). Index types are a closed list, and an unknown one throws. Record mergers are the exception: they are identified by UUIDs stored in table configuration, so that "Hudi can automatically deduce the record merger to use for the table across different language and engine runtimes" ([record merger](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/docs/record_merger.md)).
- **Paimon.** Identifiers are flat strings, and duplicate registrations are resolved arbitrarily with a warning, "choose one of them" ([`FileIndexerFactoryUtils.java`](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/paimon-common/src/main/java/org/apache/paimon/fileindex/FileIndexerFactoryUtils.java)).

### E23. Pluggable implementations

Every JVM system loads extensions by class name or `ServiceLoader`: Delta's log stores and Kernel engine, Hudi's indexes, key generators, lock providers and mergers, Paimon's formats, file indexes and global indexes, Hive's ACID formats. Lance's index plugins are compiled-in Rust, "not dynamic libraries", and registration "is not persisted in the dataset" ([`registry.rs`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/rust/lance-index/src/registry.rs)). Delta adds a protocol rule for one kind of plugin: a clustering implementation "must only recluster files that have the field `clusteringProvider` set to the name of the same clustering implementation" ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#writer-requirements-for-clustered-table)), which records which implementation owns each file.

### E24 and V12. File-attached metadata across rewrites, and rules per kind

- **Delta** (protocol). Row tracking fields must be carried to the last committed value for the same path, and rewrites preserve row ids through a materialized column. No preservation rule exists for file tags, and checkpoints drop them from removals ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#writer-requirements-for-row-tracking)). For deletion vectors, "`UPDATE`, `MERGE`, and `OPTIMIZE` do not have strict guarantees" of applying them; `REORG … APPLY (PURGE)` does ([deletion vectors](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/docs/src/content/docs/delta-deletion-vectors.mdx)). A proposal for column updates with their own rewrite rules was opened on August 10, 2026.
- **Hudi** (protocol). Table services update the metadata table and its indexes under the same lock and before the completed instant is published ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)).
- **Lance** (protocol). Each kind has its own rule, stated separately per operation: rewrites "can materialize deletions", indexes are remapped or deferred, overlays conflict. Fragment metadata is not inherited by new fragments ([table specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/table/index.md)).
- **Paimon** (protocol). One generic lifecycle for associated files of manifests, and a special case for file index sidecars, found by suffix ([manifest specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/manifest.md)).
- **DuckLake** (protocol). Merges preserve per-row snapshot ownership so that "time travel and data change feeds" survive, and skip files with deletes ([merging files](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/duckdb/maintenance/merge_adjacent_files.md)).

**For Iceberg.** Lance and Delta both show the cost V12 describes, a rule per kind written into the specification. Paimon's generic rule for associated files is the closest thing to the per-kind declaration the Puffin proposal makes.

### E25. Shared metadata replaced whole

- **Delta** (protocol). "Writers must preserve all domains even if they don't understand them", which is a precise answer to the problem for domain metadata. The `metaData` action, by contrast, is replaced whole ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#writer-requirements-for-domain-metadata)).
- **Lance** (protocol). Configuration updates are per key and conflict only on the same key; fragment and index metadata maps must "preserve all entries, including unknown ones" ([`transaction.proto`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/protos/transaction.proto)).
- **DuckLake** (protocol). Metadata is a set of rows versioned by snapshot, and only changed rows are rewritten.
- **Hudi** (counterexample). All index definitions live in one JSON file rewritten whole, and the code comments that a change "would drop the definitions that another meta client of the table wrote since"; the mitigation is to reload before modifying (`HoodieTableMetaClient.java` at [Hudi master](https://github.com/apache/hudi/blob/9a7728c5862a8939f58f47c35abb1d838fc2dcb3/)).
- **Paimon** (counterexample, from reading the code). Rebuilding a file's index appears to keep only entries of currently configured types, so unknown ones would be dropped; this was not tested.

**For Iceberg.** Delta's rule for domains and Lance's rule for metadata maps are the PNG safe-to-copy bit stated for a table format.

### E26 and V15. Statistics from defective writers, and statistics as answers

- **Lance** (implementation). The manifest records `writer_version` "to detect whether the file may have known bugs", and the implementation distrusts a cached row count when it is absent ([`table.proto`](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/protos/table.proto)). This is the only case found of behavior keyed to writer identity.
- **Hive ACID** (implementation). Statistics are bound to a write id: "we store a write ID of the latest stats writer", and statistics written without visibility of that id are marked invalid ([HIVE-19416](https://issues.apache.org/jira/browse/HIVE-19416)). Hive answers `count` from them by default.
- **DuckLake** (protocol). Bounds carry `min_is_exact` and `max_is_exact`, with null meaning unknown, and table-level statistics may be used for pruning "only when they were read from a consistent metadata view" in which their snapshot was the latest. A migration cleared bounds stored with the wrong type by older versions ([file column statistics](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/specification/tables/ducklake_file_column_stats.md)).
- **Delta** (protocol and implementation). `tightBounds` distinguishes exact bounds. The reference implementation answers `count`, `min` and `max` from the log only when every file has statistics, no deletion vectors affect the query, and there are no filters or grouping. Writer identity is recorded only in `commitInfo.engineInfo`.
- **Hudi, Paimon** (partial). Hudi's `isTightBound` defaults to false and the remedy is to drop and rebuild an index; Paimon records `writerVersion` per snapshot but uses it only for display.

**For Iceberg.** No format revokes a writer's statistics as a class, which is what E26 proposes. Lance and Hive are the nearest precedents, and DuckLake's migration is a one-off revocation performed by a version upgrade.

### V7 and V8. Defaults that are expressions

- **DuckLake** (protocol). The closest precedent. A default records `default_value_type`, "either `literal` … or `expression` (e.g., `random()`)", and `default_value_dialect`, "the name of the system that created that value" ([column specification](https://github.com/duckdb/ducklake-web/blob/139cfaf571e7c4a94caad2c312c7b7998932d759/docs/stable/specification/tables/ducklake_column.md)). The implementation allows only literals when adding a column.
- **Delta** (protocol). Column defaults are a writer feature, so a writer that cannot evaluate one must refuse to write, and writers "MUST enforce" that the result of the expression is assigned ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#default-columns)). For generated columns, the implementation rejects non-deterministic expressions and anything outside an allow-list.
- **Paimon** (protocol). Generated-column expressions that read a time zone "are rejected because their results can depend on a writer's default time zone", and non-deterministic functions are not supported ([schema specification](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/spec/schema.md)).

**For Iceberg.** DuckLake's dialect field records whose functions an expression uses, and Paimon's rejection of zone-dependent expressions is a classification by what the expression reads. Both are partial forms of the classification the note proposes, and Delta's writer feature is [prose fix 6](prose-fixes.md#6-writers-that-cannot-evaluate-a-default-must-fail) enforced through a declaration.

## Not covered

Nothing bearing on E29 or on V3 to V6 was found beyond what is reported above. The index-related problems V13 and V14 are covered in the [companion document](../proposals/prior-art.md).

## Caveats

- The Delta protocol says that features are never removed from the `protocol` action, while its type widening section and the shipped drop-feature command remove them. The two `variantType` and `typeWidening` features are defined but absent from the specification's table of feature names at the commit read.
- Hudi's 1.x specification claims to describe table version 9 but describes the column statistics schema written by version 8.
- Lance was read from a shallow clone, so the release in which each passage first appeared is not known, and several features cited are reserved or marked unstable.
- Paimon's documentation was read from the repository, whose layout has moved; website addresses may differ.
- Hive's transactions documentation dates in part from release 0.14.

## Sources

All sources were read on November 11, 2026.

- Delta Lake: [`PROTOCOL.md`](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md) and documentation at delta-io/delta `ecde102`.
- Apache Hudi: the [1.x technical specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md) and documentation at asf-site `56ebc22` (table version 9, release 1.2.0); source at master `9a7728c`.
- Apache Paimon: specification, documentation and source at master `d98f087`.
- Lance: format specification, protobuf definitions and source at lance-format/lance `fbfea4f`.
- DuckLake: specification 1.1 and extension documentation at duckdb/ducklake-web `139cfaf`; extension source at duckdb/ducklake `1adfc4c`.
- Hive ACID: source at apache/hive master `3802476`, the [transactions documentation](https://hive.apache.org/docs/latest/user/hive-transactions/) and the [ORC ACID documentation](https://orc.apache.org/docs/acid.html).
