# Pluggable Puffin Blob Types and a Compaction-Aware Blob Lifecycle

*A discussion draft for the Apache Iceberg community.*

Alex Cruise, October 2026. Status: draft for discussion on the Iceberg dev list. Nothing described here is accepted Iceberg behavior, and the identifiers, property keys and class names below are placeholders chosen to make the discussion concrete.

## 1. Summary

Puffin gives Iceberg a well-specified container for statistics and indexes that live outside data files, but the specification treats a blob as little more than a type string and a byte range, and it says nothing about what should happen to a blob when the data it describes is rewritten by compaction. We propose to close both gaps. On the specification side, we propose a namespace for blob type identifiers with a lightweight community registry, a machine-readable descriptor for each blob type, a general notion of blobs scoped to a single data file (generalizing the binding that v3 deletion vectors already use), and candidate normative text for how compaction treats such blobs. On the implementation side, we propose a service-provider interface in the Java library, discovered through `java.util.ServiceLoader`, so that engines can handle registered blob types, compaction included, without compile-time knowledge of them.

The design has one property that we consider essential and that shapes most of the detailed choices: because a data-file-scoped blob is valid only while its data file is live, an implementation that ignores this proposal entirely (an older reader, or a compactor with no knowledge of a given blob type) can lose index coverage but can never produce an incorrect query result. Every new obligation is therefore an opportunity to retain value rather than a new way to corrupt a table, which we believe is what makes it plausible to adopt incrementally and without a format version increment.

## 2. Motivation

### 2.1 Puffin today

The observations in this section were checked against the Iceberg 1.12.0 source distribution (`iceberg-api`, `iceberg-common`, `iceberg-core` and `iceberg-data`) and the published Puffin and table specifications.

A Puffin file consists of a sequence of blobs followed by a JSON footer that records, for each blob, its `type`, the `fields` from which it was computed, a `snapshot-id` and `sequence-number`, its `offset` and `length`, an optional `compression-codec`, and a free-form `properties` map. The specification defines two blob types, `apache-datasketches-theta-v1` and `deletion-vector-v1`, and the Java library exposes them as the two constants of `org.apache.iceberg.puffin.StandardBlobTypes`. Beyond those constants there is no registry of blob types and no discovery mechanism: none of the four modules named above uses `java.util.ServiceLoader` or ships a `META-INF/services` entry, and `PuffinWriter` and `PuffinReader` operate on raw `ByteBuffer` payloads, leaving every consumer to switch on the type string in its own code. The writer also accepts only zstd for blob compression; LZ4, although listed in the specification, is marked as a TODO in `PuffinFormat`.

Puffin files reach query planning through two routes. Table-level statistics files are listed in table metadata and bound to a snapshot (`StatisticsFile` carries a `snapshotId`), which suits a table-wide distinct-value estimate but is the wrong granularity for an index that describes one data file and remains valid across many snapshots. Deletion vectors take the other route, and they are the precedent this proposal builds on. A deletion-vector blob is written with `snapshot-id` and `sequence-number` set to -1, records the data file it applies to in a `referenced-data-file` property, and is located from the manifest entry of its delete file through `referenced_data_file`, `content_offset` and `content_size_in_bytes`. The format therefore already contains a blob whose lifetime is that of a data file rather than a snapshot; it is simply defined for one type only, and the property key that expresses the binding is a private constant in `BaseDVFileWriter` rather than part of the specification's vocabulary.

### 2.2 Compaction and out-of-band metadata

The table specification defines what a rewrite must preserve about rows, including the application of delete files, but it does not define what a rewrite must do with any other metadata attached to its inputs. In the 1.12.0 core library the rewrite commit path (`BaseRewriteFiles` and `MergingSnapshotProducer`) makes no reference to statistics files; a statistics file remains associated with the snapshot for which it was computed and is removed only when that snapshot expires (`FileCleanupStrategy`). The practical consequence is that the snapshot produced by a compaction carries no statistics at all, and anything an operator wants on the compacted data must be recomputed by a separate maintenance job that the ecosystem does not standardize.

We regard this as a gap in the specification rather than an oversight in any implementation. Compaction is largely treated as an operator concern (which strategy, which target file size, when to run it) rather than as a mechanism with defined interactions with the rest of the table's metadata, and so the question of what becomes of an index when its data file is consolidated has never needed an answer. It needs one as soon as indexes are expected to outlive the snapshot in which they were built.

