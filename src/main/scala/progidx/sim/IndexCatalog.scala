package progidx.sim

import scala.collection.mutable

/** A model of Puffin files as the index substrate.
  *
  * Built blobs are staged and become visible to queries only when a batch is flushed into a new container, which is one
  * metadata commit. Blobs are keyed by data file, so they stay valid across snapshots until their file dies. Dead blobs
  * leave garbage in their container; a container whose live fraction falls below the threshold has its live blobs
  * rewritten into a fresh container, and a container with nothing live is dropped.
  */
final class IndexCatalog(cfg: SimConfig, metrics: Metrics) {
  private final class Container(val id: Long, blobs: Seq[(IndexKey, Double)]) {
    val live: mutable.LinkedHashMap[IndexKey, Double] = mutable.LinkedHashMap.from(blobs)
    val totalSize: Double = blobs.map(_._2).sum
    def liveSize: Double = live.values.sum
  }

  private val containers = mutable.LinkedHashMap[Long, Container]()
  private val location = mutable.HashMap[IndexKey, Long]()
  private val pending = mutable.LinkedHashMap[IndexKey, (Double, Long)]()
  private var nextContainerId = 0L

  def isVisible(k: IndexKey): Boolean = location.contains(k)
  def isPending(k: IndexKey): Boolean = pending.contains(k)
  def pendingCount: Int = pending.size
  def containerCount: Int = containers.size

  /** Queue a blob for the next batched flush. */
  def stage(k: IndexKey, size: Double, now: Long): Unit = pending(k) = (size, now)

  /** Write blobs into a container as part of a commit that is happening anyway (a data write or a compaction). */
  def commitWith(blobs: Seq[(IndexKey, Double)]): Unit = if (blobs.nonEmpty) writeContainer(blobs)

  /** The blob's data file died; forget it wherever it is. */
  def drop(k: IndexKey): Unit = {
    pending.remove(k)
    location.remove(k).foreach(cid => containers(cid).live.remove(k))
  }

  def tick(now: Long): Unit = {
    if (pending.nonEmpty && (pending.size >= cfg.puffinBatchBlobs || now - pending.head._2._2 >= cfg.puffinMaxDelayTicks)) {
      writeContainer(pending.toSeq.map { case (k, (size, _)) => k -> size })
      pending.clear()
      metrics.indexCommits += 1
    }
    collectGarbage()
  }

  private def collectGarbage(): Unit = {
    // Dropping an empty container's reference rides along with the compaction commit that emptied it.
    containers.values.filter(_.live.isEmpty).map(_.id).toList.foreach(containers.remove)

    val sparse = containers.values.filter(c => c.liveSize / c.totalSize < cfg.puffinGcLiveFraction).toList
    if (sparse.nonEmpty) {
      val survivors = sparse.flatMap(_.live.toSeq)
      sparse.foreach(c => containers.remove(c.id))
      metrics.puffinRewriteWork += survivors.map(_._2).sum * cfg.puffinRewriteCostPerUnit
      writeContainer(survivors)
      metrics.indexCommits += 1
    }
  }

  private def writeContainer(blobs: Seq[(IndexKey, Double)]): Unit = {
    val c = new Container(nextContainerId, blobs)
    nextContainerId += 1
    containers(c.id) = c
    blobs.foreach { case (k, _) => location(k) = c.id }
    metrics.containersWritten += 1
  }
}
