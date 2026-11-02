# Draft post for dev@iceberg.apache.org

Status: draft, not sent. The Google Doc link below must point at a regeneration that includes deltas entries 28 and 29 before this goes out, and the proposal issue in apache/iceberg should exist first so that it can be linked here.

---

**Subject:** [DISCUSS] Pluggable Puffin blob types and a compaction-aware lifecycle for data-file-scoped indexes

Hi all,

I would like to open discussion on a proposal that extends Puffin in two directions the specification currently leaves open: how a blob type that the specification does not define can be identified and described by anyone, and implemented without coordination, and what happens to a blob when compaction rewrites the data file it describes.

Proposal document (comments welcome): `<Google Doc link>`
Canonical text, prototype and simulations: https://github.com/acruise/iceberg-indexing-ecosystem
Proposal issue: `<apache/iceberg issue link>`

The proposal is deliberately narrow in scope and, I hope, complementary to the secondary index specification that Péter Váry and Huaxin Gao have under review in PR 16961. That specification defines positive, table-level indexes pinned to a snapshot, with extracts, a tracking file and a commit protocol to keep them consistent. This proposal is about blobs bound to a single data file, in the way v3 deletion vectors already are, which are valid for exactly as long as that file is live and which by default can only exclude a file from a scan. The two meet at one question, which is whether such a blob could be hosted as an index type under the index specification rather than located from table metadata on its own, and I would rather settle that with the specification's authors than guess at it, so it is the first open question in the document.

What the proposal contains, in brief:

- A reverse-DNS namespace for blob type identifiers, with a major version in the identifier and a small machine-readable descriptor per type (scope, input fields, compaction behavior, how it combines with deletes, what a reader may infer from it) published in a community registry.
- A general notion of data-file-scoped blobs, with two normative rules: the blob must be a pure function of the file's content and its build configuration, and it must be exclusion-only unless its descriptor declares otherwise and takes on the obligations that come with locating rows or aggregating values.
- A build configuration mechanism, since a Bloom filter or n-gram index is only evaluable under the exact parameters it was built with. Each blob records the digest of its configuration (prefixed by the digest of the type's configuration schema, so the same JSON under two types has two identities), the configuration is resolvable from an in-band blob or a shared store, and a reader evaluates under the resolved configuration or does not use the blob at all. The motivating failure is the quiet one: something changes in a place nobody looks at, and queries start returning too few rows.
- Candidate normative text for compaction: per type, drop, rebuild, merge or retain, with merge precision declared.
- A Java SPI as the JVM binding of that contract, with a prototype in the repository, and explicit rules for compiled-in implementations (Rust, C++, anything that links its types at build time), which cannot discover types at run time and must therefore be precise about which major versions and configuration schemas they support.

The property the whole design rests on is that an implementation which ignores all of this, whether an older reader or a compactor that has never heard of a given type, can lose index coverage but cannot produce a wrong result. That is what makes me believe the feature can be introduced without a format version increment, gated by a table property, and it is the claim I would most like scrutinized. The document is candid about where the claim is thin: unaware compactors put an indexer on a treadmill, and orphan file removal run from an older engine will delete every index Puffin file it cannot reach from the metadata structures it knows, which constrains how index files should be listed.

The document also surveys how Paimon, Pinot, Lucene, ClickHouse, Hudi, PostgreSQL BRIN, Lance and Delta Lake have handled the same problems, and it carries a list of open questions, among them where index metadata should live, what the expression vocabulary for derived inputs should be, type promotion, encryption and whether row-level invalidation could ever be tracked finely enough to spare an index a rebuild.

For context, the proposal grew out of work on indexes built after the fact, once a file has shown which predicates it actually receives, and the repository contains a simulator comparing build timings under drifting workloads. None of that is proposed here; it is the case against which the mechanisms were tested. I work for Cloudera, but this is intended as a contribution to the project and the identifiers, property keys and class names in the document are placeholders.

Comments on the document or replies here are equally welcome, and the repository takes issues. I am particularly interested in hearing from people who maintain non-JVM implementations and from anyone who has run compaction against a table carrying Puffin blobs that an engine did not understand.

Thanks,
Alex