### 2.3 Why this matters now

Our immediate motivation is a line of work we call *progressive indexing*, in which index construction is deferred from write time to background workers that select data files likely to be queried and unlikely to be compacted soon. Write-time indexing is expensive precisely where it is least useful, on small files that the next compaction will discard, and it commits to a choice of index before the workload is known. Deferred indexing addresses both problems, but in simulation its benefit depends heavily on two capabilities that Iceberg does not currently provide: indexes that are bound to data files rather than snapshots, so that building one does not require a new snapshot and does not expire with an unrelated one, and indexes that compaction can carry forward, by composition where the index type permits it, instead of discarding.

A second motivation is extensibility in the ordinary sense. Range filters, learned indexes, quantile sketches and quantized presence maps are all plausible Puffin payloads, and the research literature produces new ones faster than any specification process could standardize them. If such types can only be added by amending the specification and every engine, they will instead be added in incompatible, private ways, which is the outcome a namespace and a discovery mechanism exist to prevent.

## 3. Goals and Non-Goals

The proposal aims to let any party define a new blob type without coordination beyond owning a namespace, to let readers and writers discover the types they can handle at run time, to let a data-file-scoped blob be added to a table without rewriting data files or creating a snapshot, and to give compaction precise, per-type obligations toward such blobs. It aims to do all of this without weakening the existing rule that readers ignore blob types they do not understand.

The proposal does not attempt to standardize any particular index type, to specify how a query planner should use an index, or to require any engine to build indexes at all. It also leaves the existing snapshot-scoped statistics mechanism unchanged; table-wide statistics remain useful and their binding to snapshots is appropriate for what they describe.

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

The major version is part of the identifier, and a change that would cause an existing reader to misinterpret a payload MUST be published under a new identifier with an incremented major version. Compatible evolution, such as an additional optional property, does not change the identifier; the descriptor (Section 5) records it.

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

The first tier is the set of types defined by the Iceberg specification. It would appear as a table in the Puffin specification, one row per un-namespaced identifier, each linking to its normative definition and its descriptor. Additions to this tier follow the ordinary specification process.

The second tier is a community index of namespaced types, maintained as a single machine-readable file in the Iceberg repository (for example `format/puffin-blob-types.json`), in which each entry records an identifier, its owner, a link to its definition, and a link to or a copy of its descriptor. Additions are made by pull request and accepted under lazy consensus, with review limited to checking that the namespace is plausibly owned by the submitter, that the identifier is well formed, and that the descriptor validates. Listing a type in the community index implies nothing about its quality or about any engine's support for it.

Two precedents inform this design. Apache Arrow names extension types by string and recommends that third-party names be namespaced, while reserving a prefix for canonical extension types that the project itself specifies. The IANA media-type registry distinguishes a standards tree from vendor and personal trees, with lighter registration requirements outside the standards tree (RFC 6838). Both have kept independently developed types from colliding over many years without imposing a heavyweight approval process, which is the balance we are seeking.

## 5. Blob Type Descriptors

A blob type identifier tells a reader whether it recognizes a payload, but engines and maintenance tools also need to know things about a type that they can act on without decoding it: what the blob is bound to, which properties it requires, how it behaves when its data file is compacted, and how it behaves in the presence of row-level deletes. We propose that each blob type have a descriptor, a small JSON document published with its definition, listed in the registry, and exposed at run time by its implementation (Section 8). The fields we propose are as follows.

`type` is the identifier from Section 4. `title` and `definition` give a human-readable name and a URL for the normative description of the payload. `scope` is one of `snapshot`, for blobs bound to a snapshot as statistics files are today, `data-file`, for blobs bound to one data file as described in Section 6, or `delete-file`, which is the scope deletion vectors effectively have. `input-fields` states how many field ids the blob records and, optionally, which Iceberg primitive types those fields may have. `properties` lists the type-specific keys, whether each is required, and the syntax of its value.

