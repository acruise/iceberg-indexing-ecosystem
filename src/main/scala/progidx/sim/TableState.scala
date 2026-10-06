package progidx.sim

import scala.collection.mutable

/** The live file set of one table, indexed by partition. */
final class TableState {
  private val files = mutable.LinkedHashMap[Long, DataFile]()
  private val partitions = mutable.TreeMap[Long, mutable.LinkedHashSet[Long]]()
  private var nextFileId = 0L

  def newFileId(): Long = {
    val id = nextFileId
    nextFileId += 1
    id
  }

  def add(f: DataFile): Unit = {
    files(f.id) = f
    partitions.getOrElseUpdate(f.partition, mutable.LinkedHashSet[Long]()) += f.id
  }

  /** Metadata-only change to a live file (e.g. marking it finalized). */
  def replace(f: DataFile): Unit = {
    require(files.contains(f.id), s"no live file ${f.id}")
    files(f.id) = f
  }

  def remove(id: Long): Option[DataFile] =
    files.remove(id).map { f =>
      partitions.get(f.partition).foreach { ids =>
        ids -= id
        if (ids.isEmpty) partitions.remove(f.partition)
      }
      f
    }

  def get(id: Long): Option[DataFile] = files.get(id)
  def liveFiles: Iterable[DataFile] = files.values
  def fileCount: Int = files.size
  def partitionIds: Seq[Long] = partitions.keys.toSeq
  def filesIn(partition: Long): Seq[DataFile] =
    partitions.get(partition).map(_.toSeq.map(files)).getOrElse(Nil)
}
