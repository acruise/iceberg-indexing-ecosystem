# Problems in the Iceberg Contracts

*A register of the problems raised by the [note on contracts](README.md) and by the [Puffin blob proposal](../proposals/puffin-blob-types-and-compaction.md) and its companion notes, separated from the solutions proposed for them. Problems that exist in the specification and its implementations today come first, then problems that the proposals for version 4 are predicted to introduce or to make worse. Solutions are kept in their own documents: the [prose fixes](prose-fixes.md), the [version 4 asks](v4-asks.md), and the candidate requirements of the note itself. How other table formats handle each problem is surveyed in [prior art](prior-art.md).*

**Status:** draft, November 10, 2026, against apache/iceberg `main` at commit `26c9544`. The identifiers are stable within this draft so that the solution documents can cite them.

Each entry states the problem and what goes wrong because of it, without proposing a fix, and ends with a list of the places where a solution is proposed. An entry with no proposed solution says so. Where the Java implementation already behaves correctly, the entry says that too, since the problem is then confined to the specification and to other implementations. An entry describing a failure that has not been observed, only inferred from what the specification permits, says that it is anticipated.

## Problems today

### Commits

#### E1. The retry conditions omit row-level deletes

The specification's list of conditions that a retried commit must verify predates position delete files and deletion vectors. A compaction that rewrites data file F while a concurrent row-level delete adds a deletion vector against F meets every condition the list states, since every file it removes is still in the table. A retry that re-applies the compaction drops the deletion vector along with F, and the deleted rows reappear. The Java implementation checks for new deletes against the files a rewrite removes, so the gap is in the specification and in any implementation that follows only its text.

