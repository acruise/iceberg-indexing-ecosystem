# Toward Explicit Contracts Between Iceberg, Its File Formats, Its Storage and Its Writers

*A working note collecting the questions that contracts between Iceberg and its data file formats, its storage and its writers ought to answer, with proposed answers to some of them. It began with the discussion of whether version 4 should stop writing ORC and Avro data files, and it gives particular attention to read-only support, to degradation as a dimension of every part of the contract, and to sidecars.*

**Status:** working note. It does not attempt to write the contracts themselves. Its numbered *candidate requirements* are proposals, offered to show that a question can be answered and roughly how, not as settled text. It is kept separate from the [Puffin blob proposal](../proposals/puffin-blob-types-and-compaction.md), and the two refer to each other; the last section before the open questions sets out where they align. Sources were read on November 10, 2026, against apache/iceberg `main` at commit `26c9544`.

## Scope and reading order

The note began as a question about data file formats and grew, as each answer turned out to depend on something beneath or beside it, into a set of questions about the contracts Iceberg has, mostly implicitly, with three kinds of party: the formats that hold its data, the storage that holds its files, and the writers whose commits change a table. Those contracts are between a handful of components, each in its own role:

- an engine that plans queries and reads;
- writers that commit changes;
- maintenance that expires snapshots and collects files;
- the table's metadata, from the catalog's pointer down through snapshots and manifests;
- data files and their sidecars, in their formats;
- the storage beneath them all, reached through a `FileIO`.

The same questions apply to every pair of them. What does each require of the other? Is the requirement stated in the specification, and if not, is it at least relied on? Where it may not be met, is there an allowable degradation, or must the operation refuse? And how does the component learn which, from a declaration, by probing, or only by failing? The answers the note proposes are expressed in a common vocabulary of dimensions, such as reliance and outcome, which the next section summarizes and Appendix B defines.

The note's parts take these questions up in turn, one family of components at a time:

- **The framework in brief.** A page of shared ideas (outcomes, reliance and bindings) and shared vocabulary, including the notion of a sidecar, each defined fully in an appendix. Read these first; the later parts use them without restating them.
- **Data file formats.** What a format provides and requires, read-only support, sidecars for format integration and predicate pushdown.
- **Storage.** What object stores and HDFS guarantee, and what Iceberg assumes they do.
- **Commits.** What a retried commit owes the table, and what enforces it.
- **Degradations in practice, and alignment.** Worked examples across all three, and the parallels with the Puffin blob proposal.
- **Appendices.** Terms, the dimensions of the contract (with the graph in which verdicts are evaluated), outcomes, sidecars and bindings.

The note is deliberately over-broad. Its purpose at this stage is to find as many problems as possible, not to propose a mechanism for each, and many of the problems it raises could probably be settled by a sentence or two of prose in the existing specifications: stating what an implementation already does, or making explicit a rule that every implementation already infers. Only a minority seem to need new fields, and fewer still new mechanisms.

Three companion documents separate problems from solutions. The [register of problems](problems.md) states each problem the note raises, divided between those that exist today and those that proposals for version 4 are predicted to introduce or aggravate, and says where a solution is proposed. The solutions that need only prose are in the [list of prose fixes](prose-fixes.md), and those that would have to ride version 4 in the [version 4 asks](v4-asks.md). What other table formats already do about each problem is collected in a [survey of prior art](prior-art.md). A note on [versioning by feature](feature-versioning.md) applies the note's dimensions to the format version itself, and states when an implementation that lacks a feature may proceed.

## The framework in brief

The parts that follow share a few ideas about how components should behave toward one another, defined fully in the appendices:

