# Splitting the Proposal into Phases

*A working note on how [Pluggable Puffin Blob Types and a Compaction-Aware Blob Lifecycle](puffin-blob-types-and-compaction.md) could be taken to the Iceberg community in stages.*

**Status:** working note, not part of the proposal. The proposal is not intended to land all at once; this note says how it could be cut into phases that are, and the proposal links back here from its header.

The proposal runs to some 22,000 words, and the [DISCUSS] post of November 2, 2026 said as much. Its mechanisms were written as one design because each was tested against the same running example, but they do not have to be adopted as one, and the proposal already makes the argument for separability in Section 11.1: the namespace and the descriptor can ship without a format change, as can the interfaces, while file-scoped blobs need either a sidecar outside table metadata or a format version. This note takes that argument the rest of the way and proposes four phases, each small enough to be reviewed in a sitting and to land on its own, together with a side track that was never part of the specification work.

## Principles

Four principles decided where the cuts fall, and a reader who disagrees with one of them will probably want a different split.

Each phase has to be adoptable on its own, which means it carries its own motivation and its own compatibility argument, and it leaves the format in a state the community could live with indefinitely if the later phases stalled. A phase that only makes sense as a down payment on the next one is a section, and belongs in that phase's document.

Dependencies point backward only. An earlier phase may reserve a name or a descriptor field for a later one, as phase 1 reserves the `config` member for phase 2, but it may not rely on anything the later phase defines, so that each document can be reviewed with only the earlier ones in hand.

The correctness guarantee of the proposal's executive summary holds at every phase boundary, and it holds by construction, so it does not constrain where the cuts fall: a blob that readers may safely ignore is safe to ignore whether or not the machinery that would have used it has shipped, so a table carrying phase 3 blobs is safe under phase 1 readers, and a phase 4 descriptor is safe under a phase 3 compactor.

The phases are ordered by format impact, with the changes confined to the Puffin footer first and the one that needs new table metadata or a format version last. That is the order in which the community can review them most cheaply, and it keeps the part that depends on another group's decision, the hosting question of phase 3, from blocking the parts that can proceed without it.

## The phases

The table summarizes each phase; the sections of the current draft that each one draws on are listed at the head of its subsection below.

| Phase | Subject | Format impact | Blocks on |
|---|---|---|---|
| 1 | Open blob types: identifiers, registry, core descriptor, discovery | Puffin spec amendment and a registry file; footer only, plus a placement rule and one writer obligation | (none) |
| 2 | Build configurations: digests, stores, reader obligations, revocation | Reserved property keys, one standard blob type, table properties; footer and table properties only | phase 1 |
| 3 | Data-file-scoped blobs and compaction: binding, derivation rule, lifecycle fields, rewrite obligations, location, garbage collection | Sidecar outside table metadata (6.5) or hosting under the index specification, at every version; v4 adds a list of index roots for maintenance | phases 1 and 2; the hosting question (14.3) |
| 4 | Richer inferences and expression inputs: locating and aggregating blobs, `masked` deletes, `input-expressions` | Descriptor extensions only | phase 3 |
| side | Workload telemetry fixes in Spark, Impala and Trino | outside the format; three small pull requests to other projects | (none) |

### Phase 1: open blob types

*Sections of the current draft:* 4, 5 (core fields), 9.1 to 9.4 (identifier and descriptor rules), 11.3 (placement and statistics-file carry-forward), 11.5, 13.2, 13.3.

The phase comprises:

- the identifier grammar, the reservation of un-namespaced names and the namespacing of property keys;
- the two-tier registry;
- the core of the descriptor (`type`, `title`, `definition`, `scope`, `input-fields`, `properties` and `compression`);
- `PuffinBlobType`, `PuffinBlobTypes` and `MalformedBlobException`, together with the rule that a compiled-in implementation matches the full identifier and carries its descriptor;
- descriptors and `ServiceLoader` registrations in `iceberg-core` for the two existing types.