`compaction` declares the type's behavior when a rewrite replaces the data files it describes, using one of the four modes defined in Section 7: `drop`, `rebuild`, `merge` or `retain`. A type that declares `merge` also declares `merge-precision`, either `exact`, when the merged blob is identical to the blob that would be built from the output data, or `conservative`, when it is a valid but possibly less selective approximation. `deletes` declares how row-level deletes affect the blob's meaning: `conservative` for types whose answers remain correct when some rows are deleted (any presence filter, since deleting rows can only introduce false positives), `approximate` for types whose values drift but remain usable as estimates (distinct-value sketches), and `invalidated` for types that must not be used once a delete applies to their data file. Finally, `compression` lists the codecs that writers may apply, which allows a type such as `deletion-vector-v1`, whose definition forbids compression, to say so mechanically.

A descriptor for a quantized presence index, the type that prompted this proposal, would read as follows.

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

A data-file-scoped blob is a Puffin blob whose `snapshot-id` and `sequence-number` are both -1 and whose `referenced-data-file` property holds the location of exactly one data file, written exactly as that location appears in the table's manifests. Its `fields` list the field ids from which it was computed. The blob is valid for a reader of snapshot *S* if and only if its referenced data file is live in *S*; it carries no other binding to snapshots, and the same blob therefore serves every snapshot in which its data file appears. This definition is deliberately the deletion-vector binding generalized to any type whose descriptor declares `scope: data-file`.

Row-level deletes do not change a blob's binding, but they may change its meaning, and readers MUST consult the descriptor's `deletes` field before using a data-file-scoped blob for a data file that has deletes applied in *S*. The common case, an index used to skip files, is unaffected: deleting rows can only make a file less likely to match a predicate, so an index that admitted the file before the delete remains correct, if slightly less selective, afterward.

### 6.2 Locating blobs

The binding says when a blob is valid; readers also need to find the blobs that apply to the data files they are planning to scan, and here there is a genuine design choice. We see three options and favor the third.

The first option adds a field to the data file's manifest entry pointing at its index blobs, mirroring how delete-file entries locate deletion vectors. It gives planners direct access, but adding an index would then require rewriting the manifest that lists the data file and committing a new snapshot, which ties index construction to the data commit path and its contention, the very coupling that file-scoped blobs are meant to remove.

The second option lists Puffin files in table metadata, as statistics files are listed today, but without a snapshot binding, leaving readers to discover which data files each file covers by reading its footer. It requires no new file type, but planning cost then grows with the number of Puffin files rather than with the number of data files in the scan, and table metadata grows with every index batch.

The third option introduces an *index manifest*: an Avro file, like a data manifest, in which each row records a data file location, a blob type, a field id, and the location, offset and length of the blob in a Puffin file. Table metadata lists the current index manifests, and adding indexes is a metadata-only commit that replaces or appends to that list without producing a snapshot, as `SetStatistics` does today. Planners join index manifest rows to the data files selected by ordinary manifest pruning, so planning cost scales with the scan. The cost of this option is a new metadata structure in the specification, which we think is justified by the separation it buys between index maintenance and data commits.

### 6.3 Garbage collection

An index manifest row, and the blob it locates, become garbage once the referenced data file is not live in any retained snapshot. Snapshot expiration SHOULD remove such rows and SHOULD delete Puffin files that no longer contain a referenced blob. Because readers check liveness before using a blob, deferring this cleanup never affects correctness; it affects only storage and planning cost. A maintenance operation MAY rewrite a Puffin file whose live blobs have become a small fraction of its size, copying the live blobs into a new file and updating the index manifest in one commit.

## 7. Compaction Semantics: Candidate Specification Text

The text in this section is offered as a candidate for inclusion in the table specification. The key words MUST, MUST NOT, SHOULD and MAY are to be interpreted as described in RFC 2119.

**7.1 Definitions.** A *rewrite* is a commit that replaces a set of live data files, its *inputs*, with a set of new data files, its *outputs*, such that the rows of the outputs equal the rows of the inputs after the application of any delete files that the rewrite removes. A blob is *attached* to an input if it is data-file-scoped and references that input. An output's *sources* are the inputs that contributed at least one row to it; when a rewrite does not track row provenance, every input is a source of every output.

**7.2 General rule.** A rewrite MUST NOT cause a data-file-scoped blob to become referenced from table metadata in a state where it describes a data file that is not live. Since validity is defined by liveness (Section 6.1), this rule is satisfied automatically for readers, and the obligations below concern which blobs the outputs receive.

**7.3 Modes.** For each blob type attached to the inputs, the writer of a rewrite MUST act according to the type's declared `compaction` mode, as follows.

