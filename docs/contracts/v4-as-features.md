# Iceberg Table Format, Version 4, As If Features Existed

*An illustration for the [note on versioning by feature](feature-versioning.md): the table specification as of version 4, rewritten as the index that note says it could become. It is not a proposal for how Iceberg should be restructured, and the reliance judgments are our reading of the specification, not the specification's. The facts are taken from apache/iceberg `format/spec.md` at commit `0cb5041`.*

**Author:** Alex Cruise  
**Updated:** November 13, 2026  
**Status:** illustrative

## The core

The core is what every feature extends and none can be declined: table metadata and its atomic swap through a catalog, schemas with field ids, partition specs and sort orders, snapshots and their ancestry, manifests as the record of which data files a snapshot contains, and the list of features below. It is shorter than one might expect, and most of the specification's length lives in the features.

## Named versions

A version is a named set of features. Each feature's range of versions is stated per role, because removing a feature turns out to be two events, not one: writers stop using it in one version, and readers stop meeting it only when no table that might contain it remains, which may be never.

| Version | Readers must understand | Writers may use |
|---|---|---|
| 1 | core, `filesystem-commits-v1` | core, `filesystem-commits-v1` |
| 2 | version 1, `sequence-numbers-v1`, `position-deletes-v1`, `equality-deletes-v1` | version 1, `sequence-numbers-v1`, `position-deletes-v1`, `equality-deletes-v1` |
| 3 | version 2, `deletion-vectors-v1`, `row-lineage-v1`, `default-values-v1`, `variant-v1`, `geospatial-v1`, `timestamp-nanos-v1`, `encryption-keys-v1` | version 3's readers' set, less `position-deletes-v1` |
| 4 | version 3, `features-v1`, `adaptive-metadata-tree-v1`, `colocated-deletion-vectors-v1`, `column-files-v1`, `relative-paths-v1`, `external-location-v1`, `constraints-v1`, `expression-defaults-v1`, `file-type-v1`, `monotonic-snapshot-timestamps-v1` | version 4's readers' set, less `filesystem-commits-v1`, `position-deletes-v1` and `equality-deletes-v1` |

Version 4's readers still meet equality and position deletes, in tables upgraded from earlier versions, and must apply them; its writers may not create them. A specification that versions by integer has to say this in prose, in three places. Here it is two columns.

## Lifecycle by revision

