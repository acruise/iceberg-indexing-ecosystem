---
marp: true
theme: default
paginate: true
size: 16:9
title: Indexes for Iceberg, Built When They Pay
author: Alex Cruise
style: |
  section { font-size: 26px; padding: 56px 72px; }
  section.title { justify-content: center; }
  section.title h1 { font-size: 52px; }
  section.part { justify-content: center; }
  section.part h1 { font-size: 48px; }
  h1 { font-size: 40px; }
  h2 { font-size: 32px; }
  table { font-size: 20px; }
  pre, code { font-size: 18px; }
  .small { font-size: 20px; color: #555; }
  .columns { display: grid; grid-template-columns: 1fr 1fr; gap: 40px; }
---

<!-- _class: title -->
<!-- _paginate: false -->

# Indexes for Iceberg, Built When They Pay

An open, compaction-aware index ecosystem, and a study of when to build

Alex Cruise · October 2026

<!--
About fifteen minutes in two parts: first what Iceberg would need in order to host an ecosystem of per-file indexes, then what a simulator says about when such indexes are worth building. The parts are deliberately separate, because the case for the first does not depend on the answer to the second.
-->

---

# The tension

- A lake is built for write throughput, so nothing heavy happens on the write path
- Readers get file-level min/max statistics for free, and little else
- Min/max prunes well on sorted or time-correlated columns and barely at all on ids, keys or free text
- Parquet's Bloom filters help, but the writer chooses them, at write time, and a reader can only consult them after opening the file

<!--
Every lake format records min/max per column per file. For a column that correlates with write time that is a decent index; for an order id, a session id or a SKU it spans nearly the whole domain in every file and prunes almost nothing. Parquet does have Bloom filters per column chunk, but the writer has to decide to build them, on which columns, before any query has been seen, and a reader needs the footer before it can find them.
-->

---

# Two questions, kept apart

1. **Can Iceberg host an open ecosystem of per-file indexes?** Index types anyone can define and ship, discovered at run time, carried through compaction where possible, and harmless to engines that don't understand them.

2. **When is an index worth building, and on what?** At write time, inside the compaction that rewrites the file, or later in the background once the workload has been observed.

The first is a format question with a proposal and a prototype. The second is a scheduling question with a simulator, and its answer is mixed. The case for the first does not depend on it.

<!--
I used to present these as one argument, deferred indexing, with the format work as its prerequisite. That undersells the format work. An ecosystem of index types is valuable whether indexes end up built at write time, at compaction, or later; and deferral, as the second half will show, does not always help.
-->

---

<!-- _class: part -->

# Part 1: an index ecosystem for Iceberg

---

# What Iceberg is missing

- **Puffin** holds blobs as a type string plus bytes, with two standard types and no registry or discovery
- **Statistics files** are bound to a snapshot; a per-file index must outlive many snapshots
- **Deletion vectors** already bind a blob to one data file, but only for that one type
- **Compaction** has no defined obligations toward out-of-band metadata, so the snapshot after a rewrite carries no statistics at all

<div class="small">

Checked against the Iceberg 1.12.0 sources and the published Puffin and table specifications.

</div>

<!--
None of this is an oversight in any implementation. The specification never had to say what becomes of an index when its data file is compacted, because nothing expected indexes to outlive the snapshot they were built for.
-->

---

# A proposal for the Iceberg community

1. **Namespaced blob type identifiers** (`com.example.ngram-bloom-v1`) with a light two-tier registry
2. **Descriptors**: machine-readable scope, compaction mode, delete semantics, configuration
3. **Data-file-scoped blobs**, generalizing the deletion-vector binding, located through an *index manifest* so adding an index needs no new snapshot
4. **Compaction semantics** per type: `drop`, `rebuild`, `merge`, `retain`
5. **Build configurations**, resolvable in-band or from an external store
6. A **Java SPI** discovered through `ServiceLoader`

One invariant shapes everything: a blob is valid only while its data file is live, so an implementation that ignores all of this **loses coverage, never correctness**. And the proposal is **neutral about when** indexes are built.

<!--
That invariant is what makes incremental adoption plausible without a format version change. An old reader ignores unknown blob types and index manifests; an old compactor drops coverage for its outputs; neither can produce a wrong answer. Neutrality about timing is deliberate: building with the data, in the rewrite, or later are all first-class.
-->

---

# Build configurations

A type identifier fixes the payload *format*, not how a payload was *built*.

- An n-gram filter built **case-sensitive** and probed by a reader that **case-folds** its literal excludes files containing the row being sought
- Filters with different seeds or sizes cannot be merged; normalization data even depends on the **Unicode version**
- Operators need to find, and **revoke**, every blob built under a configuration later found to be wrong

So each blob can record `config-digest` (SHA-256 of the RFC 8785 canonical JSON), resolved from a `build-config-v1` blob in the same Puffin file or from a store declared in table properties. A reader that cannot resolve a configuration its type needs plans without the blob.

<!--
The n-gram filter is in the proposal because it needs configuration in every role: to evaluate, to compose and to audit. One subtle case the prototype handles: a stored "cafe" plus a combining accent normalizes to "café", whose grams lack "afe", so probing every gram of the literal "cafe" would wrongly exclude the file; raw-value probes therefore trim literals to normalization boundaries.
-->

---

# Prototype

<div class="columns">
<div>

**`com.cloudera.iceberg.proposed.puffin`** (Java)
the proposed SPI: blob types, descriptors, build configurations, stores, resolver, revocation, and a read path that reports every blob it declines to use, and why

**`com.cloudera.acruise.progidx`**
`ngram` (Java, ICU4J), `f16presence` (Scala), `sim` (Scala)

</div>
<div>

**Tested** against the unmodified Iceberg 1.12.0 `PuffinWriter` and `PuffinReader`

- RFC 8785 vectors plus ~4,300 numbers serialized by a JavaScript engine
- XXH64 reference vectors
- no false negatives for raw predicates across every normalization

**Not yet**: the index manifest, a real rewrite path that applies merges or rebuilds

</div>
</div>

<!--
Upstreaming the SPI would amount to replacing the com.cloudera.iceberg.proposed prefix with org.apache.iceberg. The two example blob types implement the same Java interface, one from Java and one from Scala.
-->

---

# Who tells anyone what queries filter on?

Iceberg's `ScanReport` records the pushed-down filter (literals hashed), the projected columns, and files scanned and skipped. Where it goes:

| Engine | REST catalog | Hive metastore |
|---|---|---|
| Spark | sent to the catalog | logged; redirectable via `metrics-reporter-impl` |
| Hive | not checked | logged; the configured reporter is lost when planning on a deserialized table |
| Impala | not checked | logged; its catalog properties omit `metrics-reporter-impl` |
| Trino | sent to the catalog | debug log only; reporter hard-wired |

Three small upstream changes would fix the metastore column. Until then, engine query profiles are the practical source.

<!--
Most of our customers run a Hive metastore, where the report mostly ends up in logs. The fixes are small and independent of the proposal: SerializableTable should keep its table's reporter, Impala should accept metrics-reporter-impl, and Trino should make its reporter configurable. Even then the report only contains predicates the engine pushed into Iceberg, so substring patterns and predicates on variant fields are missing; Impala and Hive query profiles fill that gap.
-->

---

<!-- _class: part -->

# Part 2: when is an index worth building?

---

# The simulator

One table over three days: ingest, tiered compaction with a settle step per hourly partition, retention, and a workload with scheduled shifts. Five seeds per configuration.

- **Costs** are requests plus bytes: a footer per file opened, a predicate-column chunk per row group, other columns only where a row group matches
- **Schedulers** vary two things separately: *how they value* an index (most-queried columns, or expected savings over the file's predicted life) and *when they may build* (with ingest, with compaction, in the background)
- **Workloads** include drift, session and tenant lookups, CDC merges, erasure batches and streaming micro-batches; identifiers turn over with time, as real keys do

<!--
Two details matter for what follows. Index kinds include a Bloom filter sized per file, which compaction must rebuild, and one sized from theta sketches for the settled file it will end up in, which merges forward by OR. And demand is learned per column and partition age, plus a rate of whole-table queries, so that rare scans of the whole table count on every partition.
-->

---

# Sizing for the settled file is what makes deferral competitive

Query cost as a share of min/max pruning alone, mean of five seeds (spread under ±0.4):

| Scenario | build at write | background, per-file Bloom | background, theta-sized Bloom |
|---|---:|---:|---:|
| steady | 89.6% | 91.3% | **89.1%** |
| drift | 90.1% | 90.9% | **88.5%** |
| sessions | 90.4% | 91.7% | **87.8%** |
| streaming | 90.8% | 91.0% | **89.7%** |

- Per-file filters die at every compaction, so deferring them wastes the work
- Theta-sized filters merge forward; even building at write sheds over 90% of its compaction rebuild work

<!--
The realistic cost model shrinks every gain: the best schedulers land at 84 to 88 percent of baseline, because the files that do match dominate the bill. Within that, the ordering is stable across seeds. Background scheduling only wins once its indexes survive compaction.
-->

---

# Rare, table-wide queries

| Share of prunable rows pruned | build at write | background (survival) |
|---|---:|---:|
| CDC merges, 50-key `IN`, 0.1% filters | 0% | **95%** |
| Erasure batches, 5 keys, 0.1% filters | 0% | **97%** |

- Write-time indexing follows the most-queried columns, and never indexes these keys
- The valuation catches them only once whole-table queries count as demand on every partition age
- **`IN` compounds false positives:** 1% filters let about 40% of prunable rows through a 50-key merge; 0.1% filters, about 4%

<!--
These were the scenarios where the first version of the valuation failed outright: a few queries a day, each reading the whole table, registered nowhere. In metastore deployments, a scan report with no partition predicate is exactly the signal that would tell a scheduler about them, which is one more reason to fix the telemetry plumbing.
-->

---

# Drift

| Pruned share in the first six hours | build at write | background | build with compaction (unbudgeted) |
|---|---:|---:|---:|
| `order_id` becomes hot | 57% | **85%** | 99% |
| `status`: equality → range | **100%** | 0% | 100% |
| `trace_id` forensics on day-old data | 0% | **25%** | 45% |

- Write-time indexing never sees the forensic column: it was dormant when the data was written
- Background indexing cannot help the range shift: the only prunable files live three minutes, less than it takes a background index to become visible

<!--
The range shift is the honest counterexample to deferral. Ranges of eleven values over a thousand only miss small, young files, and those are merged before a background index on them could be committed. Only indexes committed with the data reach them, which is why write-time and compaction-time building get 100 percent. The stakes are small, though: about forty prunable rows per query.
-->

---

RESULTS_TIMING

---

# Streaming writes and small files

- Ten times the commits for the same rows: 53 data commits per minute instead of 5, write amplification 4.9 instead of 3.8
- **Indexing need not make it worse:** blobs bind to data files and background builds batch into one Puffin commit, so no snapshots are added to the write path
- **Mergeable, theta-sized indexes ride the compactions streaming forces anyway**
- **Read-aware compaction** (skip intermediate tiers nobody reads) is roughly a wash: merging tiny files pays back after a handful of queries

<!--
With readers mostly on older data, read-aware compaction cuts rows rewritten by a quarter, but the few queries that still read recent data pay more requests, and by this model's weights the two roughly cancel. The commit count is dominated by the micro-batches themselves; nothing in indexing moves it.
-->

---

# Open questions

- Index manifest as a new file type, or a new content type in the v4 manifest work?
- Should a configuration record *why* a blob was built, or only *how*?
- How does an external configuration store learn that a table no longer needs a document?
- Partition-scoped blobs, whose compaction semantics are much harder

**Next**: the discussion draft to the Iceberg dev list, the three telemetry fixes upstream, and a scheduler fed by engine query profiles.

<!--
The proposal is in docs/proposals/puffin-blob-types-and-compaction.md. I would rather hear the objections now than on the dev list.
-->