1. `drop`: the writer MUST NOT produce a blob of this type for any output as part of the rewrite.
2. `rebuild`: the writer MAY compute a blob of this type for an output from the output's data and include it in the rewrite commit. If it does not, the output has no blob of this type until some later operation builds one.
3. `merge`: if the writer has an implementation of the type, and every source of an output carries an attached blob of the type for the same field with compatible parameters, the writer SHOULD compute the output's blob by composing the sources' blobs and include it in the rewrite commit. A composed blob MUST be exactly the blob that would be built from the output's data when the descriptor declares `merge-precision: exact`, and MUST be a valid conservative approximation of it otherwise. When these conditions do not hold, the writer MUST treat the type as `rebuild`.
4. `retain`: applicable only to blobs whose scope is not `data-file`; such blobs are unaffected by the rewrite.

**7.4 Unknown types.** A writer that has no implementation of a blob type attached to its inputs MUST treat that type as `drop`. This preserves correctness without requiring the writer to read the type's descriptor, and it reproduces the behavior of every compactor in use today.

**7.5 Atomicity.** Blobs produced for outputs under the `rebuild` or `merge` modes MUST be committed in the same commit as the rewrite, so that no snapshot exists in which an output is live but its merged blob is not, and the index manifest rows for attached blobs of the inputs SHOULD be removed in that commit. Their removal MAY be deferred to maintenance, by the argument of Section 6.3.

**7.6 Concurrency.** A metadata-only commit that adds data-file-scoped blobs MUST fail validation, or be retried against fresh metadata, if any data file it references has been removed by a concurrent commit. Such a commit MUST NOT conflict with concurrent appends, rewrites or deletes that do not remove a data file it references.

**7.7 Reporting.** A rewrite SHOULD record in its snapshot summary the number of attached blobs it merged and the number it dropped, per blob type (for example `blobs-merged.com.example.f16-presence-v1`), so that background indexers can find the outputs that lost coverage without scanning index manifests.

We draw attention to one consequence of the provenance rule in 7.1. A rewrite that redistributes rows across outputs, such as a sort or a space-filling-curve clustering, typically cannot say which inputs fed which output, so every input is a source of every output. Merging is then still correct for a conservative type, because the union of every input's filter admits every output row, but the merged blob may be so unselective as to be worthless, and writers SHOULD prefer `rebuild` in that situation. Bin-packing rewrites, where each output is the concatenation of whole inputs, are the case in which merging is both exact and cheap, and in our simulations it is the case that dominates the write path, since files are merged several times on their way to a settled size.

## 8. Java Service-Provider Interface

### 8.1 Interfaces

We propose that the Java library define the following interfaces in `org.apache.iceberg.puffin`. The names are illustrative.

```java
public interface PuffinBlobType<T> {
  /** The identifier written as the blob's type, e.g. "com.example.f16-presence-v1". */
  String type();

  /** The descriptor of Section 5, as published in the registry. */
  BlobTypeDescriptor descriptor();

  /** The decoded representation. */
  Class<T> javaClass();

  ByteBuffer serialize(T value);

  /** {@code data} is the decompressed payload; {@code metadata} gives access to fields and properties. */
  T deserialize(BlobMetadata metadata, ByteBuffer data);
}

public interface MergeablePuffinBlobType<T> extends PuffinBlobType<T> {
  /** Composes the blobs of a rewrite's sources into the blob for one output, per Section 7.3. */
  T merge(List<T> sources, MergeContext context);
}

public final class PuffinBlobTypes {
  public static PuffinBlobTypes load(ClassLoader classLoader);
  public static PuffinBlobTypes of(PuffinBlobType<?>... types);
  public Optional<PuffinBlobType<?>> forType(String type);
  public PuffinBlobTypes withAdditional(PuffinBlobType<?>... types);
}
```

`PuffinBlobTypes.load` discovers implementations listed in `META-INF/services/org.apache.iceberg.puffin.PuffinBlobType` and, on the module path, declared with a `provides` clause. Implementations MUST have a public no-argument constructor and MUST be safe for concurrent use. The two standard types would be implemented in `iceberg-core` and registered in the same way, which keeps the mechanism honest by making its first users the types the project already maintains.

### 8.2 Behavior

Two implementations claiming the same identifier constitute a configuration error, and `load` SHOULD fail rather than choose one silently, since a silent choice would make the meaning of a payload depend on class-path order. Decoding a blob whose type has no implementation MUST yield an empty result rather than an exception, preserving the existing rule that readers ignore unknown types, and a reader SHOULD avoid fetching the payloads of such blobs at all.

