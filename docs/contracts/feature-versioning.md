# Versioning Iceberg by Feature

*A companion to the [note on contracts](README.md). It argues that Iceberg's single, table-wide format version fences every feature more heavily than most features need, that this pushes the project toward infrequent releases that bundle unrelated changes, and that the dimensions the note defines are enough to say exactly when an implementation that lacks a feature may still proceed. It also takes up the Puffin proposal's open question on compatible minor revisions of a blob type, which is the same question asked of a smaller structure.*

**Status:** working note, November 11, 2026. Specification text is quoted from apache/iceberg `main` at commit `26c9544`. Statements about other formats are drawn from the [survey of prior art](prior-art.md), which pins its sources.

## The rule today

Iceberg versions its table format with one integer. The specification increments it "when new features are added that will break forward-compatibility", and for every structure that carries it, the obligation on an implementation is the same: "Implementations must throw an exception if a table's version is higher than the supported version." The only way to keep a table readable by older implementations is to leave it at the older version and forgo every feature introduced since.

In the note's terms, the format version is a declaration with the strongest possible setting on every dimension at once:

| Dimension | Setting of the format version |
|---|---|
| Reliance | Required |
| Role | The same for readers, writers and maintenance |
| Scope of refusal | The whole table |
| Earliest point of detection | Table load |
| What it covers | Every feature added since the previous version, as one unit |

The specification's own appendix on version changes reasons more finely than the rule it enforces. Of version 3's defaults it says that "The `write-default` is a forward-compatible change because it is only used at write time", and that "Tables with `initial-default` will be read correctly by older readers if `initial-default` is always null for optional fields." Those are statements about reliance by role and by instance: the write default is ignorable for readers, and the initial default is ignorable for readers whenever its value is null. The analysis is done, in prose, and then discarded by a fence that treats both features as required by everyone.

Version 4 as drafted shows how much the fence bundles. Reading Appendix E of the draft, our assessment of what an implementation that predates each feature would actually do is as follows:

