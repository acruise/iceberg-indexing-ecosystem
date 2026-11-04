# Splitting the Proposal into Phases

*A working note on how [Pluggable Puffin Blob Types and a Compaction-Aware Blob Lifecycle](puffin-blob-types-and-compaction.md) could be taken to the Iceberg community in stages.*

**Status:** working note, not part of the proposal. The proposal is not intended to land all at once; this note says how it could be cut into phases that are, and the proposal links back here from its header.

The proposal runs to some 22,000 words, and the [DISCUSS] post of November 2, 2026 said as much. Its mechanisms were written as one design because each was tested against the same running example, but they do not have to be adopted as one, and the proposal already makes the argument for separability in Section 11.1: the namespace, the descriptor and the interfaces can ship without a format change, while file-scoped blobs need a table-property gate or a format version. This note takes that argument the rest of the way and proposes four phases, each small enough to be reviewed in a sitting and to land on its own, together with a side track that was never part of the specification work.

## Principles

Four principles decided where the cuts fall, and a reader who disagrees with one of them will probably want a different split.

Each phase has to be adoptable on its own, which means it carries its own motivation and its own compatibility argument, and it leaves the format in a state the community could live with indefinitely if the later phases stalled. A phase that only makes sense as a down payment on the next one is a section, and belongs in that phase's document.

Dependencies point backward only. An earlier phase may reserve a name or a descriptor field for a later one, as phase 1 reserves the `config` member for phase 2, but it may not rely on anything the later phase defines, so that each document can be reviewed with only the earlier ones in hand.

The correctness guarantee of the proposal's executive summary holds at every phase boundary, which is less a constraint on the split than a property of the design: a blob that readers may safely ignore is safe to ignore whether or not the machinery that would have used it has shipped, so a table carrying phase 3 blobs is safe under phase 1 readers, and a phase 4 descriptor is safe under a phase 3 compactor.

The phases are ordered by format impact, with the changes confined to the Puffin footer first and the one that needs new table metadata or a format version last. That is the order in which the community can review them most cheaply, and it keeps the part that depends on another group's decision, the hosting question of phase 3, from blocking the parts that can proceed without it.

## The phases

| Phase | Subject | Sections of the current draft | Format impact | Blocks on |
|---|---|---|---|---|
| 1 | Open blob types: identifiers, registry, core descriptor, discovery | 4, 5 (core fields), 9.1 to 9.4 (identifier and descriptor rules), 11.5, 13.2, 13.3 | Puffin spec amendment and a registry file; footer only | (none) |
| 2 | Build configurations: digests, stores, reader obligations, revocation | 7.1 to 7.7, the `config` descriptor member, 9.4 (schema rule), 11.4 | Reserved property keys, one standard blob type, table properties; footer and table properties only | phase 1 |
| 3 | Data-file-scoped blobs and compaction: binding, derivation rule, lifecycle fields, rewrite obligations, location, garbage collection | 6.1, 6.2 (exclusion-only part), 6.3, 6.4, 8, `MergeablePuffinBlobType`, 11.1 to 11.3 | New metadata structure or hosting under the index specification; table-property gate or v4 | phases 1 and 2; the hosting question (14.1) |
| 4 | Richer inferences and expression inputs: locating and aggregating blobs, `masked` deletes, `input-expressions` | 6.2 (second half), 7.8 to 7.10, 14.5, 14.6, 14.16 | Descriptor extensions only | phase 3 |
| side | Workload telemetry fixes in Spark, Impala and Trino | 10 | outside the format; three small pull requests to other projects | (none) |

### Phase 1: open blob types

The identifier grammar, the reservation of un-namespaced names, the namespacing of property keys, the two-tier registry and the core of the descriptor (`type`, `title`, `definition`, `scope`, `input-fields`, `properties` and `compression`), together with `PuffinBlobType`, `PuffinBlobTypes`, `MalformedBlobException` and the rule that a compiled-in implementation matches the full identifier and carries its descriptor. The two existing types get descriptors and `ServiceLoader` registrations in `iceberg-core`. Existing blobs are written and read exactly as before in this phase, which makes it the phase most likely to be adopted quickly and the one that the November 2 post, with its framing of an ecosystem under guardrails, already asks for.

The `scope` values `data-file`, `delete-file` and `puffin-file` can be declared in this phase as classifications of what exists (deletion vectors are already file-bound) without any of the obligations phase 3 attaches to them, provided the descriptor text says plainly that the validity rule for a `data-file` scope arrives with phase 3. Open questions 14.12 (descriptor evolution) and 14.13 (registry governance) belong here.

### Phase 2: build configurations

