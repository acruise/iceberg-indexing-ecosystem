# Open Puffin Blob Types

*Phase 1 of [Pluggable Puffin Blob Types and a Compaction-Aware Blob Lifecycle](puffin-blob-types-and-compaction.md), proposed for the Apache Iceberg community.*

**Author:** Alex Cruise  
**Updated:** November 9, 2026  
**Status:** draft for discussion; the first of four phases set out in the [phasing note](phasing.md)

Nothing described here is accepted Iceberg behavior, and the identifiers, property keys and class names below are placeholders chosen to make the discussion concrete. This document is cut from the full proposal and can be read without it; where it reserves a name for a later phase, it says so and does not define anything that depends on that phase.

## 1. Summary

The Puffin specification describes a blob as a type string, a list of field ids, a byte range and a free-form property map. It defines two types and says that readers ignore types they do not recognize, but it does not say who else may define a type or how an engine would learn what one is for. We propose to close that gap without changing a byte that any existing writer produces, by adding four things:

1. a grammar for blob type identifiers, with the major version in the identifier and un-namespaced names reserved for the types the Iceberg specifications define (Section 3.1);
2. a rule for blob property keys, under which un-namespaced keys reserved by the specification mean the same thing for every type (Section 3.2);
3. a machine-readable descriptor for each type, and a two-tier registry in which descriptors are published (Sections 3.3 and 4);
4. a service-provider interface in the Java library through which engines find implementations at run time, together with the rules that an implementation linked in at build time must follow instead (Section 5).

Two further rules govern where blobs of the new types may live until the table specification gives them a place of their own (Section 3.4).

The guarantee the full proposal makes holds here in its simplest form, since a reader that does not understand a blob's type still ignores it, and so **the worst a blob of an unfamiliar type can do is fail to help**. What this phase adds is the means for readers and compactors, and for people as well, to learn what a familiar type promises from a document rather than from the code that decodes it.

## 2. Motivation

The observations below were checked against the Iceberg 1.12.0 source distribution and the published Puffin specification.

* The Java library exposes the two standard types as the constants of `org.apache.iceberg.puffin.StandardBlobTypes`, and beyond those constants there is no registry of types.
* There is no discovery mechanism either: none of `iceberg-api`, `iceberg-common`, `iceberg-core` or `iceberg-data` uses `java.util.ServiceLoader`.
* `PuffinWriter` and `PuffinReader` operate on raw `ByteBuffer` payloads, leaving every consumer to switch on the type string in its own code.
* The one property key that already has cross-type meaning in practice, `referenced-data-file`, which binds a deletion vector to its data file, is a private constant in `BaseDVFileWriter` rather than part of the specification's vocabulary.

Range filters, learned indexes, quantile sketches, n-gram filters for substring predicates and quantized presence maps are all plausible Puffin payloads, and the research literature produces new ones faster than any specification process could standardize them. If a type can be added only by amending the specification and every engine, new types will be added anyway, in private and mutually incompatible ways, and the first sign of a collision between two of them will be a reader that decodes one vendor's payload with another vendor's code.

A namespace prevents the collision, and a descriptor together with a discovery mechanism lets an engine act on a type it did not write, loading the code without being rebuilt. That combination is valuable whatever index types the ecosystem ends up holding, and of everything in the full proposal it is the part with the smallest format footprint, since it amends the Puffin specification and adds a registry file while leaving the table specification alone.

## 3. Specification changes

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119). As a matter of style, this document numbers a list when its items look as though they might one day become requirements, or be cited one at a time, and uses bullets for informal enumerations.

### 3.1 Blob type identifiers

A blob type identifier is a single string with one canonical encoding, written in the footer's existing `type` field; its namespace, name and major version are recovered by parsing it, not stored separately. It takes the following form, in ABNF:

```
blob-type   = [ namespace "." ] name "-v" major
namespace   = label *( "." label )
label       = lower-alpha *( lower-alpha / DIGIT / "-" )
name        = segment *( "-" segment )
segment     = 1*( lower-alpha / DIGIT )
major       = %x31-39 *DIGIT        ; 1, 2, ... without leading zeros
lower-alpha = %x61-7A
```

An identifier without a namespace is reserved for types defined by the Iceberg specifications, which keeps `apache-datasketches-theta-v1` and `deletion-vector-v1` valid exactly as written. Every other identifier MUST carry a namespace, and that namespace SHOULD be a reverse-DNS name controlled by the party defining the type, as for Java packages: `com.example.f16-presence-v1`, say.

