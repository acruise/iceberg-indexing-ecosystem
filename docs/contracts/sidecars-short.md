# Sidecars: A Name for Something Iceberg Already Has (Short Version)

*A condensed form of the [proposal on sidecars](sidecars.md), for circulation. The full proposal remains the reference; where the two differ, the difference is an omission, not a change of design.*

**Author:** Alex Cruise  
**Updated:** November 13, 2026  
**Status:** draft for discussion on the Iceberg dev list

Iceberg's table specification already contains a class of files under several names: table and partition statistics files, position delete files, Puffin files of deletion vectors, and in version 4 column files. This proposal gives the class one name, one definition and one specification template, so that the next member of the class is specified, maintained and reasoned about the way the last ones should have been. It does not change the format, or the behavior of any conforming implementation.

## What we are asking

1. **Of table specification reviewers:** would the specification accept a short section that defines *sidecar*, lists the properties every kind of sidecar has, and classifies the structures it already contains? Nothing is renamed.
2. **Of the authors of new kinds of sidecar, column files being the case in hand:** would they specify the kind by filling a fixed template, writing "none" where a slot is empty, so that what has been decided and what has been left open are both visible?
3. **Of both:** would the specification state two rules that every existing kind already obeys but no text says (below)?

## The thing already exists

| Structure | Describes | How a reader finds it | What a reader that does not support it must do |
|---|---|---|---|
| Table statistics file | A snapshot | Listed in table metadata | Ignore it ("informational") |
| Partition statistics file | A snapshot | Listed in table metadata | Ignore it |
| Position delete file (v2) | The data files it names | A delete manifest | Unstated; skipping it returns deleted rows |
| Puffin file of deletion vectors | Data files | A delete-file entry (v3); the data file's own entry (v4) | Unstated, for the same reason |
| Column file (v4 draft) | A data file or leaf manifest | The subject's `column_files` field | Unstated; a reader projecting its fields cannot proceed without it |

Each was introduced at a different time and answered the same questions in a different place, at a cost:

- **Unstated answers.** For three of the five, the specification never says whether a reader may do without them; implementations infer it, which is safe only while they all infer alike.
- **Maintenance taught kind by kind.** Statistics files took four releases of the Java implementation to become safe from its own orphan removal, expiration and purge ([E17](problems.md#e17-maintenance-learns-what-to-keep-only-from-snapshot-metadata)).
- **Rewrite rules written one at a time.** Deletion vectors have their own rule under compaction, column files need another, and the next kind will need a third ([V12](problems.md#v12-each-kind-of-sidecar-needs-its-own-rewrite-rule)).

Version 4 is already moving in the direction this proposal would name: deletion vectors and column files are referenced from their data file's own entry and committed with it. The proposal is that the pattern be named, so that the next kind applies it deliberately rather than rediscovering it.

## Definition

A **sidecar** is a file that Iceberg tracks separately from the one or more **subjects** it describes, whose content has meaning only relative to them, and which contributes no rows by itself.

Metadata files are excluded although they share the properties below. A metadata file is *constitutive*, deciding which other files belong to the table; a sidecar is *dependent*, garbage when its subjects are. That difference is what expiration, orphan removal and purge turn on, and a term covering both would hide it. Equality delete files are excluded because they have no identifiable subjects, and things that are not files (properties, summary entries) because they are not separately tracked.

## Six properties and seven slots

Every kind of sidecar states six properties:

1. **Subjects and scope:** a data file, a group of data files, a manifest, a snapshot or the table.
2. **Binding:** how it identifies each subject, fixed at commit and verifiable by a reader.
3. **Reference:** *referenced*, named by its subjects' metadata and committed with them, or *discovered*, found by another route.
4. **Reliance:** whether a reader that does not support the kind may ignore it or must refuse.
5. **Encoding:** Puffin, a data file format, or anything else; independent of the rest.
6. **Lifecycle:** when it becomes garbage, and which operations remove it.

Rules that apply to one kind alone go into seven fixed slots, each written as "none" where empty: internal structure and addressing; cardinality; correspondence with the subject; replacement and supersession; behavior under rewrite; concurrent commits; encryption.

## Two constraints that already hold

1. **A required sidecar MUST be referenced from its subjects' metadata and committed in the same snapshot as the subjects it is required for.** Its reachability is then exactly its subjects', and maintenance keeps and collects it correctly without knowing its kind. Position delete files, deletion vectors and column files all satisfy this.
2. **A discovered sidecar MUST be ignorable.** Whether a discovered sidecar exists at a snapshot is decided when the snapshot is read, not when it was committed, so two reads of one snapshot may differ. That is tolerable only if no reader's answer depends on it.

## Keeping the abstraction small

A new abstraction can *leak*, so that the wire format carries the abstraction rather than the things it describes, or *balloon*, until every new kind must satisfy a framework. Six rules, which are part of the proposal, prevent both:

1. **The label is descriptive, not structural.** No field is named for sidecars, nothing is renamed, and no implementation is asked to have a sidecar type.
2. **The property list is closed.** A rule that applies to some kinds goes into a slot, not the definition.
3. **Promotion requires two users.** A slot rule becomes a common property only when two kinds fill it the same way and a third is expected to.
4. **Empty slots are stated, not filled.** "None" records a decision; silence leaves the next implementer to guess.
5. **The class stays narrow.** The label earns its keep through what it says about reachability and lifecycle, and would say less if it covered more.
6. **It is not a plug-in system.** Run-time discovery and registries of kinds are out of scope.

The proposal succeeds if the next kind of sidecar is specified in fewer words than the last, and if maintenance that knows only the two constraints keeps and collects it correctly without being taught about it.

## Column files through the template

| Property or slot | Column files (version 4 draft, as read in November 2026) |
|---|---|
| Subjects and scope | A data file, or a leaf manifest |
| Binding | By the subject's own entry |
| Reference | Referenced, by `column_files`, which lists the field ids each carries |
| Reliance | Required for readers that project any of those field ids; ignorable otherwise |
| Encoding | A data file format |
| Lifecycle | With the subject |
| Structure and addressing | None beyond the file format's own |
| Cardinality | A field id appears in at most one column file of a subject |
| Correspondence | **Open:** how a column file's rows correspond to its base file's is under discussion |
| Replacement | An update produces a modified entry and a replaced entry |
| Behavior under rewrite | **Open:** fold the values into the output, or carry the column files forward |
| Concurrent commits | **Gap:** not in the specification's retry conditions |
| Encryption | Its own `key_metadata` |

None of the three marked rows is news to the people specifying column files. The template's contribution is to set them beside the settled rows, where the next reviewer will look for them.

## Not requested

Two mechanisms follow from the label and each would need a format change: recording reliance in the reference, so that a reader can learn whether it may ignore a sidecar without understanding its kind (as X.509 marks extensions critical), and a declaration per kind of what a rewrite that does not understand a sidecar should do with it (as PNG marks chunks safe to copy). Neither is requested; each would be proposed only once two kinds need it. The [full proposal](sidecars.md#where-the-label-leads-and-what-is-not-requested) describes both, and a broader direction beyond them.
