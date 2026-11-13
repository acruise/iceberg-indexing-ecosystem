# Sidecars: A Name for Something Iceberg Already Has

*A small proposal to give one name, one definition and one specification template to a class of files that the Iceberg table specification already contains under several names, so that the next members of the class can be specified, maintained and reasoned about uniformly. It began as Appendix D of the [note on contracts](README.md#appendix-d-sidecars), which now summarizes it and points here.*

**Author:** Alex Cruise  
**Updated:** November 13, 2026  
**Status:** draft, not yet circulated; a [short version](sidecars-short.md) is the one for circulation

Nothing in this proposal changes a field, a file or the behavior of a conforming implementation. Its first two steps are editorial, and the mechanisms that would need a format change are described only so that the label can be judged by where it leads, and are explicitly not requested.

## What we are asking

1. **Of table specification reviewers:** would the specification accept a short section that defines *sidecar*, lists the properties every kind of sidecar has, and classifies the structures the specification already contains? This changes no behavior and renames nothing.
2. **Of the authors of new kinds of sidecar, column files under version 4 being the case in hand:** would they specify the kind by filling a fixed template of properties and rule slots, writing "none" where a slot is empty, so that what has been decided and what has been left open are both visible?

A third step, two generic mechanisms that each need a format change, is described at the end and is not part of the request.

## The thing already exists

Iceberg tracks several kinds of file that contribute no rows to a table, describe one or more other files or the table's state, and are meaningless apart from what they describe. The specification introduced each at a different time, for a different purpose, and answered the same questions about each in a different place:

| Structure | Describes | How a reader finds it | What a reader that does not support it must do |
|---|---|---|---|
| Table statistics file (Puffin) | A snapshot | Listed in table metadata under `statistics`, by snapshot id | Ignore it: statistics are "informational" and support "is not required to read the table correctly" |
| Partition statistics file | A snapshot, one row per partition | Listed in table metadata under `partition-statistics` | Ignore it |
| Position delete file (version 2) | The data files it names by path | Tracked as a delete file in a delete manifest | Unstated, though a reader that skipped it would return deleted rows |
| Puffin file of deletion vectors (version 3) | Data files, one per deletion vector | In version 3 a delete-file entry gives the data file, offset and length; in version 4 the data file's own `deletion_vector` field | Unstated, for the same reason |
| Column file (version 4 draft) | A data file or a version 4 leaf manifest | The subject's `column_files` field, which lists the field ids it carries | Unstated; a reader that projects one of its field ids cannot proceed without it |

The questions recur because they are the questions any such file raises: what it describes, how it is bound to that, how it is found, whether a reader may do without it, how it is encoded, and when it becomes garbage. Answering them separately for each kind has had visible costs:

- **Unstated answers.** For three of the five kinds above, the specification never says what a reader that does not support the kind must do. Every implementation infers the answer from the semantics, which is safe only as long as every implementer infers the same thing.
- **Divergent answers.** A version 2 position delete file has a lifecycle of its own, and nothing removes it with the data files it names, so it can outlive all of them. In version 4 a deletion vector is a field of its data file's entry and is removed with it. Neither rule is wrong, but the second was not arrived at by generalizing the first.
- **Maintenance taught kind by kind.** Statistics files are listed in table metadata, yet the Java implementation took four releases to teach orphan removal, expiration and purge about them ([note on index reachability](../proposals/index-reachability.md#icebergs-own-record-with-auxiliary-files)), and purge still leaves replaced statistics files behind. Each new kind has so far been made safe by changes to maintenance in every engine (E17 in the [register of problems](problems.md#e17-maintenance-learns-what-to-keep-only-from-snapshot-metadata)).
- **Rewrite rules written one at a time.** Deletion vectors have their own rule for what a rewrite does with them, and column files need another (V12). The next kind will need a third, along with its own retry conditions (E1, V4).

Version 4 is already moving in the direction this proposal would name. Deletion vectors, tracked as separate delete files in version 3, become a field of the data file's entry in version 4, and column files are introduced the same way: a required structure is referenced from its subject's own entry and committed with it. That is the first of the two constraints proposed below, arrived at independently. The proposal is that the pattern be named, so that it is applied to the next kind deliberately and not rediscovered.

This repository proposes two further kinds, each of which the definition below covers without change: the data-file-scoped index blobs of the [Puffin proposal](../proposals/puffin-blob-types-and-compaction.md), which are discovered and ignorable, and the *format sidecars* of the [note on contracts](README.md#sidecars-for-format-integration), which carry what a data file format cannot hold natively (field ids, type annotations) and whose reliance is declared per kind and, where a kind cannot decide, per instance.

## Definition

A **sidecar** is a file that Iceberg tracks separately from the one or more **subjects** it describes, and whose content has meaning only relative to them. A sidecar contributes no rows to the table by itself.

Two neighboring classes are excluded, each for a reason that matters to maintenance:

- **Metadata files** (manifests, manifest lists, the table metadata file) satisfy every property listed below, but they stand in the opposite relation to reachability. A metadata file is *constitutive*: its content decides which other files belong to the table, and it is garbage when no retained snapshot reaches it. A sidecar is *dependent*: whether it belongs to the table is decided by its subjects, and it is garbage when they are. In the graph that maintenance walks from the catalog's pointer, metadata files are interior nodes and sidecars hang from the nodes they describe, and a term covering both would hide the difference that expiration, orphan removal and purge turn on.
- **Equality delete files** have no identifiable subjects. An equality delete applies to every data file in its partition with a lower sequence number, a set decided at read time by a rule rather than named, so there is nothing for it to be bound to.

Whether something is a sidecar can depend on the level of the reachability graph from which it is seen. A discovery chain, the numbered metadata files under a root of their own that list discovered sidecars ([note on decoupled index metadata](../proposals/decoupled-index-metadata.md)), is a sidecar of its table, bound to it by the table's UUID, discovered and ignorable. To the index blobs it lists, though, its files are constitutive, since they decide which of those blobs are live. The term therefore names a relationship at one level of the graph, not a fixed kind of file.

Data files are excluded because they contribute rows. Things that are not files, such as table properties and snapshot summary entries, are excluded because they are not separately tracked; some of them raise similar questions under rewrite, but stretching the term to cover them would cost it its connection to reachability, which is where most of its value lies.

## The six properties

Every kind of sidecar has the following properties, and a specification of the kind states each one.

1. **Subjects and scope.** What the sidecar is about: a data file or a unit within one, a group of data files, a metadata file such as a manifest, a snapshot, or the table. One sidecar may have many subjects of the same scope, as a Puffin file of deletion vectors for many data files does. A kind declares the scopes it admits, and a sidecar of the kind at any other scope is invalid: writers must not commit one, and a reader that meets one refuses it. The subject is not always a data file (column files may belong to a leaf manifest, and statistics files belong to a snapshot), so rules written only for data-file subjects would not cover existing kinds. The specification already makes such declarations, in that `deletion_vector` "may only be set if the tracked file is a data file" and `column_files` "may only be set if the tracked file is a data file or a v4 leaf manifest".
2. **Binding.** How the sidecar identifies each subject, for example by a data file's location and size or by a snapshot id. A binding should be fixed by the commit, durable with the sidecar, and verifiable by a reader ([note on contracts, Appendix E](README.md#appendix-e-bindings-between-a-file-and-the-metadata-that-tracks-it)).
3. **Reference.** How a reader finds the sidecar. A *referenced* sidecar is named by its subjects' own metadata and committed together with it. A *discovered* sidecar is found by some other route, such as an index manifest or a discovery chain, and may be committed independently of its subjects. A reference names the sidecar as a whole; locating a part within it, as a deletion vector's offset and length do, is an addressing rule of the kind (the first slot below), not a reference of its own.
4. **Reliance.** What a reader that does not support the kind must do: read the subjects as though the sidecar were absent (*ignorable*), or refuse them (*required*). Reliance may depend on the read, as it does for a column file, which is required only by a reader that projects one of the field ids it carries.
5. **Encoding.** The physical format: Puffin, a data file format, or something else. The encoding is independent of the other properties, and in particular it is the reference, not the encoding, that makes a sidecar required or ignorable.
6. **Lifecycle.** When the sidecar becomes garbage and which operations remove it. A sidecar is garbage once none of its subjects is live in any retained snapshot. For a referenced sidecar this follows from its subjects; for a discovered one it has to be specified separately. Whether a sidecar whose content is mostly dead may be rewritten to shed it, as the Puffin proposal allows for Puffin files, is a rule of the kind (the fifth slot below).

The dependency of a sidecar on its subjects is stated for the sidecar as a whole, even when its kind divides it into parts that each concern one subject, as a Puffin file of deletion vectors does. The definition does not make such internal structure a concept of its own, because not every kind has it; rules that depend on it belong in the first slot below, and can be generalized later if several kinds turn out to share them.

Stated per snapshot, the dependency is simple. At any snapshot a given sidecar either exists or does not, and if it exists, there is exactly one bitstream for it at its scope: the bytes its binding memorializes. A sidecar is never partly present, and never present in two versions within one snapshot; changing one means writing a new one, which exists from the snapshot that commits it. For a referenced sidecar, existence at a snapshot is decided by that snapshot's own metadata, and so is as reproducible as the snapshot. For a discovered sidecar it is decided by the discovery route when the snapshot is read, which is one more reason discovered sidecars must be ignorable (the second constraint below).

## Slots for kind-specific rules

Most kinds also need rules that apply to them alone. Left unplaced, such rules end up wherever their author first needed them. A specification of a kind of sidecar should instead fill a fixed set of slots, and say "none" explicitly where a slot is empty:

1. **Internal structure and addressing.** How the sidecar is divided into parts and how a reference locates one; for example, a version 4 `deletion_vector` offset and size "must exactly match" the offset and length in the Puffin footer.
2. **Cardinality.** How many sidecars of the kind a subject may have, and how they may overlap; "at most one deletion vector is allowed per data file in a snapshot".
3. **Correspondence with the subject.** What must hold between content and subject beyond the binding; a deletion vector's positions are row positions of its data file.
4. **Replacement and supersession.** What a new sidecar does to older ones and to other structures; a deletion vector "must replace all previously written position delete files", and an update to a data file's deletion vector or column files produces a modified entry and a replaced entry.
5. **Behavior under rewrite.** What compaction and other rewrites of a subject do with the sidecar: carry it forward, merge it, rebuild it or drop it; and whether the sidecar itself may be rewritten to shed content whose subjects are no longer live. Sections 6.4 and 8 of the Puffin proposal fill this slot for blobs.
6. **Concurrent commits.** What a retried commit must check about sidecars of the kind; two deletion vectors added concurrently for one data file conflict, which the Java implementation checks and the specification's list of retry conditions does not yet mention.
7. **Encryption.** Whether and how the sidecar is encrypted with respect to its subject; a column file carries its own `key_metadata`.

## Two constraints that already hold

The definition makes it possible to state two rules that every existing kind already obeys and that no specification text currently says. They are offered as normative text because a future kind that broke either would produce wrong answers.

1. **A required sidecar MUST be referenced from its subjects' metadata and committed in the same snapshot as the subjects it is required for.** Its reachability is then exactly its subjects', and expiration, orphan removal and purge treat the pair as one unit without needing to know the kind. Position delete files, deletion vectors and column files all satisfy this.
2. **A discovered sidecar MUST be ignorable.** Whether a discovered sidecar exists at a snapshot is decided by its discovery route when the snapshot is read, which can change after the snapshot was committed, so two reads of one snapshot may see different discovered sidecars. That is tolerable only if no reader's answer depends on them. Statistics files are ignorable and referenced, which is permitted; the index blobs of the [Puffin proposal](../proposals/puffin-blob-types-and-compaction.md) are ignorable and discovered, which is also permitted.

## Keeping the abstraction small

A new abstraction in a specification can fail in two directions. It can *leak*, so that implementations or the wire format start to carry the abstraction itself rather than the things it describes, and every change to the abstraction becomes a change to them. Or it can *balloon*, absorbing every rule anyone attaches to it until it becomes a framework that each new kind must satisfy in full. This proposal is built to do neither, by the following rules, which are part of what is proposed:

1. **The label is descriptive, not structural.** No metadata field is named for sidecars, no existing field is renamed, and no implementation is asked to have a sidecar type. A kind of sidecar is specified where it is specified today, in the section of the specification that defines its fields, and the label appears there only as a heading for the template. The wire format does not learn the word until a mechanism needs it, and the mechanisms below are not requested.
2. **The property list is closed.** The six properties are fixed. A rule that applies to some kinds but not others goes into a slot, not into the definition.
3. **Promotion requires two users.** A rule moves from a slot into the common properties only when at least two kinds fill that slot the same way and a third is expected to. This is how the definition can grow without its growth being guessed at.
4. **Empty slots are stated, not filled.** Writing "none" for a slot costs a word and records a decision; leaving it out leaves a question that the next implementer answers silently. The template's value is in making gaps visible, not in obliging any kind to have a rule in every slot.
5. **The class stays narrow.** Metadata files, equality deletes and things that are not files are excluded even where the properties would fit, because the label earns its keep through what it says about reachability and lifecycle, and it would say less about them if it covered more.
6. **It is not a plug-in system.** Nothing here concerns run-time discovery of implementations, a registry of kinds or extension by third parties. Those questions arise for Puffin blob types and are taken up separately in the [Puffin proposal](../proposals/puffin-blob-types-and-compaction.md), where a blob type is one instance of a kind of sidecar and needs nothing from this proposal to proceed.

The proposal succeeds if the next kind of sidecar is specified in fewer words than the last, and if a maintenance process that knows only the two constraints above can keep and collect it correctly without having been taught about it.

## A worked example: column files

The column files of the version 4 draft are the kind currently being specified, and filling the template from what the draft and its dev list threads say shows what the template is for. The entries below reflect the draft as read for the note on contracts and may be out of date.

| Property or slot | Column files |
|---|---|
| Subjects and scope | A data file, or a version 4 leaf manifest |
| Binding | By the subject's own entry, which holds the reference |
| Reference | Referenced, by the subject's `column_files` field, which lists the field ids each carries |
| Reliance | Required, for readers that project any of those field ids; ignorable otherwise |
| Encoding | A data file format, from the closed list `avro`, `orc`, `parquet` |
| Lifecycle | With the subject |
| Structure and addressing | None beyond the file format's own |
| Cardinality | A field id "may appear in `field_ids` of at most one column file" of a subject |
| Correspondence | How a column file's rows correspond to its base file's is under discussion ([file representation](https://lists.apache.org/thread/jbh1gbrso5h6l4by9rh9poy2cjjtb8j0), [metadata representation](https://lists.apache.org/thread/7jryw9dfvc02s411twn4o7s5gjrybfxg)) |
| Replacement | An update produces a modified entry and a replaced entry |
| Behavior under rewrite | Not established here; a rewrite must fold the values into its output or carry the column files forward (V12) |
| Concurrent commits | Not in the specification's retry conditions (V4); [prose fix 1](prose-fixes.md#1-retry-conditions-for-deletion-vectors-and-column-files) proposes the rule |
| Encryption | Its own `key_metadata` |

Two of the thirteen rows are open questions, and one more is a gap the specification has not yet addressed. None of the three is news to the people specifying column files, but the template puts them in a table beside the rows that are settled, and a reviewer of the next kind of sidecar will look for the same thirteen rows.

Two further rows deserve attention beyond the template. The encoding row draws on the same closed list of formats as data files, so any decision to stop writing ORC and Avro in version 4 applies to column files as well. And the correspondence row is where column files are most exposed: their values are matched to the rows of their base file by position, so they depend on the row-position binding more heavily than anything else in the table ([E13](problems.md#e13-row-positions-are-not-required-to-be-stable), [V6](problems.md#v6-a-column-file-is-bound-to-its-data-file-by-location)).

## Where the label leads, and what is not requested

Two mechanisms follow naturally from the definition, and each needs a format change; a broader direction lies beyond them. All three are described here so that the label can be judged by its consequences. The mechanisms would be proposed only when at least two kinds of sidecar need them, under rule 3 above, and the direction is not proposed at all.

- **Reliance in the reference.** A reader that does not understand a kind cannot read that kind's specification to learn whether it may proceed, so reliance has to be readable from the reference, at a position defined independently of any kind. An instance could then strengthen its kind's reliance but never weaken it. X.509 certificate extensions carry a critical flag for this reason, and PNG encodes the same distinction in the case of each chunk type's first letter.
- **A carry-forward declaration.** A writer that rewrites a subject but does not understand a sidecar attached to it must either copy the sidecar forward, drop it, or refuse the rewrite, and today it chooses without being told. PNG answers the same question with a further bit per chunk type, saying whether an editor that does not understand the chunk may copy it into a modified image. The Puffin proposal's compaction modes (its Section 8) answer it for blobs; a declaration per kind would answer it for every sidecar (E24, E25).
- **A waypoint toward describing every stored object.** Each of the stored-object dimensions of the [note on contracts](README.md#appendix-b-dimensions-of-the-contract) is a property a kind of sidecar has to state: its scope, how it is referenced, its reliance, the polarity of what it asserts, what its binding memorializes and what a rewrite does with it. A descriptor for kinds of sidecar is therefore where those dimensions meet, and the same envelope could describe other stored objects wherever it fits, metadata files included, with the Puffin proposal's blob type descriptors as one instance. The dimensions that describe formats, stores, commits and operations would remain outside it, which is why sidecars are a waypoint toward that description and not the destination.

## Relation to other documents

- The [note on contracts](README.md#sidecars-for-format-integration) treats reliance and placement for format sidecars at more length, including the rules by which an instance may strengthen its kind's reliance, and its [Appendix E](README.md#appendix-e-bindings-between-a-file-and-the-metadata-that-tracks-it) sets out the binding protocol the second property relies on.
- The [register of problems](problems.md) records the costs this proposal addresses: E17, E24, E25 and V12, with V4 and E1 for retry conditions.
- The [Puffin proposal](../proposals/puffin-blob-types-and-compaction.md) specifies one kind of sidecar, data-file-scoped blobs, in full, and its descriptor is an instance of the template here.
- The [note on decoupled index metadata](../proposals/decoupled-index-metadata.md) describes a route by which discovered sidecars can be found and collected.