- **Outcomes.** When something a component needs is missing or cannot be verified, the operation either degrades, going ahead in a worse form, or refuses. A wrong answer is never an allowable outcome, and the contract's aim is that every gap be assigned one of the other two in advance ([Appendix C](#appendix-c-outcomes)).
- **Reliance.** Whatever a component may meet without understanding it, from a metadata field to a sidecar, is either ignorable or required, and that decides between degrading and refusing ([Appendix B](#appendix-b-dimensions-of-the-contract), with the other dimensions the contract varies along).
- **Bindings.** Every relationship between something in a file and something in metadata, such as a column's field id or a deletion vector's row positions, is fixed by a commit and recorded with enough (a length, ideally a content hash) to detect that the file has changed ([Appendix E](#appendix-e-bindings-between-a-file-and-the-metadata-that-tracks-it)).

They also share a vocabulary for the things those ideas apply to. Most of it is the specification's own (data files, delete files, manifests and so on), listed in [Appendix A](#appendix-a-terms). One kind of thing needs a name the specification does not give it: a *sidecar* is a file tracked separately from the subjects it describes (data files, a snapshot, the table) and meaningful only relative to them, as Puffin files of deletion vectors or of index blobs, column files and statistics files are ([Appendix D](#appendix-d-sidecars)). Sidecars are also where most of the dimensions the contract varies along meet, which makes a single description of kinds of sidecar a candidate waypoint toward a unified description of the whole space.

## Data file formats

This part of the note is where it began: with the question of what a data file format owes Iceberg, prompted by the proposal to stop writing ORC and Avro data files in version 4. It applies the framework summarized above, and defined in the appendices, to formats.

### Why now

Five things have happened within the past several months, and together they make the question timely:

- **Parquet-only manifests.** With the merge of the adaptive metadata tree specification ([pull request 16025](https://github.com/apache/iceberg/pull/16025)), version 4 manifests are written in Parquet. Tables upgraded from earlier versions keep their Avro manifests, but any new or rewritten manifest must be Parquet ([Kevin Liu, October 19](https://lists.apache.org/thread/fym02576n5sqy298fjxl0xmsv9z5rb7y)).
- **A proposal to stop writing ORC and Avro data files.** On November 2, Kevin Liu asked [whether version 4 should stop writing new ORC and Avro data files](https://lists.apache.org/thread/5jfzckl0cc9z683kz9vfv41pmhrm1tdb) while existing files in upgraded tables stay readable. Among his four questions is what bar a format should meet to become writable again. As of November 10 nobody had replied.
- **Objections to narrowing the data layer.** In the preceding thread, Péter Váry wrote that he wants to "keep things open in data files". Junwang Zhao named Vortex, Lance and Nimble as formats worth keeping room for, and Manu Zhang preferred converging on one format in the Java reference implementation while "leaving the options open in the Spec".
- **Column files.** The version 4 draft lets a data file carry *column files*, separate files holding the values of some of its columns, so that columns can be updated without rewriting the data file. Each names its own `file_format`.
- **A pluggable format interface.** The [File Format API](https://iceberg.apache.org/blog/apache-iceberg-file-format-api/) (the `org.apache.iceberg.formats` package) now routes engine reads and writes through a registry of format models rather than through format-specific code in each engine.

Each of these is a decision about formats taken without a written statement of what a format owes Iceberg. Kevin's third question cannot be answered without one: a bar for writability is a list of obligations, and nobody has written that list down. Nor can Manu's preference be reconciled with Péter's without knowing what "open in the spec" would oblige a format to do.

### Where the format contract lives today

The obligations exist, but they are spread across three places, and none of the three states them as obligations of a format in general:

| Layer | What it says | What it leaves unsaid |
|---|---|---|
| Specification | Names the permitted formats in `file_format` (`avro`, `orc`, `parquet`, `puffin`). Appendix A gives a type mapping for each. Column projection is by field id, with a name-mapping fallback. Defines `split_offsets`, the column metrics and the row lineage columns. | What a format must be able to do for a new name to appear in that list. |
| Java interface | `FileFormat` is a closed enum carrying an extension and a `splittable` flag. `FormatModel<D, S>` is keyed by format and engine row type, and supplies a `ReadBuilder` and a `ModelWriteBuilder`. `FileAppender` returns `metrics()` and `splitOffsets()`. | The meaning of the methods. For example, `ReadBuilder.filter` may return unfiltered rows, and nothing states which pushed-down predicates must be honored for correctness rather than performance. |
| Format test kit | `ReadFormatModelTests` and `FileFormatTestSupport` exercise every registered format, and they record unsupported features as exclusions, such as a per-format set of unsupported type ids. | Which exclusions are acceptable for a format that a table may legally contain. The kit records gaps; it does not grade them. |

Two features of the Java side deserve mention. A new format cannot be added without a change to the `api` module, because `FileFormat` is an enum. And `FormatModelRegistry` discovers models by reflection over a hard-coded list of four class names, not by `ServiceLoader`. A format maintained outside the Iceberg repository can therefore register itself only if engine code calls `register` explicitly. Neither is a defect for the formats Iceberg ships, but both stand between Vortex and the File Format API it motivated.

### What a format provides and requires

The contract is easiest to state from the format's side, as what it provides to Iceberg and what it requires from Iceberg. The provisions divide further into a read tier and a write tier, because the read-only cases discussed below need the two separable. The items are numbered because some of them might eventually become specification text.

#### What a readable format provides

1. **Column identity, as a binding.** Each column in a data file, nested fields included, is bound to an Iceberg field id under the binding protocol of Appendix E. The binding is ordinarily stored in the file's own schema. For a file that cannot store it, it must be fixed when the file is committed and recorded in the manifest entry or a required sidecar. A name mapping does not meet the protocol as it stands. It is a table property, so it is not versioned with snapshots, and editing it reinterprets every data file without field ids, including the same files as seen through snapshots committed before the edit. A name mapping is useful for deriving the binding at registration time; the result of applying it should then be frozen with the file.
2. **A read type mapping**, from the physical types the format may find in a data file to Iceberg types, as described under type mappings below.
3. **Projection by field id.** A reader returns the requested field ids, in the requested shape, and reports which requested ids are absent from the data file, so that the layer above can supply defaults or nulls.
4. **Row positions, as a binding.** Every row has a file-relative ordinal, returned as `_pos`, that is the same for every conforming reader and every version of the format's library for as long as the data file is live. Position deletes, deletion vectors and row lineage (where `_row_id` is the file's `first_row_id` plus `_pos`) are committed against these positions, so a reader that would number rows differently, for instance by skipping a row it cannot decode, must refuse the file instead.
5. **Addressable units.** If the format has units smaller than a data file, such as row groups, stripes or pages, a reader can map a unit to its range of row positions and skip it without decoding it. The `split_offsets` recorded in the manifest are a binding to those units; a reader that finds them inconsistent with the file ignores them and plans the file as one split. A format without such units declares itself unsplittable.
6. **Byte-range splits.** A reader asked for `[start, start + length)` returns exactly the rows of the units that begin in that range, so that adjacent splits partition the data file.
7. **Footer statistics.** A reader can produce per-column metrics for a data file or unit from metadata alone, without a full scan. Registering an existing file (`add_files`, migration) depends on this. It is also the input to predicate pushdown, discussed below. Once copied into a manifest, the metrics are a binding that every planner trusts for pruning, so they must describe the file as committed, and a table needs a way to revoke them when their writer is later found to be defective (see configuration paranoia below).
8. **Decryption**, given the key and additional authenticated data that the table supplies. The key metadata in the manifest entry is a binding with a lifecycle outside the table: it must remain resolvable for as long as the data file is live, so key rotation and key retirement have to be coordinated with snapshot expiration.

#### What a writable format provides

1. **Field ids in the data file**, for every column written, matching the schema recorded by the commit that adds the file.
2. **A write type mapping**, from each Iceberg type it accepts to a physical representation that a reader can map back, as described under type mappings below.
3. **Column metrics** as the specification defines them, including NaN counts and, in version 4, the typed content statistics and whether they are tight.
4. **Split offsets**, sorted ascending, when the format is splittable, matching the units actually written.
5. **Preservation of row lineage**, by writing `_row_id` and `_last_updated_sequence_number` values it is given when a rewrite carries rows forward.
6. **Encryption**, rejecting a key it cannot honor instead of writing plaintext.
7. **The table's newer types and encodings**, or a declaration that it cannot write them. Variant shredding, the geospatial types and the draft vector type are the current examples.

#### What a format requires of Iceberg

1. **Positioned reads** from `FileIO`, together with the file length from the manifest so that the footer can be found without a separate request. The storage section below takes up what Iceberg in turn requires of storage.
2. **The projected schema and a name mapping**, including the table's type for each field id where the data file alone is ambiguous, and a value for each column whose content comes from table metadata rather than from the file. Partition values are one example; in the Java interface this is `idToConstant`.
3. **Keys and additional authenticated data** for encrypted tables.
4. **A namespace for format-specific properties.** Today these are hard-coded per format (`write.parquet.*`, `write.orc.*`). A format added later would not get a reserved prefix.
5. **An identifier.** The `file_format` field is a closed list in the specification, so a new format needs a specification change before any table may contain it, even read-only.
6. **Space for sidecar files**, at stated scopes, for information about a data file that the format cannot hold natively. A later section takes this up.

#### Type mappings

The read and write tiers each refer to a type mapping, and it would be optimistic to describe it as one mapping that holds in both directions. Appendix A already shows the asymmetry. On read, ORC `tinyint` and `smallint` both become Iceberg `int`, so the read mapping is many-to-one, and a data file from a non-Iceberg writer can contain physical types that no Iceberg writer would choose. On write, a format may choose among several physical representations (Parquet stores a decimal as `int32`, `int64` or `fixed` according to its precision), and some Iceberg types survive only through an annotation that a reader must trust: ORC stores `time` as a `long` marked with the attribute `iceberg.long-type=TIME`, which a file from a foreign writer will not carry. The mapping therefore divides along the read and write axis, and again according to whether the format resolves it alone or needs something from Iceberg to resolve it:

| | What the format provides | What the format requires from Iceberg |
|---|---|---|
| **Read** | A map from the physical types it may find in a data file, including legacy representations and those of foreign writers, to Iceberg types; and recognition of physical types outside that map | The table's type for each field id, wherever the physical type is ambiguous without it (a `fixed(16)` that is a `uuid`, a `binary` that is a `variant`, a `long` without its annotation), and the promotion rules for a column whose table type has widened since the file was written |
| **Write** | For each Iceberg type it accepts, a stated physical representation, annotated well enough that a reader holding only the data file can recover the Iceberg type, or else a declaration that the annotation goes in a required sidecar | The Iceberg schema, and the engine schema where representation is a choice (a shredding layout for a variant); and agreement on who validates that each value lies within the supported range |

Each cell is partial within a type, so a mapping is stated over the type's parameters rather than over its name: a format might hold `decimal(P, S)` only for P ≤ 18 and S ≤ 16, and timestamps only at microsecond precision. Appendix A varies the physical representation with the parameters, but it does not express a limit on what a format can hold. The read range and the write range of one format may also differ, which is the ordinary state of a deprecated format.

A bijection is the wrong requirement, since the round trip from physical type to Iceberg type and back need not return the type it started from. The requirement that matters for correctness is narrower:

1. For every value *v* of Iceberg type *T* within the format's write range for *T*, a conforming reader that reads the written data file with the table's type *T* returns *v*.
2. A reader that meets a physical type outside its read map, or a value outside its read range, fails rather than returning a converted value.

### Read-only support

Read-only support comes up in four separate situations, and only the first is the subject of the current thread:

- **Deprecation.** Version 4 stops writing ORC and Avro but keeps reading them.
- **Admission.** A new format, Vortex for instance, might be admitted as readable before anyone has written a conforming writer, so that data files produced elsewhere can be registered and queried.
- **Registration of foreign data files.** `add_files` and migration already admit files that no Iceberg writer produced, often without field ids. For Iceberg's purposes these files are read-only whatever their format.
- **Partial implementations.** A library in another language, or an engine, may implement only the read half of a format. This is a property of an implementation rather than of the format, and the specification should keep the two apart.

The read tier above is meant to be the entry bar in all four cases, and the write tier the further bar that Kevin's third question asks about. Separating the tiers has consequences for every operation that rewrites data files, and they should be stated rather than left to each implementation.

#### Operations on read-only data files

| Operation | What it needs from a read-only data file |
|---|---|
| Compaction and other rewrites | Read only. The output is in a writable format, so compaction is the migration path off a deprecated format. The table's default write format must therefore be writable. What becomes of the predecessor is a separate question, taken up below. |
| Row-level deletes | Nothing beyond stable positions, since deletion vectors are Puffin files in version 3 and later. A read-only data file can have rows deleted without being rewritten. |
| Copy-on-write updates | Read only. The surviving rows are written to a new data file in a writable format. |
| Row lineage across a rewrite | Correct `_row_id` and `_last_updated_sequence_number` values on read, which the writer then copies forward. |
| Restoring tight statistics | Version 4 allows statistics to be recomputed "without rewriting the data file". For a read-only format this means a full read, or footer statistics where they suffice. |

#### Compaction and the removal of read-only predecessors

Compaction deserves particular treatment wherever something is read-only, because it is the one operation that consumes a file it may not modify and leaves behind a successor that makes the file unnecessary. Whether it may then remove the predecessor depends on what "remove" means, and on which of several things is read-only.

"Removing" a compacted file names two acts that are permitted under different conditions, and a third that is never needed:

1. **Logical removal.** The compaction's commit drops the predecessor from the new snapshot. This is a write to table metadata, not to the file, and it is permissible wherever the table itself may be written, whatever the file's format or storage.
2. **Physical deletion.** Once no retained snapshot references the predecessor, expiration or purge deletes the object. This is a write to storage, and it is permissible only if the table owns the file and the storage allows deletion.
3. **Modification.** Nothing in compaction changes the predecessor's bytes, so a read-only file is never asked to be written in that sense.

The read-only situations listed above differ in which of the first two they permit, and storage adds two more:

| Situation | Compaction may run | Logical removal | Physical deletion |
|---|---|---|---|
| A format that is read-only, by deprecation or admission | Yes; it is the migration path | Yes | Yes, if the table owns the file |
| A foreign data file registered by `add_files` | Yes | Yes | Only if ownership was transferred at registration |
| A data file shared with another table, as by the `snapshot` procedure | Yes | Yes | No |
| Storage that is read-only, archival or under a retention lock | Yes, but the predecessor's storage is not reclaimed | Yes | No, or not until the retention ends |
| An implementation that can only read the format | No, unless it can write the successor's format | (not reached) | (not reached) |

The precondition for any of this is that the successor is valid, and for a read-only predecessor the condition matters more than usual, since the predecessor may be a file the table cannot reproduce. The successor holds the same rows as the predecessor after the deletes the rewrite removes. It carries forward the predecessor's `_row_id` and `_last_updated_sequence_number` values as row lineage requires. Its required sidecars are produced in the same commit, and its ignorable sidecars are carried forward, rebuilt or dropped as their kinds declare. An operation whose tolerance excludes any risk may also verify the successor before committing, by row count or by a content check, and that is a reasonable default when the predecessor is foreign.

**Ownership** is the property the table lacks. Today it is decided for the whole table by `gc.enabled`, and the `snapshot` procedure [sets it to false](https://github.com/apache/iceberg/blob/26c9544ea4744df3d09bb3a9abcefd43997fa595/spark/v4.1/spark/src/main/java/org/apache/iceberg/spark/actions/SnapshotTableSparkAction.java), because, in the [documentation's words](https://github.com/apache/iceberg/blob/26c9544ea4744df3d09bb3a9abcefd43997fa595/docs/docs/spark-procedures.md), such tables "are not the sole owners of their data files" and are therefore "prohibited from actions like `expire_snapshots` which would physically delete data files", while "Iceberg deletes, which only effect metadata, are still allowed". The distinction between logical removal and physical deletion is thus already drawn in practice. What the table-wide flag cannot express is a table that owns some of its files and borrows others. A snapshot table that compacts the files it borrowed writes successors it does own, and when those are compacted in turn, the flag stops expiration from ever collecting them. Every successor the table has written and later superseded leaks.

Ownership belongs in the instance description of each data file, as a property of the file and not of the table:

1. A data file is **owned** or **borrowed**. Every file a table's own writers produce is owned, including the successors of borrowed files.
2. Registration says which. `migrate`, which replaces its source, transfers ownership. `snapshot` borrows. `add_files` borrows by default and transfers ownership only when asked.
3. Removing a borrowed file is logical only. Expiration and purge never delete it, and orphan removal never treats it as an orphan, whatever its location.
4. Where storage refuses deletion, as under a retention lock, physical deletion of an owned file is deferred or abandoned and reported as leaked storage, which is a degradation of cost and never a failure of the compaction.

Under these rules, compacting a read-only file is always permissible when a valid successor can be written, and the only question that remains is whether the predecessor's storage comes back. That is a question about ownership and storage, and the table can answer it file by file.

#### Disclaimers of ownership

Per-file ownership answers the question for a table that knows. Many do not. A table registered in a second catalog, copied for disaster recovery, or created by `snapshot` while its source lives on, shares files with other instances of itself or of its source, and the instances share a table UUID or nothing at all. None of them can see the others' retained snapshots. An instance that stops referencing a file therefore knows something true and something it cannot know:

- *This instance no longer needs the file*: true, and checkable, since the instance knows every snapshot it retains.
- *Nobody needs the file*: unknowable from inside any one instance, since it depends on every instance that may reference the file.

Physical deletion is safe only when the second holds. Today an instance either assumes it, and deletes, or cannot assume it, and forgets the file entirely, so that the evidence a later collector would need is lost. We propose keeping the first statement as a record, in a structured *disclaimer log*: an append-only list of the files an instance believes it no longer owns, written when it would have deleted them had it been sure.

Each entry records:

1. the file's location, length and, where available, content hash, so that the disclaimer names the exact bytes and not merely a path that may later hold others;
2. the disclaiming instance's identity, which must be more than the table UUID, since copies share it: the UUID together with the catalog and the location of the metadata file from which the instance was loaded;
3. the commit at which the disclaimer was made, and the reason: superseded and expired here, borrowed from the start, transferred to a named instance, or released when the instance was dropped without purge;
4. whether this instance ever owned the file, or only borrowed it.

A disclaimer is a statement of belief by one instance, and it is never by itself a license to delete. It becomes authoritative only together with a statement that the instances which have disclaimed a file are all the instances that may reference it. That closure is exactly what no instance can establish for itself, so it has to come from the context:

1. **A person's knowledge.** An operator who knows that a snapshot table's source has been dropped, or that a replica is the only remaining copy, can attest to the closure for a set of instances, and a collector may then delete every file disclaimed by all of them.
2. **A catalog that tracks copies.** A catalog that itself performs registration, cloning and replication can know the instances it created, and can attest to the closure on their behalf.
3. **A declared sole owner.** A table whose metadata states that it has never been copied or registered elsewhere, under a commit authority that would refuse such registration, is its own closure, and its disclaimers are authoritative as written. Such a table needs no log, since it can delete directly.

Outside those contexts the log is advisory. A collector that cannot establish the closure treats the files it lists as leaked storage, reports them, and deletes nothing, which is the degraded outcome of Appendix C and never the wrong one. The log is most valuable at the moment an instance is dropped without purge, when it is otherwise forgotten: a final entry disclaiming every file the instance retained is what lets a later, better-informed collector reclaim the storage.

The log is an ignorable sidecar of table scope. Discovered rather than referenced, it can live under a discovery root of its own, using the mechanism of the [note on decoupled index metadata](../proposals/decoupled-index-metadata.md), and it must outlive the table that wrote it, so purge spares it unless the closure is established.

#### The read obligation keeps growing

The thread describes the deprecated formats as remaining readable, as though reading were a fixed obligation, and it is not. Several of the gaps Kevin lists are read gaps. The Java ORC integration cannot read default values, and it cannot read the geospatial types. A default value is a schema-level feature. Once a column with an initial default is added to a table whose older data files are ORC, every read of those files that projects the column must supply the default, and the ORC reader cannot; it fails the read instead. Each later feature that changes how an existing file is interpreted, such as a new type promotion, adds to what a reader of a deprecated format must do. The maintenance that deprecation was meant to save would then continue on the read side.

There are three ways out, and they are not exclusive:

1. **Keep the format's read obligation small.** Specify the read tier as physical columns by field id, together with row positions and units, and nothing more. Defaults, type promotion, constants and metadata columns are then supplied once, above the format, for every format alike. In the Java interface the opposite is true today: `ReadBuilder` takes `idToConstant` and a name mapping, and each format's reader applies defaults itself, which is how one format came to lag another.
2. **Refuse table changes that a live read-only data file cannot honor.** This is the approach of the compatibility validation proposed in [pull request 13060](https://github.com/apache/iceberg/pull/13060), which was approved but never merged.
3. **Record which formats are live.** If table or snapshot metadata listed the data file formats present, a reader lacking ORC support could refuse the table at load time instead of failing partway through a scan. Maintenance could then also tell when the last ORC file had been rewritten. The reasoning is the same as for the list of discovery roots proposed in the [note on decoupled index metadata](../proposals/decoupled-index-metadata.md): metadata should say what a reader or maintenance process needs to know, rather than leave it to be discovered by scanning. If both lists are adopted, they could share one mechanism for table-level declarations.

The first is the most consequential. It also bears on the cost argument in the thread, which counts the work of supporting a format in each of five languages. If writes alone are dropped, that work shrinks only on the write side. Every version 4 reader in every language must still read ORC or refuse tables that contain it, and the smaller the read tier, the lower that cost.

#### Defaults that are not literals

A default that is a literal is the easy case for a reader, and the version 4 draft keeps it that way. Its `write-default` may now be a value expression in the sense of the new [expressions specification](https://github.com/apache/iceberg/blob/main/format/expressions-spec.md), such as `current_timestamp()`, but field references are not allowed in it, the writer evaluates it when a row is written, and "omitting a known field when writing a data file is never allowed", so its value is materialized in the data file. The `initial-default`, which readers apply retroactively to rows written before the field existed, "must always be a literal value". The draft thus draws the same line the Puffin proposal draws for input expressions: an expression may appear where its result is materialized by the component that evaluates it, and only a literal where a reader must reproduce it later.

The line matters for the contract in three ways:

- **Readers are spared, and writers take on the obligation.** A writer that meets a `write-default` naming a function it cannot evaluate, such as an engine-specific one under a catalog name like `spark_builtin_functions`, cannot supply the field's value. The specification requires the default to be written, so the writer's only conforming course is to refuse the write; the draft would do well to say so, since the alternative of writing null is the silent wrong answer the contract excludes. This is a must-understand requirement on writers, and it belongs in the write tier.
- **Literals take part in planning; expressions do not, unless their behavior is known.** A literal `initial-default` tells a planner the exact value of a column in every data file written before the column existed, so such data files can be pruned against it as against a constant. A materialized `write-default` produces ordinary column statistics. A default that readers had to evaluate per row would be usable for pruning only through functions whose behavior over bounds is specified, as the partition transforms' projections are, and not at all otherwise.
- **The line is a binding rule in disguise.** If a future version allowed an `initial-default` that was not a literal, a non-deterministic one such as `now()` would have to be evaluated once and frozen by the commit that adds the field, which is the first rule of the binding protocol (Appendix E), and one that depends on other fields of the row would make every reader of every format evaluate it, which is the read obligation growing again, and the case for supplying defaults above the format layer rather than in each format's reader. The derived-columns discussion on the dev list is where such a proposal would arrive first.

#### Classifying expressions instead of special-casing them

The draft's two rules, no field references in a `write-default` and nothing but literals in an `initial-default`, read like special cases for `current_timestamp()` and for derived columns, and special cases leak. A default of "24 hours ago", `current_timestamp()` less 24 hours by some date arithmetic function (Iceberg has no interval type), uses no field reference and is admissible as a `write-default`, but its value depends on the writer's clock, and on the session's time zone if it were written with `current_date`; the expressions specification makes function *references* independent of session context, but says nothing about the context a function's *evaluation* reads. A default of "`'default'` if `foo` is null, else `foo`" is a reasonable thing to want on write, a coalesce, and the field-reference rule forbids it even though a writer could evaluate it and store the result, while as an `initial-default` it would be a derived column that every reader of every format had to compute.

What decides admissibility is not which function appears but what the expression reads and who evaluates it.[^coeffects] A classification along those lines would replace the special cases:

| Class | Examples | As a `write-default` (writer evaluates, result stored) | As an `initial-default` (readers evaluate, retroactively) | In planning |
|---|---|---|---|---|
| Literal | `0`, `'unknown'` | Admissible | Admissible | Usable as a constant |
| Constant expression, deterministic | `upper('x')`, `truncate(4, 'abcdef')` | Admissible; should be folded to a literal when the schema change is committed | Admissible once folded | Usable once folded |
| Reads the evaluation context | `current_timestamp()`, "24 hours ago", `current_date` | Admissible, if the context it reads is defined (which clock, which time zone, per row or per statement) | Only if evaluated once by the commit that adds the field and frozen as a literal | Usable only once frozen |
| Nondeterministic | A random value, a generated UUID | Admissible | Inadmissible | Not usable |
| Reads other fields of the row, deterministically | Coalesce, `if_else` over another field, arithmetic on other fields | Admissible in principle, with rules for renamed, dropped and promoted fields and for cycles among defaults | A derived column: every reader must evaluate it | Usable only through functions whose behavior over bounds is specified |
| Reads outside the row | A lookup in another table | Inadmissible | Inadmissible | Not usable |

Two things are needed to apply it. Functions have to declare their class, and a single `deterministic` flag, which is what the UDF specification has (defaulting to false, and required to be true for functions used in `check` constraints), cannot separate "depends on the clock" from "random". And the evaluation context of context-reading functions has to be pinned down, or recorded where a value is stored, if two writers evaluating the same default are to agree on what it meant.

Functions would also have to declare what their evaluation can do, as distinct from what it reads: whether it may produce a null, or fail. The specification already depends on the first, since a writer must fail when a required field's `write-default` evaluates to null, and a writer that cannot evaluate a function at all is in the position of one whose function failed.[^effects]

Neither declaration helps unless it is propagated. Expressions nest arbitrarily, so a rule that inspects the outermost function, or a list of banned constructs, is a game of whac-a-mole: `upper(trim(coalesce(my_udf(), 'n/a')))` is built entirely of deterministic string functions except for `my_udf`, and if `my_udf` reads the clock, so does the whole expression. A syntactic rule such as the draft's ban on field references can at least search the whole tree, but it cannot see inside a user-defined function, whose body is SQL in one or more dialects. Classes therefore have to be computed:

1. Each function declares what it reads and what it can do. A function may also declare which of its arguments' effects it discharges: `coalesce` discharges the possibility of a null in its earlier arguments if its last argument cannot be null.
2. An expression reads whatever its function reads together with whatever its arguments read, and can do whatever its function and its arguments can do, less what the function discharges. Without a declaration, a function is assumed to read and do everything.
3. Admissibility in each role (an `initial-default`, a `write-default`, a `check` constraint, a partition transform, use in planning) is a test of the computed sets, and the columns of the table above become such tests.
4. A user-defined function's declarations must cover its body, including the functions it calls. Because the UDF specification versions definitions, a stored expression's computed class can change when a function it calls is redefined, so a stored expression should record which definitions it was checked against, or be checked again when they change. This is the expression counterpart of the rule in Appendix E that a binding is fixed by the commit.

There is a defensible alternative to computing classes in general: prescribe a small space of admissible expressions, built only from literals and a short list of functions whose classes are fixed by the specification, and declare everything outside it out of bounds. Propagation over such a space is trivial, and the specification can enumerate the admissible forms for each role. The question is what "out of bounds" obliges. Iceberg already uses *undefined behavior* in the sense familiar from C (a snapshot that references a content file twice "has undefined behavior"), but undefined behavior on the read side licenses exactly the silent wrong answer the contract excludes: a reader that meets an expression it does not understand in a blob's `input-expressions` and matches it anyway produces wrong exclusions. The usual way to split the obligation is by role:

1. Writers MUST NOT produce an expression outside the prescribed space; for a writer, what happens outside it is undefined.
2. Readers MUST treat an expression outside the space as unknown, and act according to the reliance of whatever carries it: ignore an ignorable structure, which degrades, or refuse a required one.

Under this split the prescribed space can stay small and grow by specification, as the partition transforms have, and an implementation that meets an expression from a later, larger space still reaches a declared outcome rather than an undefined one.

As a ceiling, though, a prescribed space is unsatisfying for a specification meant to last: every new function waits for a specification revision, and the space hardens into the vocabulary of whichever version first fixed it. It works better as a floor. The prescribed space is what every conforming implementation must evaluate, so expressions inside it are portable; beyond it, functions carry declarations of what they read and what they can do, classes are computed as above, and an implementation that lacks a function treats any expression using it as unknown under the role split. This is the arrangement the Puffin proposal makes for blob types, a small set of standard types beside an open namespace of types that describe themselves, and it lets the vocabulary grow without each addition becoming a specification change.

### Sidecars for format integration

Some of what Iceberg needs to know about a particular data file will not fit cleanly in that file's native format. A format whose schema cannot carry field ids needs them stored somewhere; a format whose type system lacks a distinction Iceberg draws, or whose footer has nowhere to put an Iceberg-specific annotation, needs that recorded somewhere too; and an integration may want to keep statistics or provenance in Iceberg's own terms rather than translate them into the format's. Today the only places for such information are the manifest entry, whose fields are fixed by the specification, and the file's own key-value metadata, where a format has it. Name mappings show the cost of having neither: they exist because files written without field ids could not be given them afterward, and they are table-wide approximations of what was a per-file fact.

We think the contract should set aside explicit space for *format sidecars*: sidecars, in the sense of Appendix D, that carry information about a data file on behalf of its format's integration. A field-id map or a set of type annotations would be a format sidecar of data-file scope. A dictionary shared by several data files, for formats that support one, would be one of group scope, and it inherits the open problem of the Puffin proposal's Section 14.12: a structure shared by several files has no single file to be bound to, so its lifecycle under compaction has to be specified separately.

#### Reliance: when a reader may ignore a sidecar

This subsection applies the reliance dimension, set out with the other dimensions in Appendix B, to sidecars, where it is most intricate. When a reader meets a sidecar it does not support, it can either refuse the data file or read it in a degraded mode, and the sidecar has to tell it which. That decision depends on how the reader's answer relies on the sidecar. Two separate questions are involved, and the Puffin proposal's distinction between positive and negative indexes (its Section 12.2) answers only one of them:

- **What may a reader conclude from the sidecar if it uses it?** A negative structure can only exclude, and a positive one asserts where rows are or what values mean. This governs the validity checks a reader must make before trusting the sidecar, and it is the question the Puffin proposal settles for blobs.
- **What happens if a reader ignores the sidecar?** This governs whether a reader that does not support it may proceed at all, and it does not follow from the first question. It is the fourth property of the definition. Deletion vectors are the clearest case. They are negative, since they only remove rows, yet a reader that ignored them would return deleted rows. A field-id map is positive, and ignoring it is just as unsafe. A sidecar of statistics may be either, and ignoring it costs only pruning.

The second question is the one that decides between refusal and degradation, and its answer should be recorded as *reliance metadata*:

1. **Ignorable.** A reader that does not support the sidecar reads the data file as though the sidecar did not exist, and reports the degradation. This is the *degrade* outcome of Appendix C.
2. **Required.** A reader that cannot find, verify or understand the sidecar MUST NOT read the data file, and MUST fail rather than fall back to reading the file without it. A sidecar of snapshot or table scope that is required makes the refusal apply to every data file it covers. This is the *refuse* outcome, with the sidecar's scope as the scope of refusal.

Reliance may also be conditional on the read. A column file is required only by a reader that projects one of the field ids it carries, so its reliance is stated over those fields, and a reader projecting none of them may ignore it whether or not it supports column files. Reliance metadata should therefore be able to name the fields, or more generally the parts of the subject, to which a requirement applies.

Required sidecars invert the rule the Puffin proposal is built on, under which a failure to find or understand a blob costs coverage and never correctness.

##### Type layer and instance layer

Reliance belongs at the type layer wherever possible: a sidecar type's descriptor declares it, and every instance of the type behaves alike. It may not always be possible. A sidecar type carrying type annotations is redundant for a data file whose native schema already says what it says, and indispensable for a file whose native types are ambiguous, so the same type can be ignorable for one instance and required for the next. Reliance therefore needs to be expressible per instance as well, and three rules follow from the fact that the readers who most need it are the ones that do not understand the type:

1. The instance-level reliance is recorded in the *reference* to the sidecar, in the manifest entry or other referring metadata, at a position defined independently of any sidecar type, so that a reader can find it without understanding the sidecar.
2. An instance may strengthen its type's reliance and may not weaken it. A sidecar of an ignorable type may be marked required; a sidecar of a required type is required in every instance.
3. A reader that understands the type obeys the stronger of the two declarations.

There is ample precedent for marking this per instance. An X.509 certificate marks each extension critical or not, and a verifier must reject a certificate carrying a critical extension it does not recognize. PNG encodes the same distinction in the case of each chunk type's first letter, and it adds a further bit saying whether an editor that does not understand the chunk may copy it into a modified image. That last bit is the counterpart of the question a rewrite raises for sidecars, and for blobs under the Puffin proposal's compaction rules: whether something not understood may be carried forward unchanged.

##### Placement

Reliance also decides where a sidecar may live.

- A required sidecar MUST be referenced from the manifest entry of the data file it describes, with its reliance, location, size and a digest, and MUST be committed in the same snapshot as that entry. Its reachability is then exactly the data file's: expiration, orphan removal and purge treat the pair as one unit, and the reachability problems catalogued in the [note on index reachability](../proposals/index-reachability.md) do not arise.
- An ignorable sidecar MAY live outside the manifest, discovered and collected by the same means as data-file-scoped index blobs, and it then accepts the same trade-offs. The [decoupled index metadata note](../proposals/decoupled-index-metadata.md) describes a mechanism that could serve both.
- The table-level declaration of live formats proposed under read-only support should also list the sidecar types that are required anywhere in the table, so that a reader that lacks one can refuse the table at load time instead of partway through a scan.

#### The same drivers as Puffin blob consistency

The questions a sidecar raises are the ones the Puffin proposal faces for blobs, and the answers there are a guide:

- **Binding.** A required sidecar is itself a binding under the protocol of Appendix E, and it must identify the exact data file it describes by more than its location, with the file's size and ideally a content hash or version token. The specification treats the path recorded in a data file's location field as the file's identity in several places (position delete files name their data file by `file_path`, and deletion vectors by `referenced_data_file`) without requiring that a path, once used for one file, never be used for another, and a sidecar bound by location alone would inherit that unstated assumption.
- **Rewrites.** A rewrite produces a new data file, and a required sidecar for it must be produced in the same commit. Only an implementation that understands the format can do this; but an implementation that does not understand the format cannot read the file either, so the rule costs nothing it was not already going to cost.
- **Typing and versioning.** A sidecar's content needs a type identifier, namespaced under the format and carrying a major version, and its schema needs to evolve under the same rules as a blob descriptor.
- **Configuration.** Where a sidecar's meaning depends on parameters that cannot be recovered from it, the parameters need to be recorded and verifiable, as Section 7 of the proposal requires for blobs.
- **Encryption.** A sidecar can leak what the data file protects, such as column names or statistics, and must be encrypted under the same key regime as the file it describes.

The encoding is a separate question from the reference. A format sidecar could be a Puffin file whose blobs are of a kind namespaced under the format, which would reuse Puffin's footer, compression and encryption. What makes it required or ignorable is how it is referenced, not how it is stored.

### Predicate pushdown

Pushdown is where format-specific wiring has historically been heaviest, and where it has been reused least. In the Java implementation:

| Format | Pushdown code | Approach |
|---|---|---|
| Parquet | `ParquetMetricsRowGroupFilter` (632 lines), `ParquetDictionaryRowGroupFilter` (487), `ParquetBloomRowGroupFilter` (353) | Iceberg evaluates its own bound expressions against Parquet footer statistics, dictionaries and Bloom filters, using one private visitor per class. |
| Parquet | `ParquetFilters` (258) | Translates to parquet-java's `FilterPredicate` for record-level filtering. |
| ORC | `ExpressionToSearchArgument` (349) | Translates to ORC's `SearchArgument`, which ORC evaluates itself. |
| Avro | none | |

Meanwhile `api/.../expressions/InclusiveEvalVisitor` already expresses inclusive evaluation against an abstract statistics source, with methods such as `lowerBound`, `upperBound`, `mayContainNull` and `mayContainNaN`. It is package-private, and its only user is `InclusiveMetricsEvaluator`, which evaluates manifest-level file metrics. The Parquet row group filter repeats the same logic over row group statistics in its own visitor. The semantic subtleties (NaN ordering, truncated string bounds, type promotion between the data file's physical type and the table type, timestamp units) are therefore implemented once for data files and again for each format's units. Each copy can go wrong in its own way, and an error in any of them excludes data that a query should have returned.

The convergence on a serialized expression representation (the version 4 draft now has an [expressions specification](https://github.com/apache/iceberg/blob/main/format/expressions-spec.md) of its own, with a JSON serialization and a section on compatibility with the REST catalog's earlier encoding) suggests dividing the work differently:

1. **The format provides a statistics oracle per unit.** For each addressable unit it answers bound, null-count and NaN-count questions by field id. Optionally it also answers membership questions from a dictionary or Bloom filter, with a declared false-positive direction. This is item 7 of the read tier, stated as an interface.
2. **Iceberg owns predicate semantics.** A single inclusive evaluator, public and shared, runs over manifest metrics and over format units. Correctness questions are then settled in one place.
3. **Native translation is an optional accelerator.** A format may also accept the serialized expression, or translate it into its own predicate language, for record-level filtering. The contract requires that this be conservative: it may return rows that do not match, which the engine filters again, and it must never drop a row that does. The `ReadBuilder.filter` documentation already says this in effect. The specification should say it explicitly.

A serialized expression has a second benefit for formats not written in Java. A format implemented in Rust, as Vortex is, can receive a predicate across a language boundary without a hand-written translation of Iceberg's Java expression classes for each binding. The REST catalog's 1.12.0 encoding left open how a parameterized transform such as `truncate[16]` is written; the expressions specification settles it, as the two-argument function `truncate(16, value)`, and whether it is complete enough for pushdown across a language boundary is now a question about its function vocabulary rather than its structure.

## Storage

The format contract has a counterpart one layer down, between Iceberg and the storage beneath a table: object stores such as S3, GCS and Azure Blob Storage, and file systems such as HDFS. Many of the bindings of Appendix E depend on it, since a binding that is durable with the data file is only as durable as the store that holds the file.

### What is specified today

The table specification's section on file system operations is short enough to quote in full. Iceberg "only requires that file systems support" three operations:

- **In-place write**: files are not moved or altered once they are written.
- **Seekable reads**: data file formats require seek support.
- **Deletes**: tables delete files that are no longer used.

It adds that tables do not require random-access writes, and that they do not require rename "except for tables that use atomic rename to implement the commit operation for new metadata files".

The Java `FileIO` interface is somewhat richer, and it divides into a mandatory core and optional capabilities that callers detect with `instanceof`:

| Interface | Methods | Used by |
|---|---|---|
| `FileIO` (mandatory) | `newInputFile`, optionally with a known length; `newOutputFile`; `deleteFile` | Every read and write |
| `InputFile`, `SeekableInputStream` | `getLength`, `newStream`, `exists`; seek and read | Every read |
| `OutputFile` | `create`, documented as failing if the file exists; `createOrOverwrite` | Every write |
| `RangeReadable` (optional) | `readFully` at a position, `readTail`, `readVectored` | Footer reads and vectored reads |
| `SupportsPrefixOperations` (optional) | `listPrefix`, returning location, size and creation time; `deletePrefix` | Orphan file removal; dropping a table with purge |
| `SupportsBulkOperations` (optional) | `deleteFiles`, which may fail for some paths and not others | Expiration and purge |
| `SupportsRecoveryOperations` (optional) | `recoverFile` | Restoring a deleted file where the store keeps versions |

The specification's three requirements suffice to read a table and to append to one. They do not suffice for the operations that keep a table healthy over time, and several of the gaps are ones that the Puffin notes and the format contract above have each run into separately.

### What is left unsaid

The following properties are relied on today, by the reference implementation or by proposals in this repository, without being stated as requirements. They are numbered because some of them might become specification text.

1. **Read-after-write consistency.** A file that a commit references must be readable by any reader that sees the commit. S3 has offered strong consistency since late 2020; before that, implementations worked around its absence. The specification does not say whether a store without it is conforming.
2. **The length of a file.** Readers take a data file's length from its manifest entry instead of asking the store, so that the footer can be read in one request. A store must therefore report and honor exactly the length that was written.
3. **Exclusive create.** `OutputFile.create` is documented as failing when the file already exists, but nothing says whether the check and the write are one atomic operation or two. In the S3, GCS and Azure implementations they are two: each `create` calls `exists()` and then writes unconditionally, so two writers racing on one name can both succeed. The discovery chain in the Puffin proposal (Section 6.5) and the [decoupled index metadata](../proposals/decoupled-index-metadata.md) commit protocol both depend on an atomic create-if-absent, and file system tables depend on atomic rename for the same purpose. Which files need this is taken up under file names and races below.
4. **Immutability that can be checked.** "In-place write" is stated as a property of writers, and nothing lets a reader detect a violation. A version token (an S3 ETag, a GCS generation number) recorded with a file's location would let a reader make a conditional read that fails if the file was replaced. The Puffin proposal leaves this as a TODO in Section 6.1, and sidecars need it for binding. A content hash, as the second rule of the binding protocol in Appendix E proposes, would do the same job for copies and relocations, where a version token is lost.
5. **Listing.** Orphan file removal lists everything under the table location and deletes what metadata does not reference and is older than a grace period. That depends on listings being complete, which some stores have historically not guaranteed, and on creation times from the store's clock being comparable with the cutoff, which the Java action takes as a value of `System.currentTimeMillis()` on the machine running it. Neither is stated. The removal is itself a race between checking and acting: a commit in flight can reference a file after orphan removal has found it unreferenced and before it deletes it, and the age threshold, which compares clocks on different machines, is the only protection.
6. **Location identity.** The same object can be named by several strings: `s3://`, `s3a://` and `s3n://` for one bucket, or an HDFS path with and without its authority. Orphan removal in the reference implementation has options for treating schemes and authorities as equal (`equalSchemes`, `equalAuthorities`) and a `PrefixMismatchMode` for locations it cannot reconcile, and the discovery chain's reader rules in the Puffin proposal fall back to losing coverage when locations differ only in scheme. Iceberg does not say which strings denote the same object, and the [index reachability note](../proposals/index-reachability.md) shows what is deleted when two components disagree.
7. **Deletion semantics.** Bulk deletion may fail partway. A store with versioning or soft deletion keeps what Iceberg deleted, which matters for cost and for data that was deleted for regulatory reasons. A bucket lifecycle policy may delete or archive files that the table still references, and Iceberg has no way to declare which files must not be touched.
8. **Credentials and scope.** Catalogs that vend credentials scope them to locations, commonly the table location. Any file a reader must reach outside that location, whether a discovery root or a required sidecar, may be unreadable with vended credentials. The decoupled metadata note's requirement that a discovery root be disjoint from the table location runs directly into this.
9. **Availability classes.** An object moved to an archival storage class is still listed and still has a length, but a read may fail or take hours. Whether such a file may be referenced by a live snapshot, and what a reader should do when it meets one, is unstated.

### File names and races

Item 3 matters only for some files, because the danger of a check followed by a write depends on how the name was chosen. A store's lack of an atomic create is a time-of-check-to-time-of-use race, and whether it can bite depends on whether two writers can arrive at the same name. Three classes of name need different treatment:

| Class | Examples | Risk of a race on create | What it needs |
|---|---|---|---|
| Random | Data files and manifests written by the Java implementation, which embed a UUID; metadata files named `<version>-<uuid>.metadata.json` | Negligible between independent writers. The residual risk is one writer reusing a name, for instance a retried task that regenerates the same one. | Enough entropy, and generation that does not repeat across retries |
| Content-addressed | Files named by a digest of their content, such as the configuration documents of the Puffin proposal | Benign: whichever writer wins wrote identical bytes | A digest that is verified on read |
| Coordinating | `v<N+1>.metadata.json` in file system tables, Delta Lake's numbered log entries, the numbered files of a discovery chain | Certain under contention, by design: the name is the lock | An atomic conditional create from the store itself |

Coordinating names cannot be replaced by random ones, since contention for one slot is their purpose. Random names, conversely, gain little from an atomic create. The specification says nothing about how files are named, so the uniqueness of random names is at present a habit of implementations, not a requirement.

A fourth case lies between these classes: names that become predictable by accident. The specification does not forbid writing a new file at a path that once held a different one, although it treats the path recorded in a location field as a file's identity (position delete files name their data file by `file_path`, and deletion vectors by `referenced_data_file`) and so depends on that not happening. Its only remark on the subject is that metadata JSON files are "not reused", offered as a reason readers may be stricter with them. A table dropped and recreated at the same location, a replication tool copying files into a location that already holds some, and location strings that alias one another (item 6) can all produce two different files under one name. These are the cases that the table-UUID check of the Puffin proposal's discovery chain, and the fifth binding rule, under which a reader refuses a file that disagrees with its metadata, are meant to catch.

The same race appears where nothing is being created. Orphan removal checks that a file is unreferenced and then deletes it, and a commit in flight can reference the file in between (item 5). A reader checks a file's recorded size and reads it later, and only a version token makes that check and the read one operation (item 4). Commit validation against the window since a writer's read base is the same pattern at the level of a whole commit.

Two candidate requirements follow:

1. Every file a writer creates has a name that is unique with overwhelming probability, by sufficient entropy, unless the name is content-addressed or coordinating.
2. A coordinating name is created only by an atomic conditional create provided by the store. A create implemented as an existence check followed by a write does not qualify.

### Storage capability tiers

As with file formats, the storage requirements divide naturally into tiers, and a store or a `FileIO` implementation could declare which it meets instead of having callers probe for interfaces:

| Tier | Requires | Enables |
|---|---|---|
| Read | Seekable or ranged reads, exact length, read-after-write consistency | Querying a table |
| Write | The read tier, plus exclusive create of new files | Appending and rewriting data |
| Commit | The write tier, plus an atomic primitive (rename, or create-if-absent) | Committing without a catalog, and discovery chains |
| Maintain | The write tier, plus complete listing with comparable timestamps, deletion and bulk deletion | Orphan removal, expiration, purge |

Read-only storage is as real a case as a read-only format. A table published over HTTP, or held in an archive, needs only the read tier, and the specification should be able to say that such a table is readable and that maintenance on it is undefined rather than unsafe.

### Object stores, guarantee by guarantee

The same guarantees can be stated for the major object stores. The table records what each store offers; the outcome where an operation needs a guarantee that is absent is the one given in the HDFS table below, which uses the same rows.

| Guarantee | S3 (general purpose buckets) | S3 Express One Zone (directory buckets) | Google Cloud Storage | Azure Blob Storage | Azure Data Lake Storage Gen2 (hierarchical namespace) |
|---|---|---|---|---|---|
| Atomic exclusive create | Holds, by `If-None-Match: *` on a put or on completing a multipart upload (since 2024) | Holds, as for general purpose buckets | Holds, by the precondition `ifGenerationMatch=0` | Holds, by `If-None-Match: *` | Holds, as for Blob Storage |
| Atomic rename | Does not hold | Holds within a bucket, by `RenameObject` (since 2025) | Does not hold for flat buckets | Does not hold | Holds |
| Complete, consistent listing | Holds (strong consistency since 2020) | Holds | Holds | Holds | Holds |
| Version token that detects replacement | ETag; a version id where versioning is enabled | ETag | Generation number | ETag | ETag |
| Store checksum comparable with a recorded hash | Full-object CRC-64/NVME by default; SHA-256 of a multipart upload is a composite | CRC-based checksums | CRC32C always; MD5 except for composite objects | MD5 only in some cases | As for Blob Storage |
| One canonical string per file | Does not hold (`s3`, `s3a`, `s3n`; access point aliases) | Does not hold, as for general purpose buckets | Largely holds (`gs`) | Does not hold (`wasb`, `wasbs`, `abfs`, `abfss`; blob and dfs endpoints) | As for Blob Storage |
| Clock shared by writer, store and maintenance | Does not hold | Does not hold | Does not hold | Does not hold | Does not hold |
| Deleted files recoverable | Only with versioning | Not by default | Holds by default (soft delete, seven days unless changed) | Only with soft delete or versioning | As for Blob Storage |
| Unbounded number of files | Holds | Holds | Holds | Holds | Holds |

Three points qualify the table:

- **A guarantee of the store is not a guarantee of the stack.** Every store in the table now offers an atomic exclusive create, yet none of the Java implementation's object store `FileIO`s uses it. `S3OutputFile.create`, `GCSOutputFile.create` and `ADLSOutputFile.create` each check for existence and then write, and the output streams behind them set no write precondition (no `If-None-Match` on S3 or Azure, no `doesNotExist` or generation match on GCS), so the guarantee does not reach Iceberg through any of them. The capability that matters is the one the `FileIO` exercises, which is why declarations are per pair of `FileIO` and substrate.
- **Timestamps are not uniform even within one store.** The last-modified time of an S3 object completed by multipart upload is reported to be the time the upload was *initiated*, not completed. A long upload can therefore look older than orphan removal's threshold before any commit references it, which narrows the margin that threshold is meant to provide.
- **Recoverability is a setting, not a property.** Versioning and soft delete are enabled per bucket or account, default differently across stores, and can be changed under a table without its knowledge, which is another way the substrate moves a table between outcomes over time.

### HDFS

HDFS figures in this note in two ways: as a storage layer that Iceberg tables are expected to run on, and as the place where several of the problems above were first met and, in their own setting, solved.

#### HDFS as a conforming store

HDFS meets most of the requirements above more directly than object stores do, because its NameNode keeps one consistent namespace. Renames are atomic, listings are complete and consistent, and a new file written at a path that held another has a new inode id. Stated as fine-grained guarantees, in the form the degradation dimension asks for, HDFS looks like this:

| Guarantee | On HDFS | Outcome where an operation needs it |
|---|---|---|
| Atomic exclusive create | Holds | Coordinating names are safe |
| Atomic rename | Holds | File system tables can commit, although version 4 removes them |
| Complete, consistent listing | Holds | Orphan removal sees every file |
| Version token that detects replacement | Holds within one cluster only (the inode id) | Detection of out-of-band mutation degrades to the content hash, or to length alone, for copied tables |
| Store checksum comparable with a recorded hash | Does not hold by default | Verification costs a full read, unless the writer's hash uses an algorithm the cluster can report |
| One canonical string per file | Does not hold (nameservices, host and port, `viewfs`, the default file system) | Orphan removal must refuse unless equivalences are configured |
| Clock shared by writer, store and maintenance | Does not hold | Orphan removal depends on its age threshold, as on object stores |
| Deleted files recoverable | Does not hold for Iceberg's deletes, which bypass the trash | Operational cost only |
| Unbounded number of files | Does not hold (NameNode memory) | Sidecars with one subject each degrade the cluster; sidecars with many subjects are preferred |

The rows that do not hold, and the question of where a guarantee comes from, need care in practice:

- **Guarantees come from the file system, not from the interface.** Iceberg's `HadoopOutputFile.create` calls `FileSystem.create(path, false)`, which HDFS's NameNode checks and applies atomically. The same Iceberg code over another Hadoop file system, such as S3A, has historically been a check followed by a write. A `FileIO` built on the Hadoop interface therefore does not have one storage contract; it has the contract of whichever file system is behind it, and the capability tiers would have to be declared per file system.
- **Version tokens.** The inode id is an exact version token within one cluster and means nothing outside it. Recording it would detect replacement in place, but it would not survive a copy to another cluster or to an object store.
- **Content hashes.** HDFS's own `getFileChecksum` defaults to an MD5 of per-block MD5s of CRCs, which depends on the block size and so cannot be compared between clusters with different settings, or with any object store. A block-independent composite CRC mode exists in recent Hadoop releases. A content hash computed by the writer avoids the question.
- **Location identity.** The same file can be named with a nameservice, with a NameNode host and port, through `viewfs` mount tables, or with no authority at all when it lies on the default file system. These are the aliases that the orphan removal options for equal authorities exist to reconcile.
- **Clocks.** Modification times come from the NameNode's clock, and orphan removal compares them with a cutoff from the clock of the machine running it, as on object stores.
- **Deletion bypasses the trash.** `HadoopFileIO` deletes with `FileSystem.delete`, which does not move files to the HDFS trash, so the recovery that operators of HDFS clusters may expect is not available for files Iceberg removes.
- **The number of files.** The NameNode holds the whole namespace in memory, so the number of files a cluster can hold is limited. Every sidecar adds to that count. On HDFS this is an argument for sidecars that serve many subjects, as Puffin files can, and it is a cost that per-file sidecar designs should state.

#### Namespace contention, historically

Iceberg's file system tables were designed around HDFS. A file system table commits version *N*+1 by writing its metadata to a temporary file and renaming it to `v<N+1>.metadata.json`, which HDFS performs atomically and refuses if the target exists; `HadoopTableOperations` then writes a `version-hint.text` file, again by rename, and a reader that distrusts the hint lists the metadata directory for the highest version. The coordinating name is the lock, and the NameNode is what makes it one.

The protocol did not survive the move to object stores, which offered neither atomic rename nor, until recently, conditional create. The specification now calls the scheme unsafe on object stores and local file systems and deprecates it, and version 4 removes it. Two lessons carry over:

1. **Contention moved to whatever owned the namespace.** On HDFS every commit was serialized by the NameNode. Without it, the role passed to catalogs, first through the Hive Metastore's locks and later through compare-and-swap. The Iceberg Hive catalog still takes a metastore lock by default and offers a lock-free mode that conditions the update on an expected table parameter. Locks held by failed writers, and the timeouts needed to recover them, were part of why compare-and-swap displaced them.
2. **A coordinating name is only as safe as the primitive behind it.** The same naming scheme was safe on HDFS and unsafe on S3, with no change to Iceberg's code. A protocol that relies on coordinating names, whether a file system table, a Delta Lake log or a discovery chain, has to name the primitive it needs and refuse to run where the primitive is missing, which is the second requirement under file names and races.

## Commit contract

Most bindings are established by a commit, and most degradations are produced by one, so the contract also has to say what a commit owes the table when it contends with others. Iceberg commits optimistically: a writer prepares a new version of the table metadata against a base version and swaps the table's pointer from the base to the new version. When the swap fails because another commit got there first, the writer retries against the new current version. Usually the contending commits touch different files and partitions, and a retry that simply re-applies the change is correct. The contract question is what happens when they do overlap.

### What the specification requires of a retry

The specification's sections on optimistic concurrency and on conflict resolution say that a failed commit "must retry the update based on the new current version", and that updates "verify the conditions under which they can be applied". They then list the conditions:

- Appends can always be applied.
- Replace operations, such as compaction, must verify that the files they delete are still in the table.
- Deletes of specific files must verify that those files are still in the table; deletes by expression can always be applied.
- Schema and partition spec changes must verify that the schema has not changed since the base.
- Operations on a table with constraints must recompute the constraint statuses.

Three things are left open:

- **Isolation is the writer's choice.** "Writers can select what to validate and can make different isolation guarantees." The specification sets no minimum.
- **The list predates deletion vectors.** Row-level operations that add deletion vectors are not in it, although two deletion vectors for one data file are a conflict in the plainest spatial sense.
- **It checks preconditions, not interference.** A concurrent commit can leave every stated precondition true and still change what the retried mutation ought to have done. If a compaction rewrites data file F while a concurrent row-level delete adds a deletion vector against F, every file the compaction removes is still in the table when it retries. A retry that re-applies the compaction drops the deletion vector along with F, and the deleted rows reappear in its output.

### What enforces it

The Java implementation re-runs validation on every attempt: `SnapshotProducer.apply()` refreshes the table and calls `runValidations` against the new parent, and operations such as `BaseRowDelta` and `BaseRewriteFiles` examine the commits between the writer's starting snapshot and that parent, restricted by a conflict-detection filter. That filter is Iceberg's notion of spatial overlap. Several of the checks are enabled by flags that the engine sets according to the isolation level it chose. For equality deletes there is also a fix that does not depend on the writer: a rewrite can commit its output with the data sequence number of its starting snapshot, so a concurrent equality delete still applies to the output. Deletion vectors and position deletes name a data file by location and do not benefit.

Nothing outside the client enforces any of this. The component that performs the swap checks the pointer and nothing else:

| Swap mechanism | What performs the check | What it can verify |
|---|---|---|
| REST catalog | A server | The `assert-*` requirements of the request, which compare identifiers the client supplies. A server can read manifests, so in principle it could validate content, but the REST specification does not ask it to. |
| Hive Metastore, Glue, JDBC, Nessie | A service or database performing check-and-put | That the pointer still names the base |
| File system tables | The storage, by atomic rename | That the next version slot is free. The scheme is deprecated, described in the specification as unsafe on object stores, and to be removed in version 4. |

A client that rebases without validating, and asserts the new parent as its base, passes every one of these checks. Readers cannot detect the result afterward either: a deletion vector whose data file is no longer live is ignored, and the rewritten file looks like any other. A writer that skips validation, in any implementation, therefore produces a table that is well formed and wrong, which is the outcome the degradation dimension of this note exists to exclude, reached through writer conformance alone.

### Recording the read base

Designating a base snapshot is not in itself enough; it is what `assert-ref-snapshot-id` already does, and it yields only the trivial check that nothing at all has changed. What is lost today is the distinction between two snapshots. The *read base* is the snapshot whose contents the mutation actually read and on which its correctness is contingent. The *commit parent* is the snapshot it is finally committed on top of. They differ after every retry, and only the commit parent is recorded: the Java implementation tracks the read base during the commit and then discards it.

Recording the read base fixes a window of intervening commits, from the read base to the commit parent, that anyone can inspect later. For the conflicts that corrupt a table silently, the mutation's dependence on those commits can be read from the commit itself:

- A compaction or a copy-on-write update depends on the data files it removes. An intervening commit that added a deletion vector or position delete against one of them is a conflict.
- A row-level delete depends on the data files its deletion vectors reference. An intervening commit that removed one of those files, or added another deletion vector for it, is a conflict.

Predicate-level conflicts are different. An `UPDATE` whose predicate matched nothing in some data file leaves no trace of that file in its commit, so a concurrent append of rows matching the predicate cannot be detected from the commit alone. Detecting it requires the writer to declare its predicate, as the Java conflict-detection filter does internally. This divides along the existing choice of isolation level: snapshot isolation needs only the read base, and serializable isolation needs the declared predicate as well.

The read base helps in two further cases:

- **Commits of unknown outcome.** A commit that times out may or may not have been applied, and a blind retry of an append can duplicate data. A mutation that carries its read base and an identity lets a catalog, or the client, recognize that it has already been committed.
- **Expiration.** If snapshots between the read base and the commit parent have been expired, the commit can no longer be validated by anyone. With the read base explicit, that case becomes a defined refusal, and a long-running compaction has a concrete reason to protect its read base from expiration until it commits.

### Commits without a catalog server

The role of the catalog in a commit is to provide the atomic swap, and nothing in the role requires a server. A protocol that uses only storage can provide it with an atomic create-if-absent of the next numbered metadata file, as Delta Lake does with its log. The [decoupled index metadata note](../proposals/decoupled-index-metadata.md) and the discovery chain of the Puffin proposal (its Section 6.5) are already designs of this kind. Version 4 as drafted removes the rename-based scheme without providing a conditional-write replacement, so it currently has no catalog-free commit protocol.

Such a protocol can enforce only what its primitive checks, which is the trivial condition that the base is still current. It remains a full participant in the contract if commits record their read base and their own changes imply their dependencies, because validation can then move from commit time to any later time: an auditor, a maintenance process, or in the extreme a cautious reader can check a commit without trusting the client that made it. Two qualifications apply. The primitive must be the store's own conditional write, since the Java `FileIO` create is not atomic on S3 (see the storage section). And a design whose readers validate, such as the discovery chains of the Puffin notes, tolerates an unvalidated commit at the cost of coverage, while a required sidecar attached to an existing data file has no such reader check to fall back on: it is a replace-like mutation, which conflicts with a concurrent rewrite of the file and with a concurrent sidecar of the same type, and it needs the same validation as a deletion vector.

### Candidate requirements

1. A retried commit re-validates against every commit between its read base and its new parent, and the specification's list of conditions covers every kind of mutation it defines, deletion vectors and sidecars included.
2. Every snapshot records its read base alongside its parent.
3. A commit whose read base can no longer be resolved, because intervening snapshots have been expired, is refused.
4. A writer that claims serializable isolation records the predicate its validation used.
5. A commit request may carry an identity, so that the swap mechanism can recognize a retried commit that has already been applied.
6. The specification defines at least one commit protocol that uses only storage, based on conditional create, to replace the rename-based scheme that version 4 removes.

## Degradations in practice

This section applies the outcomes of Appendix C to concrete combinations.

### Examples

| Combination | What degrades | What stays correct | How it changes over time |
|---|---|---|---|
| A predicate on a column stored in Avro data files | Pruning: Avro data files written by the Java implementation carry no column metrics, so neither files nor units can be skipped | Every row is read and filtered | Restored as compaction rewrites the data files into a format with metrics |
| A path predicate on a `variant` column in ORC or Avro data files | Pruning on variant paths, since neither format shreds variants and no variant bounds are produced | The engine evaluates the path on every row | As above |
| A pushed-down filter that a format's reader cannot evaluate | Record-level filtering inside the reader | The engine applies the filter again | Restored when the reader gains support, with no change to the data |
| Statistics that are not tight in version 4 | Answering aggregates such as `count` or `max` from metadata | The aggregate is computed by reading | Restored by recomputing statistics, without rewriting data |
| ORC data files in a version 4 table, if writes are dropped | Writing: updates by copy-on-write and compaction produce data files in another format | Reads, and row-level deletes through deletion vectors | Decays to nothing as the data files are rewritten; the proposed list of live formats would show when |
| An index blob that is missing, unreadable or built under an unresolvable configuration | Index coverage for the affected data files | The data files are planned as though unindexed (Puffin proposal, Section 7.4) | Restored when an indexer rebuilds the blob |
| A sidecar the reader does not support | Whatever the sidecar served, if its reliance is ignorable | The data file is read without it; if the sidecar is required, the reader must refuse instead | Restored when the reader gains support |
| A data file that uses a format extension the reader lacks | Nothing degrades gracefully; this must be a refusal | n/a | With a per-file record of extensions, the refusal can move from mid-scan to planning |
| A value or physical type outside a reader's supported range | Must be a refusal, under the second correctness rule for type mappings | n/a | n/a |

### Transitions through time

A combination does not stay in one class. It moves when any of its parts changes, and the dangerous moves are the ones in which a survivable degradation becomes a failure, or worse, because of a change to a different part:

- **The schema changes.** A column added with an initial default is read from older data files by supplying the default. If a format's reader cannot do that, a table that was degraded only on writes becomes one that some readers cannot read at all. Whether such a reader refuses or returns nulls decides whether the transition is to a refusal or to a wrong answer, and the contract should require the former. The Java ORC integration does refuse: when a projected column is absent from an ORC data file and has an initial default, `ORCSchemaUtil` throws an `UnsupportedOperationException` ("ORC cannot read default value for field …"). The refusal comes only when a scan reaches such a file, however, so the transition is to a refusal discovered mid-scan, which a declared read tier would let a reader make at table load instead.
- **The specification version changes.** Upgrading to version 4 would turn ORC data files from writable to read-only in a single commit, moving every ORC-backed table into the degraded class for writes.
- **An implementation joins.** A reader with a smaller read tier than the table's other readers sees refusals, and possibly different degradations, for the same data.
- **Data turns over.** Compaction moves data files out of deprecated formats and out of degraded combinations, so many degradations decay without any decision being taken.
- **Storage changes beneath the table.** A lifecycle policy, a credential change or a migration between stores can move a combination between classes; the storage rows below give examples.

### Degradations contributed by storage

Storage contributes its own rows to the examples above:

| Storage lacks | What degrades | What stays correct, or must refuse |
|---|---|---|
| Ranged or vectored reads | Footer and column reads take more requests | Reads |
| Prefix listing | Orphan removal is unavailable, and storage leaks until someone removes files by hand | Everything else |
| Bulk deletion | Expiration and purge run slowly | Everything else |
| Credentials that reach a discovery root | Whatever the discovered sidecars provided, such as index coverage, for readers holding vended credentials | Reading without those sidecars, which a discovery chain may hold only if they are ignorable |
| Credentials that reach a required sidecar | Nothing can degrade here | The reader must refuse the data file |
| Atomic create-if-absent | Commits that depend on it cannot be made safely | The commit must be refused; a check followed by a write is not a substitute |
| Agreement on which locations are equal | Orphan removal cannot run safely | Orphan removal must refuse rather than guess, since a wrong guess deletes live files |
| Immediate availability (an archived object) | A read takes hours, if it succeeds | The reader must fail or wait, and never skip the file |

Storage also moves combinations between classes over time. A lifecycle policy can archive a file a year after it was written, so a table that was healthy becomes one whose old snapshots, or old partitions, can no longer be read promptly. A change of credentials or of bucket policy can withdraw access to a discovery root and turn full coverage into none without any change to the table.

Location identity matters most during a migration between stores, or between scheme spellings for one store, when old and new strings for the same objects coexist in metadata.

### Candidate requirements

1. A capability declaration, whether for a format, a sidecar type, an implementation or a store, carries a declared outcome for each gap, in the four parts set out in Appendix C. A gap that can produce a wrong answer is a defect, and cannot be declared away.
2. Degradations are observable. A planner reports which applied to a scan, such as data files without metrics, blobs ignored and filters not pushed down, so that the cost of a degraded combination can be measured instead of guessed.
3. A table change that would move a live data file from a degraded combination to a refused or incorrect one is detected at commit time, using the list of live formats and the type ranges of their read mappings.
4. Storage guarantees are declared per substrate, for each pair of `FileIO` and underlying file system, and a planner or maintenance process reports the degradations a table incurs from the substrate it is on.
5. Degradations that decay with turnover can be tracked. A per-format count of live data files and bytes would show, for example, how much of a table remains in a deprecated format.

## Alignment with Puffin blob types

The [Puffin blob proposal](../proposals/puffin-blob-types-and-compaction.md) asks the same strategic question about a different kind of file: what it would take for parties outside the Iceberg project to add a kind of structure that engines discover, that is safe for engines that do not understand it, and that tools which did not write it can maintain. Set side by side, the two answers are made of the same parts, several of which are proposed for one ecosystem and missing for the other. Section numbers in the table refer to the proposal.

| Ingredient | Puffin blob types | Data file formats |
|---|---|---|
| An identifier space that others can extend | Proposed: namespaced identifiers and a two-tier registry (proposal, Section 4) | Missing: `file_format` is a closed list in the specification, and `FileFormat` is a closed enum in the Java API |
| A declaration of capabilities that engines read rather than assume | Proposed: a machine-readable descriptor per type (Section 5) | Mostly missing: a `splittable` flag; the read and write tiers of this note would supply the rest |
| A one-directional correctness rule | Proposed: a blob may only exclude unless its descriptor says otherwise, and may err toward false positives only (Section 6.2) | Implicit: a pushed-down filter may return extra rows and must never drop one; this note asks that it be stated |
| Run-time discovery of implementations | Proposed: a `ServiceLoader`-based interface (Section 9) | Partial: the File Format API registry exists, but discovers models from a hard-coded list of class names |
| A defined outcome for implementations that do not know a member | Proposed: unaware readers and writers (Section 11) | Missing today: a reader lacking a format learns this partway through a scan |
| Graded support short of full support | Partly proposed: a reader may ignore a blob it does not understand | Proposed here: a read tier and a write tier, with read-only support as a first-class state |
| Obligations on maintenance | Proposed: compaction semantics (Section 8); reachability under orphan removal and expiration remains open (Section 14.1) | Sketched here: what rewrites, deletes and statistics recomputation require of a read-only data file |
| A table-level declaration of what is present | Proposed: a list of discovery roots in table metadata ([decoupled index metadata](../proposals/decoupled-index-metadata.md)) | Proposed here: a list of the data file formats live in the table |
| A declared outcome for every gap | Implicit and uniform: an unknown or unusable blob is always ignored, so every gap degrades coverage | Proposed here: degrade or refuse, declared per capability, per sidecar type and per instance |
| Supplementary data stored beside a data file | The subject of the proposal: data-file-scoped blobs, all ignorable | Proposed here: format sidecars, some of which are required to read the data file |
| A conformance check | Missing | Partial: the format test kit exercises every registered format but records gaps without grading them |

The contents of the two columns differ, since a blob descriptor and a format capability declaration describe different things, but the patterns that produce them recur. Three recur closely enough that they could be specified once and instantiated twice, and the note on [versioning by feature](feature-versioning.md#features-in-general-kinds-in-particular) takes the first two further, specifying them once over features of every kind.

### A namespace

Both ecosystems need identifiers that a party outside the project can mint without a vote and without colliding with anyone else, and that carry a major version so that an incompatible revision is a new name. The grammar of Section 4.1 of the proposal (an optional dotted namespace, a name and a `-v` major suffix) and its two-tier registry would serve `file_format` as they stand. The same question recurs one level down, inside a format, wherever a format admits extensions that a reader must implement to decode a data file. Parquet's encodings and logical types are a closed list amended through parquet-format; Vortex allows encodings to be added. Iceberg does not need to govern either list, but it does need to know whether a reader can decode a given file, which leads to the second pattern.

### A global, versioned type descriptor and a per-instance description

The proposal separates what is true of every blob of a type, published once in a versioned descriptor, from what is true of one blob, recorded beside it. The same separation fits formats:

| Level | Puffin blob types | Data file formats |
|---|---|---|
| Type, published once and versioned | The descriptor (Section 5): scope, inference, behavior under deletes and compaction, which configuration parameters play which roles | A format descriptor: the read and write tiers it meets, its read and write type mappings with the parameter ranges over which each type is supported, the version of that mapping, its addressable units, whether it is splittable, and the extensions a reader may need |
| Instance, recorded per file in production | The blob's properties in the Puffin footer: its bound data file, its configuration reference, any input expressions | Today, only the `file_format` string in the manifest entry. A production setting would also want the format extensions the data file uses, so that a planner can tell before opening it whether this reader can decode it, and the writer's identity and version |

The per-instance side is the larger gap. A manifest entry says that a data file is Parquet, but not whether it uses an encoding that is still in preview, nor whether its writer is one whose statistics are known to be wrong. A reader learns each of these from the footer, after it has already committed to the file, or does not learn it at all.

### Configuration paranoia

Section 7 of the proposal rests on the observation that a type identifier fixes the format of a payload but not the parameters it was built under, and that some of those parameters are needed to interpret the payload correctly. It classifies parameters as needed for evaluation, needed for composition, or recorded for provenance; it requires a reader that cannot verify an evaluation parameter to ignore the blob; and it provides revocation by digest for configurations later found to be defective. Data file formats have their own history of each case:

- **Evaluation.** Parameters that change what the bytes mean have repeatedly lived outside the bytes. Parquet's INT96 timestamps do not record whether they were adjusted to UTC, and Spark's change of calendar in 3.0 left dates and timestamps written by earlier versions needing a rebase that only a footer key written by later versions can indicate. Modern formats record most such parameters in the data file, as a well-designed blob type keeps its own in the payload, but the specification does not require it.
- **Composition.** A compaction that merged data files by concatenating row groups, without decoding them, would require the inputs to agree on their schema and on every encoding and codec they use, in the same way that two Bloom filters must agree on a seed to be combined.
- **Provenance and revocation.** parquet-java ignores binary column statistics from writers older than a version that fixed a statistics bug, identifying them by the `created_by` string in the footer. That is revocation by provenance, implemented privately in one reader. Iceberg's manifest metrics are copied from such footers at write time and trusted by every planner thereafter, so a defective writer's statistics become wrong exclusions at the table level. A table-level revocation list naming writer versions whose metrics must be ignored, or whose data files must be rewritten, would be the format analogue of `puffin.config.revoked`, and it would need the writer identity in the manifest entry that the previous subsection asks for.

### Storage

The same patterns recur in the storage layer, though more faintly:

- **Identifier space.** A location's scheme selects the `FileIO` implementation (through `ResolvingFileIO` in the Java implementation), and that mapping is configuration rather than specification.
- **Declared rather than probed capabilities.** Optional `FileIO` interfaces play the role of a descriptor today, but a capability that is detected with `instanceof` cannot express a partial guarantee, such as a `create` that is exclusive but not atomic.
- **Per-instance description.** The version token of item 4 under storage is to a stored object what the per-file format description is to a data file: information recorded beside the reference, so that a reader can verify what it is about to trust.

### What could be shared

Given those patterns, five pieces might share a specification as well as an implementation, with the contents kept separate:

1. **The identifier grammar and registry rules**, applied to blob types and to `file_format` alike, with separate registries.
2. **A descriptor envelope**: identifier, major version, schema version and digest, with a body whose schema differs between blob types and formats. The note on [versioning by feature](feature-versioning.md#features-in-general-kinds-in-particular) generalizes both pieces to features of every kind, with blob types and formats as two specializations.
3. **The vocabulary of declared outcomes**, so that a blob type, a format, a sidecar type and a store describe their gaps in the same terms, and a planner can report degradations from all of them together.
4. **Table-level declarations.** A list of discovery roots and a list of live formats and required sidecar types are both facts that readers and maintenance need without scanning, and one mechanism in version 4 table metadata could carry both, together with the revocation lists.
5. **Predicate evaluation.** The public inclusive evaluator proposed above would serve data-file-scoped blobs as readily as manifest metrics and format row groups.

The parallel also helps each argument. A reviewer who accepts that blob types need an identifier space, a descriptor and a rule for unaware readers has already accepted the outline of a format contract, and a reviewer persuaded that version 4 needs a bar for writable formats has accepted that capabilities should be declared rather than inferred from a format's name.

## Open questions

- Should `file_format` become an open identifier space with a registry, or remain a closed list amended by vote?
- Who maintains the type mapping for a new format: Iceberg's Appendix A, or the format project, with Iceberg citing a version?
- How should the parameter ranges of a type mapping be written down? They should ideally be machine-readable, so that a table can check a schema change against the formats it contains, but settling on a notation for constraints such as P ≤ 18 is deliberately left for later. Until then a prose statement per type, in a fixed position in the descriptor, would at least make the ranges findable.
- Should the format test kit become the normative check for each tier, and should it be portable across language implementations?
- Should the specification distinguish capabilities of a format (Vortex can store variant shredded) from capabilities of an implementation (this Rust library cannot yet write it), and where would the latter be declared?
- `FileFormat` currently includes `PUFFIN` and `METADATA` alongside the data formats. Should data formats, delete formats and metadata formats be separate enumerations, or separate tiers of one?
- Is the group scope for sidecars worth having before multi-file blobs are settled in the proposal, or should shared dictionaries and the like wait for that answer?
- Should a required sidecar be allowed for a format that is merely readable, or only for a writable format whose writers are obliged to produce it?
- Should an unsupported type, or a supported type outside its supported range, fail at read time, at table load, or at the schema change that introduced it?
- Should the specification's file system section be extended in place, or should storage requirements become a separate part of the specification with tiers, as this note suggests?
- Should a version token and a content hash become optional fields of every tracking entry, for data files, delete files, column files and Puffin files alike, and which hash algorithms should be admitted?
- Who is responsible for location normalization: the specification, by defining equivalence, or each catalog, by refusing to record non-canonical locations?
- Should a table be able to declare files that storage lifecycle policies must not act on, or is that the operator's business alone?
- Should a reader be required to fail, or allowed to wait, when it meets a file in an archival class?
- Should commit validation stay a client obligation, with the read base recorded for audit, or should a catalog that already reads manifests be expected to validate commits itself?
- Should the graph evaluation of verdicts (Appendix B) become normative, with conforming implementations required to behave as if they followed it, and if so, how much of each edge's evaluation rule belongs in the specification?
- Should admissibility of expressions in defaults, and elsewhere in table metadata, be decided by a classification of what an expression reads and who evaluates it, with functions declaring their class, instead of by rules about particular constructs?

## Unverified claims

- In the object store table: that S3 conditional writes cover multipart completion; that S3 Express directory buckets and ADLS Gen2 support the conditional create and checksum modes shown; Azure's Content-MD5 behavior; Azure soft delete defaults; and that GCS location strings are effectively canonical. The S3 multipart last-modified behavior rests on user reports, not on AWS documentation.

- That S3A's `create` without overwrite has historically been a check followed by a write, that recent Hadoop releases offer a block-independent composite CRC mode for `getFileChecksum` (believed to be since Hadoop 3.1), and that lock leaks drove the move from Hive Metastore locks to compare-and-swap are from memory.

- That Spark task retries and speculative attempts never regenerate the same data file name in the Java implementation is believed, from the task attempt id in the name, and was not checked.

- Whether the REST catalog specification has gained an idempotency key for commits, which would partly answer the fifth commit requirement, was not checked.
- What the Java implementation does when a writer's starting snapshot has been expired before it commits was not checked.
- That Delta Lake commits by put-if-absent on its log, using S3 conditional writes directly and formerly requiring DynamoDB for coordination on S3, is from memory, as is the claim that Glue's swap is a version check.


- The INT96, calendar-rebase and parquet-java `created_by` examples are from memory: Spark's rebase footer key (believed to be `org.apache.spark.legacyDateTime`), the Spark 3.0 calendar change, and parquet-java's handling of statistics from old writers (believed to be `CorruptStatistics`, after PARQUET-251) all need checking before this note is shared.
- That Vortex admits added encodings, and how it names them, is from memory.

- The objections and the "converge in the reference implementation" preference are quoted from the October thread; whether they still represent their authors' positions after Kevin narrowed the question to writes is unknown.
- That Vortex was the motivating case for the File Format API rests on the Iceberg blog and secondary coverage, not on the API's design discussion.
- The gap list for ORC and Avro is Kevin's, citing the format test kit at commit `6951360`; it was not rechecked against `26c9544`.
- The date S3 became strongly consistent (December 2020) is from memory.
- That some object stores have historically returned incomplete listings, and the behavior of archival storage classes on read, are stated generally and should be made specific before this note is shared.

## Appendix A: Terms

The note concerns several kinds of file, and a statement that is true of one kind is often false of another, so "file" is qualified wherever the kind matters. The terms follow the table specification where it has one:

| Term | Meaning |
|---|---|
| Data file | A file that contains rows of the table |
| Delete file | A file that encodes deleted rows by position or by value (version 3 and earlier) |
| Content file | A data file or a delete file, as in the specification |
| Metadata file | A table metadata file, a snapshot root file (a manifest list, or in version 4 a root manifest), or a manifest |
| Sidecar | A file tracked separately from the subjects it describes and meaningful only relative to them, defined in Appendix D; Puffin files, column files and statistics files are examples |
| Stored object | Anything the storage layer holds under a location, of whatever kind; the storage section uses this when a statement holds for every kind |
| Unit | A part of a data file that a reader can address and skip, such as a row group, a stripe or a page |
| Type descriptor | Metadata about a kind of thing (a blob type, a data file format, a feature), published once, revised rarely and compiled into implementations; see [versioning by feature](feature-versioning.md#type-descriptors-and-instance-descriptions) |
| Instance description | Metadata about one instance of a type, written with it and living as long as it: a reference to its type, its binding to its subject, its configuration reference and its provenance |

A bare "file" is used only where the statement holds for every kind, or where the kind has just been named.

## Appendix B: Dimensions of the contract

The contract is easier to state, and eventually to encode in descriptors, if the questions it keeps asking are named once. Each dimension below recurs in several sections, and the table says what each means and where the note treats it in detail.

| Dimension | What it means |
|---|---|
| **Outcome** | What happens when something a component needs is missing or cannot be verified: the operation either goes ahead in a worse form (it degrades) or stops (it refuses), and a wrong answer is never an allowable outcome. A declared outcome says what degrades, or how far a refusal reaches and how early it can be known. Treated in Appendix C and in degradations in practice. |
| **Reliance** | Whether a component that meets something it does not understand, such as a sidecar, a metadata field, a transform or a requirement handed down from a plan, may proceed as though it were absent (ignorable) or must stop (required). It can depend on the component's role and on what an operation touches. Treated below, and for sidecars in the section on sidecars for format integration. |
| **Assertion polarity** | What a reader may conclude from a structure when it does use it: only that rows are absent (negative), or also where rows are or what they contain (positive). Positive assertions need stronger checks before they are trusted. Treated in the Puffin proposal, Sections 6.2 and 6.3. |
| **Scope** | How much of the table a sidecar, a binding or a refusal is about, from a unit within a data file through a data file, a group of data files, a manifest and a snapshot to the whole table. Treated in sidecars. |
| **Reference** | How a reader finds a sidecar: named by its subject's own metadata and committed with it (referenced), or found by another route such as a discovery chain (discovered). Only referenced sidecars can be required. Treated in sidecars. |
| **Capability tier** | How much of the contract a format or a store supports, as a level that includes the levels below it: read or write for formats, and read, write, commit or maintain for storage, with parameter ranges for each type a format maps. Treated in the formats and storage parts. |
| **Memorialization** | What the metadata records about a file beyond its location, so that a reader can tell that the file is the one that was committed: its length, a version token from the store, or a content hash. Each detects different changes and survives different kinds of copying. Treated in bindings. |
| **Isolation** | Which concurrent changes a retried commit must detect: only changes to the files it read or replaced (snapshot isolation), or also new rows that match what it read (serializable, which needs the commit's predicate). Treated in the commit contract. |
| **Tolerance** | Which degradations an operation will accept, as stated by whoever runs it: any, only a listed few, or none, so that an interactive query and an audit can reasonably differ. Treated in requirements flow down, verdicts flow up. |
| **Carry-forward** | What a rewrite does with a sidecar whose subject it replaces, especially one it does not understand: copy it unchanged, drop it, or rebuild it. Not yet settled here; the Puffin proposal's compaction rules (Section 8) answer it for blobs. |
| **Ownership** | Whether a table may physically delete a data file it tracks once no snapshot references it (owned), or may only stop referencing it (borrowed). Recorded per file, since one table can hold both. Treated in read-only support, under compaction. |

The dimensions fall into two groups. Most describe stored objects: reliance (which also applies to fields, transforms and requirements), assertion polarity, scope, reference, memorialization, carry-forward and ownership. The rest describe components and operations: capability tiers describe formats and stores, isolation describes commits, and tolerance describes operations. Outcomes are computed where the two meet, on the edges of the graph described below.

One further axis conditions all the others without being a dimension of its own. The **substrate**, meaning the stack of format implementation, `FileIO` and storage beneath a read or a write, decides which guarantees hold, so most declarations are made per substrate rather than once.

The dimensions are not independent, and some of the note's rules are constraints between them. A required sidecar must be referenced. A discovered sidecar must therefore be ignorable. An unsupported required sidecar produces a refusal whose scope is the sidecar's scope. A positive assertion needs stronger verification than a negative one, because trusting a stale positive assertion loses rows.

### Reliance

Reliance is the most general of the dimensions. Whenever a component meets something it does not understand, whether a field in table metadata, a transform, a kind of sidecar, an extension inside a data file or a requirement handed down from a plan, it must either proceed as though the thing were absent or stop, and which is correct is a property of the thing, not of the component. The specification already makes this decision in many places, one at a time:

| What a component may not understand | What the specification says | Reliance |
|---|---|---|
| A table's `format-version` | Implementations "must throw an exception if a table's version is higher than the supported version" | Required |
| A table's UUID that does not match | Implementations must throw | Required |
| A partition transform | Readers may ignore the partition field when filtering, whose inclusive projection is then true; writers "are not allowed to commit files with a partition spec that contains a field with an unknown transform" | Ignorable for readers, required for writers |
| A sort order id | Assumed unsorted | Ignorable |
| A stats-only field of an unsupported type | Readers "must not fail", and the stats are ignored | Ignorable |
| A table statistics file | "Statistics are informational" | Ignorable |
| A deletion vector, or a column file the read projects | Applied; nothing in the specification contemplates a reader skipping either | Required, though never stated as such |

Two lessons follow from the table. Reliance depends on the role as well as the thing, as the partition transform shows: a reader can ignore an unknown transform at the cost of pruning, while a writer that ignored one would write files that misstate their partition. And where the specification does not state reliance, as for deletion vectors and column files, it is implied by the semantics, which is safe only for as long as every implementer infers the same thing. The contract would state it everywhere, by kind wherever possible and by instance where a kind cannot decide (see the reliance of sidecars, in the section on sidecars for format integration), and would let the requirements an operation passes down carry it as well.

### On structure

Two properties would make the dimensions much easier to use, and neither can be assumed yet.

1. **Unparameterized enumerations.** A dimension whose values are a short fixed list fits in a descriptor field and lets a planner or maintenance process switch on it. Most of the dimensions above are an enumeration at their core, but nearly every one carries parameters that the enumeration cannot hold: the fields a requirement covers, the ranges a type mapping supports, a hash algorithm, a group's members.
2. **Lattices.** If each dimension were ordered, with a join, declarations made at different layers could be combined mechanically. The rule that an instance may strengthen its type's reliance but not weaken it would become monotonicity, and the outcome for a read would be the join of the outcomes declared by its format, its `FileIO` and its substrate. Some dimensions come close: reliance (ignorable below required), isolation (snapshot below serializable) and the capability tiers (each including the last) are chains. Others resist. Outcomes of different kinds, a slower scan and an unavailable compaction, are not comparable, and scope is ordered by containment over subjects rather than over a fixed set of values. Memorialization is ordered by strength only for a given threat, since a CRC and a version token detect different things.

The note therefore treats both as goals. For now each dimension is stated as an enumerated core with parameters, and an order is claimed only where it is clear. The next subsection shows that composition does not depend on either goal being met.

### Evaluating a deployment as a graph

The dimensions need not be lattices for declarations to compose, if composition is moved from the dimensions to the arrangement they describe. A specific deployment is a graph of instances: a catalog, a table's metadata files, its snapshots and manifests, its data files and their sidecars, and the implementations that touch them, down to the `FileIO` and the store. Each operation traverses part of that graph, and for a read the part is close to a chain, fanning out at the manifests and the data files and branching to each data file's sidecars:

| Edge | What is checked, from the declarations at both ends |
|---|---|
| Catalog to table metadata file | The commit primitive the catalog provides; whether the reader's credentials reach the table location |
| Table metadata file to snapshot root file and manifests | The format version and manifest format the reader supports |
| Manifest entry to data file | The data file's format and the extensions it uses against the reader's read tier; the type ranges in use against the read type mapping; the memorialized length and hash against what the store reports |
| Data file to each sidecar | The sidecar's reliance, for the fields the read projects, against whether the reader supports its type; its binding against its subject |
| Reader to `FileIO` to store | The guarantees the read needs (ranged reads, a comparable checksum, credentials for a discovery root) against those the substrate provides |

Each edge is evaluated with the capabilities of both of its nodes taken into account, and yields a verdict:

1. **Safe.**
2. **Degraded**, with the ways it is degraded (a set, such as "no index coverage for these files" or "verification by length only").
3. **Refused**, with the reasons (a set, such as "required sidecar type T unsupported" or "type outside read range").

A further value, **undeclared**, is needed for a node that makes no declarations at all, such as an implementation that predates the contract. How an undeclared edge is treated is itself a rule of the contract, and it differs by edge: an undeclared reader of an ignorable sidecar can be treated as degraded, while an undeclared reader of a data file with a required sidecar has to be treated as refused.

The verdicts do form a lattice, whatever the dimensions do: safe lies below every degraded verdict, degraded verdicts are ordered by inclusion of their sets, every degraded verdict lies below every refusal, and refusals are ordered by inclusion of their reasons. The join is the union of sets. The verdict for an operation is the join of the verdicts of the edges it traverses, which is refused with every reason found if any edge refuses, degraded with every degradation found if any edge degrades, and safe otherwise. Keeping the reasons and the degradations, instead of collapsing them to a single outcome, is what lets a planner report them (the second candidate requirement under degradations in practice) and what lets an operator see which edge to fix.

The graph is notional in this note: a way of reasoning about verdicts, not a structure any implementation is asked to build. It may nonetheless want to become normative, in the form specifications usually give to an abstract procedure, by requiring that a conforming implementation behave *as if* it evaluated the graph. An engine would then be free to compute verdicts however it likes, caching them, deciding some at load time and others mid-scan, or never materializing a graph at all, provided that every operation it completes is one the procedure would have completed, with the degradations the procedure would have reported, and that every operation it refuses is one the procedure would have refused. Stating the requirement that way would make the verdict, rather than any particular mechanism, the thing a conformance test checks.

Three features of the graph matter for when a verdict can be computed:

- **Each operation selects its own subgraph.** A read traverses one snapshot's chain. A commit adds the edge to the catalog and, under the commit contract, the window of snapshots since its read base. Orphan removal traverses the whole reachability graph from every retained snapshot, together with the store's listing, and so is exposed to every edge at once.
- **Some edges can be evaluated early.** Edges whose declarations sit in table or snapshot metadata, such as the list of live formats and required sidecar types, can be evaluated at table load or planning time. Edges that depend on a particular data file's footer or a particular object's checksum can be evaluated only during the scan. Moving declarations upward in the graph moves refusals earlier, which is the purpose of the table-level declarations proposed throughout the note.
- **The graph changes over time.** Every commit changes it, and so do changes to the substrate that no commit records, such as a bucket policy or a lifecycle rule. A verdict therefore holds for a graph at a moment, and the transitions described under degradations in practice are changes of verdict on particular edges.

### Requirements flow down, verdicts flow up

Several edges cannot be evaluated from the declarations of their two nodes alone, because the verdict depends on the operation. A column file is required only by a read that projects one of its fields. Whether an index blob matters depends on the predicate. Whether verification by length alone is acceptable depends on what the query's answer will be used for. The properties that decide such edges originate in the query plan, or in the write plan, and have to be carried down to the component that evaluates the edge, which is often the format reader or writer at the bottom of the stack.

For a read, the properties that bear on reliance and outcome include:

- the projected fields, including metadata columns such as `_pos` and `_row_id`, which decide which per-field sidecars are required;
- the predicate, which decides which sidecars and units can affect the answer;
- the snapshot or range of snapshots read, which decides which bindings apply;
- whether an exact answer is required, or an approximate one is acceptable, as for sampling or approximate vector search;
- the operation's tolerance: which degradations it accepts, and which it would rather have refused, since an interactive query and an audit can reasonably answer that differently.

For a write they include the kind of operation (an append, a rewrite, a row-level change), the isolation level and any declared predicate, which sidecars of rewritten files are to be carried forward or rebuilt, and whether content hashes must be produced.

This is a layering violation, and a necessary one. A format reader ought not to need to know what query it is serving. It needs to here because past writers left breadcrumbs, such as deletion vectors, column files, type annotations and build configurations, whose relevance to a read depends on what the read asks for. The coupling is between a writer and a reader separated in time, and the query is what connects them. The violation can be kept narrow in two ways:

- **Resolve what can be resolved above the reader.** Where the breadcrumbs are visible in metadata, the planner can decide which are required for this read and pass down the resolved list instead of the query: the manifest entry lists a data file's column files and their field ids, so the planner can tell the reader which column files to apply. Only what the reader alone can see, such as annotations in a data file's footer or a format sidecar named from within the file, needs the query's properties themselves.
- **Pass a fixed vocabulary, not the plan.** What crosses the boundary should be a small, versioned set of properties defined by the contract, such as those listed above, so that the format layer depends on that vocabulary and not on any engine's representation of a plan.

The channels that carry these properties today were not designed for them. The table scan API carries a projection, a filter and string options. The REST scan planning request carries a snapshot or range, a selection of columns, a filter and a few flags, with no place for a tolerance. The File Format API's `ReadBuilder` and `ModelWriteBuilder` carry a projection, a filter and string properties, and their documentation tells readers and writers to "ignore configuration keys not known for them".

That last rule is the hazard. A property bag whose unknown keys are ignored is a downgrade channel: a requirement passed through it reaches a component that does not understand it and is silently dropped, which is the unaware-reader problem the Puffin proposal solves for blob properties by forcing a new major version. The requirements themselves therefore need reliance, as sidecars do:

1. A requirement passed down the stack is marked either may-ignore or must-understand.
2. A component that receives a must-understand requirement it does not recognize refuses the operation, and says which requirement it did not recognize.

The precedents are the ones cited for sidecars, and a few more: a CoAP option is critical when its number is odd, and a receiver must reject a message carrying a critical option it does not recognize; a SOAP header block can carry `mustUnderstand`, which obliges a receiver that cannot process the block to return a fault; and X.509 marks extensions critical in the same spirit.

Verdicts travel the other way. Each layer that evaluates an edge returns its verdict, with its reasons or degradations, to the layer above, and the planner joins them into the operation's verdict. Where planning happens in a catalog server, the planning response needs a place for the verdict, so that a client can report degradations it did not itself observe.

Two candidate requirements follow:

1. Reliance-relevant properties of an operation are carried from the plan to the readers and writers that evaluate edges, through channels that distinguish may-ignore from must-understand, and a must-understand requirement that is not recognized causes a refusal.
2. Every layer that evaluates an edge reports its verdict upward, and planning interfaces, the REST scan planning response among them, carry verdicts back to the client.

## Appendix C: Outcomes

Most of the gaps this note describes do not make a table unusable. They make some combination of query, schema, format and storage worse in a particular way. We therefore treat degradation as a dimension of the whole contract rather than as a topic within it: every capability, binding, type mapping, sidecar and storage tier in the note is stated together with what happens when it is absent. Every combination should end in one of three outcomes:

1. **Correct but degraded.** The query returns the right answer, and something else is worse: it runs slower, an index is not used, an operation such as compaction or statistics recomputation is unavailable, or storage leaks until a collector runs.
2. **Refused.** The reader or writer fails loudly, preferably at planning time or table load rather than partway through a scan.
3. **Wrong.** A query returns an incorrect answer without saying so. The contract exists to rule this outcome out.

The first two are both acceptable, and the difference between them is a matter of cost. What the contract should require is that every gap be assigned to one of them in advance, in a declaration, rather than discovered by users. A declared outcome has four parts:

| Part | Values |
|---|---|
| Outcome | Degrade or refuse |
| What degrades, for the first | Performance, index coverage, an operation that becomes unavailable, or cost |
| Scope, for the second | A unit, a data file, a snapshot or the table |
| Earliest point of detection | Commit, table load, planning or scan |

Several mechanisms in the body of the note are this dimension applied to one part of the contract. The two correctness rules for type mappings assign values outside a reader's range to refusal. The reliance metadata of sidecars is a declared outcome carried per type and per instance. The storage capability tiers say which operations become unavailable on which stores. The section on degradations in practice gives examples, describes how combinations move between outcomes over time, and sets out candidate requirements.

A declared outcome is conditional on more than the component that declares it. Whether a guarantee holds depends on the whole stack beneath a read or a write: the format, the implementation of the format, the `FileIO`, and the substrate behind the `FileIO`, whether an object store, HDFS or another Hadoop file system. The same Iceberg code can rely on an atomic create that one substrate provides and another does not. Declarations therefore take the form of fine-grained guarantees stated per substrate ("on this substrate, guarantee G does not hold"), and every operation that needs G inherits the outcome declared for its absence. The HDFS subsection of the storage section works one substrate through in this form.

## Appendix D: Sidecars

Much of what follows concerns information about a file that is stored outside it. Iceberg already has several kinds of such information, under different names, and version 4 adds another. This section gives them one definition, so that the rules later in the note can be stated once.

### Definition

A *sidecar* is a file that Iceberg tracks separately from the one or more *subjects* it describes, and whose content has meaning only relative to them. A sidecar contributes no rows to the table by itself.

Many kinds of sidecar have internal structure: a Puffin file holds blobs, each usually concerning one subject, and a position delete file holds rows naming different data files. The definition does not make that structure a concept of its own, because it does not apply to every conceivable sidecar. Rules that depend on it, such as a reference that locates one deletion vector within a Puffin file by offset and length, belong to the specification of that kind of sidecar, in the slots described below, and can be generalized later if several kinds turn out to share them.

Every kind of sidecar has the following six properties, and a specification of the kind states each one:

1. **Subjects and scope.** What the sidecar is about: a data file (or a unit within one), a group of data files, a metadata file such as a manifest, a snapshot, or the table. One sidecar may have many subjects of the same scope, as a Puffin file of deletion vectors for many data files does. A kind of sidecar declares which scopes it admits, and a sidecar of the kind at any other scope is invalid: writers must not commit one, and a reader that meets one refuses it. The specification already makes such declarations case by case, in that `deletion_vector` "may only be set if the tracked file is a data file" and `column_files` "may only be set if the tracked file is a data file or a v4 leaf manifest", and the Puffin proposal's descriptor gives every blob type a `scope` field for the same purpose.
2. **Binding.** How the sidecar identifies each of its subjects, under the protocol for bindings set out in Appendix E (for example, a data file's location and size, a snapshot id, or the table's UUID).
3. **Reference.** How a reader finds the sidecar. A *referenced* sidecar is named by its subjects' own metadata, such as a subject's manifest entry or the table metadata, and is committed together with that metadata. A reference names the sidecar; locating a part within it, as a deletion vector's offset and length do, is an addressing rule of the sidecar's kind (the first slot below), not a reference of its own. A *discovered* sidecar is found by some other route, such as an index manifest or a discovery chain, and may be committed independently of its subjects.
4. **Reliance.** What a reader that does not support the sidecar's kind must do: read the subject without it, or refuse. This is the reliance dimension of Appendix B applied to sidecars, and the section on sidecars for format integration treats it in detail.
5. **Encoding.** The physical format of the sidecar: Puffin, a data file format, or something else. The encoding is independent of the other properties.
6. **Lifecycle.** When the sidecar becomes garbage and which operations remove it. A sidecar is garbage once none of its subjects is live in any retained snapshot. For a referenced sidecar this follows from its subjects; for a discovered one it has to be specified separately. Whether a sidecar whose content is mostly dead may be rewritten to shed it, as the Puffin proposal's Section 6.4 allows for Puffin files, is a rule of the kind (the fifth slot below).

The dependency of a sidecar on its subjects is stated for the sidecar as a whole. It is bound to its subjects, referenced, kept and collected as a unit, even when its kind divides it into parts that each concern one subject. Kinds may add constraints on scope and on state in the slots below, such as how many sidecars a subject may have or what must hold between a sidecar's content and its subject, but they do not divide the dependency itself.

Stated per snapshot, the dependency is simple. At any snapshot a given sidecar either exists or does not, and if it exists, there is precisely one bitstream for it at its scope in that snapshot: the bytes its binding memorializes. A sidecar is never partly present, and never present in two versions within one snapshot. Changing a sidecar means writing a new one, which exists from the snapshot that commits it, while the old one ceases to exist in that snapshot and every later one. For a referenced sidecar, existence at a snapshot is decided by that snapshot's own metadata, so it is as reproducible as the snapshot is. For a discovered sidecar it is decided by the discovery chain's state when the snapshot is read, which can change after the snapshot was committed; two reads of one old snapshot may therefore see different discovered sidecars. That is tolerable only because discovered sidecars must be ignorable, and is one more reason they must be.

The six properties describe metadata files as well as sidecars. A manifest has a subject (a snapshot, or the data files it lists), a binding, a reference from its snapshot root file, a reliance (required), an encoding (Avro or Parquet) and a lifecycle, and the table metadata file is referenced from the catalog's pointer in the same way. In that sense every metadata file could be described as a sidecar, and the property schema should be shared by all of them.

We nonetheless keep the term for the narrower class, because metadata files and sidecars stand in opposite relations to reachability. A metadata file is *constitutive*: its content decides which other files belong to the table, and so which are reachable, and it is garbage when no retained snapshot reaches it. A sidecar is *dependent*: whether it belongs to the table is decided by its subject, and it is garbage when its subject is. In the graph that maintenance walks from the catalog's pointer, metadata files are the interior nodes and sidecars hang from the nodes they describe. Expiration, orphan removal and purge all turn on that difference, and a term that covered both would hide it. Data files are excluded for a different reason: they contribute rows by themselves.

### Slots for kind-specific rules

The six properties are common to every kind of sidecar, but most kinds also need rules that apply to them alone. Left unplaced, such rules end up wherever their author first needed them. The specification of a kind of sidecar should instead fill a fixed set of slots, each holding that kind's rules in one category, and say "none" explicitly where a slot is empty. The categories, with the rules existing kinds already have in each:

1. **Internal structure and addressing.** How the sidecar is divided into parts and how a reference locates one. A version 4 data file's `deletion_vector` offset and size "must exactly match" the offset and length in the Puffin footer.
2. **Cardinality.** How many sidecars of the kind a subject may have, and how they may overlap. "At most one deletion vector is allowed per data file in a snapshot", and a field id "may appear in `field_ids` of at most one column file" of a subject.
3. **Correspondence with the subject.** What must hold between the sidecar's content and its subject beyond the binding itself. A deletion vector's positions are row positions of its data file; how a column file's rows correspond to its base file's is still under discussion.
4. **Replacement and supersession.** What a new sidecar does to older ones and to other structures. A deletion vector written for a data file "must replace all previously written position delete files", and an update to a data file's deletion vector or column files produces a modified entry and a replaced entry.
5. **Behavior under rewrite.** What compaction and other rewrites of the subject do with the sidecar: carry it forward, merge it, rebuild it or drop it; and whether the sidecar itself may be rewritten to shed content whose subjects are no longer live. The Puffin proposal's Sections 6.4 and 8 fill this slot for blobs.
6. **Concurrent commits.** What a retried commit must check about sidecars of the kind. Two deletion vectors added concurrently for one data file conflict, and the Java implementation checks for it; the specification's list of retry conditions does not yet mention it.
7. **Encryption.** Whether and how the sidecar is encrypted with respect to its subject. A column file carries its own `key_metadata`.

A slot that several kinds fill the same way is a candidate for promotion into the common properties, which is how the definition can be generalized later without being guessed at now.

### Existing sidecars

The definition is meant to cover what Iceberg already has, so that new kinds of sidecar extend a familiar category instead of founding a new one:

| Sidecar | Encoding | Subjects | Reference | Reliance |
|---|---|---|---|---|
| Table statistics file | Puffin | One snapshot | Referenced from table metadata (`statistics`, by snapshot id) | Ignorable: the specification says that statistics are "informational" and that their support "is not required to read the table correctly" |
| Partition statistics file | A data file format | One snapshot; one row per partition | Referenced from table metadata (`partition-statistics`) | Ignorable |
| Puffin file of deletion vectors | Puffin | Any number of data files, one per deletion vector | Each deletion vector referenced: in version 3 by a delete-file entry giving the data file, blob offset and length; in version 4 by the data file's own `deletion_vector` field | Required |
| Column file (version 4 draft) | A data file format | One data file, or a version 4 leaf manifest | Referenced by the subject's `column_files` field, which lists the field ids it carries | Required, for readers that project any of those field ids |
| Position delete file (version 2) | A data file format | The data files it names by path | Tracked as a delete file in a delete manifest, with a lifecycle of its own | Required, although nothing removes it with its subjects, so it can outlive all of them |
| Puffin file of index blobs (Puffin proposal) | Puffin | Any number of data files, one per blob | Discovered, through index manifests | Ignorable |
| Format sidecar (this note) | Any | Any | Required ones referenced; ignorable ones may be discovered | Declared per kind, and possibly per instance |

Three observations follow from the table.

- **Version 4 already moves required sidecars into their subject's entry.** Deletion vectors were tracked as separate delete files in version 3 and become a field of the data file's entry in version 4, and column files are introduced the same way. This is the placement rule the note proposes for required sidecars generally, arrived at independently.
- **Reliance can depend on the query.** A column file is required only by a reader that projects one of the field ids it carries; a reader projecting other columns can ignore it. Reliance is therefore a property of a sidecar relative to a read, not of the sidecar alone, and the reliance metadata proposed later should be able to say so.
- **The subject is not always a data file.** Column files may belong to a version 4 leaf manifest, and statistics files belong to a snapshot. Rules written only for data-file subjects would not cover them.

Column files deserve particular attention for this note. Their `file_format` field is the same closed list of `avro`, `orc` and `parquet`, so any decision about writing ORC and Avro in version 4 applies to them as well. And they depend on the row-position binding more heavily than anything else in the table, since a column file's values are matched to the rows of its base file. The draft specification defines the fields and two constraints (column files may be attached only to data files and leaf manifests, and a field id may appear in at most one column file per subject); how a column file's rows correspond to its base file's is still under discussion on the dev list, in threads on [their file representation](https://lists.apache.org/thread/jbh1gbrso5h6l4by9rh9poy2cjjtb8j0) and [their metadata representation](https://lists.apache.org/thread/7jryw9dfvc02s411twn4o7s5gjrybfxg), among others.

### What the definition excludes

Two kinds of non-data file fall outside the definition. *Equality delete files* have no identifiable subjects: an equality delete applies to every data file in its partition with a lower sequence number, a set decided at read time by a rule rather than named, so there is nothing for the sidecar to be bound to. They behave more like content than like a sidecar, and the version 4 draft permits them only in versions 2 and 3. *Metadata files* are excluded for the reason given above: they are constitutive, not dependent.

Whether something is a sidecar can also depend on the level of the graph from which it is seen. A discovery chain is a sidecar of the table, bound to it by the table's UUID, discovered and ignorable. To the index blobs it lists, though, its metadata files are constitutive: they decide which of those blobs are live. The definition therefore names a relationship at one level of the reachability graph, not a fixed kind of file.

### Discovery chains

A discovered sidecar needs somewhere to be discovered from. The Puffin proposal (its Section 6.5) and the [decoupled index metadata note](../proposals/decoupled-index-metadata.md) describe one such place: a chain of numbered metadata files under a root of its own, advanced by conditional create and committed independently of the table, which lists sidecars and the subjects they are bound to. Those documents call the chain a *discovery chain* and its root a *discovery root*. Indexes are their subject, but nothing in the mechanism is specific to indexes, and ignorable sidecars of other kinds (statistics or provenance kept in Iceberg's terms, for instance) could use it equally.

### Sidecars as a waypoint

Sidecars matter beyond their own sake. Every dimension in the first group of Appendix B is a property that a kind of sidecar has to state: its scope, how it is referenced, its reliance, the polarity of what it asserts, what its binding memorializes, and what a rewrite does with it. A sidecar kind's descriptor is therefore a place where all of those dimensions meet, and the existing sidecars tabulated above are points in the space they span.

That makes sidecars a candidate waypoint toward describing the space as a whole. One descriptor envelope for kinds of sidecar would carry a field for each object dimension, with per-instance overrides where a dimension allows them, as reliance does in one direction only. Other stored objects could then be described by the same envelope wherever it fits: the six properties of the definition already apply to metadata files, and the Puffin proposal's blob type descriptors (its Section 5) would become one instance of the envelope. The dimensions of the second group are untouched by this, since they describe formats, stores, commits and operations, not stored objects, and outcomes are still computed on the edges between the two. That is why sidecars are a waypoint rather than the destination.

## Appendix E: Bindings between a file and the metadata that tracks it

Several provisions of the note relate something inside a file to something in Iceberg metadata: a column of a data file to a field id, a row to a position that a deletion vector names, a unit to a split offset in the manifest, footer statistics to the metrics copied from them, an encrypted file to the key metadata that opens it, a sidecar to its subject. It is not enough that such a relationship exist, or that a reader be able to construct it. A deletion vector, a row id and a manifest's metrics are all committed once and trusted thereafter, so each relationship they depend on is a *binding*. The bound file is most often a data file, but it may equally be a sidecar, and every binding must obey the same consistency protocol:

1. **Fixed by the commit.** The binding is established no later than the commit that adds the bound file to the table, and from then on it is a fact of the table, not a computation each reader performs.
2. **Durable with the bound file, and memorialized.** It is recorded in the bound file itself, in the metadata entry that tracks it, or in a required sidecar committed in the same snapshot, so that it lives exactly as long as the bound file and is collected with it. The tracking entry also records the bound file's length and, wherever one is available, a content hash of its bytes together with the algorithm that produced it, so that the binding names the exact bytes it was made against.
3. **Immutable, or versioned.** It does not change for the life of the bound file. If it must change, the change is made by a commit, and every snapshot resolves it unambiguously, so that reading an old snapshot gives the answer that snapshot was committed with.
4. **Independent of the reader.** Every conforming reader, in every language and every version of the format's library, resolves it identically.
5. **Verifiable.** A reader can detect when the bound file and the metadata disagree, and it then refuses the bound file rather than choosing one side.

Bindings that fail these rules give wrong answers without any component misbehaving, which is why they belong in the contract rather than in an implementation's care.

### Out-of-band mutation

Nothing in the contract can prevent a file from being changed behind Iceberg's back: an operator overwrites it, a tool rewrites it in place, a lifecycle policy replaces or removes it, a replication job copies a different version over it. What the contract can do is make such a mutation detectable, so that it ends in the refuse outcome instead of a wrong answer. The memorialized length and content hash of rule 2 are what make that possible, and they complement the version token proposed in the storage section:

| Record | Identifies | Survives copying, relocation and relative paths | Cost to check |
|---|---|---|---|
| Length | Nothing on its own, but catches truncation and most replacements | Yes | Free: readers already use it to find the footer |
| Version token (an ETag, a GCS generation) | One write to one location | No | Free on a conditional read |
| Content hash | The bytes themselves | Yes | Free where the store reports a matching checksum; otherwise a full read |

A content hash has three further uses. It lets a copied or relocated table be checked against its source, which a version token cannot do. It makes a race on a content-addressed name harmless (see file names and races). And it gives a scrubbing process, run as part of maintenance, something to compare against, which matters because a reader checks only the files it happens to read.

"Wherever one is available" needs care, since the cost and the meaning of a hash vary:

- **At write time a hash is nearly free**, because the writer streams every byte anyway.
- **At registration it costs a full read** of a file that no Iceberg writer produced, which `add_files` and migration would then have to pay or forgo.
- **Store-reported checksums differ by store and by upload method.** S3 can return a full-object CRC-64/NVME, its default, even for multipart uploads, whereas its SHA-256 checksum of a multipart upload is a composite computed from the parts' checksums and does not match a hash of the whole object; an S3 ETag is an MD5 of the content only in some cases. ([S3 integrity checking](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html)) Google Cloud Storage reports a CRC32C for every object and an MD5 only for objects not assembled by composition. ([GCS composite objects](https://docs.cloud.google.com/storage/docs/composite-objects)) The recorded hash is therefore most useful when its algorithm can be compared directly with what the store reports.
- **Checksums and cryptographic hashes protect against different things.** A CRC detects accidental change. Only a cryptographic hash, computed by the writer rather than reported by the store, detects deliberate replacement, and only if the metadata itself is trusted.

What a reader does when the check fails is a declared outcome like any other. For a data file or a required sidecar it is refusal; for an ignorable sidecar it is to read without it.

[^coeffects]: What an expression reads from its evaluation context, as distinct from what it does, is what the programming languages literature calls its *coeffects* (Tomas Petricek, Dominic Orchard and Alan Mycroft, "Coeffects: A Calculus of Context-Dependent Computation", ICFP 2014). A function catalog in which each function declares its coeffects, of the kind the classification in the section on defaults would need, is sketched in [`acruise/_meta`](https://github.com/acruise/_meta/blob/f344af6cf3502329b2afdedff764b0ace9648d02/types/src/coeffects.rs), whose `CoeffectSet` records whether an expression reads event data, the current time, aggregates or enrichment, or calls external functions.

[^effects]: What an expression can do when it is evaluated, such as produce a null, raise an error or fail to terminate, is in the same literature its *effects*, the dual of its coeffects; effect systems in this sense go back at least to John Lucassen and David Gifford, "Polymorphic Effect Systems", POPL 1988. The same [`acruise/_meta`](https://github.com/acruise/_meta/blob/f344af6cf3502329b2afdedff764b0ace9648d02/types/src/effects.rs) repository declares effects per function beside its coeffects, recording whether an expression may produce a null, may raise an error of a given type, may be partial or may block.