Since a name cannot contain a dot, the namespace is everything before the final dot and parsing is unambiguous. Identifiers are compared as byte strings, which is why upper-case letters are excluded.

The major version belongs to the identifier because the identifier is the only thing every reader is guaranteed to look at. A change under which an existing reader could misinterpret a payload, or rely on a descriptor obligation the type no longer meets, MUST be published under a new identifier with an incremented major version, and readers of the old version then ignore the new one as they would any unknown type. Compatible evolution (an additional optional property, or a descriptor change that only tightens an obligation) leaves the identifier alone and is recorded in the descriptor.

Compatible evolution is recorded in the descriptor. A scheme under which a blob records the compatible revision it was written under, and a reader can tell what a newer writer may have added and whether it is safe to ignore, is proposed in the [note on versioning by feature](../contracts/feature-versioning.md#within-puffin-compatible-revisions-of-a-blob-type) (the reserved properties `type-revision` and `required-features`), and is outside this phase.

| Identifier | Status |
|---|---|
| `com.example.f16-presence-v2` | Well formed; an incompatible successor to `-v1`, and so a distinct type. |
| `org.example.lake.range-filter-v3` | Well formed; a namespace may have any number of labels. |
| `f16-presence-v1` | Syntactically valid, but reserved, so a third party MUST NOT write it. |
| `com.example.f16-presence-v1.1` | Rejected; minor versions are not part of the identifier. |
| `com.example.f16.presence-v1` | Well formed, but it names `presence` in the namespace `com.example.f16`, which is probably not what its author meant. |

### 3.2 Property keys

Blob properties are at present an unstructured map, and we propose to give them three classes of key:

1. A key without a namespace that the Puffin specification lists as reserved has the same meaning for every type that carries it.
2. A key defined by a type's descriptor is interpreted only in the context of that type and does not need a prefix, which keeps the existing `ndv` property of `apache-datasketches-theta-v1` valid as written.
3. A key that a party attaches to blobs of types it does not own MUST carry that party's namespace.

A type MUST NOT define a key that the specification reserves, and the specification, before reserving a new key, SHOULD check the registry for types that already define it.

This phase reserves one key in use and four for later. `referenced-data-file` holds the location of the one data file a blob refers to, written exactly as that location appears in the table's manifests, which is what deletion vectors already record; this phase gives the key a name in the specification and does not attach any new obligation to it. The keys `config-digest`, `config-id`, `config-version` and `config-store` are reserved for the build-configuration contract of phase 2, and a writer MUST NOT use them for any other purpose.

### 3.3 Descriptors

An identifier tells a reader whether it recognizes a payload. Engines and maintenance tools also need to know things about a type that they can act on without decoding it, and the descriptor is where those things are written down: a small JSON document published with the type's definition and listed in the registry, which the type's implementation also exposes at run time.

The descriptor is the whole of the type's contract with the rest of the system, which means that blobs of the type MUST behave as the descriptor says in every respect the descriptor speaks to, and that beyond this the payload format, the algorithm, the implementation language and the manner of distribution are the type's own business.

This phase defines the descriptor's core members.

| Member | Value | Meaning |
|---|---|---|
| `type` | identifier (Section 3.1) | The type described. |
| `title` | string | A human-readable name. |
| `definition` | URL | The normative description of the payload. |
| `scope` | `snapshot`, `data-file`, `delete-file` or `puffin-file` | What a blob of the type is bound to, as classified below. |
| `input-fields` | object with `count` and optional `types` | How many field ids a blob records in `fields`, and which Iceberg primitive types those fields may have. |
| `properties` | map from key to `{ required, syntax }` | The type-specific property keys, whether each is required, and the syntax of its value. |
| `compression` | list of codec names | The codecs a writer may apply, so that a type whose definition forbids compression can say so mechanically. |

The `scope` values classify what already exists rather than creating anything new. `snapshot` is the binding of today's table statistics files, and `delete-file` the binding deletion vectors effectively have. `puffin-file` names a blob that describes nothing outside the Puffin file containing it, and is reserved for the in-band configuration blobs of phase 2.

`data-file` names a blob that describes one data file and is meant to live exactly as long as that file. This phase defines only the value, and **the binding and the validity rule for data-file-scoped blobs arrive with phase 3**, so that until then a reader MUST NOT infer from the value alone that a blob is safe to use for a snapshot in which its data file is live.

Four further members are reserved by name so that later phases can define them without colliding with anything a type has published in the meantime: `config` for phase 2, and `compaction`, `merge-precision`, `deletes` and `inference` for phase 3 and its successors. A descriptor that conforms to this phase MUST NOT contain any of them, and a consumer that finds one it has not been taught to interpret MUST disregard it rather than guess at its meaning. The same rule applies to any other member a consumer does not recognize, which is what lets the descriptor grow by the evolution rule of Section 3.1 without breaking tools that predate an addition.

One rule in this phase exists for the sake of the next. The full proposal forbids a type from acquiring evaluation parameters (build-time settings that a reader needs in order to evaluate a predicate correctly) after its first published version, because a reader that knows the type but not the configuration contract would evaluate without them and could exclude a file that matches.

A type defined under this phase MUST therefore be **self-describing**: every parameter a reader needs to evaluate a payload MUST be recoverable from the payload and the blob's properties. A type that cannot meet this condition, such as a Bloom filter whose hash seed and normalization live in table configuration, SHOULD wait for phase 2 rather than publish a first version that would have to be abandoned.

The descriptor of a quantized presence index, the type that prompted the full proposal, reads as follows under this phase:

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
  "compression": ["none", "zstd"]
}
```

The two standard types receive descriptors declaring `scope: snapshot` and `scope: delete-file` respectively, the latter with `compression: ["none"]`, and neither changes in how it is written or read.

### 3.4 Where blobs live

Opening the set of types makes it likely that blobs of new types will be written well before the table specification says anything about where they belong. Until it does, their fate is decided by table maintenance, which treats as reachable only the structures its implementation parses. A blob of a new type can live in one of two places today, and each needs a rule, although both rules can be adopted without a format version, for the reasons given in Section 6.

**Statistics files.** A blob of a type declaring `scope: snapshot` can be listed today only through a snapshot's statistics file. Implementations keep at most one statistics file per snapshot, and the Java implementation's `compute_table_stats` replaces it without carrying forward the blobs it did not write. A single statistics run would therefore discard every third-party snapshot-scoped blob on the snapshots it touches.

We propose that a writer replacing the statistics file of a snapshot SHOULD copy into the replacement every blob of the replaced file whose type it does not recognize, with its properties, its `fields`, its `snapshot-id` and its `sequence-number` unchanged. The copy needs no decoding, since the writer can transfer the blob's bytes and footer entry as they stand. A writer MAY omit such a blob when it is removing that blob deliberately, at an operator's request, for example.

The rule is a SHOULD because a MUST could not bind the writers that predate it, and a writer that fails to honor it loses blobs that every reader may ignore, which is the exposure the format already accepts for statistics files themselves. If format version 4 is adopted while this phase is under review, the rule could become a MUST for writers of version 4 tables and stay a SHOULD for writers of earlier versions.

**Files that no table structure reaches.** A blob of a type declaring `scope: data-file` has no structure in table metadata to list it until phase 3, and an implementer who writes such blobs before then will keep them in Puffin files that table metadata does not reference. Orphan file removal deletes every unreferenced file it finds beneath the table location, and an implementation that predates any future structure will do so whatever that structure turns out to be.

We therefore propose that a writer that places a Puffin file where no structure in table metadata references it MUST NOT place it beneath the table location. It SHOULD NOT place it beneath any location whose string form shares a prefix with the table location, since at least one implementation has been reported to compare locations by raw string prefix ([apache/iceberg#16493](https://github.com/apache/iceberg/issues/16493)). The writer then owns the collection of those files, and dropping the table with purge leaves them in place for it to find.

The placement rule has a cost that falls outside the format. Catalogs that vend storage credentials commonly scope them to the table location, and a reader given only those credentials cannot read files placed elsewhere; what such a reader loses is the blobs and nothing more, while deployments in which engines hold storage credentials of their own are unaffected. The [decoupled metadata note](decoupled-index-metadata.md) proposes a list of discovery roots in version 4 table metadata, which would let maintenance written for that version spare such files and would let the rule be relaxed for version 4 tables.

## 4. The registry

Uniqueness follows from namespace ownership, so registration is not a condition of validity. The registry exists for discoverability and for the community's shared picture of what is in use, and it has two tiers.

The first tier is the set of types defined by the Iceberg specifications, which would appear as a table in the Puffin specification with one row per un-namespaced identifier, each linking to its normative definition and its descriptor. Additions follow the ordinary specification process.

The second tier is a community index of namespaced types, kept as one machine-readable file (for example `format/puffin-blob-types.json`) in which each entry records an identifier, its owner, a link to its definition and a link to or copy of its descriptor.

Entries are added by pull request under lazy consensus, and review is limited to checking that the namespace is plausibly owned by the submitter, together with the mechanical checks a script can perform on the identifier's grammar and on the descriptor's schema. A listing does not imply anything about a type's quality or about any engine's support for it.

[Apache Arrow](https://arrow.apache.org/docs/format/CanonicalExtensions.html) names extension types by string, recommends that third parties namespace their names, and reserves a prefix for the canonical types the project specifies. The IANA media-type registry ([RFC 6838](https://www.rfc-editor.org/rfc/rfc6838)) separates a standards tree from vendor and personal trees and asks less of registrations outside the standards tree. Both have kept independently developed types from colliding for many years without a heavyweight approval process, which is the balance sought here.

## 5. Implementations

Iceberg has implementations in Java, Rust, Python, Go and C++, so nothing that holds the ecosystem together may depend on any one of them; the identifier and the descriptor, with the reserved keys, are bytes in a footer and documents in a registry, and any implementation can produce and consume them. What each implementation needs beyond that is a way to find the code that handles a given identifier, and each language answers in its own idiom.

### 5.1 The Java binding

We propose that the Java library define the following in `org.apache.iceberg.puffin`, with illustrative names.

```java
public interface PuffinBlobType<T> {
  /** The descriptor of Section 3.3, as published in the registry. */
  BlobTypeDescriptor descriptor();

