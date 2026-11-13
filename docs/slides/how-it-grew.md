---
marp: true
theme: default
paginate: true
size: 16:9
title: It Started With an Index
---

<!-- _paginate: false -->

# It Started With an Index

How a question about building indexes late turned into a question about whether Iceberg admits it is a distributed system

Alex Cruise · November 2026

<!--
This is the story of one narrow question widening five times, told for the reasons to worry rather than for the mechanisms. The mechanisms are in the repository; this deck is about why they kept turning out to be needed.

Timing for thirty minutes: the question, 3 minutes (slides 2-3); the five widenings and the catalog, 15 minutes (slides 4-12); the commonalities, 4 minutes (13-15); the distributed-systems turn, 5 minutes (16-20); the ask and the close, 3 minutes (21-22). If running long, cut the "reasons to worry" slides for formats and contracts to their first two bullets.
-->

---

## The question

Is it worth building indexes **late**, after the data is written, once a background process has watched which predicates each column actually receives?

- Lakes favor write throughput, so heavy indexes can't be built with ingest
- Min/max statistics are free but coarse
- Workloads drift: a new column gets hot, equality becomes range, an incident sends lookups at old data

Late indexing can follow the drift. But only if the index **survives compaction** and can be **trusted by an engine that did not build it**.

<!--
That is the whole original scope. Those two conditions are where every later section of this deck comes from.
-->

---

## What a late index needs that Iceberg doesn't have

| Needs | Iceberg today |
|---|---|
| A type someone outside the project can define | Blob types are bare strings |
| A binding to a **data file**, not a snapshot | Puffin metadata binds to snapshots |
| A record of how it was built | Nothing records parameters |
| A rule for what compaction does with it | Unspecified; in practice, dropped |

Each row became a mechanism in the Puffin proposal. Each mechanism raised a question about something else.

---

## Widening 1: Puffin blob types

- Namespaced, versioned type identifiers and a registry
- A machine-readable descriptor per type
- Blobs scoped to one data file, valid while that file is live
- Build configurations identified by digest
- Compaction modes per type: drop, rebuild, merge, retain

Built to one principle: **an index may make a query slower, never wrong**.

---

## Reasons to worry, round 1

- Conforming v4 **orphan removal deletes every index** under the table location, silently, because a planner without an index just plans without it
- Every compaction **quietly discards** the index coverage built on the files it rewrites
- A filter built with one normalization, consulted for a predicate normalized differently, **excludes files that match**
- Two vendors pick the same bare name for different encodings, and a reader that matches by name applies the wrong one

---

## Widening 2: file formats

The same four questions, asked of data files:

- v4 may **stop writing ORC and Avro** while keeping them readable
- Vortex, Lance and Nimble want room to be admitted
- The File Format API made formats pluggable in Java

Stopping a format and starting one are the same question from opposite ends: **what does a format owe Iceberg?** Nobody has written it down.

---

## Reasons to worry, round 2

- "Keep reading ORC" is not a fixed cost: every later feature must be implemented by every ORC reader, in every language, or they refuse
- The Java ORC reader already **cannot supply default values**, so adding a defaulted column fails reads **mid-scan**
- Nobody can tell when a table's **last ORC file is gone**, so the reader can never be retired
- A new format either waits for a vote and a release, or ships under a **private name** only its vendor reads

---

## Widening 3: contracts

Formats were one party. The same questions apply between every pair of:

engines · writers · maintenance · table metadata · data files and sidecars · storage · the catalog

**What does each require of the other? Is it written down? If it isn't met, may the operation degrade, or must it refuse?**

The answers live mostly in the Java implementation, as read, not as specified.

---

## Reasons to worry, round 3

- Follow the spec's retry conditions to the letter and a compaction racing a delete **resurrects deleted rows** (Java checks; the text doesn't)
- A file overwritten at the same path keeps its deletion vectors, which now **delete the wrong rows**, detectable only if the length changed
- Every major object store offers atomic create; the Java `FileIO`s **don't use it**
- And underneath all of it, a decision nobody has written down in one place (next slide)

---

<!-- _footer: "¹ If your disaster recovery plan is \"we replicate the bucket\", it now replicates every file except the record of which ones are the table. No need to panic. Perhaps reread the plan, though." -->

## Meanwhile: Iceberg now requires a catalog

**Decided:** file system tables were deprecated in August 2024 and are removed in v4, so every commit goes through a catalog; v4 lets metadata omit its `location`, which "catalogs should provide"

**Happening anyway:** every capability added since (scan planning, credentials, multi-table commits, functions, and indexes as proposed) is a catalog endpoint, in practice the REST catalog's

- The catalog's availability becomes the table's; the object store is no longer a complete backup¹
- A catalog that writes metadata must understand v4 **before any of its tables can use it**, and none is visibly preparing
- Every other open format added a catalog mode and **kept a storage-only floor**; S3 shipped conditional writes sixteen days after Iceberg dropped its

