package com.cloudera.acruise.histories

/** Which data sequence number a rewrite gives its output files: the new snapshot's, or that of the snapshot it read
  * (as a compaction that commits at its starting sequence number does).
  */
sealed trait OutputSeq
object OutputSeq {
  case object New extends OutputSeq
  case object Base extends OutputSeq
}

final case class PlannedDv(file: String, partition: Int, positions: Set[Int])
final case class PlannedEq(partition: Option[Int], ids: Set[Int])

/** The physical change an operation decided on when it read its base. A retry re-applies the same plan to the new
  * current snapshot without re-reading, which is what a writer that only re-checks the retry conditions does.
  */
final case class Plan(
    op: SnapshotOp,
    removedFiles: Set[String] = Set.empty,
    addedRows: Vector[(Int, Vector[Int])] = Vector.empty,
    outputSeq: OutputSeq = OutputSeq.New,
    dvs: Vector[PlannedDv] = Vector.empty,
    eqDeletes: Vector[PlannedEq] = Vector.empty,
    refreshState: Option[RefreshState] = None,
    carryForward: Boolean = false
)

object Plan {

  /** Commit `plan`, decided against `base`, on top of `current`. Files already gone are not removed twice. A deletion
    * vector is written as planned: the writer merged its new positions with the vector it saw at its base, as the
    * specification requires, and a retry does not re-read, so a vector committed concurrently for the same file is
    * replaced, not merged. A deletion vector for a file no longer live is still written, and dangles.
    */
  def commit(current: Snapshot, base: Snapshot, plan: Plan, author: String, commitId: Option[Int] = None): Snapshot = {
    val seq = current.seq + 1
    val outSeq = plan.outputSeq match {
      case OutputSeq.New  => seq
      case OutputSeq.Base => base.seq
    }
    val removed = plan.removedFiles.intersect(current.files.keySet)
    val added = plan.addedRows.zipWithIndex.map { case ((p, rows), i) => DataFile(s"$author@s$seq-$i", p, rows, outSeq) }
    val newDvs = plan.dvs.map(d => DeletionVector(d.file, d.partition, d.positions, seq))
    val eqs = plan.eqDeletes.zipWithIndex.map { case (e, i) => EqualityDelete(s"$author@s$seq-eq$i", e.partition, e.ids, seq) }
    Snapshot(
      id = current.id + 1,
      seq = seq,
      op = plan.op,
      author = author,
      files = current.files -- removed ++ added.map(f => f.path -> f),
      dvs = current.dvs -- removed ++ newDvs.map(d => d.referencedFile -> d),
      eqDeletes = current.eqDeletes ++ eqs,
      delta = Delta(removed, added, newDvs, eqs),
      readBase = base.id,
      commitId = commitId,
      refreshState = plan.refreshState.orElse(if (plan.carryForward) current.refreshState else None)
    )
  }
}

/** An operation, with both a physical plan (what it writes) and a logical effect (what it means), so that the ground
  * truth can be computed without trusting the physical model. Effects act on a sorted multiset of row values: two
  * appends of the same value are two rows, and a delete by value removes every row with that value.
  */
sealed trait Op {
  def label: String

  /** The table the operation commits to; single-table slices use `t`. */
  def table: String = "t"

  /** A label prefixed by the table, unless it is the default one. */
  protected def at(l: String): String = if (table == "t") l else s"$table.$l"

  /** Whether `effect` describes the operation. A refresh's effect depends on another table, so its own table's
    * retries are not judged against a serial order.
    */
  def effectKnown: Boolean = true

  /** The plan, given the heads of every table at the read point. Only an operation that reads another table needs
    * more than its own base.
    */
  def planIn(base: Snapshot, heads: Map[String, Snapshot]): Option[Plan] = plan(base)

  /** Whether the operation has a read point. One that does not (expiration, here) appears only as a commit. */
  def reads: Boolean = true

  /** How many commit events the operation has: two for a commit of unknown outcome, which its writer retries. */
  def attempts: Int = 1
  def plan(base: Snapshot): Option[Plan]
  def effect(rows: Vector[Int]): Vector[Int]
}

/** A merge-on-read delete: reads its base, finds the row, writes a deletion vector for its file. */
final case class DeleteByVector(id: Int, override val table: String = "t") extends Op {
  val label: String = at(s"dv-delete($id)")

  def plan(base: Snapshot): Option[Plan] =
    base.liveFiles.iterator.flatMap { f =>
      f.rows.indices
        .find(p => f.rows(p) == id && !base.isDeleted(f, p))
        .map { p =>
          val existing = base.dvs.get(f.path).filter(_.dataSeq >= f.dataSeq).map(_.positions).getOrElse(Set.empty[Int])
          Plan(SnapshotOp.Delete, dvs = Vector(PlannedDv(f.path, f.partition, existing + p)))
        }
    }.nextOption()

  def effect(rows: Vector[Int]): Vector[Int] = rows.filterNot(_ == id)
}