Existing blobs are written and read exactly as before in this phase, which makes it the phase most likely to be adopted quickly and the one that the November 2 post, with its framing of an ecosystem under guardrails, already asks for.

The `scope` values `data-file`, `delete-file` and `puffin-file` can be declared in this phase as classifications of what exists (deletion vectors are already file-bound) without any of the obligations phase 3 attaches to them, provided the descriptor text says plainly that the validity rule for a `data-file` scope arrives with phase 3. Open questions 14.14 (descriptor evolution) and 14.15 (registry governance) belong here.

Part of the maintenance exposure of Section 11.3 belongs here as well, although the section as drafted is framed around index manifests, because two of its findings do not depend on anything phase 3 defines.

- The first concerns snapshot-scoped blobs. A statistics file is the only place a snapshot-scoped blob of a new type can live today, and implementations keep at most one statistics file per snapshot; the Java implementation's `compute_table_stats` replaces it without carrying forward blobs it did not write. Phase 1 invites third parties to write exactly such blobs, so it should say that a writer replacing a snapshot's statistics file SHOULD carry forward the blobs whose types it does not recognize; otherwise those blobs are as perishable under an unaware statistics run as file-scoped blobs are under an unaware compactor.
- The second is the placement rule for Puffin files that no structure an existing tool walks will reach: such files belong under a location disjoint from the table location and sharing no string prefix with it. The rule does not wait on the hosting question and binds the secondary index specification equally. Implementers will begin writing file-scoped blobs under conventions of their own as soon as phase 1 lets them name a type, so stating the rule early costs little.

The section on format versions below explains why neither finding needs a version increment. What remains in phase 3 is the part that does depend on hosting, namely which option of Section 6.3 makes index files reachable and how snapshot expiration removes them.

### Phase 2: build configurations

*Sections of the current draft:* 7.1 to 7.7, the `config` descriptor member, 9.4 (schema rule), 11.4.

The phase takes in the three rules at the head of Section 7 and their working out:

- the four reserved `config-*` property keys;
- digests over RFC 8785 canonical JSON prefixed by the schema digest;
- the `config` descriptor member with its `evaluation` and `composition` lists;
- the in-band `build-config-v1` type, and out-of-band stores declared in table properties;
- the reader obligation to resolve or decline;
- revocation by digest;
- `BuildConfigStore`.

The n-gram filter of Section 7.6 is the worked example and travels with the phase, as does the drift discussion of 7.7. Open questions 14.16 and 14.17 belong here.

This phase applies to snapshot-scoped blobs as much as to file-scoped ones, and it has to precede any registered type that needs evaluation parameters, because Section 11.4 forbids a type from acquiring such parameters after its first version. That is the one hard coupling between phases 1 and 2.

The cheapest way to honor it is for phase 1 to reserve the `config` member and the four property keys by name, and to say that a descriptor declaring evaluation keys cannot be registered until the configuration contract is adopted. The alternative is to propose the two phases as a single specification pull request with two sections, which is defensible, since both are confined to the Puffin footer, but gives up the chance to land the uncontroversial part first.

### Phase 3: data-file-scoped blobs and compaction

*Sections of the current draft:* 6.1, 6.2 (exclusion-only part), 6.3, 6.4, 8, `MergeablePuffinBlobType`, 11.1, 11.2, 11.3 (reachability of index files, expiration).

The phase takes in:

- the binding of 6.1, and the derivation rule and exclusion-only default of 6.2;
- the lifecycle fields `compaction`, `merge-precision` and `deletes` (with the three original values);
- the candidate normative text of Section 8, and `MergeablePuffinBlobType`;
- garbage collection;
- the compatibility analysis of 11.1 to 11.3, including the orphan-file-removal exposure.

This is the phase with a format consequence, and it is the one that cannot be drafted in final form until the hosting question is settled with the authors of the secondary index specification, because the answer decides whether Section 6.3 describes an index manifest in table metadata or a file filter index type under pull request 16961.