The three rules at the head of Section 7 and their working out: the four reserved `config-*` property keys, digests over RFC 8785 canonical JSON prefixed by the schema digest, the `config` descriptor member with its `evaluation` and `composition` lists, the in-band `build-config-v1` type, out-of-band stores declared in table properties, the reader obligation to resolve or decline, revocation by digest, and `BuildConfigStore`. The n-gram filter of Section 7.6 is the worked example and travels with the phase, as does the drift discussion of 7.7. Open questions 14.14 and 14.15 belong here.

This phase applies to snapshot-scoped blobs as much as to file-scoped ones, and it has to precede any registered type that needs evaluation parameters, because Section 11.4 forbids a type from acquiring such parameters after its first version. That is the one hard coupling between phases 1 and 2. The cheapest way to honor it is for phase 1 to reserve the `config` member and the four property keys by name, and to say that a descriptor declaring evaluation keys cannot be registered until the configuration contract is adopted. The alternative is to propose the two phases as a single specification pull request with two sections, which is defensible, since both are confined to the Puffin footer, but gives up the chance to land the uncontroversial part first.

### Phase 3: data-file-scoped blobs and compaction

The binding of 6.1, the derivation rule and the exclusion-only default of 6.2, the lifecycle fields `compaction`, `merge-precision` and `deletes` (with the three original values), the candidate normative text of Section 8, `MergeablePuffinBlobType`, garbage collection, and the compatibility analysis of 11.1 to 11.3, including the orphan-file-removal exposure. This is the phase with a format consequence, and it is the one that cannot be drafted in final form until the hosting question is settled with the authors of the secondary index specification, because the answer decides whether Section 6.3 describes an index manifest in table metadata or a file filter index type under pull request 16961. Open questions 14.1 to 14.4, 14.9 to 14.11 and 14.7 and 14.8 (type promotion defaults and encryption, which bite as soon as any file-scoped blob over a sensitive column exists) belong here, and the relevant parts of Section 12 (12.2 on positive and negative indexes, 12.3 on hosting) become this phase's motivation rather than related work.

### Phase 4: richer inferences and expression inputs

Everything the current draft admits under declared obligations: `inference: location` and `inference: aggregate`, the `masked` value of `deletes`, the `input-expressions` property and the `expressions` member of `input-fields`, the hot-value bitmaps of 7.9, the equality-delete application of 7.10, and the positional invalidation direction of 14.16. Each of these is a descriptor extension under the evolution rule of phase 1, so each can be proposed on its own once phase 3 exists. Section 14.5 already leans toward deferring the inferences to a second version; this phasing makes that the plan rather than a leaning. The expression vocabulary (14.6) is the open question that would gate the `input-expressions` half, and it could reasonably be proposed before the inference half, since ClickHouse, Hudi and the index specification all already index expressions.

### The side track

Section 10's three changes (a reporter carried by `SerializableTable`, `metrics-reporter-impl` accepted by Impala, a configurable reporter in Trino) are independent of the proposal and should not wait for it. They are three pull requests to three projects and can go now. Telemetry is a convenience rather than a prerequisite, since late index choices will often be proposed and tested by people looking at their own workloads, and the proposal should not read as though an automated indexer were the only consumer.

## The document split

The single file would become six, and a reader would face at most about 5,500 words at once.

| Document | Content | Approximate size |
|---|---|---|
| Overview | The executive summary, the invariant, the phase roadmap with its dependencies, the relation to the index specification, and a table saying where each open question now lives | 1,500 words |
| Phase 1 proposal | Sections 4, 5 (core), 9 (core), 11.5, with its own short motivation drawn from 2.1 and 2.3 | 3,500 words |
| Phase 2 proposal | Section 7.1 to 7.7 with the n-gram example | 4,500 words |
| Phase 3 proposal | Sections 6, 8, the lifecycle descriptor fields, 11.1 to 11.3, 12.2 and 12.3 | 5,500 words |
| Phase 4 proposal | Sections 6.2 (second half), 7.8 to 7.10, 14.16 | 4,500 words |
| Related work | Section 12.1 and Section 13, as a companion survey in the manner of the backgrounder | 4,500 words |

Each phase document keeps its own motivation, its own RFC 2119 text, its own interface delta, its own compatibility section and its own open questions, so that it can be read, and argued with, without the others. The [backgrounder's](puffin-blob-types-background.md) section map and the README would be updated to point into the new files, and the current monolith would be kept at its path, frozen, with a note at the top pointing at the overview, since the dev-list post and the Google Doc link to it.

## Sequencing

The hosting question is the critical path for phase 3 and should be put to the index specification's authors directly rather than waiting for the thread; it was the first open question in the draft and the November 2 post did not ask it. Phases 1 and 2 can be drafted as standalone documents while that conversation runs, and a reply on the existing thread that leads with the overview and the phase 1 document, with a concrete and small ask, is a better reason to bump the thread than a reminder would be.
