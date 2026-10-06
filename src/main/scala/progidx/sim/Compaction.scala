package progidx.sim

sealed trait CompactionAction
final case class Merge(inputs: Seq[DataFile], generation: Int, finalized: Boolean) extends CompactionAction
final case class Finalize(file: DataFile) extends CompactionAction

trait CompactionPolicy {
  def plan(table: TableState, now: Long): Seq[CompactionAction]

  /** Predicted ticks until `f` is consumed by compaction; PositiveInfinity if the policy never plans to touch it.
    * Retention is not the policy's concern.
    */
  def expectedRemainingLife(f: DataFile, table: TableState, now: Long): Double
}

/** Size-tiered merging within each time partition (`fanout` files of generation g become one of g+1, up to
  * `maxGeneration`), plus a settle step: once a partition has been closed for `settleDelayTicks`, everything in it is
  * merged into one finalized file that is never compacted again.
  */
final class TieredWithSettle(cfg: SimConfig) extends CompactionPolicy {
  def closeTime(partition: Long): Long = (partition + 1) * cfg.partitionTicks
  def settleTime(partition: Long): Long = closeTime(partition) + cfg.settleDelayTicks

  def plan(table: TableState, now: Long): Seq[CompactionAction] =
    table.partitionIds.flatMap { p =>
      val files = table.filesIn(p)
      if (now >= settleTime(p)) {
        if (files.size > 1) Seq(Merge(files, cfg.maxGeneration, finalized = true))
        else files.filterNot(_.finalized).map(Finalize)
      } else {
        (0 until cfg.maxGeneration).flatMap { g =>
          files
            .filter(_.generation == g)
            .sortBy(_.id)
            .grouped(cfg.fanout)
            .filter(_.size == cfg.fanout)
            .map(Merge(_, g + 1, finalized = false))
        }
      }
    }

  def expectedRemainingLife(f: DataFile, table: TableState, now: Long): Double = {
    if (f.finalized) return Double.PositiveInfinity
    val p = f.partition
    val untilSettle = math.max(0L, settleTime(p) - now).toDouble
    if (f.generation >= cfg.maxGeneration || now >= closeTime(p)) return untilSettle

    // Files at generation g arrive at filesPerTick / fanout^g while the partition is open.
    val peers = table.filesIn(p).count(_.generation == f.generation)
    val needed = math.max(0, cfg.fanout - peers)
    if (needed == 0) return 0.0
    val rate = cfg.filesPerTick / math.pow(cfg.fanout.toDouble, f.generation.toDouble)
    val t = needed / rate
    if (now + t <= closeTime(p)) math.min(t, untilSettle) else untilSettle
  }
}