Open questions 14.1 to 14.6, 14.11 to 14.13 and 14.9 and 14.10 (type promotion defaults and encryption, which bite as soon as any file-scoped blob over a sensitive column exists) belong here. The relevant parts of Section 12 (12.2 on positive and negative indexes, 12.3 on hosting) become this phase's motivation rather than related work.

### Phase 4: richer inferences and expression inputs

*Sections of the current draft:* 6.2 (second half), 7.8, 7.9, 14.7, 14.8, 14.18.

The phase takes in everything the current draft admits under declared obligations:

- `inference: location` and `inference: aggregate`;
- the `masked` value of `deletes`;
- the `input-expressions` property and the `expressions` member of `input-fields`;
- the positional invalidation direction of 14.18.

Each of these is a descriptor extension under the evolution rule of phase 1, so each can be proposed on its own once phase 3 exists. Section 14.7 already leans toward deferring the inferences to a second version, and this phasing turns the leaning into the plan. The expression vocabulary (14.8) is the open question that would gate the `input-expressions` half, and it could reasonably be proposed before the inference half, since ClickHouse and Hudi already index expressions, as does the index specification.

### The side track

Section 10's three changes (a reporter carried by `SerializableTable`, `metrics-reporter-impl` accepted by Impala, a configurable reporter in Trino) are independent of the proposal and should not wait for it; they are three pull requests to three projects and can go now. Telemetry is a convenience rather than a prerequisite, since late index choices will often be proposed and tested by people looking at their own workloads, and the proposal should not read as though an automated indexer were the only consumer.

## Does any phase need a format version?

Two version numbers are in play: the table format version, which an implementation must refuse when it is higher than the one it supports, and the Puffin file version carried in the magic bytes. A version increment works only by refusal: every implementation that predates it, reader and writer alike, declines the table. A version increment is therefore strong protection, and an expensive one, and we sort each phase's changes by what that refusal would buy. A change makes an increment

- *necessary* if an implementation that predates it, acting in good faith on a table that uses it, can return a wrong answer;
- *very desirable* if such an implementation can silently destroy state the feature depends on, in the ordinary course of work and with no cheaper fence available;
- *unnecessary* otherwise, though the change may still ride along with a version that is shipping for other reasons.

The derivation rule of Section 6.2 makes every blob safe to ignore, so under these definitions the first category is empty for blobs as such, and the question becomes how much loss the community will accept from implementations that do not know the feature.

The precedent is more permissive than Section 11.3 of the proposal suggests. The format specification lists `statistics` and `partition-statistics` as optional fields of table metadata in v1 and v2, so both arrived without an increment, and an implementation that predates them neither walks their files during orphan removal nor, being unable to parse the fields, writes them back when it commits new metadata. The community accepted that exposure for structures that are advisory and can be recomputed. Of the three structures Section 11.3 names, only deletion vectors were fenced by a version, and they are not advisory.

**Phase 1: unnecessary.** Readers that predate the phase already skip blob types they do not recognize, and nothing in the phase changes how existing blobs are written. Property values remain strings, which matters, because the Java reader rejects an entire footer, and every blob in it, when a property value is not a string. The placement rule is a convention for writers that old maintenance tools cannot violate, since they never list outside the table location. The carry-forward obligation for statistics files cannot bind writers that predate it, and the worst those writers can do is drop advisory blobs, which is the exposure the precedent already accepts. An increment bought for this phase would make every older reader refuse the table in order to protect blobs that readers may ignore, which is out of proportion.

If v4 ships while the phase is under review, the phase could state carry-forward as a MUST for v4 writers and a SHOULD for writers of earlier versions, but nothing should wait for that. One caveat concerns the placement rule rather than the format: catalogs that vend storage credentials commonly scope them to the table location, so index files under a disjoint location may be unreadable with the credentials a reader is given, and the rule needs the catalog's cooperation.

**Phase 2: unnecessary.** Configuration references are properties, and out-of-band stores are declared in table properties, which older writers carry through commits unchanged. The one hazard, a reader that knows a type but not the configuration contract, is fenced by the rule of Section 11.4 that a type acquires evaluation parameters only under a new major version. A type version does the work of a format version here, for the only implementations at risk.