| Version 4 feature | An older reader | An older writer |
|---|---|---|
| Default values that are expressions | Unaffected, since the default is used only at write time | Must refuse, since it cannot evaluate the default ([V7](problems.md#v7-a-writer-that-cannot-evaluate-a-default-has-no-stated-obligation)) |
| Relative paths | Must refuse, since it would resolve locations wrongly | Must refuse |
| Strictly increasing snapshot timestamps | Unaffected | Must refuse, since it could commit a timestamp that violates the rule |
| Equality deletes prohibited | Unaffected | Must refuse, since it could add an equality delete |
| Constraints | Unaffected | Must refuse if any constraint is enforced; unaffected otherwise |
| The `file` type | Must refuse a read that projects a field of the type | Must refuse a write to such a field |

Only one of these needs every reader to refuse the whole table. The rest need writers to refuse, or readers to refuse a projection, and the integer cannot express either.

## What the coarse rule costs

The cost of a version increment is that every implementation that predates it refuses every table that adopts it. That cost is paid per increment, whatever the increment contains, and the community responds in the way the price invites:

- **Features are batched.** If refusal costs the same for one feature as for ten, it is rational to wait for ten. Versions therefore arrive years apart, each a large release.
- **Each release is high stakes, so review is conservative.** A feature that might need revision cannot be shipped and revised in the next minor version, because the next version is years away. A reviewer's safest course is to keep it out.
- **Unrelated timelines become coupled.** A feature that is ready waits for the version, and one that is nearly ready is rushed or misses a whole cycle. The index sync of November 9 met this directly, when the participants chose to keep derived columns for indexes out of the version 4 timeline precisely so that neither piece of work would hold up the other.
- **Upgrading a table is all or nothing.** An operator who wants one feature that only writers need to understand must make the table unreadable by every reader that predates the version, including readers that the feature would never have affected.
- **Features that miss a version go unfenced.** A change that cannot wait for the next version tends to arrive as an optional field with no fence at all. Statistics files and partition statistics files did exactly this in version 1 and 2 tables, and implementations that predate them still drop the fields when they commit and delete the files as orphans ([E25](problems.md#e25-shared-metadata-is-replaced-whole-by-writers-that-understand-only-part-of-it), [E17](problems.md#e17-maintenance-learns-what-to-keep-only-from-snapshot-metadata)). The coarse fence thus produces two outcomes, too much protection or none, and nothing in between.

The last point is the one that most concerns the contracts. A fence that is too expensive to use is not used, and the features it would have protected degrade in ways nobody declared.

## When an implementation that lacks a feature may proceed

The note's dimensions are enough to state the condition exactly. Let a table use a feature F, and let a component C, acting in a role R, not implement F. The role is one of reading, writing (which includes rewriting) and maintenance (expiration, orphan removal, purge, and copying or registering a table). Then **C may proceed without refusing if and only if all of the following hold for R**:

1. **F is declared where C looks before acting.** The table or snapshot metadata names F in a list that C can read without understanding F. If F is not declared, its presence can be discovered only by meeting its structures, and an undeclared feature is treated as the note treats undeclared edges: it is required wherever it could be, and it can be ignorable only where its structures are themselves ignorable by construction.
2. **F's reliance for R is ignorable.** F declares, per role, whether a component lacking it may proceed. The declaration may be conditional on what an operation touches, as a column file is required only by reads that project its fields.
3. **For readers, what F's structures assert survives being ignored.** Every structure F adds is negative, or is derived from data the reader already reads, so that ignoring it changes what the reader can skip but not what it returns. This is the derivation and exclusion-only rule of the Puffin proposal's [Section 6.2](../proposals/puffin-blob-types-and-compaction.md#62-what-a-data-file-scoped-blob-may-assert), generalized from blobs to features.
4. **For writers, F's state survives an unaware writer as valid or detectably invalid.** A writer that does not know F behaves as unaware writers do: it drops fields it cannot parse, drops blobs it cannot interpret, and replaces files whose attachments it does not carry forward. After any of these, each remaining piece of F's state must still be true, or carry a binding (see [Appendix E](README.md#appendix-e-bindings-between-a-file-and-the-metadata-that-tracks-it)) strong enough that an aware reader detects that it is no longer true. A stale claim that cannot be detected as stale makes F required for writers, which is why Lance carries a writer-only flag for its stable row ids: "Older writers could treat its entries as stable row IDs and silently extend coverage".
5. **For maintenance, F's files are safe from C's deletions.** Every file F adds is either reachable through structures C walks or placed where C never deletes, as under a discovery root disjoint from the table location. Otherwise F is required for maintenance, which is the fence Delta's VACUUM protocol check provides on its own.
6. **The degradation is within the operation's tolerance.** Conditions 2 to 5 guarantee that proceeding is never wrong. Whether it is acceptable is the operation's to say, and an audit may refuse a degradation an interactive query accepts.

If any condition fails, C refuses. The refusal's scope is F's scope for R: a field, a data file or a snapshot where F's declaration says so, and the whole table only when F's structures reach it. Its earliest point of detection is wherever the declaration of condition 1 sits, which for a table-level list is table load.

Applied to features Iceberg already has, the condition reproduces what the specification says or implies feature by feature:

| Feature | Reading | Writing | Maintenance |
|---|---|---|---|
| Deletion vectors | Required, since ignoring one returns deleted rows | Required, since a rewrite that ignored one would bring deleted rows back | Required |
| Column files (version 4 draft) | Required for reads that project their fields | Required | Required |
| Statistics files | Ignorable | Ignorable, at the cost of losing them | Required as things stand, since they are reachable only through a field older implementations do not parse |
| Data-file-scoped Puffin blobs, as proposed | Ignorable, by derivation | Ignorable; an unaware rewrite orphans them, and the liveness check detects it | Ignorable under a disjoint discovery root, at the cost of leaked storage |
| Write defaults that are expressions | Ignorable | Required | Ignorable |
| Row lineage | Ignorable, unless the read projects `_row_id` or `_last_updated_sequence_number` | Required, since a writer that moves a row without copying its `_row_id` gives it a new identity silently | Ignorable |
| Relative paths | Required | Required | Required |

Two consequences follow from reading the table by column. Most features need far less than refusal by every reader, so a table that adopts them could stay readable by older engines. And the features that do need refusal by every role are few, which suggests that a whole-table fence is the right tool only for them.

## Versions as sets of features

Declared features and integer versions are not alternatives. A sensible arrangement keeps both:

1. **Features are the unit of compatibility.** A table declares the features it uses, each with its reliance per role and its scope, and a component checks the list against what it implements.
2. **Versions, major and minor, are named sets of features.** A major version names a set that every conforming implementation supports, so that "supports version 4" still means something definite, and its set is given in full rather than as a difference from its predecessor, so that it can be read without reconstructing history. A minor version names a larger set, its major version's plus a known list, so that "4.2" is version 4 plus the features 4.1 and 4.2 added, with any deprecations and removals they made recorded per role; a deprecated feature stays in the set, and a removed one leaves it only for the roles from which it was removed. Version 4 is then also minor version 4.0, the zero point of the series, and the two names denote the same set.
3. **A table's version is derived from the features it uses.** A table that uses only version 4 features, plus one feature from 4.2 that readers may ignore, remains readable by every version 4 reader and writable only by writers that support the feature.
4. **Dependencies between features are declared.** A feature that relies on another, as a feature built on row lineage would, names it, and adopting the first adopts the second.
5. **Deprecations and removals are entries in a version, as additions are.** A named version, major or minor, records the features it adds, those it deprecates and those it removes, each per role, and a deprecation announced between major versions is a minor version, so that it has a name, a date and a place to be cited.

The fifth point needs a vocabulary, because the specification's word "deprecated" currently covers at least four different states. Of `distinct_counts` and `file_ordinal` it means "do not write". Of file system tables it means "will be removed in version 4". Of position delete files in version 3 it means that new ones may not be created and existing ones "must be written to delete vectors when updating the position deletes for a data file". Of the table metadata field `schema` it means "use `schemas` instead", although version 1 still requires the old field. One word cannot carry these, and neither can a short list of states, because a deprecation has several independent dimensions. A revision should record each deprecation with its parameters:

1. **Status per role.** For reading, writing and maintenance separately: *supported*, *deprecated* (still valid, but components in that role should stop), or *removed* (components in that role must not create instances, or may refuse them). Removal is not a different kind of event from deprecation; it is the point at which a role's status reaches its end, and each role's status changes at a revision of its own.
2. **Strength.** Whether a deprecated status is a SHOULD or a MUST, stated per role, since "do not write" and "prefer not to write" are different obligations.
3. **Subject.** The whole feature, or a part of it: a member, a value of an enumeration, or a use, such as storing the deleted row in a position delete file.
4. **Replacement.** The feature or member to use instead, if there is one.
5. **Planned removal.** Per role, the version at which removal is intended. It is advisory until a later revision makes it so, but it lets an operator plan.
6. **Migration duty.** What must happen to existing instances, and when: never, on the next write that touches the instance (the version 3 rule for position deletes), on upgrade of the table, or by a stated version; and which operation performs it, a writer or maintenance.
7. **Treatment by unaware rewriters.** Whether a writer that rewrites an instance without implementing the deprecation should carry it forward, convert it or drop it, which is the carry-forward question asked of a deprecated thing.
8. **Applicability.** Which tables the deprecation binds: all tables, tables at or above some format version, newly created tables only, or tables upgraded in place.
9. **Reporting.** Whether a component that meets a deprecated instance reports it, so that an operator can see how much remains, which is what [V2](problems.md#v2-nothing-shows-how-much-of-a-table-remains-in-a-deprecated-format) asks for.

These are the dimensions the specification's own deprecations already vary along, and there are undoubtedly more. Under the rule on [normative text](#normative-text-and-what-the-descriptor-does-not-say), a deprecation's prose states its rationale and lists any of its rules the parameters above do not capture, and where such a rule bears on whether a component may proceed, the recorded status for that role is the conservative one.

Removal for reading deserves its own caution: it is safe only once no table that a conforming component may meet can contain an instance, which for tables upgraded in place is in practice never, unless the upgrade itself is obliged to migrate them. The [closure of features in use](#which-features-a-snapshot-uses-computed-upward) shows, per table, when it has become safe for that table.

Within a major version, deprecating a feature, or ceasing to write an optional member of one, is compatible: it adds an annotation, and a reader that never relied on the member is unaffected. Removal for writing of anything a reader of the same major version relies on finding is not, and takes a major version.

The whole-table fence remains available, as one setting of one feature's declaration, for the features that need it. What changes is that the price of a feature is the refusal it needs and no more.

A consequence for the specification's text follows, though nothing above depends on it ([version 4 rewritten this way](v4-as-features.md), as an illustration). Today the table specification is one document, in which adopted rules, draft rules for the next version and deprecations are interleaved and distinguished by annotations in the prose, and in which a rule can be cited only as the whole document at some commit. If each feature carries its own normative text and descriptor, and a version is a named set of features, that document can become mostly an index: the core model that every feature extends, and for each named version the list of features it includes, each linking to text with its own history, its own reviewers and its own `spec-versions`.

### Tombstones

Deprecation is planned, gradual and compatible with trusting what already exists. A feature, a major version of one or a range of its revisions can also be found to be unsafe, because its definition admits a wrong answer, or because a defect in it cannot be repaired compatibly, and that needs a different record. A *tombstone* says that the subject must no longer be used and that existing instances may no longer be trustworthy, with immediate effect. It has precedents in the revocation of configurations by digest (see [configuration](#configuration)) and in the revocation of defective writer versions that the note on contracts proposes, and outside Iceberg in a package registry's withdrawal of a broken release, which stays recorded but is no longer offered.

A tombstone records:

1. **Its subject:** a feature in every major version, one major version, or a range of revisions within one.
2. **Its consequence per role.** Writing: components must not create new instances, always. Reading: refuse the instances in their declared scope, or, where the feature's structures are ignorable by construction, decline them (as a reader declines a blob it does not trust, losing coverage and nothing else). Maintenance: preserve instances by default, since a tombstone must never be the cause of data loss, unless the tombstone states that they may be collected.
3. **A forward reference, optionally:** to a replacement feature, or to the revision that repairs the defect.
4. **Remediation:** what should happen to existing instances (rebuild, rewrite, convert, or nothing), and which operation performs it.
5. **Its reason,** in normative prose, or a reference to an advisory where disclosure has to wait.

A tombstone is permanent. A tombstoned identifier or revision is never reused, and a tombstone found to be mistaken is lifted by a later record that says so, never by deleting the first, so that the history of what was trusted when stays readable.

Publishing a tombstone in the registry reaches only implementations that consult the registry. An implementation with compiled-in descriptors learns of it only through the tables it reads, so a tombstone also has to be carried by tables, in two ways:

1. **For a range of revisions,** by raising the table's recorded revision for each affected role past the range, to the repairing revision. Components implementing only a tombstoned revision then fall below the table's floor and refuse, under the rules for [versions of a feature](#versions-of-a-feature), without needing to know why.
2. **For a whole feature or major version,** by a list of tombstoned subjects in table metadata, which the feature list's own definition obliges every component to consult. A component that finds a feature it implements on that list acts as the tombstone's consequence for its role requires, whatever its compiled-in descriptor says.

Either way, a tombstone changes a component's behavior only toward refusing or declining, so a component that has not yet heard of it is no less safe than it was the day before, and one that has heard of it cannot be made to give a wrong answer by it.

### The one increment the mechanism needs

The list of features is itself a structure that older implementations cannot parse, so it cannot be introduced as an optional field: an older writer would drop it, and the next reader would see a table that declares nothing. Introducing the list is therefore a feature that is required of every role, and it needs one last integer increment. The cheapest moment for that is version 4, which is being prepared. If the list rides version 4, later features need not wait for version 5.

The list proposed in the [third version 4 ask](v4-asks.md#3-declaring-what-a-snapshot-depends-on), declaring the data file formats present, the kinds of sidecar required for reading and the discovery roots, is a particular case of the same declaration and should share its mechanism.

The increment can also be the fallback for every other version 4 ask. If version 4 carries the list and nothing else from this repository, each other recommendation can arrive later as a feature, without waiting for version 5. For that to hold, four things must ride the increment, and the second is easy to miss:

1. **Refusal of unknown required features** in every role (candidate requirement 2). A version 4 implementation that ignores a feature it does not recognize makes every later feature unsafe to add, and the list becomes decoration.
2. **Maintenance that consults the list before it deletes anything** (candidate requirement 10). Condition 5 can fence maintenance alone, as Delta's VACUUM protocol check does, only if maintenance reads the list. If version 4 maintenance does, a structure kept outside table metadata, the index files of [V13](problems.md#v13-conforming-version-4-maintenance-may-delete-indexes) among them, can be protected by a feature added after version 4, and the list of discovery roots stops being a version 4 deadline. If it does not, no later feature can protect such files from conforming version 4 maintenance.
3. **A list of tombstoned features, defined as part of the feature list,** so that every version 4 component consults it, and a feature found unsafe after version 4 ships can be withdrawn even from components whose descriptors are compiled in (see [tombstones](#tombstones)).
4. **At least one feature that uses the list in version 4 itself.** A mechanism with no users at launch reads as speculative generality and is easy to decline. The declaration of the data file formats present in a table is the natural first user: it is needed anyway if version 4 stops writing ORC and Avro, since otherwise nobody can tell when a table's last such file is gone ([V2](problems.md#v2-nothing-shows-how-much-of-a-table-remains-in-a-deprecated-format)).

### Which features a snapshot uses, computed upward

A list of features that a writer declares by hand is only as accurate as the writer's attention. The list can instead be computed, in the way an expression's effects and coeffects are computed from those of its parts ([classifying expressions](README.md#classifying-expressions-instead-of-special-casing-them)): each structure contributes the features it uses, and each container inherits the features of what it contains. The result is the transitive closure of the features in use, and it can be stated at two levels.

1. **Per snapshot.** A snapshot uses the features of every structure reachable from it: the formats and format extensions of its data files, the kinds of sidecar attached to them, the table-level features in effect when it was committed, and every feature those depend on through `depends-on`. Each manifest can carry the set for the entries it lists, as the manifest list already carries partition summaries, so that a snapshot's set is the union of its manifests' sets and a commit computes it from the manifests it adds rather than from every manifest in the table.
2. **Per table, subscripted by snapshot.** A table uses, at any moment, the union of the sets of its retained snapshots, and the union is only useful with its subscripts: for each feature, which retained snapshots use it. Expiration updates the record as snapshots leave it.

The closure composes mechanically, which few of the note's dimensions do. Sets of features combine by union, and where a feature's [revisions](#versions-of-a-feature) are recorded per role, the required revision for each role combines by taking the maximum; both are joins, so the answer does not depend on the order in which structures are visited, and a container's declaration is always the join of its parts'. The same discipline as everywhere else governs what cannot be computed. Declaring a feature that is not used costs an unnecessary refusal, and failing to declare one that is used allows a wrong one; so a manifest written by an implementation that does not maintain the set records it as unknown, a snapshot that contains such a manifest has an unknown set, and a component meeting an unknown set falls back to checking structures as it meets them (the fifth rule for [implementations without run-time discovery](#implementations-without-run-time-discovery)), losing only the earliness of its refusal.

The subscripts are what make the closure useful beyond refusal at load time:

- **Time travel.** A reader of an old snapshot needs only that snapshot's features, not the table's. A table whose current snapshot no longer contains ORC data files, but whose retained history does, can be read at its current snapshot by a reader without ORC, and refused only at the snapshots that need it.
- **Retirement.** A feature leaves a table when no retained snapshot uses it, which is the precise answer to [V2](problems.md#v2-nothing-shows-how-much-of-a-table-remains-in-a-deprecated-format): the last ORC data file is gone when ORC appears under no retained snapshot, and an operator can see which snapshots still hold it and when they will expire. Recording counts of files and bytes alongside each feature, as the [third version 4 ask](v4-asks.md#3-declaring-what-a-snapshot-depends-on) proposes for formats, turns the same record into a measure of progress.
- **Maintenance.** Expiration and orphan removal can tell, before acting, whether the snapshots they touch use a feature that is required for maintenance and that they do not implement.

The declaration of the third version 4 ask is this closure restricted to formats, required sidecar kinds and discovery roots, and recorded at table level; the general form adds the subscripts and computes the set rather than trusting each writer to state it.

## A worked example: row lineage

Row lineage, added in version 3, exercises nearly every field of the envelope, and it is an active concern: as of November 11, 2026 the version 4 draft extends it, Spark's change data capture is being built on it, and its readers are still being corrected. It is worth working through in detail.

**What it is.** Every row has a `_row_id`, unique within the table, and a `_last_updated_sequence_number`. Neither is normally stored: a data file records a `first_row_id`, and a row's id is that value plus its position, while its sequence number is inherited from the file's manifest entry. When a writer moves an existing row to another file, as compaction and copy-on-write updates do, the [specification](https://github.com/apache/iceberg/blob/26c9544ea4744df3d09bb3a9abcefd43997fa595/format/spec.md#row-lineage) says the writer "should" write the two fields under rules within which the row's existing `_row_id` "must be copied into the new data file", and the sequence number is copied too unless the row was modified. Rows of a table upgraded from version 2 have no ids until a later snapshot assigns them, and rows updated through equality deletes are treated as removed and re-added.

**Reliance by role.** Under the safety condition, the feature lands as follows:

1. *Reading* is ignorable unless the read projects one of the two fields. A read that does project them relies on them completely, and a reader that derives them wrongly returns wrong values without an error. Issue [#18543](https://github.com/apache/iceberg/issues/18543) is such a case: the vectorized ORC reader in Spark gives every row of a file its `first_row_id`, ignoring the values the file stores. A per-field condition on reliance, of the kind the envelope allows, describes this exactly.
2. *Writing* is required. A writer that rewrites a file without materializing the ids of the rows it moves assigns them new ids by inheritance, and nothing afterward distinguishes those rows from new ones. Condition 4 fails, since the damage is a claim that cannot be detected as stale. Delta reaches the same classification for its [row tracking](https://github.com/delta-io/delta/blob/ecde102bc37ba916810da1aaa41e9d56ba669544/PROTOCOL.md#row-tracking), which is a writer feature: readers that do not know it are unaffected, and writers that do not know it cannot proceed.
3. *Maintenance* is ignorable, since expiration and orphan removal never move rows.

**Scope and instances.** An *instance* of a feature is one particular use of it, as distinct from its definition: one blob of a given blob type, one data file written in a given format, or, for a table-level feature such as row lineage, one table's adoption of it from the snapshot at which it began ([type descriptors and instance descriptions](#type-descriptors-and-instance-descriptions) treats the distinction in full). The scope of a refusal is narrower than the table in two directions. Across columns it is the two fields, since a read that projects neither is unaffected. Across time it is this table's instance, the snapshots committed after its upgrade to version 3, since earlier snapshots carry no ids at all. Delta distinguishes these states explicitly, calling the feature *supported* once writers must assign ids and *enabled* once every row is known to have one, with a backfill between them. Iceberg's specification states the equivalent in prose, and a declaration recorded with the instance, saying from which snapshot every row has an id, would let a consumer know which of a table's snapshots it can rely on.

**Specification versions.** The feature is defined from table format version 3. Pull request [#18526](https://github.com/apache/iceberg/pull/18526) proposes a `_last_updated_timestamp` column defined only from version 4, which under this note would be a revision of row lineage with its own lower bound. Pull request [#18652](https://github.com/apache/iceberg/pull/18652), which makes the Java implementation reject row lineage fields in snapshots of tables below version 3, enforces by hand the first rule of [compatibility with specification versions](#compatibility-with-specification-versions).

**Configuration.** None, declared explicitly. Row ids are assigned by inheritance from values the table already records, and nothing about their meaning depends on a parameter outside the bytes.

**Dependents.** Other features are being built on row lineage, and each would declare it in `depends-on`. Open work on Spark's changelog reads for version 3 tables ([#18317](https://github.com/apache/iceberg/pull/18317)) covers it, and pull request [#18896](https://github.com/apache/iceberg/pull/18896) post-processes it for native change data capture. The secondary index specification [recommends](https://github.com/apache/iceberg/blob/42f4b128f69fb250919f55d2e354e2ec0028229d/format/index-spec.md#recording-the-source-row-location) `_row_id` among its identity columns, noting that it "stays valid when a data file is rewritten, but has to be resolved to a location when reading". An index whose entries carry row ids instead of file and position would survive compaction, which bears directly on [V14](problems.md#v14-each-rewrite-disables-a-snapshot-pinned-index-until-it-is-rebuilt), but only if every writer that compacts the table preserves the ids. That is condition 4 again: such an index inherits row lineage's reliance for writers, and its correctness rests on a rule the specification states as "should".

The example suggests one change to the specification, independent of the rest of this note. The rules for writers that move rows are written as a SHOULD around a MUST, while every consumer of row lineage relies on them as a MUST. Stating them as a MUST for version 3 and later writers, with the cases in which ids may be reassigned listed explicitly, would make the reliance consumers already place on them a requirement.

## Features in general, kinds in particular

The Puffin proposal designs a namespace and a descriptor for blob types, and the contracts note proposes [the same pair](README.md#what-could-be-shared) for data file formats. A list of table features needs both a third time, and format extensions, kinds of sidecar and functions in stored expressions would each need them again. Specifying the pair once, over features in general, and treating each family as a specialization costs less than specifying it five times and keeping the copies aligned.

### One identifier space

The grammar of the Puffin proposal's [Section 4.1](../proposals/puffin-blob-types-and-compaction.md#41-identifier-syntax) names any feature, with the same rules: an identifier without a namespace is reserved for the Iceberg specifications, every other identifier carries a namespace its author controls, and an incompatible revision is a new major version and so a different feature. `relative-paths-v1` would be a feature the specification defines, `com.example.vortex-v1` a data file format a third party defines, and `com.example.ngram-bloom-v1` a blob type. Identifiers of different kinds share one space, so a name means one thing wherever it appears.

### Versions of a feature

A feature's identifier carries its major version, and matching the major version exactly is always safe: a component that implements `com.example.vortex-v1` understands every table that declares `com.example.vortex-v1`, provided the feature's definition never changes. Definitions do change, though, and if every change that a component must understand had to become a new major version, features would multiply with each refinement and implementations would fall behind for reasons that matter to none of their users. We would like compatible changes to be minor revisions within a major version, and the difficulty is to allow that without ever letting a component that implements an earlier revision proceed where it would be wrong to.

The Puffin proposal meets the same difficulty for blob types (see [within Puffin](#within-puffin-compatible-revisions-of-a-blob-type) below), and the answer generalizes:

1. **A revision only adds.** A minor revision may add to a feature's definition: fields, structures, values, rules. It may not change the meaning of anything an earlier revision defines, and it may not relax an obligation an earlier revision states, since a component that relies on the obligation would then be wrong. Either change makes a new major version.
2. **Each addition declares its reliance per role,** under the same safety condition as a feature: an addition is ignorable for a role if a component of an earlier revision, acting in that role, stays correct when it meets the addition's effects, and required otherwise. Where the authors cannot establish that, the addition is required for that role, and the normative text says so (as the preceding subsection on normative text requires of anything the declarations do not express).
3. **The table records the revision each role needs.** A table's entry for a feature records, for reading, writing and maintenance, the lowest revision a component must implement to proceed in that role: the highest revision whose required additions for that role the table actually uses. A writer that begins using such an addition raises the entry in the same commit. A single number, applying to every role, is the conservative default for a feature that does not distinguish roles.
4. **A component proceeds if its revision reaches the table's.** A component implementing revision *r* of a feature may act in role *R* on a table that records revision *t* for *R* if and only if *r* is at least *t*. Otherwise it treats the feature as unrecognized for that role, which under the safety condition means refusal wherever the feature is not ignorable.

Exact major matching is the floor this scheme never goes below. A component that implements a different major version, or none, is governed by the safety condition alone. The scheme only lets a component of an earlier minor revision proceed where every addition it does not implement is either unused by the table or ignorable for the role it is acting in. A revision is to a feature what a named version is to the specification: a named set of additions, against which a component can state what it implements.

The descriptor carries the revision history: for each revision, its number, its additions and each addition's reliance per role, and the `spec-versions` ranges it is defined against, which may differ between revisions. A registry can check the first rule only in part, since whether an addition changes an earlier meaning is a matter for review of the prose, and the descriptor records the reviewer's conclusion, not a proof of it.

### One envelope

Every feature has a descriptor with a common envelope and a body whose schema depends on the feature's kind:

| Envelope field | Meaning |
|---|---|
| `id` | The identifier |
| `kind` | Which family the feature belongs to, from a list the specification defines |
| `definition` | The normative description, in prose. It is authoritative, and it states which of its rules the fields below do not express |
| `reliance` | For each of reading, writing and maintenance, whether a component lacking the feature may proceed, with any condition (the fields a read projects, the files an operation touches) |
| `refusal-scope` | What a refusal covers: a unit, a data file, a snapshot or the table. Named to keep it apart from the Puffin descriptor's `scope`, which says what a blob is bound to. |
| `depends-on` | Features that adopting this one adopts |
| `incompatible-with` | Features that may not be used together with this one, each with the relation over which the incompatibility holds: on the same object, or across a reference such as a table that is the source or the storage table of a materialized view ([incompatibilities](#incompatibilities)) |
| `spec-versions` | For each specification the feature touches, the range of versions it is defined against, and the named versions whose sets include it |
| `revisions` | The minor revisions within the major version: for each, its number, its additions and each addition's reliance per role ([versions of a feature](#versions-of-a-feature)) |
| `config` | Required. Either the explicit value `none`, or the feature's configuration contract: a schema and its digest, and which parameters play the roles of evaluation, composition and provenance |
| `body-schema-digest` | The digest of the schema the kind-specific body conforms to |

The envelope is everything a component needs in order to decide whether to proceed without understanding the feature, which is the purpose of the safety condition above. The body is everything a component needs in order to use it, and only components that implement the feature read it.

#### Normative text, and what the descriptor does not say

The envelope is not meant to be a complete, machine-decidable semantics of a feature, and nothing in this note depends on one. A feature is specified by normative prose, written for people, and the machine-readable fields are a summary of the part of that prose a tool needs in order to decide whether to proceed. Much of what matters about a feature will not fit the fields, at least at first: how it interacts with other features, where it departs from a rule of the specification it extends rather than adding a new one, and conditions on its reliance that are richer than the enumerations. Such rules belong in the prose, and the prose is obliged to be explicit about them:

1. **Every definition lists what its descriptor does not express.** A feature's normative text contains a section naming each of its rules that the machine-readable fields do not capture, or saying "none". A reviewer, and an implementer reading the definition for the first time, then knows which rules a tool has checked and which only a person can.
2. **What the fields cannot say, they say conservatively.** Where an unexpressed rule bears on whether a component may proceed in some role, the descriptor declares the feature required for that role, so that a tool reading only the descriptor refuses rather than proceeds. The prose may then say when an implementer who has read it can do better. The fields may be more cautious than the prose, and never less.
3. **Interactions and departures are written down, not inferred.** A feature's definition names the other features it interacts with and says how, and names each rule of a parent specification it modifies. `depends-on` records only what adopting the feature adopts; it is not a model of interactions.

Rules move from prose into fields as the vocabulary for them matures, under the same discipline the [proposal on sidecars](sidecars.md#keeping-the-abstraction-small) applies to its slots: a rule becomes a field when several features need it expressed the same way, not before. Machine-readable coverage is a direction the envelope allows, never a precondition for specifying a feature.

> **TODO (declarations as expressions).** Reliance, scope and the ranges of `spec-versions` are written above as enumerations with a few parameters, and they can begin life that way: `ignorable` or `required`, per role, optionally qualified by a list of field ids, a kind of operation or a version range. They will probably not stay that way. Each is already a predicate over the properties of an operation (its role, the fields it projects, the files it touches, the table's version, its tolerance), and the qualifications that accumulate on an enumeration are a predicate language arriving one special case at a time, as the note's discussion [on structure](README.md#on-structure) anticipates. The eventual form is an expression over the fixed, versioned vocabulary of operation properties that the note proposes passing [down the stack](README.md#requirements-flow-down-verdicts-flow-up), evaluated by the safety condition. Three constraints carry over from what the note asks of other stored expressions. Such an expression reads nothing but the declared properties of the operation, so that every component evaluates it identically. Its language is itself a feature, with its own identifier and `spec-versions`, so that a newer vocabulary is detectable. And a component that cannot evaluate a declaration, because it meets an unknown operator or property, treats the feature as required for the role in question, which keeps an older binary on the side of refusal. The enumerated form should be chosen so that each of its values is a constant of the later language, and a declaration written today then remains valid when the language arrives. The language is an option, not a goal the mechanism depends on: until and unless it arrives, a condition the enumerations cannot express is written in the definition's prose, and the field records the conservative value, as the preceding subsection requires.

#### Compatibility with specification versions

A feature is written against particular versions of particular specifications, and its descriptor says which, explicitly, instead of leaving a reader to infer it from the date it was registered. `spec-versions` maps the name of each specification the feature touches (the table specification, the view specification and the materialized view extension proposed for it, Puffin, the expressions specification, the REST catalog specification) to three things:

Each range may be given separately for reading, writing and maintenance, and must be wherever they differ: a retired feature stops being written at one version and may go on being read indefinitely, as equality deletes are in tables upgraded to version 4 ([an illustration](v4-as-features.md)).

1. **A lower bound**, the earliest version under which the feature is defined. A feature that relies on deletion vectors or row lineage is defined from table format version 3, and one that relies on relative paths from version 4. The Puffin proposal's direction on positional invalidation ([Section 14.18](../proposals/puffin-blob-types-and-compaction.md#1418-positional-invalidation)) is an example, since it is sound only where a data file has at most one deletion vector.
2. **An upper bound, where one exists.** A later version can prohibit what a feature depends on, as version 4 prohibits equality deletes, or absorb the feature into its own definition, after which declaring the feature separately is meaningless. Most features have no upper bound when they are written; a bound is added by revising the descriptor once a version that ends the feature is published.
3. **Membership in named versions.** When a minor or major version adopts a feature into its set, deprecates it or removes it for some role, the feature's descriptor records it, in the vocabulary of [minor versions as sets of features](#versions-as-sets-of-features), so that "supports version 4.2" can be expanded into features mechanically and a feature's history of adoption can be read from its descriptor.

The reliance declarations of the envelope may also differ by version, and `spec-versions` is where they are keyed when they do. Statistics files are the case in point: reachable only through a field that implementations predating them do not parse, they are required for maintenance at the versions where that exposure exists, and they would be ignorable at a version whose maintenance is obliged to walk them.

Three rules follow:

1. A writer MUST NOT use a feature in a table whose format version lies outside the feature's declared range for the table specification, and likewise for the other specifications it touches, such as the Puffin version of the file a blob is written to.
2. A component that meets a feature declared outside the range for the version it is reading treats the feature as unrecognized, which under the safety condition means refusal wherever the feature is not ignorable.
3. Upgrading a table's format version re-checks every feature the table declares against the new version, and the upgrade fails, or the feature is removed first, where a feature's range ends below the new version.

The registry can check the first rule's precondition when a feature is registered: a descriptor whose range names a version that does not exist, or whose dependencies are defined only outside its range, is malformed.

#### Features that span specifications

Iceberg's specifications are written as though they were independent, and their independence can only be verified in retrospect, once a change to one has been seen not to disturb the others. A materialized view is defined by the view specification, yet its refresh state records the snapshot identifiers of tables, so its meaning rests on the table specification's rules for snapshots. An index defined by its own specification is reachable only if table maintenance spares it. A Puffin blob type is bound to data files whose identity the table specification defines. A feature's reference to specifications is therefore multivalued in fact, not only in form, and three rules keep it honest:

1. **"Touches" is read broadly.** A feature lists every specification whose rules its correctness relies on, directly or through another feature, not only the one in which it is defined. Where its authors are unsure whether it relies on a specification, it lists it: an unnecessary entry costs a version check, and a missing one lets a change to that specification break the feature unnoticed.
2. **Features are declared wherever they are used.** A feature that touches several specifications appears in the list of every object it affects: a feature of a materialized view that relies on table snapshots is declared both on the view and on its storage table. The view specification has a format version but no list of features yet, and would need one under the same increment rule as tables.
3. **Closure crosses specifications.** The upward closure of [features in use](#which-features-a-snapshot-uses-computed-upward) follows references between objects: a materialized view uses its own features, those of its storage table at the storage table's current snapshot, and those of the source tables at the snapshots its refresh state records. A component that can check only some of the specifications involved treats the features it cannot check as unrecognized.

#### Incompatibilities

A feature may also need to say that it cannot be combined with another, and the cross-specification case is the one that matters most: a table feature under which a table's data can change without a new snapshot, for instance, would make any materialized view that records that table's snapshot identifiers wrong without anyone noticing. Under one identifier space the materialized view extension is itself a feature, so "incompatible with materialized views" is an entry like any other in `incompatible-with`, qualified by the relation over which it holds. Two relations cover the cases foreseen: *same object*, for two features that cannot both be used by one table or one view, and *across a reference*, for a feature that cannot be used by a table that a view reads as a source or uses as its storage table.

1. **A writer MUST NOT commit a state in which two incompatible features apply** over the relation their declaration names. Creating a materialized view over a table that uses a feature incompatible with materialized views fails, and so does adopting such a feature on a table that a materialized view already reads.
2. **Either side's declaration suffices.** A newer feature can declare its incompatibility with an older one, and the older one cannot know of the newer, so a component checks the union of the two declarations.
3. **The check runs over the closure.** Because the [closure of features in use](#features-that-span-specifications) follows references from a view to the tables it reads, an incompatibility across a reference is visible to a component evaluating either object, and a component that finds two incompatible features already in place refuses rather than choosing one.
4. **Partial incompatibilities are declared whole.** Where two features conflict only under some conditions, the descriptor declares the incompatibility unconditionally, and the normative text says when an implementer who has read it may combine them, as the rule on [normative text](#normative-text-and-what-the-descriptor-does-not-say) requires of anything the declarations cannot express.

#### Configuration

A type identifier fixes the format of a structure but not the parameters it was built under, and some of those parameters are needed to interpret it. The Puffin proposal's [Section 7](../proposals/puffin-blob-types-and-compaction.md#7-build-configurations) was written for blob types, where an n-gram filter probed under the wrong normalization excludes files that hold matching rows. The note's [configuration paranoia](README.md#configuration-paranoia) finds the same hazard in data file formats, in timestamps whose adjustment to UTC is unrecorded and dates that need a calendar rebase only a later writer flags ([E27](problems.md#e27-parameters-that-change-what-the-bytes-mean-are-kept-outside-the-bytes)). Default values that are expressions meet it as the session time zone and clock they were evaluated under ([V8](problems.md#v8-defaults-depend-on-context-that-nothing-records)), and format extensions and sidecars will meet it as encoding parameters. It is therefore a property of features in general, and the envelope carries it.

Every feature's descriptor MUST declare its susceptibility in `config`, and a descriptor without the field is malformed. Most features will declare `none`, and that is the point of requiring it: an explicit `none` is a claim a reviewer can check and a later revision cannot quietly contradict, whereas an absent field leaves every reader to assume. A Puffin blob type descriptor written under [phase 1](../proposals/phase-1-open-blob-types.md), which may not contain `config`, is read as declaring `none`, and the phase's rule that such a type be self-describing is what makes that reading true. A feature that declares a contract inherits the rules of the Puffin proposal's Section 7, generalized from blobs:

1. **Classified parameters.** The contract names each parameter's roles: *evaluation*, needed to interpret the structure correctly; *composition*, which must agree for two instances to be combined, as by a compaction that concatenates data files or merges blobs; and *provenance*, recorded so that people and tools can find instances built a particular way.
2. **Configurations identified by digest.** A configuration is identified by the digest of its canonical form, prefixed by the digest of the feature's configuration schema, so that two features whose documents coincide never share an identity.
3. **Recorded per instance.** Every instance of a feature with evaluation parameters records the digest of the configuration it was built under, in the place its kind defines: a blob's properties, a data file's per-instance description, a sidecar's reference.
4. **Resolve or decline.** A component that cannot resolve and verify an instance's configuration treats the instance as not understood, which under the safety condition means declining it where the feature is ignorable and refusing where it is required. No instance is interpreted under an assumed configuration.
5. **Revocation by digest.** A configuration found to be defective is revoked in table metadata, and every instance built under it goes out of service with one commit. The revocation list for writer versions proposed for [E26](problems.md#e26-statistics-from-a-defective-writer-are-trusted-indefinitely) is this rule applied to provenance.
6. **No late evaluation parameters.** A feature MUST NOT acquire evaluation parameters after its first published version. One that needs them takes a new major version, so that implementations which know the old version, and would interpret instances without a configuration, never meet an instance that needs one. This is the rule of the proposal's [Section 11.4](../proposals/puffin-blob-types-and-compaction.md#114-configuration-references), and it is why `none` must be declared deliberately: it is a promise about every future revision within the major version.

The contract's schema and digest are part of the descriptor, so they are compiled into implementations that link features at build time, and the comparison an implementation makes against a resolved configuration is a set difference over keys.

### Type descriptors and instance descriptions

Two kinds of metadata are in play, and the note has so far called both "descriptors". They differ in subject, in lifetime and in who can be expected to read them, and keeping them apart settles several questions that are otherwise muddled.

A **type descriptor** is metadata about a feature: one document per identifier, published with the feature's definition, listed in the registry and compiled into implementations. It changes rarely, only by compatible revision within a major version, and almost always resolves, since an implementation of the feature carries it. It says nothing about any particular table, file or blob.

An **instance description** is metadata about one use of a feature, in the scope the instance declares or the subject it sits beside: a blob, a data file, a sidecar, a table. It is written with the instance, lives exactly as long as the instance, and is the thing a reader actually meets. It must say which type it is an instance of, and carry everything about the instance that the type cannot know.

| | Type descriptor | Instance description |
|---|---|---|
| Subject | A feature | One instance: a blob, a data file, a sidecar, a table's use of a feature |
| Written | When the feature is defined or revised | With the instance, in the same commit |
| Changes | Rarely, by compatible revision; never within a revision | Never, for the life of the instance |
| Resolvable | Almost always, from the implementation or the registry | Always, since a reader holding the instance holds it |
| Carries | Envelope and body: reliance, refusal scope, `spec-versions`, `config` contract, kind-specific rules | A reference to the type, the binding to its subject, a configuration reference, provenance, and any strengthening of the type's reliance |

The two sit between the data and the kind, each referring outward from the instance:

```mermaid
flowchart RL
    subgraph global["Global: published once, revised rarely"]
        K["Kind<br/>defined by the specification, closed"]
        T["Type descriptor<br/>envelope and body<br/>e.g. com.example.ngram-bloom-v1"]
    end
    subgraph local["Local: one per instance, immutable"]
        I["Instance description<br/>e.g. a blob's footer entry,<br/>a data file's manifest entry"]
        C["Configuration document<br/>in-band or in a store"]
    end
    D["Data<br/>the subject: a data file,<br/>its rows or a unit within it"]
    T -- "specializes" --> K
    I -- "instance of: identifier,<br/>revision, required-features" --> T
    I -- "built under: config digest" --> C
    C -. "conforms to: schema digest" .-> T
    D -- "described by; the instance binds<br/>to it by location, length, content hash" --> I
```

A reader starts from the data on the right and meets its instance description first. From there it resolves the type, which it usually already holds, and the configuration, which it must resolve or decline. Then it checks the instance's binding against the data before trusting anything the instance says.

An instance description carries, at a minimum:

1. **A reference to its type**: the full identifier, the revision written under, and the `required-features` a reader must understand. The revision may be pinned further by the digest of the type descriptor it was written against.
2. **Its binding to its subject**, memorialized as [Appendix E](README.md#appendix-e-bindings-between-a-file-and-the-metadata-that-tracks-it) requires: location, length and, wherever one is available, a content hash, so that a reader can tell that the subject is the one the instance was made for.
3. **A configuration reference**, the digest of the configuration it was built under, wherever the type's `config` contract declares evaluation parameters.
4. **Provenance**: the identity and version of the writer, which is what revocation by writer version ([E26](problems.md#e26-statistics-from-a-defective-writer-are-trusted-indefinitely)) needs.
5. **Any strengthening of reliance or refusal scope** for this instance, never a weakening, by the rule the note gives for [sidecars](README.md#type-layer-and-instance-layer).

One exception to the separation is deliberate. A component that has never seen a feature cannot resolve its type descriptor, and a compiled binary should not try to fetch one in production. So the instance descriptions that a component must act on before understanding the feature, the entries in a table's list of features above all, carry a copy of the envelope fields the safety condition needs: reliance by role, scope and `spec-versions`. That copy is a cache of the type descriptor for the one decision of whether to proceed, never a substitute for it, and where the two disagree the stronger declaration governs, as the [rules for compiled-in implementations](#implementations-without-run-time-discovery) say.

Existing structures already sort themselves this way, without the vocabulary:

| Kind | Type descriptor | Instance description today |
|---|---|---|
| Puffin blob | Proposed (Puffin proposal, Section 5) | The blob's footer entry: `type`, `fields`, `properties`, offset and length |
| Data file format | Proposed ([format descriptor](README.md#a-global-versioned-type-descriptor-and-a-per-instance-description)) | The manifest entry, which records only `file_format`, location and length; the format extensions, writer identity and content hash are missing |
| Deletion vector | Implicit in the specification | The delete file's manifest entry, with `referenced_data_file`, offset and size |
| Table feature | Proposed above | The format version alone today; an entry in the table's list of features under this note |

The word "manifest" is avoided for the second kind on purpose. Iceberg's manifests are one container in which instance descriptions live, for data and delete files, and the Puffin footer is another, so naming the concept after one container would mislead about the rest.

The levels stop there. Above the type descriptor sits only the kind, which the specification defines and keeps closed, so there are two levels anyone outside the project writes, and one the project owns.

### Versions of the descriptor grammars

The descriptors are themselves written in grammars that can change: the envelope above, the body schema of each kind, the identifier grammar, and the form of instance descriptions. Each needs a version, and each descriptor and instance description says which version of each grammar it conforms to (for a body, `body-schema-digest` identifies the exact schema, and a version number beside it makes the digest readable by people). We expect these grammars to change rarely, and we propose to change them with great care, under rules modeled on those that keep Protocol Buffers messages compatible across decades of independent producers and consumers:

1. **A member's name is permanent.** A member once defined keeps its name, type and meaning for the life of the grammar's major version. Changing its type or meaning means defining a new member under a new name.
2. **New members are optional.** A member added within a major version may be absent, and its absence means the conservative reading, which the grammar states when the member is added. No member becomes required within a major version.
3. **Unknown members are ignored and preserved.** A consumer disregards a member it does not recognize, as phase 1 of the Puffin proposal already requires, and a tool that rewrites or mirrors a descriptor, such as a registry or a cache, copies unrecognized members unchanged rather than dropping them.
4. **Removed names are reserved.** A member that is withdrawn is deprecated within its major version and never deleted; when a new major version deletes it, the name stays reserved so that it cannot return with another meaning.
5. **Enumerations say what an unknown value means.** An enumerated member may gain values only if the grammar states what a consumer does with a value it does not recognize. For any member that bears on whether a component may proceed, reliance first among them, an unknown value is read as the most conservative one, so that a consumer older than the value refuses rather than proceeds.
6. **Anything else is a new major version.** A change that cannot be made under the first five rules creates a new major version of the grammar, and a consumer that does not implement the major version a descriptor declares treats the feature it describes as unrecognized.

The envelope grammar is the one feature whose version the feature list cannot carry, since a component must parse the envelope before it can read any list. Its version is therefore tied to the specification itself, through `spec-versions`, and changes to it are made only by the specification's own process, never by registry review. Body schemas of kinds the specification defines are governed the same way. These are promises about process as much as rules about syntax: a grammar that changes often cannot be relied on however carefully each change is made, and the expectation that these grammars change rarely is part of what is proposed.

### Kinds as dependent specializations

A kind does two things: it defines the schema of the body, and it constrains the envelope. The second is where the note's constraints between dimensions live. A kind can fix a dimension for all its members, or forbid some of its values, so that a member's descriptor cannot claim what its kind cannot deliver:

| Kind | Body | Constraint on the envelope |
|---|---|---|
| Table feature | Usually empty; the definition is the substance | None beyond the envelope's own |
| Data file format | Read and write tiers, type mappings with ranges, addressable units, whether it is splittable ([format descriptor](README.md#a-global-versioned-type-descriptor-and-a-per-instance-description)) | Required for reading and writing the data files in it, with data-file scope |
| Format extension, such as an encoding | What it changes in the format's decoding | Depends on its format; required for reading with data-file or unit scope |
| Sidecar kind | Reference, assertion polarity, carry-forward ([sidecars](sidecars.md)) | A discovered sidecar kind must be ignorable for reading; an instance may strengthen reliance and never weaken it |
| Puffin blob type | The descriptor of the proposal's [Section 5](../proposals/puffin-blob-types-and-compaction.md#5-blob-type-descriptors) | Ignorable for reading and writing, fixed by the derivation rule; what maintenance must protect is the file holding the blobs, so that requirement belongs to whatever references the file (a statistics file field, a discovery root), not to the type |
| Function in a stored expression | What it reads from its evaluation context and what it can do ([classifying expressions](README.md#classifying-expressions-instead-of-special-casing-them)) | Required for writers that evaluate it; for readers, as the expression's use decides |

Some consequences fall out of the arrangement without further rules:

1. **Only features that are not ignorable somewhere need to be listed in a table.** A component that meets an ignorable feature can decline it on the spot, so listing it buys nothing for correctness. Blob types, being ignorable by construction for reading and writing, never need a table's list on their own account; the structures that hold them may, for maintenance, which is what the discovery roots of [version 4 ask 3](v4-asks.md#3-declaring-what-a-snapshot-depends-on) provide. Data file formats and their extensions always do, and listing them is what moves the refusal of [E16](problems.md#e16-unsupported-formats-and-features-are-discovered-mid-scan) and [E28](problems.md#e28-a-reader-cannot-tell-whether-it-can-decode-a-data-file-before-opening-it) from mid-scan to table load.
2. **Registries can be one file with a kind column, or one per kind.** Either way the review rule is the same: a well-formed identifier, a plausible claim to its namespace, and a descriptor whose envelope satisfies its kind's constraints.
3. **The set of kinds is closed, and defined by the specification.** A new kind brings a new body schema and new constraints, and those are specification work. Opening the set of kinds to third parties is a further step that we do not propose.

The risk in this section is building an ontology before anyone needs one. The envelope is small, and kinds can be specified one at a time as each family is taken up. Table features and blob types are enough to show that the shape works, and file formats are the first kind for which a third party is already waiting. The note's [sidecars as a waypoint](sidecars.md#where-the-label-leads-and-what-is-not-requested) proposed one envelope for kinds of sidecar. This section takes that proposal one level up, to features of every kind.

## Implementations without run-time discovery

Iceberg's implementations in Rust, C++ and Go link what they support at build time, and the Puffin proposal's [Section 9.4](../proposals/puffin-blob-types-and-compaction.md#94-implementations-without-run-time-discovery) sets out rules for blob types compiled in that way. The same rules extend to features of every kind, and the extension makes one requirement on the table that the rest of this note has left implicit.

A compiled-in implementation meets two kinds of feature: those it was built with, whose descriptors it carries, and those it has never seen, whose descriptors it cannot fetch, since a binary in production should not depend on reaching a registry. For the second kind it can decide only from what the table says. Each entry in a table's list of features therefore carries the envelope fields the safety condition needs, copied from the descriptor when the feature is adopted: the identifier, the reliance for each role with its conditions, the scope of refusal, and the specification versions. An entry that named a feature without these would leave an implementation that does not know it no choice but to refuse.

The rules for a compiled-in implementation are then:

1. **Feature descriptors are compiled in.** The implementation carries the full descriptor, envelope and body, of every feature it implements, including the `spec-versions` and the configuration schema it was built against, and exposes the set at run time, so that what a binary supports can be audited from the binary.
2. **The implemented specification versions are compiled in.** The implementation records exactly which versions of each specification it implements, minor versions included, and never infers support for one version from support for another.
3. **Every table is checked before use.** On loading a table, the implementation compares the table's declared format version and each declared feature with its compiled-in set, and evaluates the safety condition for the role it is about to act in. A feature it does not implement is decided from the table's entry alone. A feature it implements but whose declared range excludes the table's version, by the compiled-in descriptor, is treated as unknown.
4. **The stronger declaration governs.** Where the table's entry and the compiled-in descriptor disagree about reliance, refusal scope or range, the implementation obeys whichever is more restrictive. The compiled-in copy is never weakened by a newer declaration, and a newer declaration that strengthens a requirement is obeyed by an older binary.
5. **Structures are checked as they are met.** A blob's `required-features`, a data file's format extensions and any other per-instance declaration are compared with the compiled-in set when the structure is read, and an unrecognized entry leads to declining the structure where it is ignorable and refusing where it is not.
6. **Every refusal and every declined structure is reported** with the feature responsible, so that an operator can tell a binary that needs rebuilding from a table that needs repair.

The Java library applies the same checks. Discovery at run time changes when the set of implemented features is fixed, which is when a class path is assembled instead of when a binary is linked, and nothing about what is checked.

## Within Puffin: compatible revisions of a blob type

This section is the blob-level instance of the [versions of a feature](#versions-of-a-feature) above, and is simpler, because declining a blob costs only coverage. It names its required additions individually rather than by revision number, since a blob type's additions need not form a sequence.

The Puffin proposal puts a blob type's major version in its identifier and leaves compatible evolution to the descriptor, with an open question ([Section 14.14](../proposals/puffin-blob-types-and-compaction.md#1414-descriptor-evolution), and the TODO under [Section 4.1](../proposals/puffin-blob-types-and-compaction.md#41-identifier-syntax)) about how a reader is to know what a newer writer may have added. The condition above answers it, and more cheaply than at table level, because a blob is ignorable by construction.

Within one major version, a type's definition can be revised. Each revision may add properties, payload sections or descriptor fields, and each addition falls into one of two kinds:

- **Ignorable additions**, which a reader of an earlier revision can disregard and still evaluate the blob correctly, such as an optional property that only refines selectivity.
- **Additions that must be understood**, without which a reader would draw a wrong conclusion from the blob, such as a new encoding of part of the payload.

The first kind needs nothing new. For the second, we would propose a reserved property, `required-features`, holding a list of feature identifiers, in the grammar above and namespaced under the type. A reader that finds an unrecognized name in that list declines the blob. Because declining a blob costs only index coverage, this is the critical bit of X.509 extensions and CoAP options at no correctness cost, and it lets a type evolve within a major version for every change except the one the proposal already reserves for a new major version, a relaxation of an obligation the descriptor states.

A writer would also record the revision it wrote under, as a `type-revision` property, for provenance. Nothing would depend on it for correctness, since the list of required features carries that.

## What other formats do

The [survey of prior art](prior-art.md#e11-ignoring-as-the-default-or-as-the-exception) found the full range:

- **Delta** declares capabilities as named table features in its protocol action, separately for readers and for writers, and checks them when a snapshot loads. Readers and writers "must not ignore table features", while unrecognized fields "are never required" and must be ignored. Its VACUUM protocol check is a feature whose only purpose is to make older maintenance refuse. That is a fence for one role alone, the case condition 5 describes. Delta's drop-feature command rewrites a table until a feature can be removed, though its protocol text says features are never removed, an inconsistency the survey records.
- **Lance** keeps separate reader and writer feature bitmaps, and either side returns an unsupported error on a flag it does not know.
- **Hudi** fences by an integer table version, all or nothing, with an unknown version failing at load.

Delta's arrangement is closest to the one proposed here. What the condition adds is the reason a feature lands where it does, stated in terms a reviewer can check: a feature is a writer feature because condition 4 fails for writers and holds for readers, not because its authors judged it so.

## Costs

The arrangement is not free, and four costs are worth weighing:

1. **A larger support matrix.** Implementations can support different subsets, and tests have to cover the combinations that occur. Named versions bound this, since most implementations will support whole versions and only some will run ahead.
2. **Feature interactions.** Two features that are each safe to ignore may not be safe to ignore together. No declaration computes this; each feature's normative text names the interactions its authors foresaw, each new feature's review asks about the rest, and a combination whose safety nobody has established is declared conservatively.
3. **Removal.** A feature once used is hard to remove, and Delta's experience shows that removal means rewriting the data that uses it. Removal could be specified as its own operation, with a declared end state.
4. **Stale declarations.** A writer that adopts a feature without declaring it defeats the mechanism. The list being required for writers, which is the point of introducing it under the last increment, is the guard.

## Candidate requirements

1. Table metadata carries a list of the features a table uses, each declaring its reliance for reading, writing and maintenance, and the scope over which a refusal applies.
2. A component that meets an undeclared or unrecognized feature that is required for its role refuses, at the earliest point the declaration allows, and says which feature it lacks.
3. A feature may declare itself ignorable for a role only if it meets conditions 3 to 5 above for that role, and its specification text shows why.
4. Major and minor versions are named sets of features, and a table's version is derived from the features it uses.
5. The list of features is introduced under one integer increment, preferably version 4, after which a feature that needs refusal by only some roles is added without one.
6. Puffin blob types reserve `required-features`, and a reader declines a blob that lists a feature it does not recognize.
7. Features of every kind share one identifier grammar and one descriptor envelope, and each kind the specification defines gives the schema of its body and the constraints it places on the envelope.
8. Every feature's descriptor declares, for each specification it touches, the range of versions it is defined against and the named versions that include it, and writers, readers and table upgrades check it.
9. Every feature's descriptor declares its configuration contract or, explicitly, that it has none; instances of a feature with evaluation parameters record the digest of their configuration, and a component that cannot resolve it declines or refuses as the feature's reliance requires.
10. Maintenance (expiration, orphan removal, purge, and copying or registering a table) reads the list of features before it deletes or moves any file, and refuses when the list names a feature that is required for maintenance and that it does not recognize.
11. Every feature is specified by normative prose that lists the rules its descriptor does not express, or says that there are none; where such a rule bears on whether a component may proceed in a role, the descriptor declares the feature required for that role.
12. A feature's identifier carries its major version, and a change to the meaning of an existing part of a feature, or a relaxation of an obligation it states, is a new major version. Within a major version, revisions only add; each addition declares its reliance per role; a table's entry for the feature records, for each role, the lowest revision a component must implement to proceed in it; and a component of an earlier revision refuses wherever the table's recorded revision for its role exceeds its own.
13. The features a snapshot uses are recorded as the transitive closure of those used by every structure reachable from it, computed upward (each manifest recording the set for its entries, and each snapshot the union of its manifests' sets) and closed under `depends-on`; a table records, for each feature, the retained snapshots that use it. A structure written by an implementation that does not maintain the set records it as unknown, and a component meeting an unknown set checks structures as it meets them.
14. Every descriptor grammar (the envelope, each kind's body schema, the identifier grammar and the form of instance descriptions) carries a version that each descriptor declares, and evolves within a major version only by the six rules of [versions of the descriptor grammars](#versions-of-the-descriptor-grammars); the envelope grammar and the body schemas of specification-defined kinds change only through the specification's own process.
15. A feature's `spec-versions` names every specification whose rules its correctness relies on, directly or through other features, and a feature that touches several specifications is declared on every object it affects; the closure of features in use follows references between objects, including from a view to the tables it reads.
16. A feature declares in `incompatible-with` the features it cannot be used together with, and the relation over which each incompatibility holds; writers do not commit a state in which two incompatible features apply over that relation, components check the union of both features' declarations over the closure of features in use, and an incompatibility that holds only under some conditions is declared unconditionally.
17. Every named version, major or minor, records the features it adds and the deprecations it makes, each deprecation recording at least its status and strength per role, its subject, any replacement, any planned removal per role, any migration duty with its trigger, the treatment expected of unaware rewriters, its applicability and whether components report it; a deprecation announced between major versions is published as a minor version; and the specification does not use the word "deprecated" without these parameters.
18. A feature, a major version of one or a range of its revisions found to be unsafe is tombstoned: the tombstone records its subject, its consequence per role (never to write; to refuse or decline when reading; to preserve, by default, under maintenance), an optional forward reference to a replacement or repairing revision, any remediation, and its reason; tombstones are permanent and lifted only by a later record; and tables carry them, by raising the recorded revision past a tombstoned range, or by listing a tombstoned feature or major version in table metadata, which every component consults.

## Open questions

1. Whether the feature list belongs in table metadata, in snapshot metadata, or in both, given that branches can differ in what they use.
2. Whether an implementation should be allowed to declare support for a minor version partially, and how that would be reported.
3. Whether the condition's verdict for each existing version 4 feature, as tabulated above, matches what the feature's authors intend; the table is our reading, not theirs.
4. How a catalog that plans scans on a client's behalf reports a refusal caused by a feature the client lacks.
