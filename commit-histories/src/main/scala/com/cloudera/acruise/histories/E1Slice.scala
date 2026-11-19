package com.cloudera.acruise.histories

/** Register entry E1: a rewrite racing a row-level delete. One data file in one partition holding rows 5 and 7; a
  * rewriter (copy-on-write delete of row 5, or a compaction at either output sequence number) races a deleter (by
  * deletion vector, or by equality on 5, 7, or 6, a value inside the file's bounds that no row has).
  */
object E1Slice {
  val file: DataFile = DataFile("F", partition = 0, rows = Vector(5, 7), dataSeq = 1)
  val initial: Snapshot = Snapshot.initial(file)

  val rewriters: Vector[Op] = Vector(CopyOnWriteDelete(5), Compaction(OutputSeq.New), Compaction(OutputSeq.Base))

  val deleters: Vector[Op] =
    Vector(DeleteByVector(5), DeleteByVector(7), DeleteByEquality(5), DeleteByEquality(6), DeleteByEquality(7))

  /** Two merge-on-read deletes of the same file, which must leave one deletion vector holding both. */
  val vectorPairs: Vector[(Op, Op)] = Vector(DeleteByVector(5) -> DeleteByVector(7), DeleteByVector(5) -> DeleteByVector(5))

  val scenarios: Vector[Scenario] =
    (for {
      r <- rewriters
      d <- deleters
    } yield Scenario(s"${r.label} vs ${d.label}", initial, Vector(r, d))) ++
      vectorPairs.map { case (a, b) => Scenario(s"${a.label} vs ${b.label}", initial, Vector(a, b)) }
}