<!--
Not an objection: a coordinator is a better place for the atomic swap, and one that sees every commit can enforce validation no client can be trusted with. The point is that it is a large change, and it deserves a sentence of its own in the specification.
-->

---

## Widening 4: sidecars

Iceberg already has a class of file with no name:

statistics files · partition statistics · position deletes · deletion vectors · column files

Each answered the same six questions separately: what it describes, how it's bound, how it's found, whether a reader may skip it, how it's encoded, when it's garbage.

Two rules every kind already obeys, never written down:
**a required sidecar is committed with its subject; a discovered one must be ignorable**

---

## Widening 5: feature versioning

The format version is a **whole-table fence**: support version N entirely, or refuse the table.

- Features declared per table, each with reliance **per role**: read, write, maintain
- Versions become named sets of features; v4 the last integer increment
- Compatible minor revisions, deprecations with parameters, tombstones for the unsafe
- The set of features in use computed **upward**, per snapshot

If v4 takes nothing else, take the feature list: everything else can follow as a feature.

---

<!-- _class: lead -->

# What they turned out to have in common

Found after the fact, every time

---

## Five questions, asked of everything

| Question | Index blobs | Data file formats | Sidecars | Table features |
|---|---|---|---|---|
| May I ignore what I don't understand? | decline the blob | fail mid-scan | unstated for 3 of 5 kinds | the integer fence |
| What exactly does this describe? | one file, by path | columns, by field id or name mapping | its subjects, by location | the whole table |
| What does a rewrite do with it? | dropped | converts or keeps the file | one rule per kind | unstated |
| Who knows not to delete it? | nobody | snapshot reachability | whoever walks its reference | n/a |
| Declared or inferred? | inferred from a name | a closed enum | case by case | one integer |

Today's answers. The specification answers each question somewhere, for something, differently.

<!--
Reliance, binding, carry-forward, reachability, declaration. The contracts note names them as dimensions. None was designed; each was discovered by tripping over it in a second place.
-->

---

## The pattern behind the pattern

- Every rule that keeps an extension safe was **inferred**, not stated
- Inference is correct only while **every implementer infers the same thing**
- The fix each time was the same: **declare it**, and where a declaration can't say it, say so in prose and default to refusal

Explicit does not mean machine-decidable. It means nobody has to guess.

---

<!-- _class: lead -->

# Oh no

this is a distributed system

---

## Who has to agree

- Writers in several engines, several languages, several versions, at once
- Readers that arrive **years later**, with binaries compiled before the data existed
- Maintenance that deletes things on its own schedule
- A catalog that holds the only pointer to the truth
- Storage that guarantees less than everyone assumes

They never talk to each other. They agree only through **the bytes they leave behind**.

<!--
That sentence is the definition of the problem. Everything in the specification that is unstated is a protocol message whose meaning each party guesses.
-->

---

## The classics, all of them

| Distributed systems problem | Where it showed up |
|---|---|
| Mixed-version upgrades | ORC readers vs. new features; v4 feature list; refuse unknown required |
| Distributed garbage collection | Orphan removal vs. index files; discovery roots; reachability |
| Concurrent mutation and validation | Compaction racing deletes; retry conditions |
| Partial failure | Commits of unknown outcome; blind retries duplicate appends |
| Clocks | Strictly increasing snapshot timestamps; orphan age thresholds; `current_timestamp()` defaults |
| A coordinator on the critical path | The catalog: availability, durability, linearizability |
| Protocol evolution | Descriptor grammars; protobuf-style rules |
| Revocation | Tombstones; configurations revoked by digest |

---

## What the spidey sense is actually saying

The specification is precise about what it controls, and quiet about the two things outside the table it depends on:

**what time it is**, and **who holds the pointer**

Rewriting v4 as features, four of the nine "not expressed in the descriptor" entries land there.

---

## The one rule that held up everywhere

**Degrade or refuse. Never be wrong.**

- Unknown and required: refuse, as early as the declarations allow
- Unknown and ignorable: proceed, and say what was lost
- Can't tell: treat it as required
- Found unsafe: tombstone it; refuse or decline, never delete data

Every mechanism in the repository is that rule applied to one more thing.

---

## What we're asking, while v4 is open

1. **A list of discovery roots** that maintenance must spare: the one with a deadline
2. **Prose fixes** that state what Java already does, starting with the retry conditions
3. **Read and write tiers** for formats, as the bar for deprecating ORC and admitting Vortex
4. **The feature list**, with refusal of unknown required features, maintenance that reads it, a tombstone list, and one real user

If only one lands, make it the fourth.

---

<!-- _paginate: false -->

## Where it started

All of this was so that an index could be built on Tuesday instead of Monday.

The index is still the easy part.

<!--
Repository: docs/roadmap.md is the map. docs/contracts/problems.md is the register of everything that can go wrong. docs/contracts/v4-as-features.md is the spec rewritten as if features existed, which is the funniest document in the repository and also the most useful.
-->