### 8.3 Why ServiceLoader, and its limits

Iceberg's usual extension pattern is a single implementation named by a catalog or table property and instantiated reflectively, as with `io-impl` and `catalog-impl`. That pattern suits a component of which exactly one is wanted. Blob types are an open set of which a process may need dozens at once, contributed by independent parties, and `ServiceLoader` is the JDK's standard mechanism for exactly that shape of problem.

Its limitation is class loading. Engines that isolate plugins in separate class loaders (Trino is the prominent example) may not see providers through the thread context class loader, and for that reason the interface includes `of` and `withAdditional`, allowing an engine to construct the set explicitly from whatever loader its plugin model provides. An engine MAY also offer a catalog property listing blob types to exclude, for operators who need to disable a misbehaving implementation without removing it from the class path.

### 8.4 Prototype

A prototype of the discovery mechanism exists in the progressive-indexing repository from which this proposal originates. It defines an equivalent handler interface and registry in Scala, a helper that writes data-file-scoped blobs using the conventions of Section 6.1, and an implementation of the presence filter described in Section 5. Its tests write and read Puffin files with the unmodified Iceberg 1.12.0 `PuffinWriter` and `PuffinReader`, including a zstd-compressed blob and a blob of an unregistered foreign type that the registry correctly skips. Merge support and the index manifest are not yet prototyped.

## 9. Compatibility

Readers that predate this proposal already ignore blob types they do not recognize, so namespaced types pose no risk to them; they also ignore index manifests, so they simply plan without the indexes. Writers that predate the proposal will compact tables carrying data-file-scoped blobs as they compact tables today, dropping coverage for the outputs and leaving stale index manifest rows behind, and by the liveness rule neither outcome affects correctness. For these reasons we believe that the identifier namespace, the descriptor and the Java interfaces require no format version change, and that data-file-scoped blobs and index manifests could be introduced as an optional feature of the current format version, gated by a table property so that writers aware of the feature know to maintain index manifests. We would welcome scrutiny of this argument, since it is the claim on which incremental adoption rests.

The two existing blob types are unaffected. `apache-datasketches-theta-v1` would receive a descriptor declaring `scope: snapshot`, and `deletion-vector-v1` one declaring `scope: delete-file` with compression disallowed, without any change to how either is written or read.

## 10. Open Questions

Whether the index manifest should be a new file type or a new content type within the existing manifest format is the question we are least sure of, and the answer may depend on how the v4 manifest work, which restructures per-file statistics, proceeds. The registry's tier-two file could equally live outside the main repository, and there may be governance reasons to prefer that. We have not addressed blobs that span several data files, such as an index over an entire partition, which would need either a multi-valued reference or a partition-scoped binding; we left them out because their compaction semantics are considerably harder and the single-file case is the one we can currently justify. Finally, the `merge` mode assumes that a writer can determine compatible parameters from the blobs themselves, and some types may need a descriptor field that names the properties which must agree for two blobs to be composable.

## 11. References

Apache Iceberg, *Puffin file format specification*, https://iceberg.apache.org/puffin-spec/.

Apache Iceberg, *Table specification*, in particular the sections on deletion vectors and table statistics, https://iceberg.apache.org/spec/.

Apache Iceberg 1.12.0 source distribution, classes `org.apache.iceberg.puffin.StandardBlobTypes`, `PuffinFormat`, `PuffinWriter`, `PuffinReader`, `org.apache.iceberg.deletes.BaseDVFileWriter`, `org.apache.iceberg.StatisticsFile`, `BaseRewriteFiles`, `MergingSnapshotProducer` and `FileCleanupStrategy`, as published to Maven Central.

Apache Arrow, *Canonical extension types*, https://arrow.apache.org/docs/format/CanonicalExtensions.html.

N. Freed, J. Klensin and T. Hansen, *Media Type Specifications and Registration Procedures*, RFC 6838, January 2013, https://www.rfc-editor.org/rfc/rfc6838.

S. Bradner, *Key words for use in RFCs to Indicate Requirement Levels*, RFC 2119, March 1997, https://www.rfc-editor.org/rfc/rfc2119.

Oracle, `java.util.ServiceLoader`, Java SE 21 API documentation, https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.html.
