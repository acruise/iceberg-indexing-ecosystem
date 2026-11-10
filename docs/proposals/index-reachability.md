# A Possible Issue with Index Reachability and Table Maintenance

*A working note on what becomes of index files when table maintenance does not know they exist, with an account of where the secondary index specification under review in apache/iceberg [pull request 16961](https://github.com/apache/iceberg/pull/16961) stands and of how Iceberg and other table formats have fared with the same problem.*

**Status:** working note supporting Section 14.1 of [the proposal](puffin-blob-types-and-compaction.md), which summarizes it. Sources were read on November 9, 2026. Section 11.3 of the proposal states the hazard as it bears on this proposal alone, and the companion note on a [decoupled metadata mechanism](decoupled-index-metadata.md) proposes a remedy that would serve both index designs.

## Problems that exist with or without this proposal

The problems below belong to the secondary index specification as drafted, on the assumption that it ships with format version 4 in its current form. None of them depends on Puffin blob types or on anything else the companion proposal defines, and each would remain if that proposal were withdrawn tomorrow. They are set out first because they are the part of this note the index specification's authors have most reason to care about, and the evidence for each is in the sections that follow.

### Urgent

The first three problems can each turn into lost indexes, and they can only be solved cheaply before version 4 is published. A short addition to the specification would answer all three: a list of index roots in table metadata, together with obligations on version 4 orphan removal to spare what lies beneath those roots and on version 4 expiration and purge to reclaim it. The [decoupled metadata note](decoupled-index-metadata.md) works the addition out in detail.

1. **Conforming maintenance may delete indexes.** The specification does not oblige table-level orphan removal, or expiration and purge, to account for indexes. A format version fences out implementations that predate it, but an implementation can conform to version 4 and still delete every index beneath the table location, because version 4 does not tell it that the files are reachable. Whether the specification even requires a table that carries an index to be at version 4 has not been established here; if it does not, every current implementation is exposed as well.
2. **The default places indexes where orphan removal looks.** The worked example and the reference implementation both put index files beneath the table location, and the reference implementation puts its pointer files beneath the table's `metadata` directory. Orphan removal lists both locations, and even the dev-list proposal to narrow it to `data` and `metadata` would not spare the pointer files.
3. **The obligations are cheapest to add before version 4 is published.** Once the version ships without them, any later obligation on maintenance is a behavioral change that is not fenced by any format version, and implementations will adopt it piecemeal. That is what happened with statistics files: the reference implementation alone took four releases to teach all three operations about them, and purge still leaves replaced files behind.

### Important

The remaining problems cost leaked storage and stale indexes, along with some confusion when a table is copied, rather than the loss of an index, and under the reader's rule of the decoupled note query results stay correct in each case. Where the remedy is an obligation on maintenance, as it is for the fifth and sixth, it belongs in the same addition and shares the third problem's deadline. The list of index roots also shrinks the fourth problem, since maintenance would then find indexes through table metadata rather than through the catalog.

4. **Maintenance has no interoperable way to find a table's indexes.** Discovery is "out of scope" for the specification and is left to the catalog, so an orphan removal or purge running against a table learns of its indexes only if the catalog implements the index endpoints of pull request 16963 and the maintenance code asks. Tables in the Hive Metastore cannot have indexes that maintenance knows about at all, and neither can tables in the AWS Glue and JDBC catalogs or in any other catalog without those endpoints. The catalog thereby governs an index's safety under maintenance as well as the index's discovery.
5. **Dropping the table leaks its indexes.** The specification does not tie an index's lifetime to its source table. The REST endpoints purge an index's files only when that index is itself dropped with `purgeRequested`, and the reference implementation's documentation concedes that most catalogs will not purge index files on table drop.
6. **Table snapshot expiration and index extracts are unrelated.** An extract can outlive the snapshot it indexes, as a reviewer has noted, and expiration is not obliged to collect it, nor is the index's own maintenance obliged to notice that its snapshot is gone. The review discussion has raised this but not settled it.
7. **Tools that copy a table (replication included) or register it in another catalog do not account for its indexes.** Because indexes are located through the catalog by the table's identity, a table registered in another catalog loses them, and pointer files copied along with the `metadata` directory would name the original index's metadata. The reference implementation's pointer paths include the table UUID, which limits the confusion, but the specification does not rely on that.
8. **Rewrites make the index unusable until it is rebuilt.** The reference implementation declines to use an index across any snapshot that is not an append, so each compaction disables the index until a refresh whose cost is proportional to the size of the index. That follows soundly from a snapshot-pinned positive index and is not a defect, but it is a cost every table that compacts will pay, and it compounds the maintenance problems above, since an index that is often stale is easy to lose without anyone noticing.

## The hazard

Three maintenance operations decide the fate of every file beneath a table, and each decides by consulting the structures its implementation knows how to read:

* Orphan file removal lists the table location and deletes every file older than a threshold that it cannot reach from table metadata.
* Snapshot expiration deletes files that only expired snapshots reference.
* Dropping a table with purge deletes the files that table metadata reaches.

An auxiliary structure that none of these operations reads is therefore deleted by the first, if it lies beneath the table location, and leaked by the other two wherever it lies. The deletion is silent, since a planner that finds no index plans without one and the query result does not reveal that the index was ever there.

What makes the hazard durable is that reachability, in practice, is defined by maintenance code rather than by the specification. The specification says what table metadata contains. It does not say that orphan removal must treat a given structure as reachable, and every engine that ships a maintenance procedure has to be taught each new structure separately.

A blob's scope does not decide whether it is exposed, since the hazard turns on placement and on what table metadata references. The secondary index specification's extracts are bound to snapshots and are exposed all the same, because table metadata does not list them.

What scope decides is whether an existing structure can hold the blob. Scope also changes the rule by which a collector reclaims index state and the checks a reader makes before trusting it, and the [decoupled metadata note](decoupled-index-metadata.md) treats both as its two validity modes. The scopes compare as follows.

* **Snapshot scope.** A snapshot-scoped blob fits in a statistics file, which table metadata already references and which maintenance has learned to honor, with the gaps recorded below, so placing it there costs nothing new. Such blobs need only the carry-forward rule of Section 3.4 of the [phase 1 specification](phase-1-open-blob-types.md).
* **File scope.** A file-scoped blob remains valid across every snapshot in which its data file is live, and a per-snapshot statistics file could hold it only by copying it, or a reference to it, into every later snapshot's file. Each such copy depends on the writer of that snapshot recognizing the blob type, and one unaware rewrite would discard state that many snapshots had accumulated, where the same failure costs a snapshot-scoped blob only its own snapshot's index. File scope is therefore what obliges [the proposal](puffin-blob-types-and-compaction.md) to keep a structure parallel to table metadata (its Section 6.5) and so to face this hazard directly.
* **`puffin-file` scope.** A blob of this scope, which describes another Puffin file rather than a snapshot or a data file, inherits the hazard at one remove: it is valid only while the file it describes is reachable, so its collector must apply whichever rule governs that file, and an index file deleted or leaked by maintenance takes every blob describing it along.

## Iceberg's own record with auxiliary files

Statistics files show how this goes even when the structure is defined by the specification itself and listed in table metadata. The `statistics` field entered the specification in 2022 as an optional field of every format version, and the first Java release to read it was 1.1.0. Partition statistics files followed in 1.5.0. The table traces, release by release, when each maintenance operation in the reference implementation learned to handle each structure.

| Structure | Listed in table metadata from | Spared by orphan removal from | Collected by expiration from | Removed by purge from |
|---|---|---|---|---|
| Statistics files | 1.1.0 | 1.1.0 ([#5795](https://github.com/apache/iceberg/pull/5795)) | 1.2.0 ([#6090](https://github.com/apache/iceberg/pull/6090); Spark actions followed in [#6091](https://github.com/apache/iceberg/pull/6091) and [#7074](https://github.com/apache/iceberg/pull/7074)) | 1.5.0 ([#9305](https://github.com/apache/iceberg/pull/9305)), latest file only |
| Partition statistics files | 1.5.0 | 1.5.0 ([#9284](https://github.com/apache/iceberg/pull/9284), fixing [#9336](https://github.com/apache/iceberg/issues/9336)) | 1.5.0 ([#9284](https://github.com/apache/iceberg/pull/9284)) | 1.5.0 ([#9409](https://github.com/apache/iceberg/pull/9409)), latest file only |

Even in 1.5.0, purge removed only the latest statistics file of each snapshot, and older replaced files remained ([#11876](https://github.com/apache/iceberg/issues/11876)); the fix proposed for that ([#12132](https://github.com/apache/iceberg/pull/12132)) was closed without merging.

The table shows the best case, a structure designed by the project and listed in table metadata, and even then the reference implementation's maintenance did not reach the first such structure from all three operations until 1.5.0, four releases after it arrived in 1.1.0, and purge covers it only partly even now. No format version fenced either one. An implementation older than the release in which a structure arrived does not parse the field, and so drops it when it commits new metadata. Whether such an implementation would also delete the files as orphans has not been tested here, although its reachable set would not protect them.

A second strand of the record concerns how orphan removal matches locations. Iceberg does not normalize the locations it records, and each of the following is a way for orphan removal to delete files it should have kept, although none of them involves an index:

* [#4346](https://github.com/apache/iceberg/issues/4346) reported that "`DeleteOrphanFiles` can corrupt a table" when the scheme or authority of a listed file differs from the recorded one (`s3a` against `s3`, or an IP address against a host name). The prefix-mismatch options added in 1.1.0 ([#4652](https://github.com/apache/iceberg/pull/4652), [#5385](https://github.com/apache/iceberg/pull/5385)) let operators ignore or fail on such differences.
* The open issue [#16493](https://github.com/apache/iceberg/issues/16493) reports, from source review, that the Spark implementation matches by raw string prefix, so that a table at `s3://bucket/table` also matches `s3://bucket/table-backup/`.
* A Flink fix in 1.11.0 ([#16324](https://github.com/apache/iceberg/pull/16324)) addressed orphan deletion caused by a stale table in the metadata listing.

A 2025 proposal on the dev list ([karuppayya](https://go.mail-archive.com/dev@iceberg.apache.org/msg09495.html)) would have orphan removal list only a table's `data` and `metadata` directories. Replies pointed out that the specification does not fix where data and metadata live, since a custom location provider may put them anywhere ([Pucheng Yang](https://go.mail-archive.com/dev@iceberg.apache.org/msg09496.html)), and suggested a configurable pattern instead ([Szehon Ho](https://go.mail-archive.com/dev@iceberg.apache.org/msg09534.html)). The outcome is not established here.

## The secondary index specification

The specification does not define a default location for an index. Its index metadata carries a required `location`, the "Index root location", against which relative locations are resolved, but the artifacts around the specification all place the index somewhere in particular:

* The specification's worked example places the index beneath the table location, at `s3://bucket/warehouse/default.db/events/index/bucket_index`.
* The REST endpoints of [pull request 16963](https://github.com/apache/iceberg/pull/16963) make the location optional, with the server choosing one if it is omitted.
* The Spark reference implementation of [pull request 18187](https://github.com/apache/iceberg/pull/18187) defaults to `table.location() + "/index/" + <columns> + "_idx"`, and its catalog writes pointer files to `<table location>/metadata/scalar-indexes/<table UUID>/<index name>.pointer`.

Index metadata is located through the catalog alone. The specification says that "It is the catalog's responsibility to map index names to metadata file locations," and lists among its goals that an index "is committed as a separate object, without modifying its source table."

Its only statement about reclaiming files concerns the index's own commits: "The `extracts` list of the current index metadata file is the only reachability root, so a commit that removes an extract should delete the files that no remaining extract references." The REST endpoints delete index files only when an index is dropped with `purgeRequested`, and otherwise leave deletion to the client. The specification and the REST endpoints are silent on orphan removal and on table snapshot expiration. Neither addresses what dropping the source table does to its indexes, a point the reference implementation's own documentation concedes: "most catalogs do not guarantee purging files this class does not know about on drop, since they are outside Iceberg's own metadata/manifest tracking entirely." The reference implementation does not change any of the existing maintenance procedures.

The specification's text, then, does not stop a table-level orphan removal run from an engine unaware of indexes from deleting the index files beneath `<table location>/index/`, or from deleting the pointer files beneath `<table location>/metadata/`. The second point matters, because it means that the narrower listing proposed on the dev list would not spare the pointer files. That conclusion is drawn from the code and the documentation and has not been tested.

As of November 9, the review comments on the three pull requests do not mention orphan removal. Reviewers have discussed how extracts relate to snapshot expiration ([pvary](https://github.com/apache/iceberg/pull/16961#discussion_r3528668880), [huaxingao](https://github.com/apache/iceberg/pull/16961#discussion_r4150930181), [osscm](https://github.com/apache/iceberg/pull/16961#discussion_r4224213894)), and a reviewer of the REST endpoints lists index lifecycle among the questions the ecosystem has not settled ([yyanyy](https://github.com/apache/iceberg/pull/16963#pullrequestreview-4621822067)).

The specification does not name a catalog. It assigns name mapping and discovery to "the catalog specification" (its notes 3 and 4), and its commit section requires only an atomic swap that succeeds if the writer's base is still current, in text contributed during review in terms that admit a metastore check-and-put or an atomic rename as readily as a REST catalog ([osscm](https://github.com/apache/iceberg/pull/16961#discussion_r3523704400)). The only catalog specification that defines index endpoints, however, is the REST catalog's, and one of the specification's authors has described that specification as "where we define the requirements for the Catalogs" ([pvary](https://github.com/apache/iceberg/pull/16961#discussion_r3542961326)).

A reviewer asked that the goals state outright that an index is a catalog-managed object ([yyanyy](https://github.com/apache/iceberg/pull/16961#discussion_r3515609786)), and the same reviewer, on the REST endpoints, listed discovery and lifecycle as unsettled, along with credential vending, and suggested a focused design document. As of November 10, the comments on the three pull requests have not asked how a table whose catalog does not implement the REST protocol would obtain an interoperable index. The reference implementation answers that question privately: its `DurableIndexCatalog` persists a pointer file for each index beneath the table location so that registrations survive a restart, which amounts to an index catalog that other engines do not know how to read.

What the specification avoids, therefore, is contention with the table's writers, a different property from the hazard, and in avoiding it the specification moves index state to a place that table maintenance never looks.

## Other table formats

**Delta Lake** documents the hazard for shallow clones, which reference data files in the source table's directory: "If you run `vacuum` on the source table, clients will no longer be able to read the referenced data files" ([utilities documentation](https://docs.delta.io/latest/delta-utility.html)). Separately, [delta-io/delta#1797](https://github.com/delta-io/delta/issues/1797) reports a VACUUM configuration under which recursive listing deleted files in `_delta_log` itself.

Delta's open Record-Level Index RFC ([delta-io/delta#7436](https://github.com/delta-io/delta/pull/7436)) is the closest parallel to the index designs discussed here, and it takes the opposite approach to coupling. Index files live under `_delta_log/_record_index/`, and writers "must commit the updated `delta.recordLevelIndex` `domainMetadata` action **in the same commit** as the data changes it describes." For maintenance, the RFC says that tables enabling the index "should also support VACUUM Protocol Check so that older VACUUM implementations cannot delete live index files." The [VACUUM Protocol Check](https://github.com/delta-io/delta/blob/master/PROTOCOL.md#vacuum-protocol-check) feature makes a VACUUM implementation refuse a table whose writer features it does not support, so Delta can fence the one dangerous operation without fencing readers, a granularity Iceberg's format version does not offer.

**Apache Paimon** records "associated files, such as external per-file indexes" in a manifest's `_EXTRA_FILES` field ([manifest specification](https://paimon.apache.org/docs/master/concepts/spec/manifest/)). [apache/paimon#9886](https://github.com/apache/paimon/pull/9886), merged in September 2026, made expiration, tag retention and orphan cleanup follow those explicit references, "rather than names inferred from a manifest suffix." The open [apache/paimon#7715](https://github.com/apache/paimon/pull/7715) describes a different failure: when snapshots expire concurrently with orphan cleanup, the set of used files can come back empty, and every candidate data file is deleted as an orphan.

**Apache Hudi** shows the failure on the reader's side. In [apache/hudi#10628](https://github.com/apache/hudi/issues/10628), the metadata table kept listing files that the cleaner had already deleted, and readers that trusted it failed with `FileNotFoundException`. In [apache/hudi#17337](https://github.com/apache/hudi/issues/17337), dropping the record-level index left a dependent secondary index behind.

**Lance** defined index reachability with the index. Its cleanup deletes an index file only "if an index file is not referenced by any valid manifest file," subject to an age guard ([cleanup source](https://docs.rs/crate/lance/0.19.2/source/src/dataset/cleanup.rs)). Its failures run toward staleness rather than deletion: [lance-format/lance#8076](https://github.com/lance-format/lance/issues/8076) reports a scalar index left stale by compaction.

## What the record suggests

The formats that handle auxiliary files well defined their reachability together with the structure, in the same specification and the same release. Lance did so from the start, and Paimon retrofitted it into an explicit field. Delta's RFC pairs its index with a feature that fences old VACUUM. Iceberg's statistics files show the cost of the alternative, which was several releases of fixes spread across operations and engines, with one leak left open.

The usual backstop, an age threshold on orphan removal, protects only for as long as the threshold lasts, and it does not protect files from purge or expiration at all. Location matching is a fragile basis for deletion in any case, as the history of `DeleteOrphanFiles` shows, so a remedy for indexes should name index roots explicitly rather than rely on a directory convention.

Readers, for their part, must not trust an auxiliary structure to be current. Hudi's metadata table failed readers because they trusted it, and both index designs under discussion avoid that by checking index state against table metadata before using it.

For Iceberg, these observations point to two additions. The first is the list of index roots in table metadata that the [decoupled metadata note](decoupled-index-metadata.md) proposes for format version 4, which gives maintenance the explicit reference that Paimon added and Lance had from the start.

The second is narrower, and it is worth raising with the community on its own. Delta's VACUUM Protocol Check shows that a format can fence a single destructive operation without fencing readers. A version 4 table could likewise declare the maintenance capabilities it requires, and an implementation lacking one would refuse to remove orphans from or purge that table. That would let future auxiliary structures arrive within a format version without repeating the history of statistics files.

## Claims not verified here

* That an Iceberg implementation predating 1.1.0 would delete statistics files as orphans. The implementation's reachable set suggests it would, but this was not tested.
* That orphan removal from a current engine deletes the reference implementation's index and pointer files. This is inferred from the code of pull request 18187 and was not tested.
* The outcome of the 2025 dev-list proposal to narrow orphan removal.
* Whether replaced statistics files still leak on purge in current releases, after #12132 was closed.
* The Delta, Hudi and Lance items come from a research pass. Of the items from other formats, only the Delta RFC and Paimon's #9886 were reread for this note.
