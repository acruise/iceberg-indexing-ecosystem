package com.cloudera.acruise.histories

/** A deliberately small physical model of an Iceberg table: data files of row ids, deletion vectors, equality deletes
  * and a linear chain of snapshots. Row ids double as row values, so an equality delete on `id` names rows directly.
  *
  * Delete application follows the scan-planning scope rules of the table specification (apache/iceberg `format/spec.md`
  * at `13135ae`, "Scan Planning"): a deletion vector applies to the data file it references when the file's data
  * sequence number is less than or equal to the vector's; an equality delete applies when the file's data sequence
  * number is strictly less than the delete's and the partitions match or the delete is global.
  */
final case class DataFile(path: String, partition: Int, rows: Vector[Int], dataSeq: Long) {
  def lower: Int = rows.min
  def upper: Int = rows.max
}

final case class DeletionVector(referencedFile: String, partition: Int, positions: Set[Int], dataSeq: Long)

/** `partition = None` is a global delete, stored with an unpartitioned spec. */
final case class EqualityDelete(path: String, partition: Option[Int], ids: Set[Int], dataSeq: Long) {
  def lower: Int = ids.min
  def upper: Int = ids.max

  def appliesTo(f: DataFile): Boolean = f.dataSeq < dataSeq && partition.forall(_ == f.partition)
}

/** The snapshot operations of the table specification ("Snapshots"). */
sealed abstract class SnapshotOp(val name: String)
object SnapshotOp {
  case object Append extends SnapshotOp("append")
  case object Replace extends SnapshotOp("replace")
  case object Overwrite extends SnapshotOp("overwrite")
  case object Delete extends SnapshotOp("delete")
}

/** What a snapshot changed relative to its parent; rules about "what has been added since the base version" read it. */
final case class Delta(
    removedFiles: Set[String],
    addedFiles: Vector[DataFile],
    dvs: Vector[DeletionVector],
    eqDeletes: Vector[EqualityDelete]
)

object Delta {
  val empty: Delta = Delta(Set.empty, Vector.empty, Vector.empty, Vector.empty)
}

/** A materialized view's refresh state, kept in the storage table's snapshot summary: the source snapshot the refresh
  * read. Summaries are not inherited, so a snapshot carries it only if its writer put it there.
  */
final case class RefreshState(sourceTable: String, sourceSnapshot: Int)

/** `readBase` is the snapshot the committing writer read, which version 4 ask 2 would record and which differs from the
  * parent after a retry; `commitId` identifies the writer's operation, so that a retry of unknown outcome can recognize
  * its own earlier commit. Only an auditor or a rule set that assumes such fields may consult them.
  */
final case class Snapshot(
    id: Int,
    seq: Long,
    op: SnapshotOp,
    author: String,
    files: Map[String, DataFile],
    dvs: Map[String, DeletionVector],
    eqDeletes: Vector[EqualityDelete],
    delta: Delta,
    readBase: Int = 0,
    commitId: Option[Int] = None,
    refreshState: Option[RefreshState] = None
) {
  def liveFiles: Vector[DataFile] = files.values.toVector.sortBy(_.path)

  def isDeleted(f: DataFile, pos: Int): Boolean = {
    val byVector = dvs.get(f.path).exists { dv =>
      dv.partition == f.partition && f.dataSeq <= dv.dataSeq && dv.positions(pos)
    }
    byVector || eqDeletes.exists(e => e.appliesTo(f) && e.ids(f.rows(pos)))
  }

  def visibleRowsOf(f: DataFile): Vector[Int] = f.rows.indices.filterNot(isDeleted(f, _)).map(f.rows).toVector

  /** A multiset, sorted: a row that appears twice is a wrong answer, not the same answer. */
  def visibleRows: Vector[Int] = liveFiles.flatMap(visibleRowsOf).sorted
}

object Snapshot {
  def initial(files: DataFile*): Snapshot =
    Snapshot(
      id = 1,
      seq = files.map(_.dataSeq).maxOption.getOrElse(0L),
      op = SnapshotOp.Append,
      author = "setup",
      files = files.map(f => f.path -> f).toMap,
      dvs = Map.empty,
      eqDeletes = Vector.empty,
      delta = Delta(Set.empty, files.toVector, Vector.empty, Vector.empty)
    )
}