Solutions: [prose fix 1](prose-fixes.md#1-retry-conditions-for-deletion-vectors-and-column-files); the note's [commit requirement 1](README.md#candidate-requirements).

#### E2. The validation window, and what to do outside it, are unstated

The specification says that updates "verify the conditions under which they can be applied", without saying against which commits, and sets no minimum isolation. Nor does it say what a writer should do when it cannot verify, for instance because snapshot expiration has removed the history between the snapshot it read and the current one. A long-running writer in that position can commit without having validated anything.

Solutions: [prose fix 2](prose-fixes.md#2-what-a-retry-validates-against); [version 4 ask 2](v4-asks.md#2-the-read-base-of-each-snapshot); the note's [commit requirements 1 and 3](README.md#candidate-requirements).

#### E3. Validation is enforced by nothing outside the writer, and is undetectable afterward

Catalogs check that the table's pointer still names the writer's base, and do not read manifests. A writer that rebases without validating passes every catalog's check, and the result is a well-formed table with wrong contents. Afterward nothing shows that anything went wrong, because a snapshot records only the parent it was committed on, and not the snapshot the writer actually read from. The Java implementation tracks that snapshot during a commit and then discards it.

Solutions: [version 4 ask 2](v4-asks.md#2-the-read-base-of-each-snapshot); the note's [commit requirement 2](README.md#candidate-requirements).

#### E4. Commits of unknown outcome

A commit that times out may or may not have been applied. A blind retry of an append can then duplicate data, and nothing in the commit lets a catalog or the client recognize that it was already applied. Whether the REST catalog specification now offers an idempotency key was not checked.

Solutions: [version 4 ask 2](v4-asks.md#2-the-read-base-of-each-snapshot), in part; the note's [commit requirement 5](README.md#candidate-requirements).

### Identity and storage

#### E5. Paths may be reused

Position delete files name their data file by `file_path`, and deletion vectors by `referenced_data_file`, so the specification treats a path as a file's identity. It does not forbid writing a different file at a path that once held another. A table dropped and recreated at the same location, or a replication tool copying into a location that already holds files, can produce two files under one name, and the old references then apply to the wrong rows. Java writers generate unique names, so this is a habit of the implementation rather than a requirement.

Solutions: [prose fix 3](prose-fixes.md#3-paths-are-not-reused); the note's candidate requirement 1 under [file names and races](README.md#file-names-and-races).

#### E6. Out-of-band mutation is detectable only by a change of length

The specification states immutability as an obligation of writers, and gives readers no means of checking it. A data file overwritten in place by an operator or a tool is detectable only if its length changed. A deletion vector bound to the replaced file then deletes the wrong rows.

Solutions: [version 4 ask 1](v4-asks.md#1-a-content-hash-on-tracked-files).

#### E7. Exclusive create is not atomic

`OutputFile.create` is documented as failing when the file already exists. In the Java S3, GCS and Azure `FileIO` implementations it is an existence check followed by an unconditional write, so two writers racing on one name can both succeed, although every one of those stores now offers an atomic conditional create. Any protocol in which a file name serves as a lock, such as a storage-only commit or a discovery chain, depends on the create being atomic. The specification does not say whether it must be.

Solutions: [prose fix 4](prose-fixes.md#4-exclusive-create-is-atomic-or-it-is-not-exclusive); the note's candidate requirement 2 under [file names and races](README.md#file-names-and-races).

#### E8. Several strings can name one object

`s3://`, `s3a://` and `s3n://` can name the same bucket, and an HDFS path can be written with or without its authority. The specification does not say which strings denote the same object. Orphan removal in the Java implementation has options for reconciling them, and two components that disagree can delete live files.

Solutions: none proposed; listed among the note's [open questions](README.md#open-questions).

#### E9. Orphan removal races with commits and compares clocks

Orphan removal finds a file unreferenced and then deletes it, and a commit in flight can reference the file between the two steps. Its only protection is an age threshold that compares the store's creation times with the clock of the machine running the removal. Neither the race nor the clock comparison is acknowledged in the specification.

Solutions: none proposed. The note describes the problem under [what is left unsaid](README.md#what-is-left-unsaid).

#### E10. Lifecycle policies, archival classes and credential scope

A bucket lifecycle policy can delete or archive files that a live snapshot references, and the table has no way to declare which files must not be touched. A file in an archival class is listed and has a length, but a read may fail or take hours, and a reader's correct response is unstated. Credentials vended for a table location may not reach files that a reader needs outside it.

Solutions: none proposed; listed among the note's [open questions](README.md#open-questions).


#### E31. Ownership of data files is decided for the whole table

A table that borrows data files from elsewhere, as one created by the `snapshot` procedure does, must never physically delete them, and Iceberg expresses this only through the table-wide `gc.enabled` property, which the procedure sets to false. Logical removal remains allowed, so the table can compact the files it borrowed. But the successors it writes are its own, and when those are compacted in turn the same flag stops expiration from collecting them, so every file the table has written and later superseded leaks. Files registered by `add_files` raise the same question with no flag set at all: whether expiration may delete them depends on whether the operator remembered that the table does not own them. The flag and its effect on `snapshot` tables are observed; the leak follows from them.

Solutions: ownership recorded per data file, owned or borrowed, set at registration and always owned for a table's own outputs ([compaction and the removal of read-only predecessors](README.md#compaction-and-the-removal-of-read-only-predecessors)). Where instances share files and none can know the others, a log of the files each instance disclaims, authoritative only when the set of instances is known to be complete ([disclaimers of ownership](README.md#disclaimers-of-ownership)).
### Reading

#### E11. Whether an unsupported thing may be ignored is decided case by case

The specification says explicitly where ignoring is safe: statistics are "informational", and readers may ignore an unknown partition transform when filtering. It does not state the converse cases, where ignoring something produces a wrong answer. A reader that skipped a data file's deletion vector would return deleted rows, and no sentence of the specification forbids it. A case the specification has not yet considered falls to whatever each implementation infers.

Solutions: [prose fixes 7 and 8](prose-fixes.md#7-readers-must-apply-deletion-vectors-and-projected-column-files).

#### E12. A reader that cannot supply a default has no stated obligation

Column Projection says that readers "return the default value" for a field absent from a data file. It does not say what a reader that cannot do so must do. The Java ORC reader fails with an `UnsupportedOperationException`, which is safe, but only once a scan reaches such a file. A reader that returned null would give a wrong answer silently.

Solutions: [prose fix 5](prose-fixes.md#5-readers-that-cannot-supply-a-default-must-fail).

#### E13. Row positions are not required to be stable

Position deletes, deletion vectors and row lineage are all committed against row positions, and the specification does not require every reader to number a data file's rows alike. A reader that skipped a row it could not decode would shift every later position, and a deletion vector would then apply to the wrong rows.

Solutions: [prose fix 9](prose-fixes.md#9-row-positions-are-fixed-when-a-data-file-is-written).

#### E14. Split offsets that disagree with the file

`split_offsets` are copied into the manifest when a data file is written. The specification does not say what a reader should do if they do not correspond to the file's units, and a reader that trusted them could read rows twice or not at all.

Solutions: [prose fix 10](prose-fixes.md#10-inconsistent-split-offsets-are-ignored).

#### E15. Editing the name mapping reinterprets existing data files

The name mapping is a table property, so it is not versioned with snapshots. Editing it changes how every data file without field ids is read, including the same files as seen through snapshots committed before the edit, and so changes the answers that queries over older snapshots give.

Solutions: [prose fix 11](prose-fixes.md#11-changes-to-the-name-mapping-reinterpret-existing-data-files); the note's proposal that the mapping's result be frozen with each file, under [what a readable format provides](README.md#what-a-readable-format-provides), for which a format sidecar of data-file scope is the natural container ([sidecars for format integration](README.md#sidecars-for-format-integration)).

#### E16. Unsupported formats and features are discovered mid-scan

Nothing in table or snapshot metadata says which data file formats, or which kinds of required file, a snapshot contains. A reader that lacks support for one of them learns so only when a scan reaches such a file, after it may have returned rows from others.

Solutions: [version 4 ask 3](v4-asks.md#3-declaring-what-a-snapshot-depends-on); the note's [degradation requirement 3](README.md#candidate-requirements-1). E28 is the same problem within a single format.

#### E17. Maintenance learns what to keep only from snapshot metadata

Orphan removal treats every file under the table location that snapshot metadata does not reference as an orphan. Structures that are kept outside snapshot metadata, as the Puffin proposal and the [note on decoupled index metadata](../proposals/decoupled-index-metadata.md) propose for index files, are deleted by it unless something declares them, and are leaked if they live elsewhere and nothing collects them. Iceberg has been through this once already. Statistics files are listed in table metadata, yet the Java implementation took four releases to teach orphan removal, expiration and purge about them, and purge still leaves replaced statistics files behind ([note on index reachability](../proposals/index-reachability.md#icebergs-own-record-with-auxiliary-files)). Each new kind of structure has so far been made safe by changes to maintenance code in every engine, because the specification says what metadata contains and not what maintenance must treat as reachable.

Solutions: [version 4 ask 3](v4-asks.md#3-declaring-what-a-snapshot-depends-on); a list of discovery roots in table metadata, worked out in the [note on decoupled index metadata](../proposals/decoupled-index-metadata.md); the discovery chain of the Puffin proposal ([Section 6.5](../proposals/puffin-blob-types-and-compaction.md#65-a-discovery-chain-for-the-current-format-version)) as an interim measure that needs no change to table metadata.

### Formats

#### E18. What a format owes Iceberg is unwritten

The specification names the permitted formats in a closed list and gives a type mapping for each, and says nothing about what a format must be able to do for a new name to join the list. The Java File Format API defines an interface, and its registry loads four hard-coded classes. Without a written statement there is no bar a new format could be measured against, for reading or for writing.

Solutions: the note's read and write tiers, under [what a format provides and requires](README.md#what-a-format-provides-and-requires); several of the note's [open questions](README.md#open-questions).

#### E19. Type mappings state no ranges, and no behavior outside them

Appendix A varies a type's physical representation with its parameters, but cannot express that a format holds `decimal(P, S)` only for some P and S. It also does not say what a reader must do with a physical type outside its mapping, or a value outside the range it supports, so a reader may convert such a value instead of failing.

Solutions: the two correctness rules under [type mappings](README.md#type-mappings); the notation for ranges is an [open question](README.md#open-questions).

#### E20. Pushdown semantics are reimplemented for each format

This is a problem of the Java implementation, not of the specification. Iceberg evaluates predicates against manifest metrics in one visitor and against Parquet row group statistics, dictionaries and Bloom filters in three more, and ORC translates them into its own predicate language. The subtleties of NaN ordering, truncated bounds, type promotion and timestamp units are therefore implemented several times, and an error in any copy excludes rows that a query should have returned. That a format's own filtering may return extra rows and must never drop matching ones is stated only in the Java interface's documentation.

Solutions: the note's division of labor under [predicate pushdown](README.md#predicate-pushdown).

### Extensions and the parties who add them

The problems in this group concern anything that a party outside the Iceberg project might add: a blob type, a data file format, an encoding within a format. They are the problems the Puffin proposal was written to solve for blobs, and the [alignment section of the note](README.md#alignment-with-puffin-blob-types) argues that they recur for formats.

#### E21. Extension identifiers are unmanaged

The Puffin specification defines two blob types and lets `type` be any string. Every other blob type is a private agreement between its writer and its readers, and nothing prevents two parties from choosing the same name for different payloads. A reader that recognizes the name then decodes a payload it does not understand, and for an index that means excluding data files that contain matching rows. Format-specific table properties have the same shape at lower stakes: `write.parquet.*` and `write.orc.*` are reserved by convention, and a format added later has no prefix of its own. `file_format` avoids the problem only by being a closed list (E18). The free-form type string is observed; a collision is anticipated.

Solutions: namespaced identifiers and a two-tier registry ([Puffin proposal, Section 4](../proposals/puffin-blob-types-and-compaction.md#4-blob-type-identifiers-and-the-registry)), which the note proposes applying to `file_format` as well ([a namespace](README.md#a-namespace)).

#### E22. Capabilities are inferred from a name

An engine that meets a blob type or a data file format knows its name and nothing else, and decides what it may do by code written for that name: whether a blob may exclude files or also locate rows, what becomes of it under compaction, whether a format is splittable and which of its types it can read. A new member of either family therefore needs code in every engine before any engine treats it correctly. An engine that guesses wrong has no declaration it can be held to, and a reviewer has nothing to check an implementation against.

Solutions: machine-readable descriptors per blob type ([Puffin proposal, Section 5](../proposals/puffin-blob-types-and-compaction.md#5-blob-type-descriptors)); the format descriptor proposed under [a global, versioned type descriptor](README.md#a-global-versioned-type-descriptor-and-a-per-instance-description), carrying the read and write tiers.

#### E23. Third-party implementations cannot be plugged in

This is a problem of the Java implementation. The File Format API's registry discovers format models by reflection over a hard-coded list of four class names, and `PuffinReader` and `PuffinWriter` hand every consumer raw buffers to interpret by switching on the type string. Adding a format, or a blob type that engines use for planning, therefore means changing Iceberg itself or forking it.

Solutions: a `ServiceLoader`-based interface ([Puffin proposal, Section 9](../proposals/puffin-blob-types-and-compaction.md#9-java-service-provider-interface)), which the note suggests for format models as well.

#### E24. Metadata attached to data files does not survive compaction

The specification says what a rewrite must preserve about rows, and nothing about any other metadata attached to its inputs. Statistics files are bound to a snapshot, and the Java rewrite path makes no reference to them, so the snapshot a compaction produces carries no statistics, and whatever an operator wants on the compacted data must be recomputed by a job that nobody standardizes. Any index kept beside data files inherits the same fate.

Solutions: blobs bound to a data file rather than a snapshot, with a compaction mode declared per type ([Puffin proposal, Sections 6 and 8](../proposals/puffin-blob-types-and-compaction.md#8-compaction-semantics-candidate-specification-text)).

#### E25. Shared metadata is replaced whole by writers that understand only part of it

In the Java implementation a snapshot has at most one statistics file, and `compute_table_stats` replaces it without carrying forward blobs it did not write. A writer that rewrites table metadata likewise drops fields it does not parse, which is how implementations that predate statistics files and partition statistics files discard them from version 1 and 2 tables. Neither loss is reported. Both are instances of the question PNG answers with a bit per chunk type, whether something not understood may be copied forward, which Iceberg answers nowhere.

Solutions: in part, the Puffin proposal's rule that a writer treats an unknown blob type as `drop` and reports what it dropped ([Sections 8.4 and 8.7](../proposals/puffin-blob-types-and-compaction.md#8-compaction-semantics-candidate-specification-text)), which makes the loss defined and visible without preventing it; the carry-forward dimension of the note ([Appendix B](README.md#appendix-b-dimensions-of-the-contract)). A version 4 table metadata field remains exposed to version 4 writers that do not parse it.

#### E26. Statistics from a defective writer are trusted indefinitely

Manifest metrics are copied from data file footers at write time and trusted by every planner thereafter. When a writer is later found to have produced wrong statistics, the only remedy is to rewrite its data files, and finding them requires opening every footer, since a manifest entry does not record which writer produced the file. A wrong bound excludes data files that contain matching rows. parquet-java has met this case for its own footers, and handles it privately by ignoring binary statistics from writer versions it identifies by the `created_by` string; Iceberg's copy of those statistics is not covered. The mechanism is observed; that parquet-java behavior is among the note's [unverified claims](README.md#unverified-claims).

Solutions: writer identity in the per-instance description of a data file, and a table-level revocation list naming writer versions whose metrics must be ignored, the format analogue of the Puffin proposal's `puffin.config.revoked` ([Section 7.4](../proposals/puffin-blob-types-and-compaction.md#74-reader-obligations); the note's [configuration paranoia](README.md#configuration-paranoia)).

#### E27. Parameters that change what the bytes mean are kept outside the bytes

Parquet INT96 timestamps do not record whether they were adjusted to UTC, and dates and timestamps written by Spark before its change of calendar in 3.0 need a rebase that only a footer key written by later versions indicates. Iceberg's own writers avoid both representations, but files registered by `add_files` or migration may carry them, and a reader interprets them by convention. A reader whose convention differs from the writer's returns wrong values without an error. The Spark rebase key is among the note's [unverified claims](README.md#unverified-claims).

Solutions: the Puffin proposal's evaluation parameters, recorded by a configuration reference that a reader must resolve or else decline the payload ([Section 7](../proposals/puffin-blob-types-and-compaction.md#7-build-configurations)); for data files, a format sidecar recording the parameters at registration ([sidecars for format integration](README.md#sidecars-for-format-integration)).

#### E28. A reader cannot tell whether it can decode a data file before opening it

E16 at a finer grain. A data file may use an encoding or a logical type newer than a reader of its format supports: Parquet has added encodings and logical types over the years, and Vortex admits new encodings by design. The manifest entry records only the format's name, so a reader learns from the footer, after it has committed to the file and possibly after it has returned rows from others.

Solutions: a per-instance description in the manifest entry listing the format extensions the data file uses, checked against the extensions the format descriptor says a reader may need ([a global, versioned type descriptor](README.md#a-global-versioned-type-descriptor-and-a-per-instance-description)).

#### E29. The choice of index is fixed when the data is written

This is a missing capability rather than a defect. An index that lives inside a data file, such as a Parquet Bloom filter per column chunk, is chosen by the writer, paid for at write time, and consulted only after the footer read that locates it. An index kept in a statistics file is bound to a snapshot and must be rebuilt after every rewrite (E24). Neither lets the index on a column follow the predicates the column later receives, which is what our hypothesis of workload drift expects to happen, though the hypothesis is not established.

Solutions: blobs added to a data file after it is written, by any process, and consulted during planning ([Puffin proposal, Section 2.3](../proposals/puffin-blob-types-and-compaction.md#23-why-this-matters-now)).

#### E30. Every format change is fenced by refusing the whole table

The format version is one integer, and an implementation must refuse any table whose version is higher than it supports, whatever the table uses and whatever the implementation means to do with it. A feature that only writers need to understand, such as a write default, therefore costs the same as one that every reader needs, and adopting it makes a table unreadable to every older reader. Because the price is paid per increment, features are batched into infrequent versions, which couples unrelated work to one release and makes review of each feature more conservative than the feature alone would warrant. Features that cannot wait arrive instead as optional fields with no fence at all, as statistics files did (E25). The specification's own appendix on version changes reasons about compatibility per feature and per role, so the finer analysis exists and is discarded by the fence. Within Puffin the same problem appears at a smaller scale: a blob type's compatible revisions have no way to mark an addition that a reader must understand. The fence is observed; the batching it causes is an inference from the version history, and the coupling was met at the index sync of November 9.

Solutions: declared features with reliance per role and a scope of refusal, versions as named sets of features, and a `required-features` property for blobs ([versioning by feature](feature-versioning.md)).

## Problems predicted under version 4 proposals

These entries concern proposals that are under discussion or in the version 4 draft as of the date above. Several of them are problems of the previous part made worse by a proposal, and say so.

### Stopping writes of ORC and Avro data files

#### V1. The read obligation on deprecated formats keeps growing

The [proposal](https://lists.apache.org/thread/5jfzckl0cc9z683kz9vfv41pmhrm1tdb) treats keeping the formats readable as a fixed obligation. It is not fixed: every later feature that changes how an existing file is interpreted, such as a default value or a new type promotion, must be supported by every reader of every deprecated format, in every language, or those readers must refuse. The Java ORC reader already cannot supply default values. The maintenance that deprecation was meant to save would continue on the read side. This aggravates E12.

Solutions: [prose fix 12](prose-fixes.md#12-if-version-4-stops-writing-orc-and-avro-data-files); the note's three ways out under [the read obligation keeps growing](README.md#the-read-obligation-keeps-growing).

#### V2. Nothing shows how much of a table remains in a deprecated format

Once writes stop, compaction gradually moves data out of the deprecated formats, and nothing tells an operator how much remains or when the last such file has gone, which is what decides whether a reader without ORC support can be used. This aggravates E16.

Solutions: [version 4 ask 3](v4-asks.md#3-declaring-what-a-snapshot-depends-on); the note's [degradation requirement 5](README.md#candidate-requirements-1).

#### V3. There is no bar for making a format writable again

Among the proposal's questions is what a format must meet to become writable. The question cannot be answered while E18 stands.

Solutions: the note's write tier, under [what a writable format provides](README.md#what-a-writable-format-provides).

### Admitting new formats

#### V11. Admitting a format takes a specification change, and the alternative is a private name

If version 4 keeps reading formats it no longer writes, and formats such as Vortex, Lance and Nimble seek admission, each new name in `file_format` needs a vote and a specification release before any table may contain a data file in that format, even read-only. A vendor that does not wait writes a private string, and E21 follows. Admission is also all or nothing: there is no state in which a format is readable by those who choose to support it and declared so. This combines E18 and E21 and is anticipated, not observed.

Solutions: the Puffin proposal's identifier grammar and registry applied to `file_format` ([a namespace](README.md#a-namespace)), with the read tier as the bar for admission ([read-only support](README.md#read-only-support)).

### Column files

#### V4. The retry conditions do not cover column files

A column file added for a data file conflicts with a concurrent rewrite of that data file, and with a concurrent column file for the same fields. The retry conditions cover neither, which is E1 again, for a new kind of file.

Solutions: [prose fix 1](prose-fixes.md#1-retry-conditions-for-deletion-vectors-and-column-files).

#### V5. A reader that ignores a column file returns wrong values

The draft does not say that a reader projecting a field carried by a column file must read it from there. A reader that did not support column files would return the data file's stale values for that field, and a reader projecting none of the column file's fields could safely ignore it, so the obligation is conditional on the projection. This is an instance of E11.

Solutions: [prose fix 7](prose-fixes.md#7-readers-must-apply-deletion-vectors-and-projected-column-files); the note's reliance metadata, under [sidecars for format integration](README.md#reliance-when-a-reader-may-ignore-a-sidecar).

#### V6. A column file is bound to its data file by location

A column file supplies values for rows of one data file, identified by path and size. If the data file were replaced at the same path, the column file would supply values for the wrong rows. This combines E5 and E6.

Solutions: [version 4 ask 1](v4-asks.md#1-a-content-hash-on-tracked-files).

#### V12. Each kind of sidecar needs its own rewrite rule

A rewrite of a data file that has column files must fold their values into its output, or carry the column files forward, and deletion vectors already have their own rule. Each kind so far has had its rewrite behavior written into the specification separately, and the next kind will need the same, along with its own conflict rules (E1, V4) and its own reliance (E11, V5). Whether the draft states the rewrite rule for column files has not been checked here.

Solutions: a compaction mode and a reliance declared once per kind, in a descriptor, as the Puffin proposal does for blob types ([Section 8](../proposals/puffin-blob-types-and-compaction.md#8-compaction-semantics-candidate-specification-text)); the kind-specific rule slots of the note's [Appendix D](sidecars.md#slots-for-kind-specific-rules).

### Default values that are expressions

#### V7. A writer that cannot evaluate a default has no stated obligation

Version 4 allows `write-default` to be a value expression, and the functions it calls may be engine-specific. A writer that cannot evaluate one cannot supply the field's value, and the draft does not say that it must then fail. Writing null instead would be a silent wrong answer.

Solutions: [prose fix 6](prose-fixes.md#6-writers-that-cannot-evaluate-a-default-must-fail).

#### V8. Defaults depend on context that nothing records

A default of "24 hours ago" depends on the writer's clock, and one built on the current date also on its time zone. The expressions specification makes function references independent of session context, but says nothing about the context a function's evaluation reads, so two conforming writers can store different values for one default without recording which context each used. The draft's admissibility rules, no field references in a `write-default` and only literals in an `initial-default`, are special cases: they admit the context-dependent default above, and forbid a coalesce over another field that a writer could evaluate perfectly well.

Solutions: [prose fix 13](prose-fixes.md#13-the-evaluation-context-of-functions); the note's classification under [classifying expressions](README.md#classifying-expressions-instead-of-special-casing-them).

### Relative paths

#### V9. Relocation becomes routine, and location-based identity does not survive it

Relative paths make copying, replicating and relocating tables routine. A version token such as an S3 ETag or a GCS generation identifies one write at one location, and is lost by all three, so it cannot detect the out-of-band mutation of E6 across a copy. Copying a table into a location that already holds files is also one of the ways E5 arises.

Solutions: [version 4 ask 1](v4-asks.md#1-a-content-hash-on-tracked-files); [prose fix 3](prose-fixes.md#3-paths-are-not-reused).

### Removing file system tables

#### V10. No catalog-free commit protocol remains

Version 4 removes the commit scheme based on atomic rename, which the specification already describes as unsafe on object stores, without providing a replacement based on conditional create. Deployments without a catalog server, HDFS among them, then have no conforming way to commit, and designs that commit through storage alone, such as the discovery chains of the Puffin notes, have no protocol in the specification to align with. A replacement would depend on E7 being fixed.

Solutions: the note's [commit requirement 6](README.md#candidate-requirements). The removal itself was agreed in 2024 and is better treated as settled; [the note on catalogs](catalogs.md) records the decision and its implications.

### Secondary indexes

#### V13. Conforming version 4 maintenance may delete indexes

The secondary index specification under review in [pull request 16961](https://github.com/apache/iceberg/pull/16961) makes each index's own metadata the only reachability root for its files, locates that metadata through the catalog, and in its worked example places index files beneath the table location. Version 4 as drafted does not oblige table-level orphan removal, expiration or purge to account for them. An implementation can therefore conform to version 4 and delete every index beneath the table location in one run, silently, since a planner that finds no index plans without one. Obligations added after version 4 is published would bind no format version, which is why the [note on index reachability](../proposals/index-reachability.md#urgent) calls the problem urgent. This is E17 with a deadline.

Solutions: a list of discovery roots in table metadata, with obligations on version 4 maintenance to spare and collect what lies beneath them ([note on decoupled index metadata](../proposals/decoupled-index-metadata.md); [version 4 ask 3](v4-asks.md#3-declaring-what-a-snapshot-depends-on)).

#### V14. Each rewrite disables a snapshot-pinned index until it is rebuilt

The reference implementation of the index specification declines to use an index across any snapshot that is not an append, so every compaction disables the index until a refresh whose cost is proportional to the size of the index. This follows soundly from a positive index pinned to a snapshot and is a cost, not a defect, but it is paid by every table that compacts, and an index that is often stale is easy to lose without anyone noticing.

Solutions: negative, data-file-scoped blobs, which stay valid across every snapshot in which their data file is live, merged or rebuilt within the rewrite ([Puffin proposal, Sections 6 and 8](../proposals/puffin-blob-types-and-compaction.md#8-compaction-semantics-candidate-specification-text)). For a positive index, identity columns that record `_row_id` instead of file and position, which the index specification already recommends among its options and which survive rewrites provided every writer preserves row lineage ([a worked example: row lineage](feature-versioning.md#a-worked-example-row-lineage)).

### Tight statistics

#### V15. Statistics declared tight become answers

Version 4 lets a writer declare its column statistics tight, so that aggregates such as `count` or `max` can be answered from metadata alone. A defective writer's statistics then become a query's result directly, with no data read that could contradict them, and E26 changes from wrong pruning to wrong answers to aggregate queries. Anticipated.

Solutions: as for E26, writer identity per data file and a revocation list, which here would also let a planner stop trusting a writer's tightness claims without rewriting its files.

## Open questions for implementers

Several entries above rest on the Java implementation as read rather than as run, or on implementations that were not read at all. The questions below are the ones that maintainers of an implementation could answer quickly, and each answer would confirm, narrow or retire the entries it cites. They are numbered so that answers can be recorded against them.

1. Do PyIceberg, iceberg-rust and iceberg-go check, when a rewrite is retried, for deletion vectors and position deletes added since its base against the data files it removes? In the Java implementation, does any path skip the check, such as a partial-progress commit of `rewrite_data_files`, `rewrite_position_delete_files`, or a particular isolation level? (E1, V4)
2. What does each implementation do when the snapshots it would need to validate a retry against have been expired: fail, or commit unvalidated? (E2)
3. How does each implementation report a commit whose outcome is unknown, and does any of them retry an append blindly? (E4)
4. Does each implementation use a fixed and unique name for every file it writes, including across retries of a task or a commit? (E5)
5. Does any `FileIO` implementation, in any language, use its store's atomic conditional create? (E7)
6. When a reader cannot supply a field's `initial-default` for a data file, does it fail or return null? (E12)
7. Can any reader assign row positions differently from the writer, for instance by skipping rows it cannot decode? (E13)
8. Do data files registered by `add_files` or migration carry INT96 timestamps, or dates and timestamps that need a calendar rebase, in practice, and how does each reader interpret them? (E27)
9. Which functions do engines intend to write as a `write-default`, and does any of them depend on the session time zone or another part of the evaluation context? What does each writer do with a default that calls a function it does not implement? (V7, V8)
10. Does the column files draft state what a rewrite does with a data file's column files, and how do implementations plan to carry them through compaction? (V12)
11. Does any implementation plan to make orphan removal, expiration or purge account for secondary index files, or to restrict orphan removal to a table's `data` and `metadata` directories? (E17, V13)
12. Does anyone have data on how many tables, files or bytes remain in ORC or Avro? (V1, V2)
