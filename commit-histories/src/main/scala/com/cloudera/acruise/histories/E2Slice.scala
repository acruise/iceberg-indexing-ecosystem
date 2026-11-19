package com.cloudera.acruise.histories

/** Register entry E2: the validation window. A rewriter and a deleter race as in E1, while an unrelated append and a
  * snapshot expiration retaining one snapshot also commit. When the deleter's snapshot and the append's both land
  * between the rewriter's read and its commit, and expiration runs in between, the snapshot that added the delete is
  * gone by the time the rewriter retries, and no condition about what was added since its base can be verified.
  *
  * A second file, in partition 1, gives the deleters a target that no rewriter touches. A retry over such a delete is
  * safe to commit, so when its window has expired it measures what refusing unverifiable retries (prose fix 2) costs.
  */
object E2Slice {
  val other: DataFile = DataFile("G", partition = 1, rows = Vector(11, 13), dataSeq = 1)
  val initial: Snapshot = Snapshot.initial(E1Slice.file, other)

  val rewriters: Vector[Op] = Vector(Compaction(OutputSeq.New), CopyOnWriteDelete(5))

  val conflicting: Vector[Op] = Vector(DeleteByVector(7), DeleteByEquality(7))
  val nonConflicting: Vector[Op] = Vector(DeleteByVector(13), DeleteByEquality(13, partition = Some(1)))

  val scenarios: Vector[Scenario] =
    for {
      r <- rewriters
      d <- conflicting ++ nonConflicting
    } yield Scenario(s"${r.label} vs ${d.label}, with append and expiry", initial, Vector(r, d, AppendRow(9), ExpireSnapshots(1)))
}
