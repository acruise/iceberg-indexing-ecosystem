# Pluggable Puffin Blob Types: The Short Version

*A condensed form of [Pluggable Puffin Blob Types and a Compaction-Aware Blob Lifecycle](puffin-blob-types-and-compaction.md), for readers who want the design and the questions without the worked examples, the survey of related work or the long tail of open questions.*

**Author:** Alex Cruise  
**Affiliation:** I work for Cloudera but this is intended to become an open source contribution  
**Updated:** November 11, 2026  
**Status:** draft for discussion on the Iceberg dev list

The full proposal remains the reference, and each section below names the sections of it that carry the detail. Nothing here is accepted Iceberg behavior. Identifiers, property keys and class names are placeholders. Where this version and the full one differ, the difference is an omission, not a change of design.

## What we are asking

Three questions, each answerable without reading further than this page and the section it points to:

1. **Of Puffin and table specification reviewers:** would the specification accept namespaced, versioned blob type identifiers, with un-namespaced names reserved for the project, and a small machine-readable descriptor per type? This is phase 1 below, and it changes nothing about how existing blobs are written or read.
2. **Of the authors of the secondary index specification ([apache/iceberg#16961](https://github.com/apache/iceberg/pull/16961)):** would a list of discovery roots in version 4 table metadata, with four obligations on maintenance, be welcome? The specification as drafted is exposed to orphan removal whether or not this proposal proceeds, and the remedy is cheapest before version 4 is published. This is the most time-sensitive of the three.
3. **Of the same authors:** could data-file-scoped blobs be hosted as a second index type under that specification, one whose state is valid while its data files are live instead of being pinned to a snapshot?

Everything else in this document is the background to those questions.

## The problem

A Puffin file is a sequence of blobs and a JSON footer, and the footer describes each blob by a type string, the field ids it was computed from, a snapshot id and sequence number, a byte range and a properties map. The specification defines two types. The Java library exposes them as constants, and beyond those constants it offers neither a registry nor a means of discovery. Nothing tells an engine what a type of blob means without decoding it, nothing records the parameters a blob was built under, and the table specification says nothing about what a compaction owes the blobs attached to the files it rewrites. (Full proposal, [Section 2](puffin-blob-types-and-compaction.md#2-motivation).)

The consequences fall into three groups:

- **Index types are closed.** A new kind of index can be added only by amending the specification and every engine, so in practice it is added privately or not at all. The [register of problems](../contracts/problems.md#e21-extension-identifiers-are-unmanaged) records the same gap for data file formats and other extension points.
- **The choice of index is fixed when the data is written.** An index finer than min/max statistics is either embedded in the data file by its writer, as Parquet's Bloom filters are, or kept in a snapshot-bound statistics file and lost at the next compaction ([E24](../contracts/problems.md#e24-metadata-attached-to-data-files-does-not-survive-compaction), [E29](../contracts/problems.md#e29-the-choice-of-index-is-fixed-when-the-data-is-written)). Delta's per-file Bloom filter index, deprecated in part because its parameters had to be guessed at write time, shows where the first course leads.
- **Maintenance cannot see what it does not list.** Orphan removal deletes any file beneath the table location that table metadata does not reach, so index files kept anywhere else in the table directory are deleted by a single run from any current engine ([E17](../contracts/problems.md#e17-maintenance-learns-what-to-keep-only-from-snapshot-metadata), [V13](../contracts/problems.md#v13-conforming-version-4-maintenance-may-delete-indexes)).

The format already contains the precedent for a fix. A deletion vector is a Puffin blob with `snapshot-id` and `sequence-number` set to -1 and a `referenced-data-file` property, so its lifetime is that of a data file and not of a snapshot. The proposal generalizes that binding, and the machinery around it, to any type that can live under the rules below.

How other table formats handle each of these questions, and where they do not, is collected in a [survey of prior art](prior-art.md). Late, partial indexing is established practice in Paimon, Hudi, ClickHouse and Lance. None of them offers an open, declared extension point for index types that works across languages.

## Two principles

**Correctness before performance.** The worst a blob can do is make a query slower. An unknown type, an unresolvable configuration, a corrupt payload or a blob orphaned by a compactor that has never heard of it each reduce to the scan the reader would have performed without the blob. A proposal that lets anyone define an index type cannot promise that every implementation will be right, so it arranges that a blob which is wrong or missing shows up as slowness and never as a missing row.

**Explicitness wherever two parties could disagree.** A blob's writer and its readers may be separated by years and by organizations. The major version is therefore part of a type's identifier, a configuration is identified by a digest of its content, and a blob whose configuration cannot be resolved is set aside, never evaluated under a guess.

Two properties of a data-file-scoped blob make the first principle hold, and both are requirements in phase 3:

1. **Derivation.** A blob's content is a function of its data file and its build configuration alone, so ignoring it discards a shortcut and never a fact.
2. **Exclusion only, by default.** A reader may conclude from a blob only that some unit of the file holds no matching row. The blob can err in one direction only, toward reading a file it could have skipped, and that direction survives row-level deletes and conservative merging alike.

The [contracts note](../contracts/README.md#reliance-when-a-reader-may-ignore-a-sidecar) generalizes the first property as *reliance*: whether a reader that ignores a structure may proceed. Data-file-scoped blobs are ignorable sidecars in its terms. Deletion vectors and column files are required ones, and [prose fix 8](../contracts/prose-fixes.md#8-ignoring-is-the-exception) proposes that the specification say which is which in every case, instead of treating permission to ignore as the default.

## The phases

The proposal is cut into four phases, each of which can be adopted alone and leaves the format in a state the community could live with if the later ones stalled. Dependencies point backward only. Because every blob is safe to ignore, a table carrying phase 3 blobs is safe under phase 1 readers. (Detail in the [phasing note](phasing.md).)

| Phase | Subject | Format impact | Depends on |
|---|---|---|---|
| 1 | Open blob types: identifiers, registry, core descriptor | Puffin specification amendment and a registry file | none |
| 2 | Build configurations: digests, stores, revocation | Reserved property keys, one standard blob type, table properties | phase 1 |
| 3 | Data-file-scoped blobs and compaction | A discovery chain outside table metadata at every version; a list of discovery roots in version 4 for maintenance | phases 1 and 2; the hosting question |
| 4 | Locating and aggregating blobs, expressions as inputs | Descriptor extensions | phase 3 |

Phases 1 and 2 are confined to the Puffin footer and to table properties. Phase 3 is the only one with a consequence for table metadata, and the only one waiting on a decision by another group. Phase 4 is described here only in outline.

## Phase 1: open blob types

*Full proposal, Sections [4](puffin-blob-types-and-compaction.md#4-blob-type-identifiers-and-the-registry), [5](puffin-blob-types-and-compaction.md#5-blob-type-descriptors) and [9](puffin-blob-types-and-compaction.md#9-java-service-provider-interface).*

### Identifiers

A blob type identifier stays a single string in the footer's existing `type` field, and its parts are recovered by parsing:

```
blob-type   = [ namespace "." ] name "-v" major
namespace   = label *( "." label )
label       = lower-alpha *( lower-alpha / DIGIT / "-" )
name        = segment *( "-" segment )
segment     = 1*( lower-alpha / DIGIT )
major       = %x31-39 *DIGIT
```

The rules are few:

1. An identifier without a namespace is reserved for types the Iceberg specifications define. The two existing identifiers, `apache-datasketches-theta-v1` and `deletion-vector-v1`, remain valid as written.
2. Every other identifier MUST carry a namespace, which SHOULD be a reverse-DNS name its author controls, as in `com.example.f16-presence-v1`.
3. A change under which an existing reader could misread a payload, or rely on an obligation the type no longer meets, MUST be published under a new major version, which is a distinct type that older readers ignore.
4. Blob property keys without a namespace are reserved for the specification and mean the same thing for every type. The first is `referenced-data-file`, promoted from a private constant in the deletion-vector writer.

### Registry

Uniqueness follows from namespace ownership, so registration is not a condition of validity. The registry has two tiers. Types the specification defines appear as a table in the Puffin specification. Namespaced types are listed in a machine-readable file maintained by pull request under lazy consensus, with review limited to well-formedness, a plausible claim to the namespace and a descriptor that validates. Arrow's canonical extension types and the IANA media type trees are the models.

The [contracts note](../contracts/README.md#a-namespace) argues that the same grammar and the same two tiers would serve `file_format`, which today is a closed list in the specification and a closed enum in the Java API ([V11](../contracts/problems.md#v11-admitting-a-format-takes-a-specification-change-and-the-alternative-is-a-private-name)). We would propose them once and instantiate them twice, with separate registries.

### Descriptors

Each type publishes a descriptor, a small JSON document listed in the registry and exposed by its implementation. An implementation MUST behave as its descriptor says, and beyond that the proposal does not constrain how a type is implemented. Phase 1 defines the core fields:

| Field | Meaning |
|---|---|
| `type` | The identifier |
| `title`, `definition` | A human-readable name and the URL of the normative payload definition |
| `scope` | What the blob is bound to: `snapshot`, `data-file`, `delete-file` or `puffin-file` |
| `input-fields` | How many field ids the blob records, and which primitive types they may have |
| `properties` | The type's own property keys, whether each is required, and its syntax |
| `compression` | The codecs a writer may apply |

In phase 1 the `scope` values classify what exists (deletion vectors are already file-bound) without the obligations phase 3 attaches to them. Phase 1 also reserves the `config` member for phase 2.

The blob's own footer entry is the other half, an *instance description*: it names the type, binds the blob to its data file, and in phase 2 carries the configuration reference, while the descriptor says what is true of every blob of the type ([type descriptors and instance descriptions](../contracts/feature-versioning.md#type-descriptors-and-instance-descriptions)). A descriptor is the type-level half of a separation the contracts note proposes for data file formats as well, between [what is true of every instance of a type and what is recorded per instance](../contracts/README.md#a-global-versioned-type-descriptor-and-a-per-instance-description). A shared descriptor envelope (identifier, major version, schema digest, with a body whose schema differs) is item 2 of its [list of what could be shared](../contracts/README.md#what-could-be-shared).

### Implementations

In Java, a `PuffinBlobType` interface discovered through `ServiceLoader`, with an explicit constructor for engines that isolate plugins in their own class loaders. Two implementations claiming one identifier are a configuration error. A payload an implementation cannot decode raises a dedicated exception that the reader reports and plans around, while any other exception is a bug and propagates.

Implementations in Rust, C++ or Go link their types at build time, and for them the hazard is a known type handled by code compiled against an older understanding of it. Such an implementation:

1. MUST match the full identifier, major version included;
2. MUST carry, compiled in, the descriptor and configuration schema it was built against, including the specification versions the type is defined for and the revision features it understands, and expose them at run time;
3. MUST check at run time, before using a blob, that the Puffin and table format versions lie within the compiled-in range, and that every entry in the blob's `required-features` is one it understands, declining the blob otherwise;
4. MUST NOT infer support for one version or revision from support for another, and MUST decline any payload, descriptor or configuration it does not fully understand.

The descriptor, not the registration mechanism, is what makes a type the same type across languages, and a Java implementation makes the same checks. The [note on versioning by feature](../contracts/feature-versioning.md#implementations-without-run-time-discovery) extends these rules to features of every kind.

### Two maintenance rules that belong here

Neither depends on anything phase 3 defines, and both bind the secondary index specification equally:

1. A writer that replaces a snapshot's statistics file SHOULD carry forward the blobs whose types it does not recognize. The Java `compute_table_stats` currently drops them ([E25](../contracts/problems.md#e25-shared-metadata-is-replaced-whole-by-writers-that-understand-only-part-of-it)), which is harmless while only the project writes blobs and is not once phase 1 invites others to.
2. Puffin files that no structure an existing tool walks will reach belong under a location disjoint from the table location and sharing no string prefix with it, since some implementations match locations by raw prefix.

## Phase 2: build configurations

*Full proposal, [Section 7](puffin-blob-types-and-compaction.md#7-build-configurations) through 7.7.*

A type identifier fixes a payload's format but not the parameters it was built under, and some of those parameters are needed to read it. A character n-gram filter built without case folding, probed by a reader that assumes folding, finds the folded grams absent and excludes a file that contains the row being sought. Nothing reports the failure. Phase 2 makes it impossible to express, under three rules:

1. A configuration is identified by the digest of its canonical form ([RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)), with the digest of the type's configuration schema as a prefix of the preimage, so that two types whose documents happen to coincide never share a digest.
2. A blob records the digest of the configuration it was built under, and a reader evaluates the blob under the configuration it resolves from that digest or does not evaluate it at all.
3. A configuration found to be defective is revoked by digest, in a table property, and every blob built under it goes out of service with one metadata commit.

The phase reserves four property keys (`config-digest`, `config-id`, `config-version` and `config-store`) and adds a `config` member to the descriptor naming the schema, its digest, and which configuration keys a reader needs for evaluation and which must agree for two blobs to be merged. Configurations are resolved from an in-band store, a standard `build-config-v1` blob in the same Puffin file, or from an out-of-band store declared in table properties and shared across tables. A reader caches by digest without invalidation, since a digest names exactly one document.

One rule protects readers that know a type but predate the phase: a type MUST NOT acquire evaluation parameters after its first published version. A type that needs them declares them from the start or takes a new major version, so the type's version does for those readers what a format version would otherwise have to.

Literal values in configuration documents are written as strings by default, because RFC 8785 serializes every JSON number as a double, and the integers most likely to be named this way (snowflake ids, hashes, packed keys) exceed 2^53. The full proposal gives the encoding per type.

The contracts note finds the same three roles of parameter in data file formats, under the heading of [configuration paranoia](../contracts/README.md#configuration-paranoia). Evaluation parameters kept outside the bytes are Parquet's INT96 timestamps and Spark's calendar rebase ([E27](../contracts/problems.md#e27-parameters-that-change-what-the-bytes-mean-are-kept-outside-the-bytes)). Revocation by provenance already exists privately in parquet-java, which ignores binary statistics from writers it knows to be defective ([E26](../contracts/problems.md#e26-statistics-from-a-defective-writer-are-trusted-indefinitely)). A table-level list of revoked writer versions would be the format analogue of `puffin.config.revoked`, and the two lists could share one place in version 4 table metadata (below).

## Phase 3: data-file-scoped blobs and compaction

*Full proposal, Sections [6](puffin-blob-types-and-compaction.md#6-data-file-scoped-blobs), [8](puffin-blob-types-and-compaction.md#8-compaction-semantics-candidate-specification-text), [11](puffin-blob-types-and-compaction.md#11-compatibility) and [14.2](puffin-blob-types-and-compaction.md#142-a-shared-index-metadata-mechanism).*

### Binding

A data-file-scoped blob has `snapshot-id` and `sequence-number` of -1, a `referenced-data-file` holding the location of exactly one data file as it appears in the manifests, and `fields` listing the field ids it was computed from. It is valid for a reader of snapshot *S* if and only if that data file is live in *S*, so the same blob serves every snapshot in which its file appears. The derivation and exclusion-only rules above become requirements, and a type that would carry information not recoverable from its file, as a deletion vector does, MUST NOT declare `scope: data-file`.

A location is a weak identity. The specification relies on a path never being reused for a different file without requiring it ([E5](../contracts/problems.md#e5-paths-may-be-reused)), and on a file never being changed in place without any means of detecting it ([E6](../contracts/problems.md#e6-out-of-band-mutation-is-detectable-only-by-a-change-of-length)). Phase 3 compensates by having the reader also compare the file size and data sequence number recorded beside the blob with the data file's manifest entry. The [binding protocol](../contracts/README.md#appendix-e-bindings-between-a-file-and-the-metadata-that-tracks-it) of the contracts note states the general rule, and its first [version 4 ask](../contracts/v4-asks.md#1-a-content-hash-on-tracked-files), an optional content hash on tracked files, would let a blob record the hash of the bytes it was built from. That one field would serve deletion vectors, column files and blobs alike, and it survives the copying and relocation that version 4's relative paths make routine, which a location does not.

### Lifecycle declarations

Phase 3 adds three fields to the descriptor:

| Field | Values |
|---|---|
| `compaction` | `drop`, `rebuild`, `merge`, or `retain` for scopes other than `data-file` |
| `merge-precision` | `exact` or `conservative`, required with `merge` |
| `deletes` | `conservative`, `approximate` or `invalidated` |

Every exclusion-only type is `deletes: conservative` whether or not it says so, since deleting rows cannot make a file match a predicate that none of its rows did.

### Compaction text

The following is condensed from the candidate specification text. A *rewrite* replaces live data files, its inputs, with outputs holding the same rows after deletes; an output's *sources* are the inputs that contributed rows to it, or all of them when the rewrite does not track provenance.

1. For each blob type attached to the inputs, the writer acts on the type's `compaction` mode. Under `drop` it produces no blob of the type. Under `rebuild` it MAY compute one from the output. Under `merge`, when it implements the type, every source carries a blob of the type for the same field, and the blobs agree on every composition key, it SHOULD compose them, exactly or conservatively as the descriptor declares, and otherwise treats the type as `rebuild`.
2. A writer that lacks an implementation of an attached type MUST treat it as `drop`. This preserves correctness without reading the descriptor and reproduces what every compactor does today.
3. Blobs produced under `rebuild` or `merge` MUST be committed in the same commit as the rewrite.
4. A commit that adds blobs MUST fail, or retry against fresh metadata, if a concurrent commit removed a data file it references, and MUST NOT conflict with commits that do not.
5. A rewrite SHOULD record in its snapshot summary how many blobs of each type it merged and how many it dropped, so that an indexer can find the outputs that lost coverage.

Bin-packing rewrites, where each output concatenates whole inputs, are the case in which merging is both exact and cheap. Sorting and clustering rewrites make every input a source of every output, and writers should prefer to rebuild.

What compaction owes other kinds of sidecar is the same question asked of a different structure. The contracts note records it for column files and future sidecars as [V12](../contracts/problems.md#v12-each-kind-of-sidecar-needs-its-own-rewrite-rule), and the `compaction` field is one answer that could be stated once for every kind of sidecar.

### Where blobs are found

Readers find blobs through an *index manifest*, an Avro file whose rows each record a data file location, a blob type, a field id, a configuration digest and the blob's location in a Puffin file. Index manifests do not belong in table metadata, since every batch of blobs would then be a table commit contending with the table's writers, and every commit by an implementation that does not parse the list would drop it. They live instead in a *discovery chain*:

1. A table property, `puffin.discovery.location`, names a discovery root. Implementations that predate the proposal carry table properties through their commits unchanged.
2. Below version 4, the root MUST be disjoint from the table location and share no string prefix with it, so that no orphan removal lists it.
3. Under the root, a sequence of numbered metadata files lists the current index manifests, and the chain advances by an atomic create-if-absent of the next file. Its commits never contend with data commits.
4. The first file records the table's UUID.

Before using a blob, a reader confirms that the chain's UUID matches the table's, that the referenced data file is live in the snapshot being planned, and that its size and data sequence number match the manifest entry. Any failure to read the chain counts as the absence of an index. Writers unaware of the chain leave it stale or leave garbage in it, and the checks make either cost coverage alone. (Full proposal, [Section 6.5](puffin-blob-types-and-compaction.md#65-a-discovery-chain-for-the-current-format-version), with a table of what each failure costs.)

The alternative, and the answer we prefer if its authors agree, is to host index manifests as a second index type under the index specification, one whose state is validated by the liveness of its data files instead of being pinned to a snapshot. That is question 3 at the head of this document.

### What maintenance needs from version 4

Readers can do without a format version for any of this, but maintenance cannot. A discovery root outside the table location is safe from orphan removal, yet expiration never collects it and purge leaks it. The secondary index specification is in a worse position as drafted, since its worked example and reference implementation place index files beneath the table location, where a single run of orphan removal from any current engine deletes them ([V13](../contracts/problems.md#v13-conforming-version-4-maintenance-may-delete-indexes)). Once version 4 is published, an obligation on maintenance becomes a behavioral change that no format version fences, adopted piecemeal as the handling of statistics files was over four Java releases.

We therefore propose, for version 4, a list of discovery roots in table metadata, each entry recording an index UUID, the root's location and the index's commit authority, together with four obligations on version 4 maintenance:

1. Orphan removal treats every file beneath a listed root as reachable.
2. Snapshot expiration may invoke an index's collector, which deletes only what no retained snapshot can use and only after a grace period longer than any plan a reader may hold open.
3. Dropping a table with purge removes every listed root, or asks the catalog to drop each index it governs.
4. Registering, copying or replicating a table says what becomes of its roots, and by default omits them.

The list changes only when an index is created or dropped, so it costs one table commit per index lifetime. It serves the index specification's roots as well as ours, and gives tables in catalogs without the REST index endpoints a way for maintenance to find their indexes. The [decoupled index metadata note](decoupled-index-metadata.md) works out the envelope, the commit authorities and the reader's rule, and the [reachability note](index-reachability.md) collects the evidence.

The same list is one entry in a larger declaration. The contracts note's [third version 4 ask](../contracts/v4-asks.md#3-declaring-what-a-snapshot-depends-on) proposes that table or snapshot metadata declare what a reader or maintenance process must handle before it starts: the data file formats present, the kinds of sidecar required for reading, and the discovery roots. With the revocation lists of phase 2 added, one small structure would let a reader refuse at load time instead of mid-scan ([E16](../contracts/problems.md#e16-unsupported-formats-and-features-are-discovered-mid-scan)) and let maintenance find everything it is responsible for without scanning. We would rather propose that structure once than propose four lists separately.

## Phase 4: richer inferences and expressions

*Full proposal, Sections [6.2](puffin-blob-types-and-compaction.md#62-what-a-data-file-scoped-blob-may-assert) (second half), [7.8](puffin-blob-types-and-compaction.md#78-expressions-as-inputs) and 7.9.*

Phase 4 admits, under declared obligations, the derived blobs that exclusion-only rules leave out. A *locating* blob states where in its file the matching rows are, which never becomes false because the file is immutable and its row positions are fixed ([prose fix 9](../contracts/prose-fixes.md#9-row-positions-are-fixed-when-a-data-file-is-written)). An *aggregating* blob states a value computed over the file's rows. Both stay safe to ignore. Neither may merge conservatively, and an aggregating blob is invalidated by deletes unless its value can be masked by the file's deletion vector. Phase 4 also adds a reserved `input-expressions` property, so that a blob can describe `lower(sku)` or `truncate[65536](src_ip)` in terms the planner can match against a predicate, using the value expressions of the version 4 expressions specification.

Expressions raise the question the contracts note takes up for [expression defaults](../contracts/README.md#classifying-expressions-instead-of-special-casing-them): a stored expression outlives the function definitions it was written against, and a builder and reader that evaluate it differently produce wrong exclusions instead of lost coverage. [Prose fix 13](../contracts/prose-fixes.md#13-the-evaluation-context-of-functions), which asks functions to declare whether they read their evaluation context, would serve both. The index specification has met the same need through derived columns, and at the index sync of November 9 the participants agreed to take derived columns out of that specification for now and follow the separate generated-columns proposal, without tying either to version 4. Phase 4 takes the same posture. It should follow whatever representation that work settles on, and none of phases 1 to 3 waits for it.

## Format versions

A version increment works only by refusal: every implementation that predates it declines the table. It is necessary only where an older implementation acting in good faith could return a wrong answer, and the derivation rule leaves no such case for blobs.

| Phase | Increment | Reason |
|---|---|---|
| 1 | unnecessary | Old readers already skip unknown types; existing blobs are written as before |
| 2 | unnecessary | Configuration references are properties; the rule on evaluation parameters fences the readers at risk |
| 3 | very desirable, for maintenance only | Readers are safe through the discovery chain's checks; the list of roots makes maintenance aware of indexes |
| 4 | unnecessary for the table format | Descriptor extensions are seen only by readers of the types that use them |

The precedent is permissive. Statistics files and partition statistics files arrived in version 1 and 2 tables without an increment, and implementations that predate them still drop the fields and treat the files as orphans. (Detail in the [phasing note](phasing.md#does-any-phase-need-a-format-version).)

The table above reads "very desirable" for phase 3 only because the format version is the one fence Iceberg has, and it makes every reader refuse in order to make maintenance aware. What phase 3 needs is a fence for maintenance alone, which Delta provides with its VACUUM protocol check. The contracts note's companion on [versioning by feature](../contracts/feature-versioning.md) states, in its dimensions, when an implementation that lacks a feature may proceed in each role, and argues that a list of declared features in version 4 would let later features, this proposal's among them, ship without waiting for version 5. The same note answers the open question of compatible revisions within a blob type's major version, with a reserved `required-features` property that a reader must understand or decline the blob.

## Relation to the index specification

The two designs use the word index for different things. The index specification's scalar index is positive: it says where matching rows are, and a reader that finds no entry concludes that no such row exists. That inference is sound only if the index describes exactly the rows of the snapshot being read, which is why each extract is pinned to a snapshot and why the reference implementation declines to use an index across any snapshot that is not an append ([V14](../contracts/problems.md#v14-each-rewrite-disables-a-snapshot-pinned-index-until-it-is-rebuilt)). The blobs here are negative by default and describe one immutable file, so their validity reduces to that file's liveness.

The difference is one of scope more than of design, and the two suit different workloads. A key index serves a merge that looks up fifty keys across a table, where per-file Bloom filters compound their false positives. A per-file blob built late serves files that live for minutes before compaction and columns whose predicates have drifted toward ranges and substrings no key index serves. Either framework could host the other's structures, provided its metadata records what a reader may infer from each type, and that is the basis of questions 2 and 3.

The REST discussion is compatible with the list of discovery roots. At the November 9 sync the participants settled on indexes as subresources of their table, renamed and dropped with it. The list in table metadata would give every catalog a floor, where maintenance and readers find indexes without asking the catalog, while a REST catalog that implements the index endpoints remains the commit authority for the indexes it governs.

## Open questions

Of the eighteen in the full proposal ([Section 14](puffin-blob-types-and-compaction.md#14-open-questions)), these are the ones that bear on the phases now:

1. **Maintenance and version 4.** Whether version 4 can take the list of discovery roots and its four obligations, and whether the index specification's authors would share it (14.1, 14.2, 14.6).
2. **Hosting.** Whether a liveness-validated extract is acceptable under the index specification, which decides the shape of phase 3 (14.3 to 14.5).
3. **Registry governance.** Whether a registry that accepts entries from any namespace owner belongs inside a repository whose other files change by consensus (14.15).
4. **Descriptor evolution.** Which copy of a descriptor a reader trusts when the registry and an implementation disagree, and what an absent field means (14.14).
5. **Encryption.** An n-gram filter over a string column reveals a good deal about the column, and its Puffin file should probably inherit the encryption of the data file it describes (14.10).
6. **Type promotion.** Whether an exclusion-only blob over a field whose type has since been promoted may be used, by default or only if its descriptor says so (14.9).
7. **Several blobs of one type for one file.** Whether a later blob supersedes an earlier one, whether the configuration digest is part of that identity, and who removes the loser (14.11).

## Where the detail is

- The [full proposal](puffin-blob-types-and-compaction.md): every rule above in full, the n-gram worked example, the Java interfaces, the cost to mixed fleets of unaware writers, related work and all eighteen open questions.
- The [phasing note](phasing.md): the cut into phases and the format-version analysis.
- The [reachability note](index-reachability.md) and the [decoupled index metadata note](decoupled-index-metadata.md): the maintenance hazard and the remedy proposed for version 4.
- The [survey of prior art](prior-art.md): what Delta, Hudi, Paimon, Lance, DuckLake and Hive do for each benefit the proposal offers.
- The [contracts note](../contracts/README.md), its [register of problems](../contracts/problems.md) and its [version 4 asks](../contracts/v4-asks.md): the same questions asked of data file formats, storage and commits, and the metadata the two proposals would share.
- The [backgrounder](puffin-blob-types-background.md): a primer on Puffin and indexing for readers new to either.