**Phase 3: very desirable, for maintenance only.** Index state stays outside table metadata at every version, either in the sidecar of Section 6.5 or hosted under the index specification of pull request 16961, so that index commits never contend with the table's writers (Section 12.3). Readers do not need an increment. Old writers carry the sidecar's one table property through their commits, and the reader's checks make a stale sidecar cost coverage rather than correctness.

What an increment buys is maintenance that knows the index exists. A version 4 table would list its index roots in table metadata, and version 4 maintenance would act on what lies beneath them: orphan removal would spare it, expiration would collect it and purge would remove it (Sections 14.2 and 14.6). The list changes only when an index is enabled or dropped, so the contention it adds is negligible, and it serves the index specification's roots as well as ours. Version 4 is being prepared anyway, so the plan is to propose the list for it. Before then, or if the list misses version 4, the sidecar stands alone under its disjointness rule and accepts stale garbage and leaked roots.

Putting index manifests themselves in table metadata, as the third option of Section 6.3 once assumed, would make the increment more than desirable, since every commit from an older implementation would drop them. It would also bring back the contention the sidecar avoids, and we no longer propose it.

**Phase 4: unnecessary for the table format; for Puffin, only if the encoding changes.** The phase consists of descriptor extensions, which old readers never see because they do not recognize the types that use them. Encoding `input-expressions` as a canonical JSON string avoids a Puffin version. Letting property values be arbitrary JSON would need one, or a new optional member of the blob metadata, as Section 7.8 says.

In short, no phase needs an increment to stay correct. Phase 3 is the only place where one is very desirable, and then only to make maintenance aware of index roots, while index state itself stays outside table metadata. The plan is to propose the list of roots for version 4; until then, and if it misses that version, the sidecar serves without one.

## The document split

The single file would become six, and a reader would face at most about 5,500 words at once.

| Document | Content | Approximate size |
|---|---|---|
| Overview | The executive summary, the invariant, the phase roadmap with its dependencies, the relation to the index specification, and a table saying where each open question now lives | 1,500 words |
| Phase 1 proposal | Sections 4, 5 (core), 9 (core), 11.5, with its own short motivation drawn from 2.1 and 2.3 | 3,500 words |
| Phase 2 proposal | Section 7.1 to 7.7 with the n-gram example | 4,500 words |
| Phase 3 proposal | Sections 6 (including the 6.5 sidecar), 8, the lifecycle descriptor fields, 11.1 to 11.3, 12.2 and 12.3 | 5,500 words |
| Phase 4 proposal | Sections 6.2 (second half), 7.8, 7.9, 14.18 | 4,500 words |
| Related work | Section 12.1 and Section 13, as a companion survey in the manner of the backgrounder | 4,500 words |

Each phase document keeps its own motivation, its own RFC 2119 text, its own interface delta, its own compatibility section and its own open questions, so that it can be read, and argued with, without the others. The [backgrounder's](puffin-blob-types-background.md) section map and the README would be updated to point into the new files, and the current monolith would be kept at its path, frozen, with a note at the top pointing at the overview, since the dev-list post and the Google Doc link to it.

## Sequencing

The hosting question is the critical path for phase 3 and should be put to the index specification's authors directly rather than waiting for the thread; it was the first open question in the draft and the November 2 post did not ask it. Phases 1 and 2 can be drafted as standalone documents while that conversation runs, and a reply on the existing thread that leads with the overview and the phase 1 document, with a concrete and small ask, is a better reason to bump the thread than a reminder would be.

Version 4 is being prepared now, so the list of index roots should be raised as a candidate for it early; it is small and serves both index proposals, which makes it a better first ask than the hosting question. The orphan-removal finding of Section 11.3 is also a natural way to open the conversation with the index specification's authors, since it applies to their specification as it stands, and whatever it asks of them this proposal also asks of itself.
