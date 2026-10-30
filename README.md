# iceberg-progressive-indexing

This repository holds a proposal for Apache Iceberg, a Java prototype of it, and the study that motivated it. The proposal asks how a table format could host an open ecosystem of per-file indexes in Puffin files: index types that anyone can define and ship under a namespace they own, that engines discover at run time, that record the configuration they were built under, that survive compaction where their structure allows it, and that can never cause a wrong answer when an engine does not understand them. The study, which gives the repository its name, asks when and on which files and columns such indexes are worth building, and in particular whether building them late, in the background, after a process has watched the workload, beats building them with the data; we call the late strategy *progressive indexing*, and a simulator measures the trade-off. The two are kept apart on purpose. The proposal's case does not depend on any particular answer to the timing question, but the late-built index is the case that exposes every gap the proposal fills, since it needs a type nobody has standardized, a binding to a file rather than a snapshot, a record of how it was built and a rule for compaction, and it is the running example throughout.

## Motivation

A data lake has to favor write throughput, which rules out building heavy indexes synchronously with ingest, and yet readers benefit from indexes finer-grained than the file-level min/max statistics that every lake format records for free. Iceberg can already store index-like payloads in [Puffin](https://iceberg.apache.org/puffin-spec/) files, but treats each blob as an opaque type string and bytes, binds out-of-band metadata to snapshots rather than data files, and says nothing about what compaction should do with it. Parquet's own Bloom filters are fixed by the writer at write time and can only be consulted after a file's footer has been read. An open, file-bound, compaction-aware index ecosystem would let the index on a column be added later, chosen to suit the predicates that column actually receives, and consulted at planning time.

Once indexes are bound to data files, the timing of their construction becomes a policy choice rather than a constraint. An index can be built with the data, inside the compaction that rewrites it, or later in the background by a process that has watched the workload, which we call *progressive indexing*. Building late lets the choice of index follow a workload that drifts (a new column becomes hot, a column switches from equality to range predicates, an incident sends lookups against old data on a column nobody had indexed) and avoids paying for files that compaction is about to discard. Building with the data reaches files that live too briefly for a background builder to help. The simulator measures that trade-off rather than assuming its outcome.

## The proposal

[`docs/proposals/puffin-blob-types-and-compaction.md`](docs/proposals/puffin-blob-types-and-compaction.md) is a discussion draft for the Iceberg community. On the specification side it proposes a namespace and a two-tier registry for Puffin blob type identifiers, with the major version carried in the identifier; a machine-readable descriptor for each type, from which engines and maintenance tools learn what a blob is bound to, how it behaves under compaction and row-level deletes, and what a reader may conclude from it; and a general notion of data-file-scoped blobs, generalizing the convention deletion vectors already use, under the rule that such a blob must be a pure function of its data file and its build configuration and, unless its descriptor says otherwise, may only exclude. It then adds references from each blob to the configuration under which it was built, identified by the digest of the configuration's canonical JSON and resolvable from in-band blobs or from shared stores, a reserved property through which a blob can describe an expression over its fields rather than the fields themselves, and candidate specification text for how compaction treats such blobs. On the implementation side it proposes a Java service-provider interface, of which `com.cloudera.iceberg.proposed.puffin` below is the prototype. A related-work section compares the design with the community's secondary index specification (apache/iceberg [PR 16961](https://github.com/apache/iceberg/pull/16961)), which pins table-level indexes to a snapshot where this proposal binds per-file structures to the files they describe, and proposes hosting data-file-scoped blobs as an index type under that specification's metadata model. The draft also records three small, independent changes that would let Spark, Hive, Impala and Trino report scan telemetry from metastore-backed tables.

The proposal is being edited in parallel in a Google Doc. [`puffin-blob-types-and-compaction.deltas.md`](docs/proposals/puffin-blob-types-and-compaction.deltas.md) records the differences between the two copies until they are reconverged.

## Contents

The code falls into four packages. Everything proposed for inclusion in Iceberg is written in Java under `com.cloudera.iceberg.proposed`, following Iceberg's conventions. This project's own code lives under `com.cloudera.acruise.progidx`; the n-gram filter is written in Java, and the f16 filter and the simulator in Scala 2.13.

`com.cloudera.iceberg.proposed.puffin` is the Java prototype of the interfaces the proposal offers to Iceberg, named so that moving it upstream amounts to replacing the `com.cloudera.iceberg.proposed` prefix with `org.apache.iceberg`. `PuffinBlobType` encodes and decodes one blob type and exposes its descriptor; implementations are discovered through `java.util.ServiceLoader` (see `src/main/resources/META-INF/services/com.cloudera.iceberg.proposed.puffin.PuffinBlobType`), and `MergeablePuffinBlobType` adds composition for compaction. `BuildConfig` identifies a build configuration by the SHA-256 digest of its [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) canonical JSON; blobs refer to it through reserved properties, and `BuildConfigResolver` resolves those references from `build-config-v1` blobs in the same Puffin file or from stores declared in table properties, verifying digests and honoring revocation. `PuffinBlobTypes.read` decodes the blobs of a Puffin file that a reader can safely use and reports the rest with reasons, and `DataFileIndexBlobs` writes blobs bound to a single data file, following the convention Iceberg already uses for deletion vectors (`snapshot-id` and `sequence-number` of -1, with the data file named in the `referenced-data-file` property). Its dependencies are `iceberg-core` 1.12.0 and the Jackson it brings.

`com.cloudera.acruise.progidx.ngram` contains `NgramBloom`, the character n-gram Bloom filter used as the worked example of build configurations in the proposal (Section 7.6). It normalizes values with [ICU4J](https://unicode-org.github.io/icu/userguide/icu4j/) (none, NFC, NFKC, or NFKC_Casefold for caseless matching), frames them with start and end markers, and hashes overlapping grams with [XXH64](https://xxhash.com/). It answers equality, prefix and substring predicates either about the stored values or about their normalized forms; for the former it trims pattern literals to normalization boundaries, since a combining mark in a stored value can compose across the edge of a literal. Its configuration is recorded with every blob, and a filter whose normalization depends on Unicode data other than the reader's is never used to exclude a file.

`com.cloudera.acruise.progidx.f16presence` contains `F16Presence`, a mergeable presence filter that records which half-precision (f16) codes occur in a column. Values are mapped through an order-preserving quantizer, so the filter answers equality and range predicates without false negatives; it occupies at most 8 KiB, and two filters merge by OR. Its precision is that of f16 (roughly 0.1% relative, with every magnitude above 65504 collapsing to infinity), so columns of large-magnitude values need a different monotone quantizer.

`com.cloudera.acruise.progidx.sim` is the simulator, described next.

## The simulator

The simulator models one table over three days: continuous ingest, size-tiered compaction with a final settle step per hourly partition, retention, and a query workload with optional scheduled shifts. Everything is a pure function of a seed, so every scheduler sees the same data and the same queries.

**Columns.** Besides Gaussian measurements, uniform integers and values that trend with write time, the table has active-set columns: identifiers drawn from a population that turns over with time but cannot be derived from it, such as sessions, tenants, open orders and customers. Each has a starting population, an arrival rate, lognormal lifetimes and optionally skewed activity, and lookups ask for keys that really were written during the queried span. Min/max statistics are useless on them, yet a file covering a short span holds only the keys active during it.

**Index kinds.** A Bloom filter (`bloom`) serves equality and `IN` predicates and is sized for its own file's distinct count, so compaction must rebuild it. A theta-sized Bloom filter (`sbloom`) is sized for the distinct count of the settled file it will eventually be merged into, as theta sketches of recently settled files forecast it, so that it merges exactly by OR until the merged false-positive rate would exceed its limit. The target size is a parameter of the filter's build configuration rather than something read from the file, which keeps the filter a function of its file and its configuration, as the proposal's derivation rule (Section 6.2) requires. The f16 presence filter serves equality and range predicates and is mergeable.

**Costs.** Costs are in units of transferring one row unit (a thousand rows) of one column, and every ranged read against the object store costs `requestCost` such units on top of its bytes. A file that survives planning costs a footer request, then a chunk of the predicate column per row group, then chunks of the other projected columns only for row groups that hold a match (late materialization, as in Trino and Impala; it can be turned off). An index probe costs its bytes and a share of one request, since one ranged read of a Puffin file serves every blob a query needs from it. Indexes built in the background become visible when a batch of them is committed to a Puffin file; indexes built with ingest or compaction are committed with the data.

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

The valuation learns demand per column and partition age, plus a per-column rate of whole-table queries (those with no partition predicate, which a scan report would show), so that rare but table-wide queries register on partitions of every age. It credits a mergeable index on a short-lived file with the value it carries into the compaction output. In the priced variants, builds made with a commit draw on the same budget as background builds and happen only if their value per unit cost beats a *shadow price*: the smoothed value per cost of the marginal background build, the first one the budget could not fund. That puts the different timings on equal terms.

**Compaction policies.** Besides plain size tiering, a read-aware policy merges a group of small files only when the requests its merged file would save the queries expected to reach the partition before it settles exceed the cost of the rewrite, including the requests that compaction itself makes.

**Scenarios.** *steady* rotates column popularity daily. *drift* applies three scheduled shifts: `order_id` becomes a hot equality column, `status` switches from equality to range predicates, and a six-hour burst of lookups on `trace_id` targets data one to two days old. *sessions* sends a fifth of queries to `session_id` lookups and a quarter to `tenant_id` lookups. *cdc* merges change data every five minutes, each merge looking up the 50 `order_key`s being updated across the whole table as one `IN` predicate. *erasure* processes right-to-erasure requests four times a day, each batch looking for every row of five `customer_id`s across the whole table. *streaming* writes the same rows as ten times as many micro-batches, and *streaming-archive* does the same with readers that mostly look at older data.

## Findings so far

These come from five-seed sweeps under the cost model above, with a background budget of 4000 per tick. Spreads across seeds are at most half a point of query cost, so the comparisons below are not artifacts of one seed; the absolute numbers matter less than the orderings, since the model is deliberately coarse.

- **Realistic read costs shrink the stakes.** The best schedulers bring query cost to 84–88% of what min/max pruning alone achieves. Once a non-matching file costs only its footer and one column chunk, the files that do match, which no index can skip, dominate the bill. Indexes that rarely exclude anything now cost more than they save, because every probe costs part of a request.
- **Sizing for the settled file is what makes deferred indexing competitive.** With per-file Bloom filters, background scheduling loses to indexing everything at write time. With theta-sized filters, which merge forward instead of being rebuilt at every compaction, it beats write-time indexing in every scenario (for example 86.4% against 91.1% of baseline cost on CDC merges, 88.5% against 90.1% under drift), and write-time indexing itself sheds over 90% of its compaction rebuild work.
- **Demand has to be weighted by cost, not by query count.** CDC merges and erasure batches are rare but each reads the whole table. Write-time indexing, which follows the most queried columns, never indexes their keys; the survival valuation does once whole-table queries are learned as demand on every partition age, and then prunes 95–97% of their prunable rows.
- **Batch lookups compound false positives.** A 50-key `IN` predicate defeats 1% Bloom filters (about 40% of prunable rows get through as false positives); 0.1% filters bring that to about 4%.
- **Building with the data only pays where background builds cannot reach.** Unbudgeted compaction-time building looks best, but only because it spends one and a half to two times the budget. Sharing the budget first-come, first-served is worse than background-only scheduling in every scenario, since compaction spends it on files that die young. Whether pricing the budget fixes this is the question of the current sweep.
- **Workload drift is the strongest argument for deciding late.** When an incident sends lookups to day-old data on a column nobody had queried, write-time indexing prunes nothing (the decision was taken when the column was dormant), background scheduling about a quarter of the prunable rows, and unbudgeted compaction-time building about half.
- **Read-aware compaction is roughly a wash.** Merging tiny files pays back after a handful of queries, so on tables read while they are written it merges almost as much as plain tiering. With readers mostly on older data it cuts rows rewritten by a quarter but adds requests to the few queries that still read recent data.

## Building and running

The build uses sbt 2.0 and Java 21; `.sdkmanrc` pins the JDK for SDKMAN users. Scala suites use MUnit and Java suites use JUnit with AssertJ, and `sbt testFull` runs both; tests run in a forked JVM with a 2 GB heap.

```
sbt testFull                                  # every suite (sbt 2's `test` runs only suites affected by changes)
sbt run                                       # every scenario, one seed, full report
sbt "run --scenarios=cdc,erasure"             # only some scenarios
sbt "run --kinds=sbloom+f16"                  # only some sets of index kinds (labels without spaces)
sbt "run --schedulers=survival-weighted,eager-at-write"
sbt "run --seeds=5"                           # every configuration over five seeds: means and standard deviations
sbt "run 1000 4000"                           # several background budgets
```

Lists of kind sets are separated by commas, not semicolons, which sbt treats as command separators. A full single-seed run takes about three quarters of an hour, and a five-seed sweep of all scenarios a little longer, since seeds run in parallel.

For each configuration the single-seed report gives, per scheduler, query cost relative to min/max pruning alone, requests per query, build work by path (background, ingest, compaction), work on indexes retired before they paid for themselves, Puffin rewrite work, write amplification and data commits per tick, and net benefit. It classifies the rows that min/max could not prune, although nothing in them matched, as *pruned* by an index, let through as a *false positive*, a *mismatch* (indexes on the column, none able to evaluate the predicate's shape) or *missing* (no index on the column), and for each scheduled shift reports the pruned share in its first six hours and over the whole shift.

## Talk

[`docs/talks/progressive-indexing.md`](docs/talks/progressive-indexing.md) is a fifteen-minute [Marp](https://marp.app/) deck on the project, with speaker notes; render it with `marp docs/talks/progressive-indexing.md`.

## Status

This is research code. The simulator's cost model is deliberately coarse (one tick stands for roughly a minute and rows are counted in thousands), its purpose is to compare strategies under the same workload rather than to predict absolute costs, and nothing here has been run against a real Iceberg table beyond the Puffin round-trip tests.