/** A blind delete by value, as a Flink upsert writes: it reads nothing and may name a value no row has. */
final case class DeleteByEquality(id: Int, partition: Option[Int] = Some(0)) extends Op {
  val label: String = s"eq-delete($id${partition match {
      case None    => ", global"
      case Some(0) => ""
      case Some(p) => s", partition $p"
    }})"

  def plan(base: Snapshot): Option[Plan] = Some(Plan(SnapshotOp.Delete, eqDeletes = Vector(PlannedEq(partition, Set(id)))))

  def effect(rows: Vector[Int]): Vector[Int] = rows.filterNot(_ == id)
}

/** A copy-on-write delete: rewrites every file holding the row without it. Commits as `overwrite`, or as `delete` when
  * nothing survives, following the snapshot operation definitions.
  */
final case class CopyOnWriteDelete(id: Int) extends Op {
  val label = s"cow-delete($id)"

  def plan(base: Snapshot): Option[Plan] = {
    val hit = base.liveFiles.filter(f => base.visibleRowsOf(f).contains(id))
    if (hit.isEmpty) None
    else {
      val added = hit.map(f => f.partition -> base.visibleRowsOf(f).filterNot(_ == id)).filter(_._2.nonEmpty)
      Some(
        Plan(
          if (added.isEmpty) SnapshotOp.Delete else SnapshotOp.Overwrite,
          removedFiles = hit.map(_.path).toSet,
          addedRows = added
        )
      )
    }
  }

  def effect(rows: Vector[Int]): Vector[Int] = rows.filterNot(_ == id)
}

/** A compaction of one partition into a single file, applying the deletes visible at its base. */
final case class Compaction(
    outputSeq: OutputSeq,
    partition: Int = 0,
    override val table: String = "t",
    carryForward: Boolean = false
) extends Op {
  val label: String = at((outputSeq match {
    case OutputSeq.New  => "compact(new-seq)"
    case OutputSeq.Base => "compact(start-seq)"
  }) + (if (carryForward) "+carry" else ""))

  def plan(base: Snapshot): Option[Plan] = {
    val fs = base.liveFiles.filter(_.partition == partition)
    if (fs.isEmpty) None
    else
      Some(
        Plan(
          SnapshotOp.Replace,
          removedFiles = fs.map(_.path).toSet,
          addedRows = Vector(partition -> fs.flatMap(base.visibleRowsOf)),
          outputSeq = outputSeq,
          carryForward = carryForward
        )
      )
  }

  def effect(rows: Vector[Int]): Vector[Int] = rows
}

/** An append of one row in a new data file. */
final case class AppendRow(id: Int, partition: Int = 0, override val table: String = "t") extends Op {
  val label: String = at(s"append($id)")

  def plan(base: Snapshot): Option[Plan] = Some(Plan(SnapshotOp.Append, addedRows = Vector(partition -> Vector(id))))

  def effect(rows: Vector[Int]): Vector[Int] = (rows :+ id).sorted
}

/** Snapshot expiration retaining the last `retainLast` snapshots. It commits table metadata but no snapshot, so it
  * never causes a retry; what it changes is how much history a later retry can see. Deleting the files that only
  * expired snapshots referenced is not modeled.
  */
final case class ExpireSnapshots(retainLast: Int = 1, override val table: String = "t") extends Op {
  val label: String = at(s"expire(retain $retainLast)")
  override val reads = false

  def plan(base: Snapshot): Option[Plan] = None

  def effect(rows: Vector[Int]): Vector[Int] = rows
}

/** A commit of unknown outcome, register entry E4: the writer is told its first commit failed (a timeout, say) and
  * retries once from its original base. If `lands`, the first commit was in fact applied; otherwise it was lost.
  */
final case class Ambiguous(inner: Op, lands: Boolean) extends Op {
  val label: String = s"${inner.label}${if (lands) "?landed" else "?lost"}"
  override def reads: Boolean = inner.reads
  override def attempts: Int = 2
  override def table: String = inner.table
  override def effectKnown: Boolean = inner.effectKnown

  def plan(base: Snapshot): Option[Plan] = inner.plan(base)

  override def planIn(base: Snapshot, heads: Map[String, Snapshot]): Option[Plan] = inner.planIn(base, heads)

  def effect(rows: Vector[Int]): Vector[Int] = inner.effect(rows)
}

/** A refresh of a materialized view whose query is `SELECT * FROM source`: it reads the source's head, replaces the
  * storage table's contents with the source's rows, and records the source snapshot it read as the refresh state.
  */
final case class Refresh(source: String = "src", override val table: String = "mv") extends Op {
  val label: String = s"$table.refresh"
  override def effectKnown: Boolean = false

  def plan(base: Snapshot): Option[Plan] = None

  override def planIn(base: Snapshot, heads: Map[String, Snapshot]): Option[Plan] = {
    val src = heads(source)
    Some(
      Plan(
        SnapshotOp.Overwrite,
        removedFiles = base.files.keySet,
        addedRows = Vector(0 -> src.visibleRows),
        refreshState = Some(RefreshState(source, src.id))
      )
    )
  }

  def effect(rows: Vector[Int]): Vector[Int] = rows
}
