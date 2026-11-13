# iceberg-indexing-ecosystem

This repository holds proposals for Apache Iceberg, a Java prototype of the first of them, and a set of notes on the contracts between Iceberg, its file formats, its storage and its writers that the proposals kept running into. A simulation study that prompted the work is kept apart, under [Experiments](#experiments), and nothing in the proposals depends on it.

The proposal asks how a table format could host an open ecosystem of per-file indexes in Puffin files: index types that anyone can define and ship under a namespace they own, that engines discover at run time, that record the configuration they were built under, that survive compaction where their structure allows it, and that can never cause a wrong answer when an engine does not understand them.

The proposal's case does not depend on when indexes are built, but the late-built index is the case that exposes every gap the proposal fills, since it needs a type nobody has standardized, a binding to a file rather than a snapshot, a record of how it was built and a rule for compaction, and it is the running example throughout.

## Map of the repository

The [roadmap](docs/roadmap.md) is the top-down view: it arranges every proposal below by the question it answers, the problems it addresses and the broader direction it opens, and says what to ask first.

The repository holds two lines of argument and the code behind one of them. The Puffin proposal and its companions concern index types. The note on contracts asks the same questions of data file formats, storage and commits, and it grew out of the proposal when the same patterns kept turning up elsewhere. The two refer to each other wherever they would share metadata, and neither depends on the other being accepted.

### The Puffin proposal and its companions (`docs/proposals/`)

| Document | Why it is here | Relation to the rest |
|---|---|---|
| [`puffin-blob-types-and-compaction.md`](docs/proposals/puffin-blob-types-and-compaction.md) | The full proposal: namespaced blob types, descriptors, data-file-scoped blobs, build configurations and compaction rules. It is the canonical text. | Everything else in this directory is cut from it, condenses it or supports it. The Google Doc copy is synced from it. |
| [`puffin-blob-types-short.md`](docs/proposals/puffin-blob-types-short.md) | The same design in about a fifth of the length, opening with the three questions we would like answered. Written for readers who will not read the full draft. | Keeps the phases of `phasing.md`, and links into the contracts note wherever the two want the same metadata. |
| [`puffin-blob-types-background.md`](docs/proposals/puffin-blob-types-background.md) | A plain-language primer on Puffin and indexing for readers new to either. | A companion to the full proposal, with a map of its sections. |
| [`phasing.md`](docs/proposals/phasing.md) | How the proposal can be adopted in four phases, each able to stand alone, and which phases would need a format version. | Defines the phases the short version and `phase-1-open-blob-types.md` follow. |
| [`phase-1-open-blob-types.md`](docs/proposals/phase-1-open-blob-types.md) | Phase 1 written as a standalone proposal: identifiers, the registry and the core descriptor. | The first document cut from the full proposal under `phasing.md`. |
| [`index-reachability.md`](docs/proposals/index-reachability.md) | Evidence that table maintenance deletes or leaks index files it does not know about, and how other formats have handled the same problem. | Applies to the community's secondary index specification as much as to this proposal; motivates the next document. |
| [`decoupled-index-metadata.md`](docs/proposals/decoupled-index-metadata.md) | A metadata mechanism, a list of discovery roots plus obligations on maintenance, that would serve both index designs. | The remedy for `index-reachability.md`, and one of the version 4 asks in the contracts note. |
| [`prior-art.md`](docs/proposals/prior-art.md) | What Delta, Hudi, Paimon, Lance, DuckLake and Hive already do for each benefit the proposal offers, and which indexes other systems have withdrawn. | Paired with the contracts survey of the same name, which covers problems instead of benefits. |

### Contracts between Iceberg, its formats, its storage and its writers (`docs/contracts/`)

| Document | Why it is here | Relation to the rest |
|---|---|---|
| [`README.md`](docs/contracts/README.md) | A deliberately broad working note on what Iceberg's contracts with data file formats, storage and writers leave unsaid. It defines the vocabulary the other documents use: outcomes, reliance, sidecars, bindings. | Its section on alignment compares it with the Puffin proposal, which faces the same questions for blobs. |
| [`problems.md`](docs/contracts/problems.md) | A register of problems, numbered E1 onward for those that exist today and V1 onward for those predicted under version 4 proposals, kept apart from their solutions. | Cites the note and the Puffin proposal as sources; every solution document cites it by identifier. |
| [`prose-fixes.md`](docs/contracts/prose-fixes.md) | Solutions that need only a sentence or two in an existing specification. | The cheapest answers to problems in the register. |
| [`v4-asks.md`](docs/contracts/v4-asks.md) | Three solutions that need a new field and so would have to ride version 4: a content hash, the read base of a snapshot, and a declaration of what a snapshot depends on. | The third ask carries the discovery roots of `decoupled-index-metadata.md`. |
| [`sidecars.md`](docs/contracts/sidecars.md) | A small, mostly editorial proposal that names the class of files Iceberg already tracks beside the files they describe (statistics files, deletion vectors, column files), with a definition, a specification template and two constraints that every existing kind already obeys. | Extracted from Appendix D of the note; the Puffin proposal's blob types are one instance of its template. |
| [`sidecars-short.md`](docs/contracts/sidecars-short.md) | The sidecar proposal condensed for circulation: the asks, the definition, the two constraints, the rules that keep the abstraction small and the column-file example. | Cut from `sidecars.md`, which remains the reference. |
| [`catalogs.md`](docs/contracts/catalogs.md) | A record that version 4 makes a catalog necessary for commits, and for some tables for reads, with the Iceberg REST Catalog the de facto requirement for anything beyond a commit; with the implications, a comparison with other formats and the questions worth asking. | Recasts V10 as decided rather than open, and asks each proposal here to say what it assumes of the catalog. |
| [`feature-versioning.md`](docs/contracts/feature-versioning.md) | An argument that Iceberg should version by declared feature instead of by one table-wide integer, with the condition under which an implementation lacking a feature may proceed. | Generalizes the Puffin proposal's identifiers and descriptors to features of every kind, and answers its open question on minor revisions. |
| [`v4-as-features.md`](docs/contracts/v4-as-features.md) | The version 4 table specification rewritten as the index feature versioning says it could become: a small core, named versions as sets of features, and what each feature's descriptor cannot express. Illustrative. | Found that version ranges must be per role, which the feature-versioning note now says. |
| [`prior-art.md`](docs/contracts/prior-art.md) | What other table formats do about each problem in the register. | Organized by the register's identifiers; paired with the proposals' survey. |

### Code

The prototype is described in [Contents](#contents) below. The simulator is not part of the proposals and is described under [Experiments](#experiments).

| Module | Why it is here | Relation to the rest |
|---|---|---|
| `puffin-spi` | The Java prototype of the interfaces the proposal offers to Iceberg, packaged so that moving it upstream is a rename. | Implements the full proposal's identifiers, descriptors, data-file binding and build configurations; its Section 9.5 says what is not yet prototyped. The blob types below build on it. |
| `blob-types` | Two reference blob types: the n-gram Bloom filter that is the proposal's worked example, and a mergeable half-precision presence filter. | Exercise `puffin-spi` from Java and from Scala. |
| `simulator` | Measures when building indexes late beats building them with the data, under drifting workloads. | Not part of the proposals; see [Experiments](#experiments). |

### Tooling

| Path | Why it is here |
|---|---|
| [`tools/gdoc_sync.py`](tools/gdoc_sync.py) | Brings the Google Doc copy of the full proposal up to date by editing only the text that changed, so that review comments survive each sync. |
| [`.claude/skills/gdoc-sync/`](.claude/skills/gdoc-sync/SKILL.md) | The procedure for running that sync, written for a coding assistant. |

## Motivation

A data lake has to favor write throughput, which rules out building heavy indexes synchronously with ingest, and yet readers benefit from indexes finer-grained than the file-level min/max statistics that every lake format records for free. Iceberg can already store index-like payloads in [Puffin](https://iceberg.apache.org/puffin-spec/) files, but today it:

* treats each blob as an opaque type string and bytes
* binds out-of-band metadata to snapshots rather than data files
* does not say what compaction should do with it.

Parquet's own Bloom filters are fixed by the writer at write time and can only be consulted after a file's footer has been read. An index ecosystem open to new types and bound to data files, with compaction aware of it, would let an engine consult a column's index at planning time, and would let that index be added later, chosen to suit the predicates the column actually receives.

Once indexes are bound to data files, the timing of their construction becomes a policy choice rather than a constraint. An index can be built with the data, inside the compaction that rewrites it, or later in the background by a process that has watched the workload, which is the strategy we call progressive indexing.

Building late lets the choice of index follow a workload that drifts (a new column becomes hot or a column switches from equality to range predicates, and sometimes an incident sends lookups against old data on a column nobody had indexed) and avoids paying for files that compaction is about to discard. Building with the data reaches files that live too briefly for a background builder to help.

## The proposal

[`docs/proposals/puffin-blob-types-and-compaction.md`](docs/proposals/puffin-blob-types-and-compaction.md) is a discussion draft for the Iceberg community.

On the specification side it proposes:

* a namespace and a two-tier registry for Puffin blob type identifiers, with the major version carried in the identifier
* a machine-readable descriptor for each type, from which engines and maintenance tools learn what a blob is bound to, how it behaves under compaction and row-level deletes, and what a reader may conclude from it
* a general notion of data-file-scoped blobs, generalizing the convention deletion vectors already use, under the rule that such a blob must be a pure function of its data file and its build configuration and, unless its descriptor says otherwise, may only exclude
* references from each blob to the configuration under which it was built, identified by the digest of the configuration's canonical JSON prefixed by the digest of the type's configuration schema, and resolvable from in-band blobs or from shared stores
* a reserved property through which a blob can describe an expression over its fields rather than the fields themselves
* candidate specification text for how compaction treats such blobs.

On the implementation side it proposes a Java service-provider interface, of which `com.cloudera.iceberg.proposed.puffin` below is the prototype.

A related-work section compares the design with the community's secondary index specification (apache/iceberg [PR 16961](https://github.com/apache/iceberg/pull/16961)), which pins table-level indexes to a snapshot where this proposal binds per-file structures to the files they describe, and proposes hosting data-file-scoped blobs as an index type under that specification's metadata model.

A second related-work section surveys the nearest precedents outside Iceberg, among them:

* Paimon's file index
* Pinot's index SPI
* Lucene codecs
* ClickHouse skipping indexes
* Hudi's expression indexes
* PostgreSQL BRIN
* Lance's handling of indexes under compaction.

The draft also records three small, independent changes that would let Spark, Hive, Impala and Trino report scan telemetry from metastore-backed tables.

A worked example carries the mechanisms through the document: the character n-gram Bloom filter of Section 7.6, an exclusion-only type whose build configuration governs how a reader must evaluate it.

The Markdown file in this repository is the canonical text of the proposal. A [Google Doc copy](https://docs.google.com/document/d/18qlGNPfPy5iT_aRgxLVCqh5W1LuINR7x8Eu2zC6HMoU) exists for review comments, and `tools/gdoc_sync.py` brings it up to date by editing only the words that changed, so that comments anchored to unchanged text survive each sync.

## Contents

The code falls into four packages in three sbt modules. Everything proposed for inclusion in Iceberg is written in Java under `com.cloudera.iceberg.proposed`, following Iceberg's conventions, while this project's own code, the two reference blob types and the simulator, lives under `com.cloudera.acruise.progidx`. The first two modules are published as libraries under the organization `com.cloudera.acruise` so that implementations elsewhere can build against them; the simulator is not.

| Module | Package | Language | Published as |
|---|---|---|---|
| `puffin-spi` | `com.cloudera.iceberg.proposed.puffin` | Java | `iceberg-indexing-puffin-spi` |
| `blob-types` | `com.cloudera.acruise.progidx.ngram` | Java | `iceberg-indexing-blob-types` |
| `blob-types` | `com.cloudera.acruise.progidx.f16presence` | Scala 2.13 | `iceberg-indexing-blob-types` |
| `simulator` | `com.cloudera.acruise.progidx.sim` | Scala | not published |

### `com.cloudera.iceberg.proposed.puffin`

The Java prototype of the interfaces the proposal offers to Iceberg, named so that moving it upstream amounts to replacing the `com.cloudera.iceberg.proposed` prefix with `org.apache.iceberg`. Its dependencies are `iceberg-core` 1.12.0 and the Jackson it brings.

* `PuffinBlobType` encodes and decodes one blob type and exposes its descriptor. Implementations are discovered through `java.util.ServiceLoader` (see `META-INF/services/com.cloudera.iceberg.proposed.puffin.PuffinBlobType` under each module's `src/main/resources`), and `MergeablePuffinBlobType` adds composition for compaction.
* `BuildConfig` identifies a build configuration by the SHA-256 digest of its [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) canonical JSON, prefixed by the digest of the blob type's configuration schema so that the same document means something different under each type (`ConfigDocument` is the schema-less document, `SchemaDigest` the prefix).
* `BuildConfigResolver` resolves the reserved properties through which blobs refer to a build configuration, looking in `build-config-v1` blobs in the same Puffin file or in stores declared in table properties, verifying digests and honoring revocation.
* `PuffinBlobTypes.read` decodes the blobs of a Puffin file that a reader can safely use and reports the rest with reasons.
* `DataFileIndexBlobs` writes blobs bound to a single data file, following the convention Iceberg already uses for deletion vectors (`snapshot-id` and `sequence-number` of -1, with the data file named in the `referenced-data-file` property).

### `com.cloudera.acruise.progidx.ngram`

`NgramBloom` is the character n-gram Bloom filter used as the worked example of build configurations in the proposal (Section 7.6). It normalizes values with [ICU4J](https://unicode-org.github.io/icu/userguide/icu4j/) (none, NFC, NFKC, or NFKC_Casefold for caseless matching) and frames them with start and end markers before hashing overlapping grams with [XXH64](https://xxhash.com/).

It answers equality, prefix and substring predicates either about the stored values or about their normalized forms. For the former it trims pattern literals to normalization boundaries, since a combining mark in a stored value can compose across the edge of a literal. Its configuration is recorded with every blob, and a filter whose normalization depends on Unicode data other than the reader's is never used to exclude a file.

### `com.cloudera.acruise.progidx.f16presence`

`F16Presence` is a mergeable presence filter that records which half-precision (f16) codes occur in a column. Values are mapped through an order-preserving quantizer, so the filter answers equality and range predicates without false negatives; it occupies at most 8 KiB, and two filters merge by OR. Its precision is that of f16 (roughly 0.1% relative, with every magnitude above 65504 collapsing to infinity), so columns of large-magnitude values need a different monotone quantizer.

### `com.cloudera.acruise.progidx.sim`

This package holds the simulator, described under [Experiments](#experiments).

## Building and running

The build uses sbt 2.0 and Java 21; `.sdkmanrc` pins the JDK for SDKMAN users. Scala suites use MUnit and Java suites use JUnit with AssertJ, and `sbt testFull` runs both; tests run in a forked JVM with a 2 GB heap.

```
sbt testFull                                  # every suite (sbt 2's `test` runs only suites affected by changes)
sbt publishLocal                              # publish puffin-spi and blob-types to the local Ivy repository
```

## Status

This is research code. Apart from the Puffin round-trip tests, the code has not been run against a real Iceberg table.

## Experiments

This section is not part of the proposals. The study asks when and on which files and columns such indexes are worth building, and in particular whether building them late, in the background once a process has watched the workload, beats building them with the data. We call the late strategy *progressive indexing*, and a simulator measures the trade-off.

### The simulator

The simulator models one table over three days: continuous ingest, size-tiered compaction with a final settle step per hourly partition, retention, and a query workload with optional scheduled shifts. Everything is a pure function of a seed, so every scheduler sees the same data and the same queries.

**Columns.** Besides Gaussian measurements, uniform integers and values that trend with write time, the table has active-set columns: identifiers drawn from a population that turns over with time but cannot be derived from it, such as sessions, tenants, open orders and customers. Min/max statistics are useless on them, yet a file covering a short span holds only the keys active during it.

Each active-set column has a starting population, an arrival rate, lognormal lifetimes and optionally skewed activity, and lookups ask for keys that really were written during the queried span.

**Index kinds.** The simulator models three kinds of index and does not yet model a locating kind.

* A Bloom filter (`bloom`) serves equality and `IN` predicates and is sized for its own file's distinct count, so compaction must rebuild it.
* A theta-sized Bloom filter (`sbloom`) is sized for the distinct count of the settled file it will eventually be merged into, as theta sketches of recently settled files forecast it, so that it merges exactly by OR until the merged false-positive rate would exceed its limit. The target size is a parameter of the filter's build configuration rather than something read from the file, which keeps the filter a function of its file and its configuration, as the proposal's derivation rule (Section 6.2) requires.
* The f16 presence filter serves equality and range predicates and is mergeable.

**Costs.** Costs are in units of transferring one row unit (a thousand rows) of one column, and every ranged read against the object store costs `requestCost` such units on top of its bytes.

* A file that survives planning costs a footer request, then a chunk of the predicate column per row group, then chunks of the other projected columns only for row groups that hold a match (late materialization, as in Trino and Impala; it can be turned off).
* An index probe costs its bytes and a share of one request, since one ranged read of a Puffin file serves every blob a query needs from it.
* Indexes built in the background become visible when a batch of them is committed to a Puffin file; indexes built with ingest or compaction are committed with the data.

**Schedulers.** Schedulers differ along two dimensions that the simulator keeps separate: how they value an index, and when they may build one.

| Scheduler | Valuation | When it builds |
|---|---|---|
| `none` | – | never |
| `eager-at-write` | the currently most queried columns | with every ingest and compaction commit |
| `generation>=1`, `finalized-only` | the currently most queried columns | in the background, once files reach a generation or settle |
| `survival-weighted` | expected savings over the file's predicted life, net of build and commit cost | in the background |
| `survival+compaction` | the same | in the background and with compaction commits, outside any budget |
| `valued@…/priced` | the same | on the paths named (`write`, `cmp`, `bg`), all drawing on one budget |
| `hybrid` | f16 on every queried column, then survival-weighted | f16 with ingest, the rest in the background |

The valuation learns demand per column and partition age, plus a per-column rate of whole-table queries (those with no partition predicate, which a scan report would show), so that rare but table-wide queries register on partitions of every age. It credits a mergeable index on a short-lived file with the value it carries into the compaction output.

In the priced variants, builds made with a commit draw on the same budget as background builds and happen only if their value per unit cost beats a *shadow price*: the smoothed value per cost of the marginal background build, the first one the budget could not fund. That puts the different timings on equal terms.

**Compaction policies.** Besides plain size tiering, a read-aware policy merges a group of small files only when the requests its merged file would save the queries expected to reach the partition before it settles exceed the cost of the rewrite, including the requests that compaction itself makes.

**Scenarios.**

* *steady* rotates column popularity daily.
* *drift* applies three scheduled shifts: `order_id` becomes a hot equality column, `status` switches from equality to range predicates, and a six-hour burst of lookups on `trace_id` targets data one to two days old.
* *sessions* sends a fifth of queries to `session_id` lookups and a quarter to `tenant_id` lookups.
* *cdc* merges change data every five minutes, each merge looking up the 50 `order_key`s being updated across the whole table as one `IN` predicate.
* *erasure* processes right-to-erasure requests four times a day, each batch looking for every row of five `customer_id`s across the whole table.
* *streaming* writes the same rows as ten times as many micro-batches, and *streaming-archive* does the same with readers that mostly look at older data.

### Findings so far

These come from five-seed sweeps under the cost model above, with a background budget of 4000 per tick. Spreads across seeds are at most half a point of query cost, so the comparisons below are not artifacts of one seed; the absolute numbers matter less than the orderings, since the model is deliberately coarse.

- **Realistic read costs shrink the stakes.** The best schedulers bring query cost to 84–88% of what min/max pruning alone achieves. Once a non-matching file costs only its footer and one column chunk, the files that do match, which an index cannot skip, dominate the bill. Indexes that rarely exclude anything now cost more than they save, because every probe costs part of a request.
- **Sizing for the settled file is what makes deferred indexing competitive.** With per-file Bloom filters, background scheduling loses to indexing everything at write time. With theta-sized filters, which merge forward instead of being rebuilt at every compaction, it beats write-time indexing in every scenario (for example 86.4% against 91.1% of baseline cost on CDC merges, 88.5% against 90.1% under drift), and write-time indexing itself sheds over 90% of its compaction rebuild work.
- **Demand has to be weighted by cost, not by query count.** CDC merges and erasure batches are rare but each reads the whole table. Write-time indexing, which follows the most queried columns, never indexes their keys; the survival valuation does once whole-table queries are learned as demand on every partition age, and then prunes 95–97% of their prunable rows.
- **Batch lookups compound false positives.** A 50-key `IN` predicate defeats 1% Bloom filters (about 40% of prunable rows get through as false positives); 0.1% filters bring that to about 4%.
- **Building with the data only pays where background builds cannot reach.** Unbudgeted compaction-time building looks best, but only because it spends one and a half to two times the budget. Sharing the budget first-come, first-served is worse than background-only scheduling in every scenario, since compaction spends it on files that die young. Whether pricing the budget fixes this is the question of the current sweep.
- **Workload drift is the strongest argument for deciding late.** When an incident sends lookups to day-old data on a column nobody had queried, write-time indexing fails to prune a single row (the decision was taken when the column was dormant), while background scheduling prunes about a quarter of the prunable rows and unbudgeted compaction-time building about half.
- **Read-aware compaction is roughly a wash.** Merging tiny files pays back after a handful of queries, so on tables read while they are written it merges almost as much as plain tiering. With readers mostly on older data it cuts rows rewritten by a quarter but adds requests to the few queries that still read recent data.

### Running the simulator

```
sbt simulator/run                             # every scenario, one seed, full report
sbt "simulator/run --scenarios=cdc,erasure"   # only some scenarios
sbt "simulator/run --kinds=sbloom+f16"        # only some sets of index kinds (labels without spaces)
sbt "simulator/run --schedulers=survival-weighted,eager-at-write"
sbt "simulator/run --seeds=5"                 # every configuration over five seeds: means and standard deviations
sbt "simulator/run 1000 4000"                 # several background budgets
```

Lists of kind sets are separated by commas, not semicolons, which sbt treats as command separators. A full single-seed run takes about three quarters of an hour, and a five-seed sweep of all scenarios a little longer, since seeds run in parallel.

For each configuration the single-seed report gives, per scheduler:

* query cost relative to min/max pruning alone, and requests per query
* build work by path (background, ingest, compaction), and work on indexes retired before they paid for themselves
* Puffin rewrite work, write amplification and data commits per tick
* net benefit.

It classifies the rows that min/max could not prune, although they did not match the predicate, as *pruned* by an index, let through as a *false positive*, a *mismatch* (indexes on the column, none able to evaluate the predicate's shape) or *missing* (no index on the column). For each scheduled shift it reports the pruned share in its first six hours and over the whole shift.

The simulator's cost model is deliberately coarse (one tick stands for roughly a minute and rows are counted in thousands), because its purpose is to compare strategies under the same workload rather than to predict absolute costs.