Additions are only half of what a version records. The table below lists the deprecations and removals that the specification has made, each with its status per role and the other parameters the [note](feature-versioning.md#versions-as-sets-of-features) asks a deprecation to record. Several were made between integer versions, by a pull request against the specification; under this arrangement each would have been a minor version with a name, and is identified here by its pull request and date instead.

| Revision | Subject | Read | Write | Replacement, plan or duty |
|---|---|---|---|---|
| 2 | table metadata `schema`, `partition-spec` | supported | deprecated (still required for version 1) | `schemas` with `current-schema-id`, `partition-specs` with `default-spec-id` |
| 3 | `position-deletes-v1` | supported | removed | `deletion-vectors-v1`; migration on the next write that touches a data file's deletes, by that writer |
| [#10833](https://github.com/apache/iceberg/pull/10833), 2024-08-04 | `filesystem-commits-v1` | supported | deprecated | Removal planned for version 4; a catalog's check-and-put instead |
| [#12182](https://github.com/apache/iceberg/pull/12182), 2025-03-03 | `data_file.distinct_counts` | supported | removed ("do not write") | None |
| [#14045](https://github.com/apache/iceberg/pull/14045), 2025-09-15 | the deleted row, as stored in position delete files | supported | deprecated | None; the specification notes that no implementation currently writes it |
| 4 | `filesystem-commits-v1` | removed, for version 4 tables | removed | A catalog's check-and-put |
| 4 | `equality-deletes-v1` | supported (existing deletes "must be applied at read time") | removed | None for new writes; no migration duty |

Two things stand out. The specification uses the word "deprecated" for the first five rows, and the rows show five different combinations of status per role, replacement, plan and duty; two of them, the second and the fourth, are removals for writing. And the only removal for reading applies to tables that could never have contained the feature, since no version 4 table is a file system table; nothing is removed for reading in a table that might still hold an instance, because nothing yet records when a table can no longer contain one.

## Features

Reliance is given for reading, writing and maintenance: **req** (a component that does not implement the feature refuses), **ign** (it may proceed without it), or **req if** a condition holds. Ranges are of table format versions, per role.

### Inherited from versions 1 to 3

| Feature | Read range | Write range | Read | Write | Maintain | Depends on |
|---|---|---|---|---|---|---|
| `filesystem-commits-v1` | 1–3 | 1–3 | ign | req | ign | |
| `sequence-numbers-v1` | 2– | 2– | req | req | ign | |
| `position-deletes-v1` | 2– | 2 | req | req | ign | `sequence-numbers-v1` |
| `equality-deletes-v1` | 2– | 2–3 | req | req | ign | `sequence-numbers-v1` |
| `deletion-vectors-v1` | 3– | 3– | req | req | ign | `sequence-numbers-v1` |
| `row-lineage-v1` | 3– | 3– | req if projected | req | ign | `sequence-numbers-v1` |
| `default-values-v1` | 3– | 3– | req if a projected field is absent from a file | req | ign | |
| `variant-v1`, `geospatial-v1`, `timestamp-nanos-v1` | 3– | 3– | req if projected | req if written | ign | |
| `encryption-keys-v1` | 3– | 3– | req if files are encrypted | req | req | |

### Added in version 4

| Feature | Read | Write | Maintain | Depends on |
|---|---|---|---|---|
| `features-v1` | req | req | req | |
| `adaptive-metadata-tree-v1` | req | req | req | |
| `colocated-deletion-vectors-v1` | req | req | req | `deletion-vectors-v1`, `adaptive-metadata-tree-v1` |
| `column-files-v1` | req if projected | req | req | `adaptive-metadata-tree-v1` |
| `relative-paths-v1` | req | req | req | |
| `external-location-v1` | req | req | req | `relative-paths-v1` |
| `constraints-v1` | ign | req | ign | |
| `expression-defaults-v1` | ign | req | ign | `default-values-v1` |
| `file-type-v1` | req if projected | req if written | ign | |
| `monotonic-snapshot-timestamps-v1` | ign | req | ign | |

Every version 4 feature has a read and write range of 4 onward. `features-v1` is the one that cannot itself be declined, since it is the list a component reads to learn what it may decline; it is the last integer increment, wearing a feature's identifier.

## What the descriptors do not express

Each feature's normative text would end with this section, which is where most of the interesting content of the specification turns out to live.

**`filesystem-commits-v1`.** That the scheme is, in the specification's own words, "**unsafe** in object stores and local file systems". This is a property of the storage beneath the feature, not of the feature, and the descriptor has no field for it.

**`position-deletes-v1`, `equality-deletes-v1`.** That a table upgraded to version 4 still contains them, so their removal ended for writers and has not ended for readers. Expressible, as above, but only because ranges are per role.

**`row-lineage-v1`.** That the rules for writers that move rows are written as a SHOULD around a MUST, while every consumer relies on them as a MUST. The descriptor says writing is required; the prose does not quite agree.

**`default-values-v1`.** That the Java ORC reader cannot supply defaults, so "req if a projected field is absent" is, for a table holding ORC data files, "req, and refused by the reference implementation mid-scan". Implementation status is not part of a descriptor, which is correct, and unfortunate.

**`relative-paths-v1`.** That an orphan removal which does not resolve relative paths against the table location would find no file referenced. This is why maintenance is req, and the descriptor records the conclusion without the reason.

**`external-location-v1`.** How the location is supplied. The specification says catalogs "should" provide it, and the descriptor has no field for "should".

**`constraints-v1`.** Whether a reader may rely on a constraint that is not enforced, such as the primary key into which an upgrade rewrites identifier fields. Reading is ign only for readers that do not try.

**`expression-defaults-v1`.** Which clock, and which time zone, `current_timestamp()` and `current_date()` are evaluated against. The descriptor declares no configuration, which is true of the feature and false of its instances.

**`monotonic-snapshot-timestamps-v1`.** Whose clock. Also that snapshots committed before the upgrade are exempt, which is an instance scope and so expressible, and that a writer whose clock is behind its parent's must make one up, which is not.

**`file-type-v1`.** None identified in this sketch, which is either good news or a sign of not having looked hard enough.

## What writing it out showed

Three things, of which the first is a gap in the note this illustrates.

1. **Ranges are per role.** The note keyed `spec-versions` by specification, with one range each. Equality deletes need two ranges for the table specification, one for readers and one for writers, and so does any feature that is retired. The note now keys ranges by role where they differ, as it already keyed reliance.
2. **Removal is two operations.** Stopping writers is a version boundary. Stopping readers needs to know that no retained snapshot of any table uses the feature, which is the [closure of features in use](feature-versioning.md#which-features-a-snapshot-uses-computed-upward), per snapshot, and nothing less.
3. **The "not expressed" sections cluster.** Of the nine entries with something to say, four concern either a clock or who holds the pointer. The specification is most precise about what it controls, and least about the two things outside the table that it depends on: what time it is, and who holds the pointer.