  default String type() { return descriptor().type(); }

  Class<T> javaClass();

  /** @throws IllegalArgumentException if this type cannot represent the value */
  ByteBuffer serialize(T value);

  /**
   * {@code data} is the decompressed payload.
   *
   * @throws MalformedBlobException if the payload or properties do not conform to the type's definition
   */
  T deserialize(BlobMetadata metadata, ByteBuffer data);
}

/** Deliberately not an IllegalArgumentException. */
public class MalformedBlobException extends RuntimeException { ... }

public final class PuffinBlobTypes {
  public static PuffinBlobTypes load(ClassLoader classLoader);
  public static PuffinBlobTypes of(PuffinBlobType<?>... types);
  public PuffinBlobTypes withAdditional(PuffinBlobType<?>... types);
  public Optional<PuffinBlobType<?>> forType(String type);

  /** Decodes the usable blobs of a Puffin file and reports the rest as skipped, with reasons. */
  public ReadResult read(PuffinReader reader);
}
```

`PuffinBlobTypes.load` discovers implementations listed in `META-INF/services/org.apache.iceberg.puffin.PuffinBlobType` and, on the module path, declared with a `provides` clause. Implementations MUST have a public no-argument constructor and MUST be safe for concurrent use, and an implementation's `descriptor().type()` MUST be the full identifier it handles. The two standard types would be implemented in `iceberg-core` and registered the same way, so that the mechanism's first users are types the project already maintains.

Two implementations claiming one identifier are a configuration error, and `load` SHOULD fail rather than choose, since a silent choice would make the meaning of a payload depend on class-path order. A blob whose type has no implementation MUST yield an empty result rather than an exception, and a reader SHOULD avoid fetching its payload at all.

An implementation that meets a payload it cannot decode throws `MalformedBlobException`, and the reader catches it and reports the blob as skipped, so that a corrupt index costs coverage and leaves a diagnostic rather than failing the query. Every other exception is a bug and propagates, and `IllegalArgumentException` is kept separate from the malformed case because it signals a caller's mistake that catching would hide.

`ServiceLoader` is used here, where Iceberg usually names a single implementation in a property (`io-impl`, `catalog-impl`), because blob types are an open set of which one process may need dozens, contributed by parties who do not know of each other. Its limitation is class loading: an engine that isolates plugins in separate class loaders, as Trino does, may not see providers through the thread context class loader, which is why `of` and `withAdditional` let it assemble the set from whatever loader its plugin model provides. An engine MAY also offer a catalog property listing types to exclude, for operators who need to disable a misbehaving implementation without removing it from the class path.

Phase 2 is expected to add a `deserialize` overload that receives a resolved build configuration, with a default that delegates to the method above; nothing in this phase depends on it.

### 5.2 Implementations without run-time discovery

A Rust, C++ or Go implementation of Iceberg links its blob types at build time, and for it the set of supported types is closed when the binary is built, and the risk changes shape accordingly: an unknown identifier is still ignored as it always was, so the danger is instead that a *known* type will be handled by code compiled against an earlier understanding of it. We would make the following normative for such implementations:

1. An implementation MUST match the full identifier, major version included, and MUST treat any other major version of the same name as an unknown type.
2. It MUST carry the descriptor it was built against, so that the obligations it was compiled to honor are inspectable from the binary and comparable with the registry's current descriptor.
3. It MUST NOT attempt best-effort handling of a payload or descriptor it does not fully understand, since declining to use a blob is always available and guessing is the one thing the format cannot make safe.

These are the rules the Java library applies, restated for a setting in which an implementation cannot be updated apart from the engine that embeds it. They make the cost of lagging behind a type's evolution a loss of coverage, and they make upgrading a matter of rebuilding against a newer descriptor rather than of auditing behavior.

## 6. Compatibility

Nothing a current writer produces changes, and no current reader is affected. The two standard types are written exactly as before, under the same identifiers and with the same properties, and acquire descriptors and `ServiceLoader` registrations in `iceberg-core`. A reader built before this phase continues to ignore types it does not recognize, which now includes namespaced third-party types, exactly as the specification already requires. The table specification is untouched, and no format version increment is needed.

The two rules of Section 3.4 do not change that. Writers that predate the statistics-file rule will go on replacing statistics files without carrying forward blobs they do not recognize, and the loss of advisory blobs is all that can follow. The placement rule binds only writers of the new types, which are new writers by construction, and the worst a writer that ignores it can suffer is the deletion of its own files by orphan removal. Neither rule can cause a reader to return a wrong answer.

Two kinds of existing practice become non-conforming without becoming unreadable. A vendor that has written blobs under an un-namespaced private identifier has used a name this phase reserves, and should move to a namespaced one under a new identifier; existing blobs remain in place and are ignored by every reader that does not know them, as before. A vendor that has attached un-namespaced properties to the standard types has used keys this phase reserves, and should prefix them. Neither case can produce a wrong query result, because neither changes what a conforming reader does with a blob it understands.

## 7. Related work

Two JVM projects have run this design for years. [Apache Pinot](https://docs.pinot.apache.org/indexing) defines each index kind as an `IndexType` discovered through `ServiceLoader`, and requires a type's id to stay constant across releases because the id keys its on-disk metadata and its configuration, which is the stability requirement of Section 3.1 and the reason an identifier here carries its version.

[Apache Lucene](https://lucene.apache.org/core/9_12_2/core/org/apache/lucene/codecs/Codec.html) writes a codec's name into each segment and resolves it at read time through `Codec.forName`, with a `META-INF/services` registration and a no-argument constructor, and keeps old format names (`Lucene99`, `Lucene912`) readable through a backward-codecs module, the role a new major version plays here.

Because both are JVM projects, a `ServiceLoader` registry can be load-bearing for their whole ecosystem. Iceberg cannot rely on that, which is why the contract of this phase lives in the identifier and the descriptor, and the Java interfaces are only its first binding.

## 8. Open questions

**Descriptor evolution.** Descriptors are published in the registry and exposed by implementations, so the two copies can disagree, and an old implementation can expose a descriptor that lacks a member a newer reader has learned to consult. Section 3.1 settles the versioning half (a change that relaxes an obligation takes a new major version, and one that tightens it does not), and Section 3.3 settles what an unrecognized member means.

What remains is which copy a consumer trusts when both are at hand. Our leaning is that a consumer acts on the descriptor carried by the implementation that decodes the blob, since that is the contract the code was written to, and uses the registry copy only to detect that an implementation is out of date; we would welcome the case for the opposite rule.

**Registry location and governance.** The second-tier file could equally live outside the main repository, and there may be governance reasons to prefer that, since a registry that accepts entries from anyone who owns a namespace sits uneasily inside a project whose other files change by consensus.

**Whether the statistics-file rule can be stronger.** A SHOULD leaves third-party snapshot-scoped blobs at the mercy of every statistics run from an engine that has not adopted it. A table property declaring that the table carries such blobs would let an aware engine refuse to run a statistics procedure that cannot carry them forward, but it would not stop an unaware one, and only a format version can do that.

**Whether phase 2 should ride along.** The self-describing rule of Section 3.3 lets this phase land alone at the cost of asking parameterized types to wait. The alternative is to propose this phase and the configuration contract as one specification pull request in two sections, which is defensible since both are confined to the Puffin footer, but gives up landing the less contentious part first.
