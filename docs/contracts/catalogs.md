# Iceberg Now Requires a Coordination Service

*A note recording that Iceberg has decided, in effect from format version 4, that a conforming table relies on a central coordination service (a catalog) to commit and may rely on one to be read, and that the role of that service is growing well beyond the atomic swap it was first needed for. Whether the service speaks the REST protocol or another is an implementation detail; the reliance on a stateful, authoritative service is the substance of the change. The note does not argue against it. It argues that the reliance should be stated plainly, in the specification and in the documents of this repository, and it sets out what follows from it.*

**Author:** Alex Cruise  
**Updated:** November 13, 2026  
**Status:** draft, not yet circulated; against apache/iceberg `main` at commit `0cb5041` for `format/spec.md`

## What has been decided

Two changes to the table specification, one adopted and one drafted, together remove the possibility of a conforming Iceberg table without a catalog.

The first is the deprecation of file system tables. Iceberg has specified two schemes for the atomic swap on which every commit depends: *file system tables*, which commit by renaming a uniquely named metadata file to the next numbered name, and *metastore tables*, which commit by a check-and-put on a pointer held in a metastore or database. In August 2024, following [agreement on the dev list](https://lists.apache.org/thread/oohcjfp1vpo005h2r0f6gfpsp6op0qps), [pull request 10833](https://github.com/apache/iceberg/pull/10833) marked the first scheme as follows:

> This file system based scheme to commit a metadata file is **deprecated** and will be removed in version 4 of this spec. The scheme is **unsafe** in object stores and local file systems.

No storage-only scheme has been proposed to replace it, so from version 4 the only specified way to commit is through a metastore: a service, outside the object store, that holds the pointer to the current metadata and serializes every change to it.

The second is the version 4 treatment of a table's location. Version 4 permits relative paths in metadata, resolved against the table location, and makes the `location` field of table metadata optional. When it is absent, "the table location must be managed externally and provided when loading the metadata", and the specification adds that "how the table location is persisted or determined when not specified in metadata is not a table-level concern; catalogs should provide a table's location." A version 4 table whose metadata omits its location therefore cannot be read from its metadata files alone, because none of its relative paths can be resolved. Version 4 is still marked as under active development and not formally adopted, but this text is in the draft on `main`.

The first change makes a coordination service necessary for writing; the second makes one necessary, for some tables, for reading. Neither says which service or which protocol, and neither needs to. What has changed is that a stateful service now stands between every writer and the table, where until now the object store alone could serve, and that the table's availability, durability and correctness now include that service's.

## What the reliance consists of

A coordinator in the sense of the metastore scheme has to provide very little: a linearizable check-and-put on one pointer per table, durably. Version 4 adds a second duty, supplying a table's location when its metadata omits it. Those two duties are all the table specification asks, and a Hive metastore, a JDBC database, AWS Glue and a REST server all discharge them.

The properties the coordinator must therefore have are the familiar ones of any central service on a critical path, and they are worth naming because the specification does not:

1. **Linearizability** of the swap, since every commit's validation assumes that the pointer it read is the pointer it replaces.
2. **Availability**, since no commit can proceed without it, and for some tables no read.
3. **Durability** of the pointer and of the location, since the object store no longer records which metadata file is current.
4. **Correctness**, since a coordinator that swaps the wrong pointer, supplies the wrong location or, as below, writes the wrong metadata, corrupts the table with no client misbehaving.

All four held of file system tables by virtue of the file system. They now hold, or fail, by virtue of a service the table does not describe.

## How the coordinator's role is growing

The specification still requires of a coordinator only the check-and-put of the metastore scheme. The capabilities added to Iceberg since, however, have each widened what the coordinator does, and each has been specified as an endpoint of the REST catalog's OpenAPI description, which is the protocol in which Iceberg currently specifies coordinator behavior. The protocol is incidental; the widening is the point:

| Capability | What it adds to the coordinator's role | Where it is specified |
|---|---|---|
| Commits applied by the server | Writer of table metadata, not only keeper of its pointer | The REST commit protocol: requirements and updates applied by the server |
| Server-side scan planning | Reader of all table metadata, on every query's critical path | `/tables/{table}/plan`, `/plan/{plan-id}` and `/tasks` |
| Storage credentials scoped to a table | Authority over who may read and write which files | `/tables/{table}/credentials` |
| Remote request signing | The same, per request | `/tables/{table}/sign` |
| Commits spanning several tables | Serialization point across tables, not only within one | `/transactions/commit` |
| Functions | Keeper of code that stored expressions invoke | `/namespaces/{namespace}/functions` |
| Commit and scan metrics | Observer of every operation | `/tables/{table}/metrics` |
| Secondary indexes (proposed) | Authority over index state | Index endpoints in [pull request 16963](https://github.com/apache/iceberg/pull/16963); the index specification of [pull request 16961](https://github.com/apache/iceberg/pull/16961) leaves discovery to "the catalog" |

Proposals still under review assume the same direction. In the review of the materialized view specification ([pull request 11041](https://github.com/apache/iceberg/pull/11041)), one of its authors explained the requirement that a storage table be registered in the catalog by saying that "the iceberg community wants to move away from the metadata.json files in the long term". That is one contributor's reading, not a decision, but it is the reading on which a specification is being built.

The coordinator that version 3 needed only as a register is becoming the component through which every capability beyond reading and writing files is offered. Nobody has proposed that it be required to offer them, and the specification may never say so; it does not need to, if every new capability arrives as something only the coordinator can do. A coordinator offering these capabilities over some protocol other than REST would leave the reliance exactly as it is, and the question worth asking is how much of the table a deployment is prepared to route through one service, not which protocol that service speaks.

## The same move elsewhere

Iceberg is not alone in moving authority from storage to a service. Every open table format has acquired a catalog-managed mode in the last two years, or was designed around one from the start, and the reasons given are mostly not commit atomicity: governance, access control and credential vending, commits that span tables, and a server that can enforce rules no client can be trusted to follow. Iceberg differs in having made the move a requirement. The others keep a storage-only path as the floor.

| Format | Commit authority today | Direction |
|---|---|---|
| Delta Lake | Creation of a numbered log file in storage, by default | The `catalogManaged` table feature, accepted on February 17, 2026, makes "the catalog … the source of truth" for tables that enable it; an earlier coordinated-commits RFC was rejected in April 2025 ([protocol](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#catalog-managed-tables); [RFC index](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/protocol_rfcs/README.md)). Opt-in per table. |
| Paimon | Atomic rename or conditional create, optionally with a Hive or JDBC lock ([concurrency control](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/concurrency-control.md)) | A REST catalog that vends table-scoped credentials ([REST API](https://github.com/apache/paimon/blob/d98f08784c2cf9aed42cc759b3f9c1b3c9bec628/docs/docs/concepts/rest/rest-api.md)), beside the file system catalog. |
| Lance | Rename-if-not-exists or put-if-not-exists in storage, with an optional DynamoDB manifest store ([transaction specification](https://github.com/lance-format/lance/blob/fbfea4f8c5cd2f008e117fdaf5cf7fde000092d6/docs/src/format/table/transaction.md)) | [Lance Namespace](https://github.com/lance-format/lance-namespace) specifies both a REST catalog, "ideal for data infrastructure teams that want to develop their own custom handling", and a directory catalog, "a storage-only catalog spec that requires no external metadata service dependencies". |
| Hudi | A timeline in storage, with a distributed lock for concurrent writers ([specification](https://github.com/apache/hudi/blob/56ebc227f0154c913ecdbc85ae186b0a0ca56083/website/learn/tech-specs.md)) | A coordination service for locking, long required for multiple writers, but not a source of truth. |
| DuckLake | A SQL database holding all metadata, with data in Parquet | Designed from the start around the database. Its [manifesto](https://ducklake.select/manifesto) (announced May 27, 2025) puts the argument this note is circling directly: "the Iceberg and Delta Lake designs already had to compromise and add a database as part of the catalog for consistency", and "once a database has entered the lakehouse stack anyway, it makes an insane amount of sense to also use it for managing the rest of the table metadata". |
| Hive ACID | The metastore, always | Where the lineage began. |

The timing of Iceberg's decision is unfortunate in one respect. The deprecation of file system tables was merged on August 4, 2024, on the ground that rename-based commits are unsafe on object stores. On August 20, 2024, Amazon S3 [added conditional writes](https://aws.amazon.com/about-aws/whats-new/2024/08/amazon-s3-conditional-writes), an atomic create-if-absent by `If-None-Match: *`, and Google Cloud Storage and Azure Blob Storage already offered the equivalent (see the note's table of [object store guarantees](README.md#object-stores-guarantee-by-guarantee)). The primitive that a safe storage-only commit protocol needs became available on every major object store sixteen days after Iceberg decided not to have one, and the decision did not consider conditional create as a replacement for rename. That does not argue for reversing the decision, whose other motives stand on their own, but it is the strongest argument for specifying a storage-only protocol as a floor (commit requirement 6 of the [note on contracts](README.md#candidate-requirements)), which is what every other format in the table has kept.

DuckLake's framing is the clearest statement of where the trend leads, and it is worth taking seriously rather than as provocation: if every deployment ends up running a transactional database beside its object store, then the files-on-storage metadata layer is carrying weight that the database could carry more cheaply. Iceberg's answer, implicit in version 4, is to keep the files as the interchange format and a coordination service as the authority over them. That is a defensible middle position, and it is the one this note asks Iceberg to state.

## Why it should be stated

A decision that is visible only as a deprecation note in one section and an optional field in another will be discovered by implementers one consequence at a time. Several consequences below are significant enough that a deployment should be able to learn of them before choosing a format version, and a specification that relies on a coordination service should say what it relies on the service for. The specification's [design principles](https://iceberg.apache.org/spec/#goals) include that job planning "will be handled primarily by clients and not bottleneck on a central metadata store", which remains true for planning but no longer describes the table as a whole, and the gap between the stated goal and the drafted text is itself a reason to write the decision down.

None of this is an objection. A coordination service is a better place than an object store for the atomic swap, and a server that sees every commit can do things no client can (implication 7 below names some from which this repository's own problems would benefit). The point is only that the change is large enough to deserve a sentence of its own.

## Implications

### For reading and availability

1. **Table metadata is no longer self-describing.** A version 4 metadata file without `location` cannot be interpreted without the catalog, or without the location supplied out of band. Tools that read a table from a metadata path, for disaster recovery, forensic inspection, migration or ad hoc querying, need a convention for supplying it, and the specification says only that catalogs "should" provide it. What a reader must do when nothing provides it (refuse, presumably) is not stated.
2. **The catalog's availability becomes the table's.** A reader that obtains the location, credentials or a scan plan from the catalog cannot read the table while the catalog is down, even from snapshots it has read before. Under file system tables, and to a lesser degree under metastore tables with absolute paths, a reader that knew a metadata location could proceed without the catalog.
3. **The object store is no longer a complete backup.** The current-metadata pointer, and for version 4 tables possibly the location itself, live in the catalog's own store. Backup, replication and disaster recovery must include the catalog, and recovery from object storage alone depends on the `register` endpoint or its equivalent being given the right metadata file, which the object store cannot identify unaided: metastore tables number metadata files by version, but a failed commit leaves a file with the same number as the one that succeeded, and nothing in storage records which was committed.

### For writing and deployment

4. **Deployments without a catalog server have no conforming way to commit.** HDFS deployments that use file system tables, and embedded uses such as single-user tools and test fixtures, must run a coordination service for version 4 tables, whatever its protocol. Implementations can still offer an embedded catalog (a JDBC catalog over a local database, for instance), but that is now an implementation's convenience and not part of the format. This is [V10](problems.md#v10-no-catalog-free-commit-protocol-remains) in the register.
5. **Designs that commit through storage alone have no specified protocol to align with.** The discovery chains of the [Puffin proposal](../proposals/puffin-blob-types-and-compaction.md) and the [decoupled index metadata note](../proposals/decoupled-index-metadata.md) advance by conditional create on the object store. They remain sound, but they are now the exception in an ecosystem that commits through catalogs, and they should be presented as a floor for catalogs that offer nothing more, not as the primary mechanism.

### For trust and enforcement

6. **The catalog becomes the point of authorization.** Credential vending and remote signing already make it the party that decides who may read and write which files. That is a good place for the decision, and it also means the catalog's correctness is part of the table's.
7. **Validation can move into the catalog.** E3 in the register observes that commit validation is enforced by nothing outside the writer and cannot be checked afterward. A catalog that sees every commit can enforce validation, or at least check that a commit's declared read base is consistent with what it changed, and server-side conflict resolution has already been proposed for compatible appends ([issue 14627](https://github.com/apache/iceberg/issues/14627)). Several of this repository's commit requirements would be easier to meet by asking the catalog to enforce them than by asking every writer to comply.

### For interoperability

8. **"Conforming" needs a qualifier.** A coordinator that only swaps a pointer conforms to the table specification while offering none of scan planning, credentials, functions or, if they are specified as proposed, indexes. Two engines can both claim Iceberg support and fail to share a table's features because their coordinators offer different capabilities. Compatibility statements will need to say which coordinator capabilities they assume; the protocol over which those capabilities are reached is a lesser matter.
9. **Features defined only as coordinator capabilities leave minimal coordinators behind by default.** That is acceptable if it is intended, and it should then be said; if it is not intended, each such feature needs a statement of what a client must do when its coordinator lacks it.

## What works with a coordinator that does not understand version 4

The specification versions tables, not coordinators, and nowhere says what a coordinator must do to host a version 4 table. The answer depends on how much of the table the coordinator understands, which is a matter of its role and not of its protocol, and it divides sharply, in a direction that may be unexpected: the coordinators that do least are the most forward-compatible.

A coordinator that only swaps a pointer (a Hive metastore, AWS Glue and a JDBC database, as Iceberg uses them) never parses table metadata. It stores the location of the current metadata file and replaces it by check-and-put, so a version 4 metadata file is as opaque to it as a version 1 file was, and nearly everything version 4 adds is done by clients. A coordinator that writes metadata is different in kind. Under the REST protocol, which is the case in practice, a client commits by sending requirements and updates, and the server applies them to its own model of the table and writes the new metadata file; loading a table returns metadata the server has serialized. Such a coordinator is a writer of table metadata, and one that does not understand version 4 cannot commit a version 4 table at all. The specification's rule that an implementation "must throw an exception if a table's version is higher than the supported version" applies to it as to any writer, and is what keeps an older server from silently dropping fields it does not model (E25 at the catalog). Correctness is protected by refusal; availability is not.

| Version 4 feature | Coordinator that only swaps a pointer, unaware of version 4 | Coordinator that writes metadata, without version 4 support |
|---|---|---|
| Adaptive metadata tree, root manifests, colocated deletion vectors, column files | Works: entirely client-side | Refuses the table |
| Relative paths, with `location` present in metadata | Works: paths resolve against the recorded location. Relocation still needs only one new metadata file with a new `location`, not a rewrite of every manifest | Refuses |
| Relative paths, with `location` omitted | Works only where the catalog stores a location and an implementation convention supplies it (a Hive or Glue table's storage location, for instance); the specification defines no such convention. A JDBC catalog that stores only the metadata pointer cannot supply one, and such a table is unreadable through it | Refuses |
| Constraints, default value expressions, the `file` type, the prohibition of new equality deletes, strictly increasing snapshot timestamps | Works: enforced by writers | Refuses |
| Commits | Works: check-and-put is all the specification asks | Refuses |
| Server-side scan planning, credential vending, remote signing, multi-table commits, functions | Not available, as for earlier versions | Available only for versions the server supports; planning a version 4 table needs a server that reads version 4 manifests |
| Secondary indexes as drafted in pull request 16961 | Not available, unless indexes have a floor that does not depend on the coordinator | Not available until the server implements the index endpoints |

Two qualifications apply to the first column. Some pointer-swap coordinators are not purely opaque in practice: the Java Hive catalog mirrors a table's schema into the metastore for Hive's benefit, and a type the mirroring cannot express, such as `file`, may fail there (not verified here). And a vending catalog that scopes credentials to the table location will miss files that version 4 records by absolute paths outside it, which is E10 in a new form.

Four conclusions follow:

1. **A version 4 table that keeps `location` in its metadata can be operated correctly through any coordinator that swaps a pointer**, for every feature the table specification defines. The features that need more than that are the ones defined as coordinator capabilities, which were never available through such coordinators.
2. **Omitting `location` is the one table-level feature of version 4 that depends on the coordinator**, and it depends on a convention the specification leaves to "should". Writers that cannot be sure their table's coordinator supplies a location should keep the field, and the specification could say so.
3. **A coordinator that writes metadata must be upgraded before any of its tables can be.** That is unremarkable once stated, but it is the reverse of the usual assumption that the catalog is the simplest component in a deployment, and it means that wherever the coordinator writes metadata (today, wherever a REST catalog is used) the pace of version 4 adoption is set by the coordinator's vendor, not by engines. The more of the table's work the coordinator takes on, the more of the format's evolution it gates.
4. **A coordinator that writes metadata can inherit its claimed format support from its library.** A coordinator built on a library implementation of the table specification decides whether to accept a table by the library's check of the format version, and the library may raise its supported version before the coordinator has done any work for that version, or before the version has been adopted. The coordinator then accepts tables it was never tested against and writes their metadata in whatever shape its library models, which for a draft version may not be the shape the draft has since settled on. Nothing fails, because the refusal rule is satisfied by the library's claim. The remedy is for a coordinator that writes metadata to declare the format versions it supports itself, and to refuse others even where its library would accept them; where the coordinator advertises its capabilities to clients, as REST servers do through their configuration endpoint, the supported versions belong there too.

## What this repository should do

1. **State the decision as settled.** V10 and the note's section on [commits without a catalog server](README.md#commits-without-a-catalog-server) treat the removal of file system tables as a version 4 problem to be solved; they should instead record it as decided, and present a storage-only commit protocol (commit requirement 6) as a minority option for deployments that need one, not as a gap in the specification.
2. **Say what each proposal assumes of the coordinator.** The Puffin proposal and its companions should state, near the top, which of their mechanisms need only the atomic swap, which need a coordinator capability beyond it, and which need no coordinator at all, so that a reader can judge them against the arrangement above.
3. **Prefer asks that a coordinator can enforce.** Where a candidate requirement could be met either by every writer or by the coordinator, say so, since the second is now the cheaper route to compliance.

## Questions for the community

These are asked to clarify, not to reopen the decision.

1. What does version 4 require of the coordinator? The text implies a linearizable, durable check-and-put on the metadata pointer, plus a location when metadata omits it. If that is the whole requirement, the specification should say so in one place, together with the properties the coordinator must have; if more is intended, it should say what. The protocol need not be specified; the role should be.
2. What must a coordinator do to host a version 4 table? For one that only swaps a pointer the answer appears to be nothing beyond supplying a location when metadata omits it; for one that writes metadata it is the whole of version 4. Stating both would let operators plan upgrades in the right order, and a coordinator that writes metadata should declare which format versions it supports rather than inherit the answer from its library (conclusion 4 above).
3. When a version 4 table's metadata omits `location` and nothing supplies it, must a reader refuse? The "should" in the current text leaves it open.
4. Will implementations continue to support file system tables for tables at version 3 and below, and for how long?
5. Is there to be a supported way to recover a table from its object storage alone, given that the current metadata file cannot be identified by its name when failed commits have left others with the same version number?
6. For capabilities only a coordinator can offer, what must a client do when its coordinator lacks one: fall back, refuse, or proceed without the capability? The answer probably differs by capability and should be stated for each, much as the [proposal on sidecars](sidecars.md) asks each kind of sidecar to state its reliance.
