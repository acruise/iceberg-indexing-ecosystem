# Pluggable Puffin Blob Types and a Compaction-Aware Blob Lifecycle

*A discussion draft for the Apache Iceberg community.*

**Author:** Alex Cruise  
**Affiliation:** I work for Cloudera but this is intended to become an open source contribution  
**Updated:** October 30, 2026  
**Status:** draft for discussion on the Iceberg dev list

Nothing described here is accepted Iceberg behavior, and the identifiers, property keys and class names below are placeholders chosen to make the discussion concrete.

**Contents**

* [1. Summary](#1-summary)
* [2. Motivation](#2-motivation)
    * [2.1 Puffin today](#21-puffin-today)
    * [2.2 Compaction and out-of-band metadata](#22-compaction-and-out-of-band-metadata)
    * [2.3 Why this matters now](#23-why-this-matters-now)
* [3. Goals and Non-Goals](#3-goals-and-non-goals)
* [4. Blob Type Identifiers and the Registry](#4-blob-type-identifiers-and-the-registry)
    * [4.1 Identifier syntax](#41-identifier-syntax)
    * [4.2 Property keys](#42-property-keys)
    * [4.3 A two-tier registry](#43-a-two-tier-registry)
* [5. Blob Type Descriptors](#5-blob-type-descriptors)
* [6. Data-File-Scoped Blobs](#6-data-file-scoped-blobs)
    * [6.1 Binding](#61-binding)
    * [6.2 What a data-file-scoped blob may assert](#62-what-a-data-file-scoped-blob-may-assert)
    * [6.3 Locating blobs](#63-locating-blobs)
    * [6.4 Garbage collection](#64-garbage-collection)
* [7. Build Configurations](#7-build-configurations)
    * [7.1 Why the type identifier is not enough](#71-why-the-type-identifier-is-not-enough)
    * [7.2 Configuration references](#72-configuration-references)
    * [7.3 Configuration stores](#73-configuration-stores)
    * [7.4 Reader obligations](#74-reader-obligations)
    * [7.5 Descriptor fields](#75-descriptor-fields)
    * [7.6 A worked example: character n-gram Bloom filters](#76-a-worked-example-character-n-gram-bloom-filters)
    * [7.7 Configurations and workload drift](#77-configurations-and-workload-drift)
    * [7.8 Expressions as inputs](#78-expressions-as-inputs)
* [8. Compaction Semantics: Candidate Specification Text](#8-compaction-semantics-candidate-specification-text)
* [9. Java Service-Provider Interface](#9-java-service-provider-interface)
    * [9.1 Interfaces](#91-interfaces)
    * [9.2 Behavior](#92-behavior)
    * [9.3 Why ServiceLoader, and its limits](#93-why-serviceloader-and-its-limits)
    * [9.4 Implementations without run-time discovery](#94-implementations-without-run-time-discovery)
    * [9.5 Prototype](#95-prototype)
* [10. Related Requests: Workload Telemetry](#10-related-requests-workload-telemetry)
* [11. Compatibility](#11-compatibility)
* [12. Related Work: the Iceberg Index Specification](#12-related-work-the-iceberg-index-specification)
    * [12.1 What the index specification defines](#121-what-the-index-specification-defines)
    * [12.2 Positive and negative indexes](#122-positive-and-negative-indexes)
    * [12.3 Points of contact](#123-points-of-contact)
* [13. Related Work Outside Iceberg](#13-related-work-outside-iceberg)
    * [13.1 Apache Paimon's file index](#131-apache-paimon's-file-index)
    * [13.2 Apache Pinot's index service-provider interface](#132-apache-pinot's-index-service-provider-interface)
    * [13.3 Apache Lucene codecs](#133-apache-lucene-codecs)
    * [13.4 ClickHouse data skipping indexes](#134-clickhouse-data-skipping-indexes)
    * [13.5 Apache Hudi's metadata table and expression indexes](#135-apache-hudi's-metadata-table-and-expression-indexes)
    * [13.6 PostgreSQL block range indexes](#136-postgresql-block-range-indexes)
    * [13.7 Lance](#137-lance)
    * [13.8 Delta Lake Bloom filter indexes](#138-delta-lake-bloom-filter-indexes)
    * [13.9 Managed warehouses](#139-managed-warehouses)
* [14. Open Questions](#14-open-questions)
    * [14.1 Where index manifests live](#141-where-index-manifests-live)
    * [14.2 How readers locate blobs](#142-how-readers-locate-blobs)
    * [14.3 Index manifest as a file type or a content type](#143-index-manifest-as-a-file-type-or-a-content-type)
    * [14.4 Which format version](#144-which-format-version)
    * [14.5 Admitting locating and aggregating blobs](#145-admitting-locating-and-aggregating-blobs)
    * [14.6 The expression vocabulary](#146-the-expression-vocabulary)
    * [14.7 Type promotion of referenced fields](#147-type-promotion-of-referenced-fields)
    * [14.8 Encryption](#148-encryption)
    * [14.9 Several blobs of one type for one data file](#149-several-blobs-of-one-type-for-one-data-file)
    * [14.10 Blobs spanning several data files](#1410-blobs-spanning-several-data-files)
    * [14.11 Puffin file granularity](#1411-puffin-file-granularity)
    * [14.12 Descriptor evolution](#1412-descriptor-evolution)
    * [14.13 Registry location and governance](#1413-registry-location-and-governance)
    * [14.14 Recording why a blob was built](#1414-recording-why-a-blob-was-built)
    * [14.15 Retention of out-of-band configurations](#1415-retention-of-out-of-band-configurations)
* [15. References](#15-references)

## 1. Summary

Puffin gives Iceberg a well-specified container for statistics and indexes that live outside data files, but the specification treats a blob as little more than a type string and a byte range, and it says nothing about what should happen to a blob when the data it describes is rewritten by compaction. We propose to close both gaps:

**On the specification side**, we propose:

* a namespace for blob type identifiers with a lightweight community registry
* a machine-readable descriptor for each blob type
* a general notion of blobs scoped to a single data file (generalizing the binding that v3 deletion vectors already use)
* a way for each blob to record, by content digest, the configuration under which it was built, so that readers resolve that configuration from an in-band or out-of-band store and evaluate the blob under it or not at all, never under an assumed one
* candidate normative text for how compaction treats such blobs

**On the implementation side,** we propose:

* a service-provider interface in the Java library, discovered through [`java.util.ServiceLoader`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.html), so that engines can handle registered blob types, compaction included, without compile-time knowledge of them; it is the Java binding of a contract that lives in the format, and the other Iceberg implementations would bind it in their own idioms.

The design has one property that we consider essential and that shapes most of the detailed choices: **because a data-file-scoped blob is derived from its data file and valid only while that file is live (Section 6.2), an implementation that ignores this proposal entirely** (an older reader, or a compactor with no knowledge of a given blob type) **can lose index coverage but can never produce an incorrect query result.** Every new obligation is therefore an opportunity to retain value rather than a new way to corrupt a table, which we believe is what makes it plausible to adopt incrementally and without a format version increment.

The subject of the proposal is this enablement of an open ecosystem of blob types, and its motivating case is the index built after the fact: added to a data file some time after the file was written, by a process that has seen which predicates the file actually receives, and carried forward or rebuilt as compaction consolidates the file. Nothing here requires that timing, and an engine may equally build blobs with the data or inside a compaction, but the after-the-fact index is the case that exposes every gap this proposal closes, since it needs a type nobody has standardized, a binding to a file rather than a snapshot, a record of how it was built, and a compaction rule, and it is the case against which each mechanism below was tested.

## 2. Motivation

### 2.1 Puffin today

The observations in this section were checked against the Iceberg 1.12.0 source distribution (`iceberg-api`, `iceberg-common`, `iceberg-core` and `iceberg-data`) and the published [Puffin](https://iceberg.apache.org/puffin-spec/) and [table](https://iceberg.apache.org/spec/) specifications.

A Puffin file consists of a sequence of blobs followed by a JSON footer that records, for each blob:

* its `type`
* the `fields` from which it was computed
* a `snapshot-id` and `sequence-number`
* its `offset` and `length`
* an optional `compression-codec`
* a free-form `properties` map.

The specification defines two blob types, `apache-datasketches-theta-v1` and `deletion-vector-v1`, and the Java library exposes them as the two constants of `org.apache.iceberg.puffin.StandardBlobTypes`. Beyond those constants there is no registry of blob types and no discovery mechanism: none of the four modules named above uses `java.util.ServiceLoader` or ships a `META-INF/services` entry, and `PuffinWriter` and `PuffinReader` operate on raw `ByteBuffer` payloads, leaving every consumer to switch on the type string in its own code. The writer also accepts only zstd for blob compression; LZ4, although listed in the specification, is marked as a TODO in `PuffinFormat`.

Puffin files reach query planning through two routes:

**Table-level statistics files** are listed in table metadata and bound to a snapshot (`StatisticsFile` carries a `snapshotId`), which suits a table-wide distinct-value estimate but is the wrong granularity for an index that describes one data file and remains valid across many snapshots.

**Deletion vectors** take the other route, and they are the precedent this proposal builds on. A deletion-vector blob is written with `snapshot-id` and `sequence-number` set to -1, records the data file it applies to in a `referenced-data-file` property, and is located from the manifest entry of its delete file through `referenced_data_file`, `content_offset` and `content_size_in_bytes`.

The format therefore already contains a blob whose lifetime is that of a data file rather than a snapshot; it is simply defined for one type only, and the property key that expresses the binding is a private constant in `BaseDVFileWriter` rather than part of the specification's vocabulary.

### 2.2 Compaction and out-of-band metadata

The Iceberg table specification defines what a rewrite must preserve about rows, including the application of delete files, but it does not define what a rewrite must do with any other metadata attached to its inputs. In the 1.12.0 core library the rewrite commit path (`BaseRewriteFiles` and `MergingSnapshotProducer`) makes no reference to statistics files; a statistics file remains associated with the snapshot for which it was computed and is removed only when that snapshot expires (`FileCleanupStrategy`). The practical consequence is that the snapshot produced by a compaction carries no statistics at all, and anything an operator wants on the compacted data must be recomputed by a separate maintenance job that the ecosystem does not standardize.

We regard this as a gap in the specification rather than an oversight in any implementation. Compaction is largely treated as an operator concern (which strategy, which target file size, when to run it) rather than as a mechanism with defined interactions with the rest of the table's metadata, and so the question of what becomes of an index when its data file is consolidated has never needed an answer, but it needs one as soon as indexes are expected to outlive the snapshot in which they were built.

### 2.3 Why this matters now

The first motivation is **extensibility in the ordinary sense**. Range filters, learned indexes, quantile sketches, n-gram filters for substring predicates and quantized presence maps are all plausible Puffin payloads, and the research literature produces new ones faster than any specification process could standardize them.

If such types can only be added by amending the specification and every engine, they will instead be added in incompatible, private ways, which is the outcome a namespace, a descriptor and a discovery mechanism exist to prevent.

An ecosystem in which a party can define an index, ship an implementation, and have engines that load it use it safely is valuable whatever index types it ends up holding, and it is the part of this proposal we would most like to see adopted.

Parquet already offers a narrower version of this: [Bloom filters per column chunk](https://parquet.apache.org/docs/file-format/bloomfilter/), written by the data writer and read after the file's footer. Blobs of the kind proposed here can be added to a data file after it is written, by any process and without rewriting the file, and they are consulted during planning, so that a file they exclude is never opened (a Parquet filter can only be consulted after the footer read that tells the reader where it is). Nor are they limited to the one index type the writer chose, which lets the index on a column follow the predicates that column actually receives.

The second motivation is that **binding indexes to data files rather than snapshots, and giving compaction defined obligations toward them, makes the timing of index construction a choice rather than a constraint**. Today an index either lives inside the data file, and so is chosen and paid for at write time, or lives in a snapshot-bound statistics file, and so must be rebuilt after every rewrite. Under this proposal it can equally be built with the data, merged or rebuilt inside the compaction that rewrites it (Section 8.3), or built later in the background by a process that has watched the workload, an approach we call *progressive indexing*.

Our own simulations of that approach suggest that no single timing dominates: building late lets the choice of index follow the workload and avoids paying for files that compaction is about to discard, while building with the data reaches short-lived files that a background builder cannot. The proposal takes no position on that trade-off, and every point on it remains available, but the late-built index is the case the mechanisms were designed around, and the reader should take it as the running example whenever the text below speaks of a blob without saying who built it or when.

## 3. Goals and Non-Goals

The proposal's aims:

* any party should be able to define a new Puffin blob type without coordination beyond owning a namespace
* readers and writers should be able to discover the types they can handle at runtime
* it should be possible to add a data-file-scoped blob to a table without rewriting data files or creating a snapshot
* compaction processes should be given precise, per-type statements of their responsibilities toward such blobs
* any party that holds a blob should be able to determine the configuration under which it was built, so that the blob can be evaluated and composed correctly.

It aims to do all of this without weakening the existing rule that readers ignore blob types they do not understand.

The proposal **does not attempt** to:

* standardize any particular index type
* specify how a query planner should use an index
* require any engine to build indexes at all
* prescribe when indexes are built
* constrain how a blob type is implemented
* define any blob scope other than the data file.

On implementation: the proposal places two obligations on a blob type and no others. Blobs of the type MUST behave as the type's descriptor says they do, in every respect the descriptor speaks to (what they are bound to, what a reader may conclude from them, how they compose, how they respond to deletes, which configuration parameters they depend on), and any change to the type under which a reader relying on the old payload format or the old descriptor could draw a wrong conclusion MUST be published under a new major version (Section 4.1). Within those two rules the payload format, the algorithm, the language of the implementation and the manner of its distribution are the type's own business, and nothing in this document, in the registry or in the Java interfaces of Section 9 is intended to narrow them.

The last two deserve a word each. On scope: every normative mechanism in this proposal concerns blobs bound to exactly one data file. We propose nothing scoped to a table, a partition or a snapshot, and nothing bound to a unit smaller than a data file; a data-file-scoped blob may well describe the row groups or pages within its file, as a page-level filter would, but it is bound to the file and lives and dies with it. The `scope` values other than `data-file` in Section 5 exist only to classify the blob types the specification already has (snapshot-bound statistics and deletion vectors) and to give the in-band configuration blobs of Section 7.3 a home; the existing snapshot-scoped statistics mechanism is left unchanged, since table-wide statistics remain useful and their binding to snapshots is appropriate for what they describe, and blobs spanning several files are an open question (Section 14.10) rather than a proposal.

## 4. Blob Type Identifiers and the Registry

### 4.1 Identifier syntax

We propose that a blob type identifier take the following form, expressed in ABNF:

```
blob-type   = [ namespace "." ] name "-v" major
namespace   = label *( "." label )
label       = lower-alpha *( lower-alpha / DIGIT / "-" )
name        = segment *( "-" segment )
segment     = 1*( lower-alpha / DIGIT )
major       = %x31-39 *DIGIT        ; 1, 2, ... without leading zeros
lower-alpha = %x61-7A
```

An identifier without a namespace is reserved for types defined by the Iceberg specification itself. This keeps the two existing identifiers valid exactly as written and gives the specification a short, readable vocabulary for the types it standardizes. Every other identifier MUST carry a namespace, and that namespace SHOULD be a reverse-DNS name controlled by the party defining the type, following the convention used for Java packages; for example, `com.example.f16-presence-v1`. Because the name segment cannot contain a dot, the namespace is everything before the final dot, and parsing is unambiguous.

The major version is part of the identifier, and a change that would cause an existing reader to misinterpret a payload, or to rely on a descriptor obligation the type no longer meets, MUST be published under a new identifier with an incremented major version. Compatible evolution, such as an additional optional property or a descriptor change that only tightens an obligation, does not change the identifier; the descriptor (Section 5) records it.

The following identifiers are well formed. The first two are the types the specification already defines, and the remainder use namespaces under the reserved `example` domains to stand in for third parties.

| Identifier | Namespace | Name | Major | Notes |
|---|---|---|---|---|
| `apache-datasketches-theta-v1` | (none) | `apache-datasketches-theta` | 1 | Defined by the Iceberg specification. |
| `deletion-vector-v1` | (none) | `deletion-vector` | 1 | Defined by the Iceberg specification. |
| `com.example.f16-presence-v1` | `com.example` | `f16-presence` | 1 | A third-party type. |
| `com.example.f16-presence-v2` | `com.example` | `f16-presence` | 2 | An incompatible successor, and therefore a distinct type that readers of version 1 ignore. |
| `org.example.lake.range-filter-v3` | `org.example.lake` | `range-filter` | 3 | A namespace may have any number of labels. |
| `com.example.bloom-v2-v1` | `com.example` | `bloom-v2` | 1 | Legal, since only the final `-v` segment is the version, though a name that imitates a version invites confusion and SHOULD be avoided. |

The next group shows the mistakes the grammar and the reservation rule are meant to catch.

| Identifier | Why it is rejected or misleading |
|---|---|
| `f16-presence-v1` | Syntactically valid, but an identifier without a namespace is reserved for the Iceberg specification, so a third party MUST NOT write it. |
| `com.example.f16-presence` | No major version. |
| `com.example.f16-presence-v01` | The major version has a leading zero. |
| `com.example.f16-presence-v1.1` | Minor versions are not part of the identifier; compatible changes are recorded in the descriptor instead. |
| `com.example.F16-Presence-v1` | Upper-case letters are not permitted, so that identifiers compare correctly as byte strings. |
| `com.example.f16_presence-v1` | Underscores are not permitted in a name. |
| `com.example.f16.presence-v1` | Well formed, but it parses as the name `presence` in the namespace `com.example.f16`, which is probably not what its author intended. |

### 4.2 Property keys

Blob `properties` are presently an unstructured map. We propose that keys without a namespace be reserved for the Iceberg specification and that their meaning be the same for every blob type that uses them. Keys defined by an individual blob type's descriptor are interpreted only in the context of that type and need no prefix. Keys that a third party wishes to attach across blob types it does not own MUST carry that party's namespace. The first reserved key would be `referenced-data-file`, promoted from its current status as a private constant in the deletion-vector writer (Section 6).

### 4.3 A two-tier registry

Uniqueness follows from namespace ownership, so registration is not a condition of validity. The registry exists for discoverability and for the community's shared understanding of what is in use, and we propose that it have two tiers.

**The first tier** is the set of types defined by the Iceberg specification. It would appear as a table in the Puffin specification, one row per un-namespaced identifier, each linking to its normative definition and its descriptor. Additions to this tier follow the ordinary specification process.

**The second tier** is a community index of namespaced types, maintained as a single machine-readable file in the Iceberg repository (for example `format/puffin-blob-types.json`), in which each entry records an identifier, its owner, a link to its definition, and a link to or a copy of its descriptor. Additions are made by pull request and accepted under lazy consensus, with review limited to checking that the namespace is plausibly owned by the submitter, that the identifier is well formed, and that the descriptor validates. Listing a type in the community index implies nothing about its quality or about any engine's support for it.

Two precedents inform this design. [Apache Arrow](https://arrow.apache.org/docs/format/CanonicalExtensions.html) names extension types by string and recommends that third-party names be namespaced, while reserving a prefix for canonical extension types that the project itself specifies. The IANA media-type registry distinguishes a standards tree from vendor and personal trees, with lighter registration requirements outside the standards tree ([RFC 6838](https://www.rfc-editor.org/rfc/rfc6838)). Both have kept independently developed types from colliding over many years without imposing a heavyweight approval process, which is the balance we are seeking.

## 5. Blob Type Descriptors

A blob type *identifier* tells a reader whether it recognizes a payload, but engines and maintenance tools also need to know things about a type that they can act on without decoding it:

* what the blob is bound to
* which properties it requires
* how it behaves when its data file is compacted
* how it behaves in the presence of row-level deletes.

We propose that each blob type have a *descriptor*, a small JSON document published with its definition, listed in the registry, and exposed at run time by its implementation (Section 9). The descriptor is the whole of the type's contract with the rest of the system: an implementation MUST behave as its descriptor says, and beyond that the proposal says nothing about how a type is implemented (Section 3). The fields we propose are as follows:

| Field | Value | Meaning |
|---|---|---|
| `type` | identifier (Section 4.1) | The blob type the descriptor describes. |
| `title` | string | A human-readable name. |
| `definition` | URL | The normative description of the payload. |
| `scope` | `snapshot`, `data-file`, `delete-file` or `puffin-file` | What the blob is bound to; values in the second table. |
| `input-fields` | object with `count`, optional `types`, optional `expressions` | How many field ids the blob records, which Iceberg primitive types those fields may have, and whether blobs may describe expressions over the fields rather than the fields themselves (Section 7.8). |
| `properties` | map from key to `{ required, syntax }` | The type-specific property keys, whether each is required, and the syntax of its value. |
| `compaction` | `drop`, `rebuild`, `merge` or `retain` | The type's behavior when a rewrite replaces the data files it describes, as defined in Section 8; values in the second table. |
| `merge-precision` | `exact` or `conservative` | Required when `compaction` is `merge`; values in the second table. |
| `deletes` | `conservative`, `approximate` or `invalidated` | How row-level deletes affect the blob's meaning; values in the second table. |
| `inference` | `exclusion` (default), `location` or `aggregate` | What a reader may conclude from a data-file-scoped blob, as Section 6.2 defines; values in the second table. |
| `compression` | list of codec names | The codecs writers may apply, which lets a type such as `deletion-vector-v1`, whose definition forbids compression, say so mechanically. |
| `config` | object with `schema`, `schema-digest`, `evaluation`, `composition` | The type's build configuration contract (Section 7.5). |

The enumerated values are as follows.

| Field | Value | Meaning |
|---|---|---|
| `scope` | `snapshot` | Bound to a snapshot, as statistics files are today. |
| | `data-file` | Bound to one data file, as described in Section 6. |
| | `delete-file` | The scope deletion vectors effectively have. |
| | `puffin-file` | Describes nothing outside the Puffin file containing it and lives exactly as long as that file; the in-band configurations of Section 7.3 are the one such type we propose. |
| `compaction` | `drop` | The writer produces no blob of this type for any output. |
| | `rebuild` | The writer may compute a blob for an output from the output's data. |
| | `merge` | The writer composes the sources' blobs into the output's blob when the conditions of Section 8.3 hold, and otherwise rebuilds. |
| | `retain` | The blob is unaffected by the rewrite; applicable only to scopes other than `data-file`. |
| `merge-precision` | `exact` | The merged blob is identical to the blob that would be built from the output data. |
| | `conservative` | The merged blob is a valid but possibly less selective approximation. |
| `deletes` | `conservative` | Answers remain correct when rows are deleted, as for any presence filter, since deleting rows can only introduce false positives. |
| | `approximate` | Values drift but remain usable as estimates, as for distinct-value sketches. |
| | `invalidated` | The blob must not be used once a delete applies to its data file. |
| `inference` | `exclusion` | A reader may conclude only that a unit of the file contains no matching row; the default when the field is absent, and the only value a type may take without the further declarations Section 6.2 requires. |
| | `location` | The blob states where within the file the matching rows are. |
| | `aggregate` | The blob states a value computed over the file's rows. |

A descriptor for a quantized presence index, the type that prompted this proposal, would read as follows:

```json
{
  "type": "com.example.f16-presence-v1",
  "title": "Half-precision presence filter",
  "definition": "https://example.com/specs/f16-presence-v1",
  "scope": "data-file",
  "input-fields": { "count": 1, "types": ["float", "double", "int", "long", "decimal"] },
  "properties": {
    "distinct-codes": { "required": false, "syntax": "non-negative integer" }
  },
  "compaction": "merge",
  "merge-precision": "exact",
  "deletes": "conservative",
  "compression": ["none", "zstd"]
}
```

## 6. Data-File-Scoped Blobs

### 6.1 Binding

A **data-file-scoped blob** is a Puffin blob for which:

* `snapshot-id` and `sequence-number` are both -1
* `referenced-data-file` holds the location of exactly one data file, written exactly as that location appears in the table's manifests
* `fields` list the field ids from which it was computed

The blob is valid for a reader of snapshot *S* if and only if its referenced data file is live in *S*; it carries no other binding to snapshots, and the same blob therefore serves every snapshot in which its data file appears. This definition is deliberately the deletion-vector binding generalized to any type whose descriptor declares `scope: data-file`.

Row-level deletes do not change a blob's binding, but they may change its meaning, and readers MUST consult the descriptor's `deletes` field before using a data-file-scoped blob for a data file that has deletes applied in *S*. The common case, an index used to skip files, is unaffected: deleting rows can only make a file less likely to match a predicate, so an index that admitted the file before the delete remains correct, if slightly less selective, afterward.

### 6.2 What a data-file-scoped blob may assert

The guarantee claimed in Section 1, that a reader or a compactor which ignores a data-file-scoped blob can lose index coverage but cannot produce a wrong answer, does not follow from the binding alone. It follows from a property of the blob's content that the binding makes possible and that this section makes a requirement. Deletion vectors show what is at stake: a deletion vector is bound to a single data file in exactly the way Section 6.1 describes, and a reader that ignores one returns rows that have been deleted. The difference is that a deletion vector carries information about the table that the data file does not, whereas an index carries information the data file already contains, in a form that is cheaper to consult.

We therefore require, first, that a data-file-scoped blob be *derived*: its content MUST be a function of the content of the referenced data file and of the blob's build configuration, and of nothing else, so that any conclusion a reader draws from it could equally have been drawn by reading the file. The absence of a blob MUST imply nothing; a reader that finds no blob of some type for a data file knows only that it has to read the file. A blob type that would carry information not recoverable from the data file, as a deletion vector does, MUST NOT declare `scope: data-file`.

We require, second, that a data-file-scoped blob be *exclusion-only* unless its descriptor says otherwise. An exclusion-only blob is one from which a reader may conclude that some unit of the data file (the whole file, a row group, a page or a row) contains no row satisfying some predicate, and from which it MUST NOT conclude anything else. Such a blob may err in one direction only: it may fail to exclude a unit that contains no match, which is a false positive, and it MUST NOT exclude a unit that contains one. This restriction is stronger than derivation, and we impose it because it makes the rest of the proposal's guarantees hold without further conditions. Deleting rows from a file cannot make an exclusion-only blob wrong, since fewer rows cannot satisfy a predicate that none did, so every exclusion-only type is `deletes: conservative` whether or not it says so. Composing two exclusion-only blobs by any operation that admits whatever either admits yields a correct blob for the union of their rows, which is what makes `merge-precision: conservative` well defined. And a reader that is asked to apply an exclusion-only blob to a predicate the type does not support can be told by the type's definition to decline, since declining is always available and always correct. Every blob type discussed in this proposal is exclusion-only: the presence filters, the n-gram filters, Bloom filters of every kind, and the range and zone-map structures that would naturally follow them.

The restriction shuts out two kinds of blob that are derived, and therefore safe to ignore, but from which a reader draws a different kind of conclusion, and the descriptor field `inference` (Section 5) exists so that a type can declare itself one of them and accept the obligations that come with it. A *locating* blob (`inference: location`) states where within the file the rows satisfying a predicate are, as a per-file inverted index from values to row positions would, so that a reader can seek to the matching rows instead of scanning for them. It is positive within the file, but since a data file is immutable the statement never becomes false, and the completeness hazard that forces the index specification of Section 12 to pin each extract to a snapshot does not arise, because a per-file blob claims completeness only over the one file it describes. An *aggregating* blob (`inference: aggregate`) states a value computed over the file's rows, such as a row count, a sum, an exact distinct count or exact bounds, so that a query can be answered without reading the file at all. Both are derived, so both remain safe to ignore and safe to drop at compaction, and we see no reason to forbid them. What they lose are the properties that exclusion-only types get for free, and a type that declares either inference MUST therefore make the following explicit. Its descriptor MUST declare `deletes`, and its definition MUST state how a reader combines the blob with the delete files that apply to the data file (for a locating blob, applying position deletes to the located rows suffices, while an aggregating blob is in general invalidated by any delete). Its descriptor MUST NOT declare `merge-precision: conservative`, since an approximate position or aggregate is simply wrong where an approximate filter is merely less selective, so such a type is composed exactly or rebuilt. And its definition MUST state what a reader is to do when the source field's type has been promoted since the blob was built, a question that an exclusion-only type with an order-preserving encoding can usually answer in one sentence and an aggregating type cannot. A reader that encounters a type declaring an inference it does not implement MUST ignore the blob, which the derivation rule makes safe.

### 6.3 Locating blobs

The binding says when a blob is valid; readers also need to find the blobs that apply to the data files they are planning to scan, and here there is a genuine design choice that this draft has not settled. We see three options; the rest of the draft assumes the third so that the other sections have something concrete to refer to, but the choice is open (Section 14.2) and may be overtaken by the hosting proposal of Section 12.3.

1. **add a field to the data file's manifest entry pointing at its index blobs**, mirroring how delete-file entries locate deletion vectors. It gives planners direct access, but adding an index would then require rewriting the manifest that lists the data file and committing a new snapshot, which ties index construction to the data commit path and its contention, the very coupling that file-scoped blobs are meant to remove.

2. **list Puffin files in table metadata**, as statistics files are listed today, but without a snapshot binding, leaving readers to discover which data files each file covers by reading its footer. It requires no new file type, but planning cost then grows with the number of Puffin files rather than with the number of data files in the scan, and table metadata grows with every index batch.

3. **introduce an *index manifest*:** an Avro file, like a data manifest, in which each row records a data file location, a blob type, a field id, the digest of the blob's build configuration if it has one (Section 7), and the location, offset and length of the blob in a Puffin file. Table metadata lists the current index manifests, and adding indexes is a metadata-only commit that replaces or appends to that list without producing a snapshot, as `SetStatistics` does today. Planners join index manifest rows to the data files selected by ordinary manifest pruning, so planning cost scales with the scan. The cost of this option is a new metadata structure in the specification, which we think is justified by the separation it buys between index maintenance and data commits.

### 6.4 Garbage collection

An index manifest row, and the blob it locates, become garbage once the referenced data file is not live in any retained snapshot. Snapshot expiration SHOULD remove such rows and SHOULD delete Puffin files that no longer contain a referenced blob. Because readers check liveness before using a blob, deferring this cleanup never affects correctness; it affects only storage and planning cost. A maintenance operation MAY rewrite a Puffin file whose live blobs have become a small fraction of its size, copying the live blobs into a new file and updating the index manifest in one commit.

## 7. Build Configurations

This section rests on an assumption we hold deliberately and want to state before the mechanism: **the configuration under which a blob was built will drift from the configuration a reader assumes, and the drift will be silent unless the format makes it visible.** A table accumulates blobs over years. In that time the indexer that writes them changes its defaults, the library it links against updates its Unicode tables or its hash implementation, a second team deploys an indexer of its own with different settings, an engine that evaluates the blobs upgrades to a release that folds case differently, and an operator tunes a parameter without rebuilding what was already built. None of these events is an error and every one of them is routine, and after any of them the table holds blobs of one type built under more than one configuration, with nothing in the payload to say which. A reader that assumes a configuration, whether a default, the table's current setting or the one it would have chosen itself, will sometimes be wrong, and when it is wrong about an evaluation parameter the result is a file silently excluded while it holds a matching row. Nothing reports that failure. It surfaces, if it surfaces at all, as a query that returns fewer rows than it should, some time after a change was made in a place nobody thinks to look when a query is wrong, and our position is that a format which permits it has a defect no matter how carefully the implementations are written.

We therefore adopt three rules, and the rest of the section is their working out:

* A configuration is identified by the digest of its content, prefixed by the digest of the schema it conforms to, never by a name or a version alone, so that two parties agree on a configuration only when they hold the same document and read it under the same schema.
* A blob records the digest of the configuration it was built under, and a reader evaluates the blob under the configuration it resolves from that digest or does not evaluate it at all. No blob is ever evaluated under an assumed configuration.
* A configuration that turns out to be wrong is revoked by digest, so that every blob built under it goes out of service with one metadata commit, without anyone having to know where those blobs are.

The cost is a property on every blob and one store lookup per distinct digest, and we regard it as the cheapest insurance in this proposal.

### 7.1 Why the type identifier is not enough

A blob type identifier fixes the format of a payload, but it does not fix *the parameters under which a particular payload was built*, and two blobs of the same type can differ in ways that matter to every party that touches them:

* a Bloom filter may have been built with a different **hash function** or **seed**
* a token index over a string column may have **case-folded** **and** **Unicode-normalized** its input, **or not**
* a quantized presence filter for large-magnitude values may have used a different **offset** and **scale**
* a learned index may have been fitted with a different **model family**.

Some of these parameters are recoverable from the payload itself (a Bloom filter's bit count, for instance, is implied by its length), and a well-designed type will keep them there. Others are not recoverable, and of those the most dangerous are the ones a reader needs in order to evaluate a predicate at all: a reader that probes a case-folded token index with an unfolded literal, or a quantized filter with a bound quantized under a different scale, will exclude files that contain matching rows, which is the kind of failure the rest of this proposal is designed to rule out.

The same parameters matter to compaction, because two blobs can be composed only if they were built compatibly (two Bloom filters with different seeds cannot be combined by OR, whatever their sizes), and they matter to operators and background indexers, who need to ask which blobs were built under a configuration that has since been superseded, or under one later found to be defective, without decoding every payload in the table. We therefore propose that **every blob be able to record a reference to the configuration under which it was built**, and that **readers be able to resolve that reference through a configuration store** that may live outside the table.

We distinguish three roles a configuration parameter can play, since the obligations differ:

* an **evaluation** parameter is needed to answer a predicate correctly
* a **composition** parameter must agree between blobs for them to be merged
* a **provenance** parameter (the policy or job that chose to build the blob, the version of the code that built it) is recorded only so that people and tools can find and reason about blobs after the fact.

A parameter can play more than one role, and a type's descriptor states which of its parameters play which (Section 7.5). One kind of parameter, a transformation applied to a column before it is indexed, sits on the boundary between a build parameter and a description of what the blob is an index of, and Section 7.8 takes it up separately.

### 7.2 Configuration references

We propose four reserved property keys, in the sense of Section 4.2, whose meaning is the same for every blob type:

| Key | Presence | Value | Meaning |
|---|---|---|---|
| `config-digest` | Required when the type declares evaluation parameters; otherwise optional | The name of a hash algorithm, a colon, and the lower-case hexadecimal digest of the configuration document in its canonical form, prefixed by the digest of its schema as described below, for example `sha-256:9f86d0…` | The identity of the configuration. Two blobs whose digests are equal are known to have been built under the same configuration, read under the same schema, without either document being fetched. |
| `config-id` | Optional | An identifier, which SHOULD be namespaced in the manner of Section 4.1, for example `com.example.indexing.orders-sku-bloom` | A name for the configuration, meant for people and for stores that organize configurations by name. |
| `config-version` | Optional | An opaque version string | The version of the named configuration. |
| `config-store` | Optional | One of the store names declared in table properties (Section 7.3) | The store from which the configuration should be resolved; the table's default store applies when absent. |

Configuration documents are JSON, and the canonical form is that of the JSON Canonicalization Scheme ([RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)), so that any party holding the document can recompute the digest. The digest is not taken over the document alone. Its preimage is the digest of the canonical form of the type's configuration schema, as bytes, followed by the canonical form of the configuration document, so that for a type whose descriptor gives `schema-digest` *S* (Section 7.5) and a configuration *C*, `config-digest` is *H*(*S* ‖ canonical(*C*)). The reason is that a configuration document has no meaning apart from the schema it is read under, and the documents most likely to be shared across types are exactly the ones whose meanings differ most. `{}` is the valid configuration of every type with no parameters, `{"seed": 0}` is valid under any schema that has a seed, and a filter type's `{"bits": 65536}` and a sketch type's `{"bits": 65536}` describe different things. Taken over the document alone, each pair would share a digest, a configuration store keyed by digest could return a document written for another type, and a revocation by digest (Section 7.4) would revoke across type boundaries. Prefixing the schema digest separates the domains, as cryptographic protocols separate theirs with tags, at the cost of one extra hash that every party already has the input for, since the schema digest is published in the descriptor and compiled into implementations (Section 9.4). When `config-id` and `config-version` are present together with `config-digest`, the digest is authoritative: a reader that resolves a configuration by identifier and version MUST verify that the document it receives has the recorded digest, and MUST treat a mismatch as a failure to resolve.

A blob whose type declares evaluation parameters MUST carry `config-digest`. A blob of any other type MAY carry it, and we expect that most blobs written by background indexers will, since provenance is cheap to record and expensive to reconstruct. The properties of a blob built under a named configuration would read as follows:

```json
{
  "referenced-data-file": "s3://bucket/warehouse/db/orders/data/00042-7-a1b2c3.parquet",
  "config-digest": "sha-256:3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b",
  "config-id": "com.example.indexing.orders-sku-bloom",
  "config-version": "2026-09-14.1"
}
```

### 7.3 Configuration stores

A configuration store is anything that can return the configuration document for a digest, and optionally for an identifier and version. We propose two kinds, and the choice between them is a trade between self-containment and sharing:

The first kind is **in-band.** A standard blob type, `build-config-v1`, carries a configuration document as its payload, with its digest in the blob's `config-digest` property, and is written into the same Puffin file as the blobs that refer to it. Resolution then needs nothing outside the Puffin file the reader has already opened, the configuration lives exactly as long as the blobs that use it, and the cost is one small blob per distinct configuration per Puffin file. We expect this to be the right default for configurations specific to one table. Its one obligation falls on the maintenance operation of Section 6.4: a rewrite of a Puffin file that copies live blobs into a new file MUST also copy the configuration blobs those blobs refer to.

The second kind is **out-of-band:** a service or object-store prefix shared across tables, perhaps across an organization, declared in table properties as `puffin.config-store.<name>.impl`, naming an implementation of the resolver interface (Section 9.1), together with whatever properties that implementation requires, and with `puffin.config-store.default` naming the store used when a blob does not name one. Sharing is the advantage, since an organization that wants every table's string indexes to use one normalization policy can then manage that policy in one place and see, through the references, every blob built under each version of it.

An out-of-band store makes two promises that Iceberg cannot enforce, and the proposal should say so plainly rather than leave the gap to be discovered in production. The store MUST NOT change the document associated with a digest, which content addressing makes automatic, nor the document associated with an identifier and version, which only the store's discipline can guarantee. And the store MUST retain every document that some live blob references, which requires that the store's garbage collection consult the tables that use it. To make that consultation tractable, index manifest rows record the digest of each blob's configuration (Section 6.3), so that the set of digests in use by a table is a projection of its index manifests rather than a scan of its Puffin footers.

### 7.4 Reader obligations

A reader that intends to use a blob for planning MUST first determine whether its type declares evaluation parameters. If it does, the reader MUST resolve the blob's configuration, verify its digest, and evaluate predicates under it; if the configuration cannot be resolved, whether because the store is unavailable or because the reader has no implementation of the named store, the reader MUST NOT use the blob, and plans as though the blob did not exist. This rule keeps the property stated in Section 1: an unavailable configuration store can cost index coverage, and it can make planning slower, but it cannot produce an incorrect result. A reader SHOULD cache resolved configurations by digest, which is safe without invalidation because a digest names exactly one document under exactly one schema.

We also propose a revocation mechanism, because the most likely reason to want to find all blobs built under some configuration is that the configuration, or the code that applied it, turned out to be wrong. A table property `puffin.config.revoked` lists digests, and readers MUST NOT use any blob whose `config-digest` appears in it. Revocation is a single metadata commit that takes effect for every subsequent reader without any payload being rewritten, while the revoked blobs stay in place until a background indexer replaces them at its own pace.

### 7.5 Descriptor fields

The descriptor of Section 5 gains a `config` field with four members:

| Member | Value | Meaning |
|---|---|---|
| `schema` | URL | A JSON Schema for the type's configuration documents. |
| `schema-digest` | algorithm and lower-case hexadecimal digest, as for `config-digest` | The digest of the canonical form of the schema document at `schema`, which is the domain-separation prefix of every `config-digest` for the type (Section 7.2). A schema change therefore changes every configuration digest under the type, which is the intended effect, since the same document may mean something different under the new schema. |
| `evaluation` | list of configuration keys | The keys readers need in order to evaluate predicates; empty for a type whose payloads are self-describing. A non-empty list is the case in which Section 7.2 makes `config-digest` mandatory. |
| `composition` | list of configuration keys, or the single value `digest` | The keys that must agree for two blobs to be merged; `digest` when nothing short of identical configurations will do. |

Two configurations agree on a key only if both specify it and the values are equal, so a key that one configuration omits (because it chose an alternative way of setting the same parameter, say) makes the blobs non-composable rather than vacuously compatible. The quantized presence filter of Section 5 has no configurable parameters, so its descriptor would declare `"config": { "schema": …, "schema-digest": …, "evaluation": [], "composition": [] }`; Section 7.6 works through a type for which neither list is empty.

### 7.6 A worked example: character n-gram Bloom filters

The presence filter that prompted this proposal turns out to be a poor illustration of build configurations, since its only parameter (the quantizer) is fixed by its type. A character n-gram Bloom filter over a string column is a better one, because it needs configuration in every role Section 7.1 distinguishes, and because the consequences of getting the configuration wrong are easy to see.

Such a filter is built by normalizing each value in a column, padding it with start and end markers, splitting it into overlapping substrings of *n* characters, and inserting each substring into a Bloom filter. It can then serve an equality predicate (every gram of the normalized literal must be present), a prefix predicate such as `LIKE 'abc%'` (the grams of the marked prefix must be present), and a substring predicate such as `LIKE '%abcd%'` whenever the literal is at least *n* characters long; it cannot serve a predicate whose literal yields no complete gram. The type's definition has to be careful at the edges of a pattern literal, because normalizing a fragment is not the same as taking a fragment of a normalized value (a combining mark that follows the fragment in the stored value can compose with its last character), so a reader must not probe grams that touch an open edge of a `LIKE` literal unless the definition shows that normalization cannot cross that edge. A descriptor for such a type would read as follows:

```json
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
```

and a configuration under which a table's blobs were built might be the following document, whose digest each blob records:

```json
{
  "normalization": "nfkc-casefold",
  "unicode-version": "15.1",
  "gram-length": 3,
  "hash": "xxh64",
  "seed": 0,
  "hash-count": 4,
  "bits": 65536
}
```

The evaluation parameters are the ones a reader must reproduce exactly when it turns a query literal into grams and probes. Normalization is the instructive case. Suppose a blob was built with `normalization: none` and a reader, lacking the configuration, assumes the case-folded form: it folds the literal `'ABC-100'` to `'abc-100'`, finds the folded grams absent from a filter that holds only the original ones, and excludes a file that contains the very row being sought. That is precisely the wrong-result failure of Section 7.1, and it is why the descriptor makes the configuration mandatory for this type and why Section 7.4 requires an unresolvable blob to be ignored rather than probed under a guess. The Unicode version belongs in the same list for a less obvious reason: Unicode's normalization and case-folding data grow as characters are assigned, and an engine whose data is older or newer than the writer's may normalize a literal containing recently assigned characters differently from the way the writer normalized the stored values. Neither side can tell which characters the other knew without carrying both versions' data, so a reader must apply the recorded version's data or decline to use the blob, and the prototype accepts only an exact match. Filters built without normalization are exempt, since they never consult Unicode data. The version's presence in the composition list has a cost worth stating: blobs built before and after a platform upgrade cannot be merged, even where no stored value contains a character on which the two versions differ, and an engine upgrade therefore turns merges into rebuilds until the old blobs have been compacted away.

The composition parameters are the evaluation parameters plus the filter's size. Two filters built with the same hashing and the same size can be merged by bitwise OR, and the result is exactly the filter that would have been built from the union of their grams, which justifies `merge-precision: exact`; filters that differ in any of these cannot be merged at all. The distinction between fixed and adaptive sizing shows why the agreement rule of Section 7.5 treats an omitted key as a disagreement. A configuration that specifies `target-fpp` instead of `bits`, so that each file's filter is sized from its own distinct-gram count, gives better selectivity per file but produces filters of different sizes, and since such configurations never specify `bits`, compaction treats their blobs as non-composable and rebuilds them, which is the correct outcome without any special case. Fixed sizing has the opposite weakness: an exact merge is not necessarily a useful one, because a filter of fixed size fills as compaction concatenates files, and a writer SHOULD rebuild rather than merge when the estimated fill of the composed filter would make its false-positive rate unacceptable, a judgment it can make from the sources' bit counts without reading any data.

### 7.7 Configurations and workload drift

Configuration drift, the subject of this section's opening, is one of two kinds of drift the mechanism addresses. The other is drift in the workload, and the mechanism serves the motivation of Section 2.3 more directly than it might appear. A background indexer that revisits its choices as the workload changes will, over time, change not only which files and columns it indexes but how it indexes them. The n-gram filter of Section 7.6 makes the point concretely. A column of product codes queried only by exact equality is well served by `normalization: none`, but once the workload shifts toward case-insensitive search (`ILIKE`, or predicates on `lower(sku)`) those blobs can no longer evaluate the new predicates at all, whereas a case-folded filter could have served both kinds, since a case-sensitive predicate can be answered conservatively by folding its literal.

The index exists and is valid, but it was built under the wrong configuration for the queries now arriving, which is the configuration-level counterpart of the predicate-shape mismatch that our simulations measure between index kinds and queries. Configuration references let such an indexer find the blobs built under each superseded configuration from the index manifests alone and rebuild the most valuable of them first, leaving the rest in service until it gets to them, which is the same progressive treatment this proposal gives to index construction in general.

### 7.8 Expressions as inputs

The proposal so far has treated a blob's input as the columns named in its `fields` list and everything else about how the input was prepared as build configuration, and the n-gram filter of Section 7.6 shows the arrangement working: normalization is an evaluation parameter, the type folds the query literal the same way it folded the column, and the planner sees a filter over `sku` that happens to be case-insensitive. The arrangement breaks down as soon as the transformation is one the planner can see. A query that filters on `lower(sku)` presents the planner with a predicate over an expression, and no inspection of `fields` will tell it that a blob over `sku` with `normalization: nfkc-casefold` is exactly the index it wants, because that fact is buried in a configuration document only the type's implementation can read. The index specification discussed in Section 12 meets the same need with derived columns, and a filter over a path into a variant column, which field ids cannot address at all, makes the need unavoidable.

We therefore propose a fifth reserved property key, `input-expressions`, whose value is a JSON array with one element per entry in `fields`, each element being a term in the encoding the [REST catalog specification](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml) uses for expressions, that is, a field reference or a named transform applied to a term. A blob that carries the key describes the values of those terms rather than of the raw fields, and a planner matches a predicate to the blob by matching the predicate's term against the recorded one. A blob that omits the key describes its fields directly, as every blob does today. The distinction from configuration is one of audience rather than of kind, and the rule we propose is that a transformation goes in `input-expressions` when the planner must know it to decide whether the blob applies to a predicate, and stays in the configuration when it governs how the already-chosen values were encoded. The same n-gram filter may legitimately be described either way, as a filter over `sku` that folds literals itself or as a filter over `lower(sku)` that does not, and the choice decides which predicates the planner will route to it, so a type SHOULD say in its definition which description its blobs use and SHOULD NOT mix them within a type version.

Because the planner, not the type, interprets the key, two obligations follow. An expression MUST be a deterministic function of the row with no reference to anything outside it, which keeps the derivation rule of Section 6.2 intact, and a type whose blobs carry expressions MUST say in its definition how an expression's result type behaves under promotion of the fields it references, as Section 6.2 already asks of locating and aggregating types. The more consequential obligation concerns readers that do not know the key. Under Section 4.2 a reader ignores reserved properties it does not recognize, and a reader that ignored `input-expressions` would evaluate predicates on `sku` against a filter built over `lower(sku)`, excluding files that contain the rows sought, which is the one failure this proposal exists to rule out. We therefore require that the descriptor's `input-fields` object gain a member `expressions`, with values `none`, `optional` and `required`, that a blob carry the key only when its type's descriptor declares `optional` or `required`, and that a type whose descriptor says `none`, or says nothing, take a new major version under Section 4.1 before its blobs may carry one. The descriptor is published with the type and exposed by its implementation, so an implementation old enough to be ignorant of the key is also old enough to be reading a type version whose blobs cannot carry it.

## 8. Compaction Semantics: Candidate Specification Text

The text in this section is offered as a candidate for inclusion in the table specification. The key words MUST, MUST NOT, SHOULD and MAY are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

**8.1 Definitions.** A *rewrite* is a commit that replaces a set of live data files, its *inputs*, with a set of new data files, its *outputs*, such that the rows of the outputs equal the rows of the inputs after the application of any delete files that the rewrite removes. A blob is *attached* to an input if it is data-file-scoped and references that input. An output's *sources* are the inputs that contributed at least one row to it; when a rewrite does not track row provenance, every input is a source of every output.

**8.2 General rule.** A rewrite MUST NOT cause a data-file-scoped blob to become referenced from table metadata in a state where it describes a data file that is not live. Since validity is defined by liveness (Section 6.1), this rule is satisfied automatically for readers, and the obligations below concern which blobs the outputs receive.

**8.3 Modes.** For each blob type attached to the inputs, the writer of a rewrite MUST act according to the type's declared `compaction` mode, as follows.

1. `drop`: the writer MUST NOT produce a blob of this type for any output as part of the rewrite.
2. `rebuild`: the writer MAY compute a blob of this type for an output from the output's data and include it in the rewrite commit. If it does not, the output has no blob of this type until some later operation builds one.
3. `merge`: if the writer has an implementation of the type, and every source of an output carries an attached blob of the type for the same field, and those blobs agree on every configuration key the descriptor lists under `composition` (Section 7.5), the writer SHOULD compute the output's blob by composing the sources' blobs and include it in the rewrite commit. A composed blob MUST be exactly the blob that would be built from the output's data when the descriptor declares `merge-precision: exact`, and MUST be a valid conservative approximation of it otherwise. A composed blob MUST carry the configuration reference of its sources; where the sources' configurations differ in keys outside `composition`, the writer MUST record the configuration it actually applied. When these conditions do not hold, the writer MUST treat the type as `rebuild`, and SHOULD rebuild under the configuration currently in force for that table and field rather than under any of the sources'.
4. `retain`: applicable only to blobs whose scope is not `data-file`; such blobs are unaffected by the rewrite.

**8.4 Unknown types.** A writer that has no implementation of a blob type attached to its inputs MUST treat that type as `drop`. This preserves correctness without requiring the writer to read the type's descriptor, and it reproduces the behavior of every compactor in use today.

**8.5 Atomicity.** Blobs produced for outputs under the `rebuild` or `merge` modes MUST be committed in the same commit as the rewrite, so that no snapshot exists in which an output is live but its merged blob is not, and the index manifest rows for attached blobs of the inputs SHOULD be removed in that commit. Their removal MAY be deferred to maintenance, by the argument of Section 6.4.

**8.6 Concurrency.** A metadata-only commit that adds data-file-scoped blobs MUST fail validation, or be retried against fresh metadata, if any data file it references has been removed by a concurrent commit. Such a commit MUST NOT conflict with concurrent appends, rewrites or deletes that do not remove a data file it references.

**8.7 Reporting.** A rewrite SHOULD record in its snapshot summary the number of attached blobs it merged and the number it dropped, per blob type (for example `blobs-merged.com.example.f16-presence-v1`), so that background indexers can find the outputs that lost coverage without scanning index manifests.

We draw attention to one consequence of the provenance rule in 8.1. A rewrite that redistributes rows across outputs, such as a sort or a space-filling-curve clustering, typically cannot say which inputs fed which output, so every input is a source of every output. Merging is then still correct for a conservative type, because the union of every input's filter admits every output row, but the merged blob may be so unselective as to be worthless, and writers SHOULD prefer `rebuild` in that situation. Bin-packing rewrites, where each output is the concatenation of whole inputs, are the case in which merging is both exact and cheap, and in our simulations it is the case that dominates the write path, since files are merged several times on their way to a settled size.

## 9. Java Service-Provider Interface

Iceberg is a specification with several implementations, in Java, Rust, Python, Go and C++, and nothing that holds the ecosystem of Sections 4 through 8 together may depend on any one of them. The load-bearing parts of this proposal are therefore all at the level of the format: the identifier and its version, the descriptor, the reserved properties, the data-file binding and the configuration digest are bytes in a Puffin footer and documents in a registry, and an implementation in any language can produce and consume them. What each implementation needs beyond that is a way to find, at run time, the code that handles a given type identifier, and that is a question each language answers in its own idiom. This section gives the Java answer, because the Java library is the reference implementation and the one most engines embed, and it should be read as one binding of the contract rather than as the contract.

### 9.1 Interfaces

We propose that the Java library define the following interfaces in `org.apache.iceberg.puffin`. The names are illustrative.

```java
public interface PuffinBlobType<T> {
  /** The descriptor of Section 5, as published in the registry. */
  BlobTypeDescriptor descriptor();

  /** The identifier written as the blob's type, e.g. "com.example.f16-presence-v1". */
  default String type() { return descriptor().type(); }

  /** The decoded representation. */
  Class<T> javaClass();

  /** @throws IllegalArgumentException if this type cannot represent the value */
  ByteBuffer serialize(T value);

  /**
   * {@code data} is the decompressed payload. {@code config} is the blob's build configuration (Section 7), verified
   * against its digest; empty when the blob records none, or when it cannot be resolved and the type needs none.
   *
   * @throws MalformedBlobException if the payload or properties do not conform to the type's definition, including
   *     a payload inconsistent with its configuration; the reader plans without the blob
   * @throws IllegalArgumentException if config is empty although the type declares evaluation parameters, which a
   *     conforming reader never does
   */
  T deserialize(BlobMetadata metadata, ByteBuffer data, Optional<BuildConfig> config);
}

public interface MergeablePuffinBlobType<T> extends PuffinBlobType<T> {
  /** The configuration the composed blob will carry. */
  record MergeContext(Optional<BuildConfig> config) {}

  /** Whether composing is worthwhile, given that it is permitted; false makes the writer rebuild instead. */
  default boolean shouldMerge(List<T> sources, MergeContext context) { return true; }

  /** Composes the blobs of a rewrite's sources into the blob for one output, per Section 8.3. */
  T merge(List<T> sources, MergeContext context);
}

/** Thrown by deserialize for a payload that does not conform; deliberately not an IllegalArgumentException. */
public class MalformedBlobException extends RuntimeException { ... }

public interface BuildConfigStore {
  default void initialize(String name, Map<String, String> properties) {}

  /** The document for a digest, or empty; the caller verifies the digest of whatever is returned. */
  Optional<BuildConfig> resolve(String digest);

  /** The document published under a name and version, or empty. */
  default Optional<BuildConfig> resolve(String configId, String configVersion) { return Optional.empty(); }
}

public final class PuffinBlobTypes {
  public static PuffinBlobTypes load(ClassLoader classLoader);
  public static PuffinBlobTypes of(PuffinBlobType<?>... types);
  public PuffinBlobTypes withAdditional(PuffinBlobType<?>... types);
  public Optional<PuffinBlobType<?>> forType(String type);

  /** Decodes the usable blobs of a Puffin file and reports the rest as skipped, with reasons (Section 7.4). */
  public ReadResult read(PuffinReader reader, BuildConfigResolver resolver);
}
```

`PuffinBlobTypes.load` discovers implementations listed in `META-INF/services/org.apache.iceberg.puffin.PuffinBlobType` and, on the module path, declared with a `provides` clause. Implementations MUST have a public no-argument constructor and MUST be safe for concurrent use. The two standard types would be implemented in `iceberg-core` and registered in the same way, which keeps the mechanism honest by making its first users the types the project already maintains.

### 9.2 Behavior

Two implementations claiming the same identifier constitute a configuration error, and `load` SHOULD fail rather than choose one silently, since a silent choice would make the meaning of a payload depend on class-path order. Decoding a blob whose type has no implementation MUST yield an empty result rather than an exception, preserving the existing rule that readers ignore unknown types, and a reader SHOULD avoid fetching the payloads of such blobs at all. The same reasoning governs failures within a known type. An implementation signals a payload it cannot decode with `MalformedBlobException`, which the reader catches and reports alongside unknown, revoked and unresolvable blobs, so that a corrupt index costs coverage and leaves a diagnostic rather than failing the query; every other exception from an implementation is a bug and propagates. The distinction from `IllegalArgumentException` is deliberate, since that exception signals a caller's mistake, such as decoding without a configuration the type requires, and catching it alongside malformed payloads would hide those mistakes.

### 9.3 Why ServiceLoader, and its limits

Iceberg's usual extension pattern is a single implementation named by a catalog or table property and instantiated reflectively, as with `io-impl` and `catalog-impl`. That pattern suits a component of which exactly one is wanted. Blob types are an open set of which a process may need dozens at once, contributed by independent parties, and `ServiceLoader` is the JDK's standard mechanism for exactly that shape of problem. It is also a JDK mechanism and nothing more: a Rust, Python or Go implementation will register its types by whatever means is natural there, and the descriptor, not the registration mechanism, is what makes a type the same type across them.

Its limitation is class loading. Engines that isolate plugins in separate class loaders (Trino is the prominent example) may not see providers through the thread context class loader, and for that reason the interface includes `of` and `withAdditional`, allowing an engine to construct the set explicitly from whatever loader its plugin model provides. Out-of-band configuration stores (Section 7.3) are a single implementation per declared store, and so follow the usual `-impl` pattern rather than `ServiceLoader`; the in-band store is built into the library. An engine MAY also offer a catalog property listing blob types to exclude, for operators who need to disable a misbehaving implementation without removing it from the class path.

### 9.4 Implementations without run-time discovery

Run-time discovery of new implementations is cheap on the JVM and expensive almost everywhere else. A Rust, C++ or Go implementation of Iceberg links its blob type implementations at build time, and while dynamic loading is possible in each, it is unusual, fragile across toolchains, and not something this proposal should assume. For such implementations the set of supported types is closed when the binary is built, and the risk changes shape: not that an unknown type will be mishandled, since an unknown identifier is ignored as it always was, but that a *known* type will be handled by code compiled against an earlier understanding of it.

We therefore recommend, and would make normative in specification text, that a compiled-in implementation of a blob type be precise about what it supports:

* It MUST match the full identifier of Section 4.1, major version included, and MUST treat any other major version of the same name as an unknown type. A binary built against `com.example.ngram-bloom-v1` ignores `com.example.ngram-bloom-v2` blobs, however similar the payloads may be.
* It MUST carry the descriptor it was built against, so that the obligations it was compiled to honor are inspectable from the binary and comparable with the registry's current descriptor for the type.
* It MUST know the configuration schema it was built against, identified by the descriptor's `config.schema` and `config.schema-digest`, and MUST NOT use a blob whose resolved configuration contains an evaluation or composition key it does not implement. A configuration document is exact, by construction of its digest, so the comparison is a set difference over keys rather than a guess at compatibility.
* It MUST NOT attempt best-effort handling of a payload, descriptor or configuration it does not fully understand. Declining is always correct (Section 6.2); guessing is the one thing the format cannot make safe.

These are the same rules the Java library applies, stated for a setting in which the implementation cannot be updated independently of the engine that embeds it. They make the consequence of lagging behind a type's evolution a loss of coverage rather than an incorrect result, which is the only consequence this proposal accepts, and they make upgrading a matter of rebuilding against a newer descriptor and schema rather than of auditing behavior.

### 9.5 Prototype

A prototype of the interfaces of Section 9.1 exists in Java in the [iceberg-indexing-ecosystem](https://github.com/acruise/iceberg-indexing-ecosystem) repository from which this proposal originates, in the package `com.cloudera.iceberg.proposed.puffin` rather than `org.apache.iceberg.puffin`. It implements the identifier syntax of Section 4.1, the descriptors of Section 5 (whose JSON form it tests against the example in Section 7.6), the data-file binding of Section 6.1, and the whole of Section 7: RFC 8785 canonicalization and SHA-256 digests, configuration references, the in-band `build-config-v1` type, out-of-band stores declared in table properties (with a directory-backed store standing in for an object-store prefix), digest verification and revocation, and a read path that reports every blob it declines to use together with the reason. Two blob types exercise it: the presence filter of Section 5, implemented in Scala to show that the interfaces serve other JVM languages, and the n-gram Bloom filter of Section 7.6, implemented in Java with ICU4J supplying NFKC_Casefold and the Unicode version. The tests write and read Puffin files with the unmodified Iceberg 1.12.0 `PuffinWriter` and `PuffinReader`, and check number canonicalization against the vectors of RFC 8785 and a fixture of several thousand values serialized by a JavaScript engine. Composition is prototyped at the level of the blob types (merge, the composition check, and the fill-based refusal to merge), but there is no rewrite path that applies it, and the index manifest is not prototyped.

## 10. Related Requests: Workload Telemetry

Choosing which indexes to build, and when, is outside this proposal, but anyone who builds such a policy needs to know which columns queries filter on and with which predicate shapes. Iceberg already produces most of that information. The `ScanReport` emitted during scan planning records the filter pushed into the scan (with literal values replaced by hashes, so that columns and predicate shapes survive and values do not), the projected fields, and counts of the manifests and files scanned and skipped. Whether that report reaches anyone depends on the catalog and the engine. We checked the Iceberg 1.12.0 library and the current main branches of Impala, Hive and Trino in October 2026.

With a REST catalog the report is sent to the catalog's metrics endpoint by default (`rest-metrics-reporting-enabled` defaults to true), and Trino passes Iceberg's reporter through for such tables. With a Hive metastore, which most deployments we see still use, the picture is different. Iceberg's metastore-backed catalogs load a reporter from the `metrics-reporter-impl` catalog property and otherwise only log. Spark exposes that property through its catalog configuration. Hive passes catalog properties through, but usually plans against a table deserialized from the job configuration, and `SerializableTable` rebuilds itself with the default reporter, so the configured one is lost. Impala builds its `HiveCatalog` from a fixed list of property keys that omits `metrics-reporter-impl`. Trino's own metastore and Glue catalogs attach a fixed reporter that logs at debug level and cannot be replaced.

Three small changes would make the report usable in metastore deployments, and none depends on the rest of this proposal:

1. `SerializableTable` should carry the reporter of the table it was made from.
2. Impala should accept `metrics-reporter-impl` among its catalog properties.
3. Trino should make its reporter configurable.

One limitation would remain even then: the report records only predicates the engine pushed into Iceberg's scan, so predicates an engine evaluates itself, such as substring patterns or predicates on fields inside variant values, do not appear. Engine query logs and profiles fill that gap where they exist.

## 11. Compatibility

Readers that predate this proposal already ignore blob types they do not recognize, so namespaced types pose no risk to them, and the derivation rule of Section 6.2 is what guarantees that ignoring a data-file-scoped blob of any type is safe; they also ignore index manifests, so they simply plan without the indexes. Writers that predate the proposal will compact tables carrying data-file-scoped blobs as they compact tables today, dropping coverage for the outputs and leaving stale index manifest rows behind, and by the liveness rule neither outcome affects correctness. For these reasons we believe that the identifier namespace, the descriptor and the Java interfaces require no format version change, and that data-file-scoped blobs and index manifests could be introduced as an optional feature of the current format version, gated by a table property so that writers aware of the feature know to maintain index manifests. We would welcome scrutiny of this argument, since it is the claim on which incremental adoption rests.

Configuration references need more care than the rest of the proposal, because they are carried in properties and old readers ignore properties they do not recognize. For a type with no evaluation parameters that is harmless, since a reader that ignores the reference loses only provenance. For a type with evaluation parameters it is not, since a reader that knows the type but not Section 7.4 would evaluate predicates without the configuration and could exclude matching files. It follows that a type MUST NOT acquire evaluation parameters after its first published version: a type that needs them must declare them from its first version, so that every implementation of it resolves configurations, or must introduce them under a new major version, which old implementations will not recognize and will therefore ignore.

The two existing blob types are unaffected. `apache-datasketches-theta-v1` would receive a descriptor declaring `scope: snapshot`, and `deletion-vector-v1` one declaring `scope: delete-file` with compression disallowed, without any change to how either is written or read.

## 12. Related Work: the Iceberg Index Specification

The Iceberg community has a secondary index specification of its own under review, targeted at format version 4, in three parts:

* the specification itself, apache/iceberg [pull request 16961](https://github.com/apache/iceberg/pull/16961)
* REST catalog endpoints for indexes, [pull request 16963](https://github.com/apache/iceberg/pull/16963)
* a Spark reference implementation, [pull request 18187](https://github.com/apache/iceberg/pull/18187).

The two documents use the word index for different things, and a reader of this proposal will reasonably ask why a second index design is wanted. The short answer is that the index specification defines one index, while this proposal defines how any blob type is named and configured and what compaction owes it, with indexes built after the fact as the case that motivates the machinery. The longer answer follows, and it matters for a second reason: several of the specification's choices expose gaps in this one.

### 12.1 What the index specification defines

The specification defines an index as a catalog-managed object separate from its table, built from four layers:

* an **index metadata file**, encoded as JSON and committed by the same atomic swap that table metadata uses, holding the index definition and a list of extracts
* an **extract**, the complete state of the index for exactly one snapshot of the source table
* a **tracking file**, an Avro or Parquet manifest whose entries describe the region files of one extract
* **region files**, ordinary Iceberg data files holding one index entry per row, sorted by an ordering key and partitioned into non-overlapping key ranges, so that a lookup resolves through the tracking file to a single region file.

One index type is defined, `scalar`, whose entries carry the indexed value together with identity columns such as the source row's file and position. The worked example is a key lookup index on a user identifier, ordered by a hash bucket of the key and then by the key itself. Around this core the specification defines extract schema evolution through type promotion, encryption of index files whenever the source snapshot is encrypted, stats-only fields computed from expressions, and a metadata log, and it defers the naming and discovery of indexes to the REST catalog specification.

Two of its boundaries matter here.

**Region files exclude Puffin.** They may be Parquet, Avro or ORC, since the columnar formats offer row group pruning and page indexes that Puffin lacks. The project anticipates using Puffin instead for the auxiliary structures of vector indexes, such as centroid lists and graph structures, should those be specified.

**Rewrites of the source table are unaddressed.** A compaction changes no row but replaces every file, so the file and position recorded in every entry are stale afterward, and whether an extract can be maintained rather than rebuilt depends on the index's transform. The specification says nothing about this, and the reference implementation takes the conservative course: it declines to use an index at all when any snapshot other than an append lies between the extract's snapshot and the one being read.

### 12.2 Positive and negative indexes

The two documents are easy to confuse because both call their structures indexes, and the first thing to fix for a reader is the direction of the inference each supports as specified today.

**The scalar index is positive.** It says where the matching rows are, and a reader that finds no entry for a key concludes that no such row exists and skips everything. That inference is sound only if the index describes exactly the rows of the snapshot being read. This is why the specification lists consistency ("an index reflects exactly the live rows of a single state of its source table") among its goals and pins each extract to one snapshot, and why a snapshot without an extract must either be scanned without the index or reconciled against the nearest extract using the file-level deltas that Iceberg records.

**The blobs of this proposal are, by default, negative.** They can exclude a data file and nothing else. Deleting rows from the file, or building the blob long after the file was written, leaves a negative index correct, if sometimes less selective, so its validity reduces to the liveness of the one file it describes, and the apparatus of extracts and reconciliation is not needed.

The distinction is worth keeping in view, because it determines what a reader may conclude from a structure and because the two vocabularies collide (an index in one document excludes files, in the other it locates rows). It is, however, a property of a type rather than of either framework, and we do not think it is what separates the two designs. What a positive index needs is not a particular container but a validity rule guaranteeing that it describes every row of the state being read, and what makes that rule expensive is scope. A structure that speaks for a whole table must be pinned to a snapshot. A structure that speaks for a single immutable file is valid for as long as the file is live, whichever direction its inference runs, which is why Section 6.2 admits locating and aggregating blobs under declared obligations rather than forbidding them.

It follows that either framework can host the other's structures, provided its metadata records a type's inference properties, as the `inference`, `deletes` and `merge-precision` fields of the descriptor do here and as the index specification's type field could there. A per-file Bloom filter under the index specification is the file filter index of Section 12.3. A key-ordered global index could be carried as snapshot-scoped Puffin blobs, and the specification's preference for columnar region files rests on row group pruning and page indexes rather than on anything a blob cannot express. What is actually distinct between the documents is the metadata each records, per-type compaction, configuration and inference obligations here and catalog-managed extracts with a commit protocol there, together with the scope each chose to describe first.

The scopes bring different cost shapes. A table-level index is clustered on a key that is by design uncorrelated with the table's physical layout, so every append intersects every region, and refreshing an extract costs time proportional to the size of the index rather than to the size of the change; the design accepts this in exchange for a lookup that reads a single region file. Building a data-file-scoped blob costs time proportional to the new file, and a background builder can defer even that until a file has shown it will live long enough to be worth indexing, which is the premise of progressive indexing.

The two cost shapes suit different workloads, and the simulations described in the [README of this repository](../../README.md) contain examples of each:

* A **change-data-capture merge** that looks up fifty keys across the whole table, or an **erasure request** that must find every row of a handful of customers, wants exactly the single region file read and the absence of false positives that a key index provides. Our own finding that batch lookups compound the false positives of per-file Bloom filters is an argument for the scalar index rather than against it.
* **Files that live for minutes** before compaction, and **columns whose predicates have drifted** since the table was designed (particularly toward range and substring predicates, which no key index serves), are where a per-file structure built late is the only index that will ever exist.

### 12.3 Points of contact

**Extensibility.** The index specification keeps the set of index types nominally open, through a type field whose single defined value is `scalar` and whose further values are left to future specifications. Its metadata and extract layers are general, but its tracking and region files are specific to key lookup, so a new type needs new file-level machinery as well as a new name. The registry and descriptors of this proposal, with the discovery mechanism of Section 9 behind them, are an open-set design of the kind that type field leaves room for, applied to Puffin blobs.

The specification also stops at defining what a valid index is. It says nothing normative about how engines use one, and in particular it does not oblige an engine to ignore an index type it does not implement, although skipping one has no correctness consequence. This proposal relies on the corresponding rule, that a reader ignores blob types it does not understand, and goes further by guaranteeing that doing so is always safe. The guarantee can be made normative because it is a statement about blobs rather than about engines: Section 6.2 requires every data-file-scoped blob to be derived from its data file, so a table in which such a blob is ignored is indistinguishable, to the engine, from the same table without it.

**Where index metadata lives.** Here the index specification's choice is the better one, and we propose to adopt it. Its indexes are committed outside table metadata, so that building or refreshing one never contends with writers of the table. Section 6.3 of this proposal instead lists index manifests in table metadata and makes every batch of blobs a metadata-only table commit, which on a table with frequent appends would contend with every one of them. Even the separate-object design leaves a long-running index refresh exposed to retry starvation when it races with other commits to the same index, and a design that contends with the table's own writers is exposed to it far more.

The generality of the index metadata and extract layers suggests the remedy: a second index type under that specification, call it a *file filter index*, with two differences from the scalar type.

* Its extract is not pinned to a snapshot but declares its validity by the liveness of the data files it references.
* Its tracking file is the index manifest of Section 6.3, listing Puffin files and the data files their blobs describe in place of region files.

Data-file-scoped blobs would then inherit the specification's commit protocol and catalog discovery without any change to table metadata, and the question raised in Section 14.3, whether the index manifest should be a new file type, would be settled. The cost is a second validity mode for extracts, and the suggestion has not yet been put to the specification's authors.

**Gaps this proposal should close.** Two features of the index specification have no counterpart here and should, and a third has one here only because we borrowed it.

* **Encryption.** The specification requires that index files be encrypted whenever the source snapshot is. The point applies with at least equal force to a Puffin file holding an n-gram filter over a string column, from which a determined reader could recover a good deal about the column's contents, and this proposal says nothing about encryption.
* **Type promotion.** The specification defines how an index survives type promotion of its source columns. This proposal records field ids in each blob and says nothing about what a reader should conclude when the field's type has since changed, although for the presence filters that motivated it the answer is favorable, since an order-preserving promotion leaves an order-preserving quantizer valid.
* **Expressions.** The specification indexes the values of expressions over source columns, through derived columns and stats-only fields that build on the expression work in the format version 4 specification, where a Puffin blob records only the field ids it was computed from. An n-gram filter built over the case-folded form of a column, which Section 7.6 reaches by way of a build configuration, is more naturally described as a filter over `lower(sku)`, and a filter over a path into a variant column cannot be described by field ids at all. Section 7.8 adds a reserved property for exactly this and borrows the catalog's term representation rather than inventing one, but it says nothing about the stats-only fields through which the specification carries values alongside an index entry, which have no analogue in an exclusion-only blob.

**Vector indexes.** If Puffin comes to hold the auxiliary structures of vector indexes, as Section 12.1 notes the project anticipates, those blobs will need type identifiers and a stated compaction behavior exactly as the filters of this proposal do, and we would hope to see the mechanisms of Sections 4 through 8 serve them.

## 13. Related Work Outside Iceberg

Several systems have built one or more of the mechanisms this proposal asks for, and the summaries below are organized by which mechanism each is precedent for: per-file indexes under string-named, pluggable types; skip indexes materialized after the data was written; indexes over expressions; indexes built in the background with partial coverage; and the interaction of indexes with compaction. Each entry is drawn from the project's own documentation or source as of October 2026. One difference from Iceberg applies to several of them at once: Paimon, Pinot and Lucene are JVM projects, so a `ServiceLoader`-style registry can be load-bearing for their whole ecosystem in a way it cannot be for Iceberg, which has implementations in five languages and must carry its extension contract in the format itself (Section 9).

### 13.1 Apache Paimon's file index

[Paimon](https://paimon.apache.org/docs/master/concepts/spec/fileindex) is the closest existing analogue to Section 6. Each data file may have a file index holding several index types over several columns, stored in a sidecar file beside the data file, or embedded in the manifest when small. The container begins with a magic number and a format version, and the current V2 layout, which must not be written until a table's readers have been upgraded, is selected by a table option. Index types are identified by strings (`bloom-filter`, `bitmap`, `range-bitmap`, and a deprecated bit-slice index), each configured per column through options such as the Bloom filter's expected item count and false positive probability, and the bitmap payload itself has a V1 and a V2 selectable per column. A reader that meets an index type it does not know fails by default, and an option, `file-index.read.ignore-unknown-type`, makes it skip the index with a warning instead, so that unknown indexes filter nothing.

Paimon has thus met the versioning, configuration and unknown-type problems of Sections 4, 5 and 7 and answered each with a table option rather than with anything carried by the index. The difference from this proposal is where the knowledge lives: a Paimon reader must be configured to tolerate a type, where a descriptor would tell it that tolerance is always safe, and the Bloom filter's parameters are table configuration that must match what the writer used, where a configuration reference would let the reader check. Its documentation does not say what compaction does to file indexes, though the per-file layout implies they are rebuilt with the file.

### 13.2 Apache Pinot's index service-provider interface

[Pinot](https://docs.pinot.apache.org/indexing) stores its indexes per immutable segment and, since its index SPI refactoring, defines each index kind as an implementation of an `IndexType` interface in the `pinot-segment-spi` module, discovered through `ServiceLoader` by way of an `IndexPlugin`. The interface's documentation requires the type's id to remain constant across Pinot versions, because it keys the type's registration, its on-disk metadata and its configuration in the table schema, which is the same stability requirement Section 4.1 places on an identifier and the reason it carries a version. Each type supplies its configuration class, a creator, a reader factory and the file extensions it owns, and declares an index build lifecycle that defaults to segment creation but need not be, since Pinot's reload operation adds a newly configured index to existing segments on the servers that hold them, without rewriting the segment and without interrupting queries.

That reload is after-the-fact indexing in a production system, and the SPI is the shape Section 9 proposes, with two differences. Pinot's segments are not compacted, so its types declare nothing about merging, and its index configuration is table-wide, so a segment's index is assumed to match the current configuration rather than recording the one it was built under.

### 13.3 Apache Lucene codecs

[Lucene](https://lucene.apache.org/core/9_12_2/core/org/apache/lucene/codecs/Codec.html) is the oldest precedent for Section 9's discovery mechanism and for Section 4's naming rules. A codec, which defines the on-disk format of a segment's postings, doc values, stored fields and the rest, has a name that is written into the index and resolved at read time by `Codec.forName` through Java's SPI, so a custom codec must be registered in `META-INF/services` and have a no-argument constructor. Names are constrained to ASCII alphanumerics of fewer than 128 characters, and new codecs may be added to a running process but never removed or replaced. Formats are versioned by name (`Lucene99`, `Lucene912`), and a backward-codecs module keeps old names readable so that an index written by one release can be read, and then rewritten on merge, by a later one.

Lucene also offers the longest-running example of index structures that live and die with immutable segments and are rebuilt when segments merge. Its merge policies decide which segments to combine on the basis of size and deletion ratio, and every per-segment structure is recomputed for the merged segment under the current codec. The proposal's `rebuild` mode (Section 8) is this behavior, and its `merge` mode is the optimization Lucene declines to attempt.

### 13.4 ClickHouse data skipping indexes

[ClickHouse](https://clickhouse.com/docs/optimize/skipping-indexes) attaches *data skipping indexes* to a MergeTree table: `minmax`, `set`, `bloom_filter`, the n-gram and token Bloom filters `ngrambf_v1` and `tokenbf_v1`, and more recently `text` and `vector_similarity` indexes. Each is declared over an expression, with a granularity in units of the table's index granule, and stored as two files in each data part's directory. The indexes are exclusion-only, which is to say negative in the sense of Section 12.2, and a part that lacks one is simply scanned. An index added to an existing table covers only newly inserted parts until `ALTER TABLE … MATERIALIZE INDEX` builds it for the existing ones, and since version 25.1 a setting that defaults to on, `materialize_skip_indexes_on_merge`, has merges build the skip indexes of the parts they produce, with a companion setting to exclude named indexes from that rebuild.

Three details bear on this proposal. The type names carry a version suffix of exactly the form Section 4.1 proposes, and the two Bloom filter variants so named are now deprecated for full-text use in favor of the text index, which shows a type lifecycle running its course. Indexes are defined over expressions as a matter of course, the case Section 7.8 adds. And the merge setting is the compaction obligation of Section 8 as a system-wide default, where this proposal has each type declare it.

### 13.5 Apache Hudi's metadata table and expression indexes

[Hudi](https://hudi.apache.org/docs/1.2.0/metadata_indexing) keeps its indexes as partitions of a *metadata table*, itself a Hudi table maintained alongside the data: a files index, column statistics, Bloom filters, partition statistics, a record-level index, a secondary index and, since the 1.0 line, *expression indexes*. An expression index is created with `CREATE INDEX … USING column_stats(ts) OPTIONS(expr='from_unixtime', …)` or `USING bloom_filters(rider) OPTIONS(expr='lower')`, and the documentation says plainly that the index is created on the transformed column, which is the planner-visible transform Section 7.8 proposes to record in a reserved property. Indexes may be built for existing data by an asynchronous indexer that schedules an indexing instant under a lock and executes it in coordination with the metadata table's own locking, so that, in the documentation's words, a table can be indexed without blocking writing.

Hudi has therefore built both late indexing and expression-valued inputs. What it has not done, and what distinguishes the two designs, is make the set of index types open or bind an index to a data file's identity: the metadata table's partitions are fixed by Hudi's code, and an index entry is keyed by file within the metadata table rather than carried with the file, so the question of what a rewrite owes an index is answered by Hudi's own table services rather than by anything a type declares.

### 13.6 PostgreSQL block range indexes

[BRIN](https://www.postgresql.org/docs/current/brin.html), the block range index, is the conceptual ancestor of every per-file filter in this proposal. For each range of physically adjacent pages it stores a summary, by default the minimum and maximum of the indexed column, and a query returns every tuple in every range whose summary is consistent with the predicate, leaving the executor to recheck them; the documentation calls the index lossy, which is Section 6.2's exclusion-only in different words. Ranges that are never summarized are not wrong, only unhelpful, and the documentation states that new ranges remain unsummarized until a summarization run is invoked. Originally only `VACUUM` performed that run. An `autosummarize` index option, off by default, later arranged for autovacuum to summarize filled ranges in the background, and `brin_summarize_new_values` and `brin_summarize_range` let an operator do so by hand.

The parallel extends to extensibility. What a BRIN summary contains and which predicates it can satisfy are determined by an *operator class*, which must provide four methods: one describing the summary, one testing consistency with a predicate, one adding a value to a summary, and one, `unionTuples`, merging two summaries into one. New operator classes can be declared through catalog entries alone. That interface is Section 9's `PuffinBlobType` and `MergeablePuffinBlobType` for a single-node database, with the union method playing the role of the proposal's `merge` mode, and the `pages_per_range` parameter is a build configuration in the sense of Section 7, fixed per index rather than recorded per summary.

### 13.7 Lance

[Lance](https://lance.org/guide/performance/) is the format that has most directly confronted the compaction problem of Section 2.2, and from the side this proposal avoids: its indexes are positive. Scalar indexes (a B-tree and a bitmap index), full-text indexes and vector indexes (IVF with product, scalar or residual quantization, and HNSW variants) are stored outside the data fragments and keyed by row address, so a compaction that rewrites fragments invalidates every address the index holds. By default compaction remaps each index to the new addresses. A *fragment reuse index* defers that work by recording the mapping from old fragment addresses to new ones, to be applied when the index is next rebuilt, and the documentation notes that compaction and index building conflict because both modify the same indexes and that indexed fragments cannot be compacted together with unindexed ones. A change merged in mid 2026 has compaction drop rewritten fragments from an address-keyed index's coverage, so that those fragments fall back to a scan rather than being served by stale addresses.

Lance is the cost side of Section 12.2 made concrete: a positive index that must be kept consistent with the data's physical addresses pays for every rewrite, and the engineering around it is a sequence of ways to defer or bound that payment. It also demonstrates that partial coverage is workable for a positive index, provided the planner knows which fragments are covered, which is the role an extract's snapshot plays in the Iceberg index specification and the role liveness plays here.

### 13.8 Delta Lake Bloom filter indexes

Delta Lake, as implemented by Databricks, offered a [Bloom filter index](https://docs.databricks.com/aws/en/optimizations/bloom-filters) consisting of a data skipping index for each data file, written alongside the data into a `_delta_index` directory and consulted before a file is read; a file was opened only if its index indicated that it might match the filter. The feature is now deprecated. The documentation says it adds write overhead, is difficult to tune, and is superseded by predictive input/output and by liquid clustering, and recommends dropping existing indexes and vacuuming the index files away.

The design is per-file and exclusion-only, as this proposal's blobs are, so its failure deserves attention. The index was chosen and sized by the writer at write time, for columns declared in advance, with parameters (false positive rate and expected item count) that the operator had to guess, and it was rebuilt whenever a file was rewritten. That is the write-time, writer-chosen filter that Section 2.3 argues against, and the documented reasons for its deprecation, the cost imposed on every write and the difficulty of choosing parameters without knowing the workload, are the costs that building late under a recorded configuration is meant to avoid.

### 13.9 Managed warehouses

Two proprietary systems have made background index construction with partial coverage a product behavior. [BigQuery](https://docs.cloud.google.com/bigquery/docs/search-index) builds and refreshes search indexes asynchronously, exposes the fraction of a table covered as `coverage_percentage`, and states that queries using the index return correct results even when some data is not yet indexed, with a revised index replacing the old one only once it is sufficiently populated. [Snowflake's](https://www.snowflake.com/en/blog/accelerating-point-lookups-and-analytical-queries) search optimization service maintains per-micro-partition search access paths in background warehouses, with per-column opt-in and a cost estimation function so that operators can confine it to the columns that are queried. Neither publishes its index formats, so neither is a precedent for the specification side of this proposal, but both are evidence that the operating model Section 2.3 describes, an index built behind the data and consulted wherever it happens to exist, is one that users accept and pay for.

## 14. Open Questions

### 14.1 Where index manifests live

Section 12.3 proposes that data-file-scoped blobs be hosted as an index type under the community's index specification rather than listed in table metadata, and the shape of Section 6.3 now depends on whether that specification's authors will accept an extract whose validity is defined by liveness rather than by a snapshot. The work this would entail, from rewriting Section 6.3 around the hosted design to settling how a liveness-validated extract interacts with garbage collection, is tracked as [issue 1](https://github.com/acruise/iceberg-indexing-ecosystem/issues/1) in the repository that holds this draft.

### 14.2 How readers locate blobs

Section 6.3 gives three ways a planner could find the blobs that apply to the data files it is about to scan: a field on the data file's manifest entry, a list of Puffin files in table metadata, or an index manifest listed from table metadata and joined to the scan. The draft assumes the third, but the choice has not been made, the first may look different once the v4 manifest restructuring settles, and all three are superseded if the hosting of Section 14.1 is accepted, since the index specification's tracking file would take the manifest's place. A quantitative comparison of planning cost, metadata growth and commit contention across the options is tracked as [issue 2](https://github.com/acruise/iceberg-indexing-ecosystem/issues/2) in the repository that holds this draft.

### 14.3 Index manifest as a file type or a content type

If the index manifest stays in table metadata, whether it should be a new file type or a new content type within the existing manifest format is the question we are least sure of, and the answer may depend on how the v4 manifest work, which restructures per-file statistics, proceeds. Hosting under the index specification (Section 14.1) would settle it, since the tracking file would take the manifest's place.

### 14.4 Which format version

Section 11 argues that the identifier namespace, the descriptors and the Java interfaces need no format version change, and that data-file-scoped blobs and index manifests could be gated by a table property within the current version. The index specification is targeted at format version 4, and if the hosting of Section 14.1 is adopted, the two would most naturally ship together; whether the community prefers an optional feature of the current version or a v4 feature with a clean dependency on the index specification is a question for the dev list.

### 14.5 Admitting locating and aggregating blobs

Section 6.2 admits locating and aggregating blobs under declared obligations. Whether a first version of the specification should admit them at all, or confine data-file scope to exclusion-only types until someone proposes a concrete type of each kind, is a question we lean toward answering conservatively.

### 14.6 The expression vocabulary

Section 7.8 borrows the catalog's term representation for input expressions without saying which transforms a planner must be able to match. Confining the vocabulary to the partition transforms, which every engine already implements, would let any engine match a predicate to a blob at the cost of leaving out the string functions that make expression-valued inputs worth having, and opening it to the richer expressions of the index specification's derived columns reverses the trade.

### 14.7 Type promotion of referenced fields

A blob records field ids, and Iceberg permits the type of a field to be promoted after the blob was written. Sections 6.2 and 7.8 oblige locating, aggregating and expression-valued types to state how they behave under promotion, but the proposal gives no default for exclusion-only types, where the answer is favorable for order-preserving presence filters and unclear for types such as hashed filters whose encoding of a value depends on its type. Whether the specification should define a default (a blob over a promoted field is not used, say) or require every descriptor to state its behavior is open.

### 14.8 Encryption

The index specification requires index files to be encrypted whenever the source snapshot is, and the point applies with at least equal force to a Puffin file holding an n-gram filter over a string column, from which a determined reader could recover a good deal about the column's contents. This proposal says nothing about encryption, and whether Puffin files holding data-file-scoped blobs should inherit the encryption of the data files they describe, or be governed by a table-level setting, needs an answer before any sensitive column is indexed.

### 14.9 Several blobs of one type for one data file

Nothing in Section 6 prevents two indexers from writing blobs of the same type for the same data file under different configurations, or the same indexer from writing a replacement without removing its predecessor. A reader can use any blob that is valid for its purpose, so correctness is unaffected, but planning cost and storage are, and the index manifest needs a rule for whether a later blob of the same type and fields supersedes an earlier one, whether the configuration digest is part of that identity, and who removes the loser.

### 14.10 Blobs spanning several data files

We have not addressed blobs that span several data files, such as an index over an entire partition, which would need either a multi-valued reference or a partition-scoped binding. We left them out because their compaction semantics are considerably harder and the single-file case is the one we can currently justify.

### 14.11 Puffin file granularity

A Puffin file may hold the blobs of one data file or of thousands, and the choice trades the number of files and requests against the amount of dead weight a Puffin file carries once some of its data files are compacted away. Section 6.4 permits a maintenance rewrite when the live fraction becomes small but gives no threshold and no guidance on batching, and the simulator accompanying this draft accounts for Puffin rewrite work separately because batching background builds makes it a cost worth measuring. Whether the specification should say anything here, or leave it to writers as it does for data file sizing, is open.

### 14.12 Descriptor evolution

This draft has already added two fields to the descriptor (`inference` in Section 6.2 and the `expressions` member of `input-fields` in Section 7.8), and more will follow. Descriptors are published in the registry and exposed by implementations, so the two copies can disagree, and an old implementation can expose a descriptor that lacks a field a reader has learned to consult. A rule is needed for which copy a reader trusts and for what the absence of a field means (the defaults given so far are each chosen to be the conservative reading). The versioning case is settled by Section 4.1: a descriptor change that relaxes an obligation is one an existing reader could be misled by and takes a new major version, while one that tightens an obligation does not.

### 14.13 Registry location and governance

The registry's tier-two file could equally live outside the main repository, and there may be governance reasons to prefer that: a registry that accepts entries from anyone who owns a namespace sits uneasily inside a project whose other files are changed by consensus.

### 14.14 Recording why a blob was built

We have proposed that a configuration describe how one blob was built, and left open whether it should also record why (the access statistics or policy that led an indexer to build it), which would make the store more useful for auditing at the cost of making configurations far less often shared and digests far less often equal.

### 14.15 Retention of out-of-band configurations

We have left the retention contract for out-of-band stores informal: a store must not discard a document while a live blob refers to it, but Iceberg has no way to tell a store that a table has stopped referring to one. Whether the specification should define that signal, or merely recommend the in-band store wherever retention matters, is a question we would like the community's view on.

## 15. References

Apache Iceberg, [*Puffin file format specification*](https://iceberg.apache.org/puffin-spec/).

Apache Iceberg, [*Table specification*](https://iceberg.apache.org/spec/), in particular the sections on deletion vectors and table statistics.

Apache Iceberg, [*REST catalog OpenAPI specification*](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml), in particular its expression and term encoding.

P. Váry and H. Gao, [*Spec: Secondary index specification*](https://github.com/apache/iceberg/pull/16961), apache/iceberg pull request 16961, opened June 2026, preceded by a [design document](https://docs.google.com/document/d/1N6a2IOzC6Qsqv7NBqHKesees4N6WF49YUSIX2FrF7S0).

H. Gao and P. Váry, [*OpenAPI: Add index endpoints and key-lookup index to REST catalog spec*](https://github.com/apache/iceberg/pull/16963), apache/iceberg pull request 16963.

Manish M. (osscm), [*Core, Spark: SCALAR Key Lookup Index, reference implementation of #16961*](https://github.com/apache/iceberg/pull/18187), apache/iceberg pull request 18187.

Apache Iceberg 1.12.0 source distribution, classes `org.apache.iceberg.puffin.StandardBlobTypes`, `PuffinFormat`, `PuffinWriter`, `PuffinReader`, `org.apache.iceberg.deletes.BaseDVFileWriter`, `org.apache.iceberg.StatisticsFile`, `BaseRewriteFiles`, `MergingSnapshotProducer` and `FileCleanupStrategy`, as published to Maven Central.

Apache Paimon, [*File index*](https://paimon.apache.org/docs/master/concepts/spec/fileindex), specification page.

Apache Pinot, [*Indexing*](https://docs.pinot.apache.org/indexing), and the `org.apache.pinot.segment.spi.index.IndexType` interface in the `pinot-segment-spi` module of the apache/pinot repository.

Apache Lucene, [`org.apache.lucene.codecs.Codec`](https://lucene.apache.org/core/9_12_2/core/org/apache/lucene/codecs/Codec.html), Lucene 9.12 API documentation.

ClickHouse, [*Understanding ClickHouse data skipping indexes*](https://clickhouse.com/docs/optimize/skipping-indexes), and the [`materialize_skip_indexes_on_merge`](https://clickhouse.com/docs/reference/settings/merge-tree-settings/materialize) MergeTree setting.

Apache Hudi, [*Metadata indexing*](https://hudi.apache.org/docs/1.2.0/metadata_indexing), version 1.2.0 documentation.

PostgreSQL Global Development Group, [*BRIN indexes*](https://www.postgresql.org/docs/current/brin.html), PostgreSQL documentation, chapter 67.

Lance, [*Performance guide*](https://lance.org/guide/performance/), and lance-format/lance [pull request 8084](https://github.com/lance-format/lance/pull/8084).

Databricks, [*Bloom filter indexes*](https://docs.databricks.com/aws/en/optimizations/bloom-filters).

Google Cloud, [*Manage search indexes*](https://docs.cloud.google.com/bigquery/docs/search-index), BigQuery documentation.

Apache Arrow, [*Canonical extension types*](https://arrow.apache.org/docs/format/CanonicalExtensions.html).

Apache Parquet, [*Bloom filter*](https://parquet.apache.org/docs/file-format/bloomfilter/), in the Parquet file format documentation.

N. Freed, J. Klensin and T. Hansen, [*Media Type Specifications and Registration Procedures*](https://www.rfc-editor.org/rfc/rfc6838), RFC 6838, January 2013.

A. Rundgren, B. Jordan and S. Erdtman, [*JSON Canonicalization Scheme (JCS)*](https://www.rfc-editor.org/rfc/rfc8785), RFC 8785, June 2020.

S. Bradner, [*Key words for use in RFCs to Indicate Requirement Levels*](https://www.rfc-editor.org/rfc/rfc2119), RFC 2119, March 1997.

Oracle, [`java.util.ServiceLoader`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.html), Java SE 21 API documentation.
