# Prose Fixes to the Iceberg Specifications

*Solutions that need only prose: a sentence or two in an existing specification, with no new field and no new mechanism. The problems they address are stated in the [register of problems](problems.md) and cited here by identifier. Solutions that need a new field are in the [version 4 asks](v4-asks.md).*

**Status:** draft, November 10, 2026, against apache/iceberg `main` at commit `26c9544`. Proposed wording is a starting point, not finished specification text.

Each item names the section it would change, proposes wording, and cites the problems it addresses. A remark follows where something about the fix itself, rather than the problem, needs saying, such as whether the Java implementation already complies. The items are numbered so that they can be discussed one at a time.

## Table specification

### 1. Retry conditions for deletion vectors and column files

**Section:** Commit Conflict Resolution and Retry.

**Proposed addition to the list of conditions:**

> * Operations that add a deletion vector or a column file for a data file must verify that the data file is still in the table, and that no deletion vector or column file for the same data file and fields has been added since the operation's base version.
> * Replace and overwrite operations must also verify that no deletion vector, position delete file or column file has been added since the operation's base version for any of the data files they remove, unless the operation adds, in the same commit, deletion vectors, position delete files or column files against its output files that remove or supply exactly the rows and values those additions did.
> * Replace and overwrite operations must also verify that no equality delete file added since the operation's base version applies to any of the data files they remove, unless that delete file also applies to the operation's output files (as it does when they keep the data sequence number of the operation's base version), or the operation can show that the delete file matches no row of the removed data file, for instance because the delete file's bounds for its equality fields do not overlap the data file's.
> * Overwrite operations must verify that the files they remove are still in the table, as replace operations must.

**Addresses:** [E1](problems.md#e1-the-retry-conditions-omit-row-level-deletes), [V4](problems.md#v4-the-retry-conditions-do-not-cover-column-files).

**Remark.** The Java implementation already performs both checks for deletion vectors, so the text codifies its behavior. The exception in the second condition admits a compactor that carries concurrent deletes forward onto its outputs instead of aborting, for example by recording how its rewrite moved rows and translating the deletes' positions accordingly; it states the outcome a repair must achieve without prescribing how, so the condition remains a rule about correctness.

The second and third conditions govern overwrite operations as well as replace operations, and the fourth gives overwrites the check that replace operations already have, because the existing list has no rule for `overwrite` at all. A copy-on-write update commits as an overwrite, so without them it would be governed by nothing; the [commit-history harness](../../commit-histories) finds four histories in which such an update, retried over a concurrent deletion vector or equality delete, restores the deleted row under the existing list and under the first three conditions written for replace operations alone.

Because the conditions judge by file rather than by row, they also refuse some retries that would have been correct. When both writers delete the same row, as when a copy-on-write delete races a deletion vector or an equality delete for that row, or two deletion vectors name it, the two deletes agree, and the conditions refuse the later commit all the same. This is the ordinary write-write conflict of optimistic concurrency: the refused writer retries from the new base and loses time, never correctness. The harness counts ten such refusals in its E1 slice, beside four from the conservative bounds test of the third condition.

The same strictness bears on commits of unknown outcome ([E4](problems.md#e4-commits-of-unknown-outcome)). A writer told that its commit failed, when in fact it was applied, retries over its own commit, and the first and fourth conditions now refuse that retry, since its deletion vector or its removed file has already changed. The table stays correct, but the writer reports a failure for a commit that took effect, and may redo the work at a level the table cannot see. In the harness's E4 slice such misreports rise from 30 under the existing list to 80 under these conditions, and fall to none when the commit carries an identity by which the writer can recognize it ([version 4 ask 2](v4-asks.md#2-the-read-base-of-each-snapshot)), or, over the REST protocol, an `Idempotency-Key`.

The third condition differs from the first two in what it can inspect. An equality delete names no file, so whether it touches a replaced file is a question about values, and the condition makes the conflict the default and puts the burden on the operation to rule it out; an implementation that cannot decide treats the delete as a conflict. Its first exception admits a rewrite whose output stays within the delete's reach, which is the position of a compaction that commits at its starting sequence number, and leaves a copy-on-write update, whose output takes the new snapshot's sequence number, subject to the check. The Java implementation performs this check but narrows it by the writer's conflict-detection filter, which misses a delete outside the filter that matches a row of a replaced file; [pull request 18846](https://github.com/apache/iceberg/pull/18846), open as of November 18, 2026, removes the narrowing, after which the text would codify Java's behavior here too. Since version 4 forbids writing new equality deletes ([pull request 17783](https://github.com/apache/iceberg/pull/17783)), the condition governs version 2 and 3 tables, and a version 4 table only when the operation's validation window spans its upgrade; equality deletes that predate the operation's base version were applied when it read, and are no conflict.

### 2. What a retry validates against

**Section:** Commit Conflict Resolution and Retry.

**Proposed addition:**

> The conditions are verified against every commit between the version the update was based on and the new current version. A writer that cannot verify a condition must not commit.

**Addresses:** [E2](problems.md#e2-the-validation-window-and-what-to-do-outside-it-are-unstated).

**Remark.** Without the read base of [version 4 ask 2](v4-asks.md#2-the-read-base-of-each-snapshot), only the writer knows which version its update was based on, so the rule binds writers but cannot be checked by anyone else.

The rule costs progress wherever expiration outpaces writers. A writer that finds any snapshot committed since its base already expired cannot verify, and under this rule must abandon its work and start again from the current version, even when nothing it would have found conflicts. The cost is therefore set by retention relative to the longest interval between a writer's read and its commit: a deployment that expires snapshots faster than its slowest writers commit converts the difference into refusals, and one whose retention covers that interval pays nothing. In the E2 slice of the [commit-history harness](../../commit-histories), where half the concurrent deletes touch a partition the retrying writer never reads, the rule refuses 96 retries that would have been safe beside the 64 it prevents from restoring deleted rows; the proportion reflects how the slice was built, not the rule.

### 3. Paths are not reused

**Section:** File System Operations.

**Proposed addition:**

> A file's location identifies it for as long as any snapshot of the table references it. Writers must not write a new file at a location that has previously held a different file of the table.

**Addresses:** [E5](problems.md#e5-paths-may-be-reused), [V9](problems.md#v9-relocation-becomes-routine-and-location-based-identity-does-not-survive-it).

**Remark.** Java writers already generate unique names. The rule may need care around relocation, where a copied table legitimately holds the same files at new locations.

### 4. Exclusive create is atomic or it is not exclusive

**Section:** File System Operations.

**Proposed addition:**

> Where a protocol relies on creating a file only if it does not already exist, the creation must be a single atomic operation provided by the storage. An existence check followed by a write does not satisfy this requirement.

**Addresses:** [E7](problems.md#e7-exclusive-create-is-not-atomic), and is a precondition for any solution to [V10](problems.md#v10-no-catalog-free-commit-protocol-remains).

**Remark.** The Java object store `FileIO` implementations do not comply today, and each would need to use its store's conditional create.

### 5. Readers that cannot supply a default must fail

**Section:** Default values, and Column Projection.

**Proposed addition:**

> A reader that cannot supply a field's `initial-default` for a data file that does not contain the field must fail rather than return any other value.

**Addresses:** [E12](problems.md#e12-a-reader-that-cannot-supply-a-default-has-no-stated-obligation).

**Remark.** The Java ORC reader already complies. The fix makes a refusal mandatory; it does not make the refusal early, which needs [version 4 ask 3](v4-asks.md#3-declaring-what-a-snapshot-depends-on).

### 6. Writers that cannot evaluate a default must fail

**Section:** Default values.

**Proposed addition:**

> A writer that cannot evaluate a field's `write-default` expression, for example because it does not implement a function the expression calls, must fail the write. It must not write null or any other value in place of the default.

**Addresses:** [V7](problems.md#v7-a-writer-that-cannot-evaluate-a-default-has-no-stated-obligation).

**Remark.** The section already requires a writer to fail when a required field's default evaluates to null, so this extends an existing rule rather than adding a new kind.

### 7. Readers must apply deletion vectors and projected column files

**Section:** Deletion Vectors; the requirements for `column_files`.

**Proposed addition:**

> A reader that cannot read a data file's deletion vector must not read the data file. A reader that projects a field carried by one of a data file's column files must read the field from that column file, and must fail if it cannot.

**Addresses:** [E11](problems.md#e11-whether-an-unsupported-thing-may-be-ignored-is-decided-case-by-case), [V5](problems.md#v5-a-reader-that-ignores-a-column-file-returns-wrong-values).

**Remark.** Every implementation already infers both rules.

### 8. Ignoring is the exception

**Section:** a new paragraph under Writer requirements, or near the beginning of the Specification.

**Proposed addition:**

> Where this specification permits an implementation to ignore something it does not support, such as an unknown partition transform when filtering, it says so explicitly. Otherwise an implementation that meets a field value, file or construct it does not support must fail.

**Addresses:** [E11](problems.md#e11-whether-an-unsupported-thing-may-be-ignored-is-decided-case-by-case).

**Remark.** This is the most consequential item, since an implementation that now ignores something the specification does not explicitly permit it to ignore would become non-conforming. An audit of the existing permissions should precede it.

### 9. Row positions are fixed when a data file is written

**Section:** Reserved Field IDs (the definition of `_pos`).

**Proposed addition to the description of `_pos`:**

> Positions are fixed when the data file is written, and every reader must assign the same position to the same row. A reader that cannot assign positions consistently, for instance because it skips rows it cannot decode, must fail.

**Addresses:** [E13](problems.md#e13-row-positions-are-not-required-to-be-stable).

### 10. Inconsistent split offsets are ignored

**Section:** the `split_offsets` field of data files.

**Proposed addition:**

> Readers must ignore `split_offsets` that do not correspond to the data file's units, and plan the file as a single split.

**Addresses:** [E14](problems.md#e14-split-offsets-that-disagree-with-the-file).

### 11. Changes to the name mapping reinterpret existing data files

**Section:** Column Projection.

**Proposed addition:**

> The name mapping in `schema.name-mapping.default` is read from the table's current properties, including when reading older snapshots, so a change to it changes how existing data files without field ids are read. A change must not remove a name, or map it to a different field id, while any live data file without field ids depends on it.

**Addresses:** [E15](problems.md#e15-editing-the-name-mapping-reinterprets-existing-data-files).

**Remark.** The constraint falls on a property that users edit by hand, and enforcing it would require a catalog or client to check edits against live data files. The first sentence alone, which only describes current behavior, would still be worth having.

### 12. If version 4 stops writing ORC and Avro data files

**Section:** Appendix A, Format-specific Requirements, conditional on the outcome of the [current discussion](https://lists.apache.org/thread/5jfzckl0cc9z683kz9vfv41pmhrm1tdb).

**Proposed addition:**

> In version 4, writers must not create data files in the ORC or Avro formats. Readers of version 4 tables must read ORC and Avro data files written by earlier versions, subject to the same rules as data files in other formats, including the rules for default values.

**Addresses:** [V1](problems.md#v1-the-read-obligation-on-deprecated-formats-keeps-growing).

**Remark.** The second sentence does not shrink the read obligation; it makes the obligation's growth explicit, so that the cost of the arrangement is visible when it is decided.

## Expressions specification

### 13. The evaluation context of functions

**Section:** Apply function, and Appendix A: Iceberg functions.

**Proposed addition:**

> Each function defined in this specification states whether its result depends only on its arguments, or also on the context in which it is evaluated, such as the current time or a session's time zone. Where a stored expression calls a context-dependent function, the specification that stores it says what context is used, and when and how often the expression is evaluated: once per row, once per statement or once per data file, and whether a retried write evaluates it again.

**Addresses:** [V8](problems.md#v8-defaults-depend-on-context-that-nothing-records).

**Remark.** This is the smallest step toward the classification the note proposes, and it does not settle the draft's special-case rules for defaults. The clause on when and how often matters because a context-dependent function reads its context once per evaluation. The table specification says only that a `write-default` "is evaluated to populate the field for any record written after the field was added", so a `current_timestamp()` default may give the rows of one insert one value or many, and a retried task, or a statement planned again after a commit conflict, may store a value later than the first attempt's, all within the current text.

## What is not here

Solutions that need a new field are in the [version 4 asks](v4-asks.md). Problems for which only a new mechanism has been proposed, or nothing yet, are marked as such in the [register](problems.md).
