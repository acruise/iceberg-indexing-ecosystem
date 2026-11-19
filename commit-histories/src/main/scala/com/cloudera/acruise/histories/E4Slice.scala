package com.cloudera.acruise.histories

/** Register entry E4: commits of unknown outcome. A writer is told its first commit failed, which may have landed or
  * been lost, and retries once from its original base. It runs alone, or beside a companion that commits one of the
  * same kinds of change, including an identical one, which is what can deceive recognition by content.
  */
object E4Slice {
  val initial: Snapshot = E1Slice.initial

  val writers: Vector[Op] = Vector(AppendRow(9), DeleteByVector(7), CopyOnWriteDelete(5), Compaction(OutputSeq.New))
  val companions: Vector[Option[Op]] = Vector(None, Some(AppendRow(9)), Some(DeleteByVector(7)), Some(Compaction(OutputSeq.New)))

  val scenarios: Vector[Scenario] =
    for {
      w <- writers
      lands <- Vector(true, false)
      c <- companions
    } yield {
      val a = Ambiguous(w, lands)
      Scenario(c.fold(a.label)(c => s"${a.label} beside ${c.label}"), initial, Vector[Op](a) ++ c)
    }
}
