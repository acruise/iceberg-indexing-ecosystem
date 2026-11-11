# Three Small Asks for Iceberg Version 4

*Solutions from the [note on contracts between Iceberg, its file formats, its storage and its writers](README.md) that would have to ride version 4, because they touch metadata structures version 4 is already redesigning. The problems they address are stated in the [register of problems](problems.md) and cited here by identifier. Solutions that need only prose are in the [prose fixes](prose-fixes.md).*

**Status:** draft, November 10, 2026. Field names are placeholders.

Version 4 carries a great deal of pent-up demand, and these asks are meant to sit lightly on it. Each is an optional field or a small optional structure. None of them obliges an existing reader to do anything, so each could land late without delaying the features ahead of it. Each addresses at least one problem that exists today, and in each case the failure it prevents is silent: the table stays well formed while its contents or its maintenance go wrong.

## 1. A content hash on tracked files

**Ask.** An optional `content_hash` on the structures that track a file by location and size, holding a hash algorithm's name and the hash of the file's bytes. That means `tracked_file` for data files, and the `column_file` and `deletion_vector` structures.

**Addresses.** [E6](problems.md#e6-out-of-band-mutation-is-detectable-only-by-a-change-of-length), out-of-band mutation detectable today only by a change of length; [V6](problems.md#v6-a-column-file-is-bound-to-its-data-file-by-location), column files bound to their data file by location; and [V9](problems.md#v9-relocation-becomes-routine-and-location-based-identity-does-not-survive-it), relocation under relative paths.

**Why a hash and not only a version token.** A version token (an S3 ETag, a GCS generation) identifies one write to one location, and it is lost when a table is copied, replicated or relocated, which version 4's relative paths make routine. A hash identifies the bytes and survives all three. Writers compute it almost for free while streaming a file out, and several stores already report a checksum (S3's default full-object CRC-64/NVME, GCS's CRC32C) that a reader can compare without reading the file.

**Cost.** One optional field. Readers that ignore it are no worse off than today.

Detail: [bindings and out-of-band mutation](README.md#out-of-band-mutation).

## 2. The read base of each snapshot

**Ask.** An optional field in each snapshot recording its *read base*: the snapshot the writer actually read from, as distinct from the parent it was finally committed on top of. The two differ after every retry, and today only the parent is recorded.

**Addresses.** [E3](problems.md#e3-validation-is-enforced-by-nothing-outside-the-writer-and-is-undetectable-afterward), validation that nothing outside the writer enforces or can check afterward; [E2](problems.md#e2-the-validation-window-and-what-to-do-outside-it-are-unstated), the unstated validation window; and, in part, [E4](problems.md#e4-commits-of-unknown-outcome), commits of unknown outcome.

**What the field enables.** With the read base recorded, the window of commits a retry had to validate against is fixed and permanent. For the conflicts that silently corrupt a table, which involve the files a commit removes or the files its deletion vectors name, the commit's own changes say what it depended on, so an auditor, a maintenance job or a catalog that chooses to can check the commit without trusting the writer. It also gives commits of unknown outcome, such as a timeout, a way to recognize that they have already been applied.

**Cost.** One optional field. Validation itself stays a writer's obligation, as now.

Detail: [commit contract](README.md#commit-contract).

## 3. Declaring what a snapshot depends on

**Ask.** A small optional declaration, in table or snapshot metadata, of what a reader or a maintenance process must be able to handle before it starts:

- the data file formats present in the snapshot, with counts of files and bytes;
- the kinds of sidecar that are required for reading it, such as column files, deletion vectors and any future kinds;
- the discovery roots holding structures that maintenance must spare and collect, as proposed in the [note on decoupled index metadata](../proposals/decoupled-index-metadata.md).

**Addresses.** [E16](problems.md#e16-unsupported-formats-and-features-are-discovered-mid-scan), unsupported formats and features discovered mid-scan; [E17](problems.md#e17-maintenance-learns-what-to-keep-only-from-snapshot-metadata), structures outside snapshot metadata that maintenance cannot see; and [V2](problems.md#v2-nothing-shows-how-much-of-a-table-remains-in-a-deprecated-format), the remaining share of a deprecated format.

**What the declaration enables.** A reader that lacks a format or a required kind of sidecar can refuse at table load, with a reason, instead of failing mid-scan. Maintenance can find everything it is responsible for without scanning. The format counts turn "is it safe to drop the ORC reader for this table?" into a lookup.

**Cost.** The largest of the three, and the one with a real design question: where the declaration lives, and whether writers that do not maintain it can leave it stale. A stale declaration should cost an early refusal its earliness and nothing more, which argues for treating it as a hint that readers confirm as they go.

Detail: [read-only support](README.md#read-only-support), [sidecars](README.md#appendix-d-sidecars), [degradations in practice](README.md#degradations-in-practice).

## What this extract leaves out

The note discusses much more, including a tiered contract for data file formats, reliance metadata for sidecars, per-substrate storage guarantees and a way of evaluating a deployment's verdicts edge by edge. None of it needs version 4, and the [register](problems.md) records which problems it is meant for. Some of what remains is implementation work, such as making the Java object store `FileIO` implementations use their stores' atomic conditional create ([E7](problems.md#e7-exclusive-create-is-not-atomic)).
