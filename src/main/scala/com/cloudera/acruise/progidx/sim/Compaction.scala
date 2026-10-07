package com.cloudera.acruise.progidx.sim

sealed trait CompactionAction
final case class Merge(inputs: Seq[DataFile], generation: Int, finalized: Boolean) extends CompactionAction
final case class Finalize(file: DataFile) extends CompactionAction

trait CompactionPolicy {
  /** `stats` lets a policy weigh a merge against the reads it would serve; most ignore it. */
  def plan(table: TableState, now: Long, stats: AccessStats): Seq[CompactionAction]

  /** Predicted ticks until `f` is consumed by compaction; PositiveInfinity if the policy never plans to touch it.
    * Retention is not the policy's concern.
    */
  def expectedRemainingLife(f: DataFile, table: TableState, now: Long, stats: AccessStats): Double
}

/** Size-tiered merging within each time partition (`fanout` files of generation g become one of g+1, up to
  * `maxGeneration`), plus a settle step: once a partition has been closed for `settleDelayTicks`, everything in it is
  * merged into one finalized file that is never compacted again.
  */
class TieredWithSettle(cfg: SimConfig) extends CompactionPolicy {
  def closeTime(partition: Long): Long = (partition + 1) * cfg.partitionTicks
  def settleTime(partition: Long): Long = closeTime(partition) + cfg.settleDelayTicks

  /** Whether to merge a full group of same-generation files in an open partition. */
  protected def worthMerging(group: Seq[DataFile], table: TableState, now: Long, stats: AccessStats): Boolean = true

  def plan(table: TableState, now: Long, stats: AccessStats): Seq[CompactionAction] =
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
            .filter(group => group.size == cfg.fanout && worthMerging(group, table, now, stats))
            .map(Merge(_, g + 1, finalized = false))
        }
      }
    }

  def expectedRemainingLife(f: DataFile, table: TableState, now: Long, stats: AccessStats): Double = {
    if (f.finalized) return Double.PositiveInfinity
    val p = f.partition
    val untilSettle = math.max(0L, settleTime(p) - now).toDouble
    if (f.generation >= cfg.maxGeneration || now >= closeTime(p)) return untilSettle
    if (!worthMerging(Seq.fill(cfg.fanout)(f), table, now, stats)) return untilSettle

    // Files at generation g arrive at filesPerTick / fanout^g while the partition is open.
    val peers = table.filesIn(p).count(_.generation == f.generation)
    val needed = math.max(0, cfg.fanout - peers)
    if (needed == 0) return 0.0
    val rate = cfg.filesPerTick / math.pow(cfg.fanout.toDouble, f.generation.toDouble)
    val t = needed / rate
    if (now + t <= closeTime(p)) math.min(t, untilSettle) else untilSettle
  }
}

/** Tiered merging only where it pays: a group of small files in an open partition is merged when the requests the
  * merged file would save the queries expected to reach the partition before it settles exceed the cost of rewriting
  * the group, or when the partition holds more than `maxOpenFiles` files. Settling is unchanged, so each row is still
  * rewritten into its partition's final file; what is skipped is the intermediate tiers nobody reads.
  */
final class ReadAwareTiered(cfg: SimConfig, maxOpenFiles: Int = 400) extends TieredWithSettle(cfg) {
  override protected def worthMerging(group: Seq[DataFile], table: TableState, now: Long, stats: AccessStats): Boolean = {
    val p = group.head.partition
    if (table.filesIn(p).size > maxOpenFiles) return true
    val reads = stats.expectedAccesses(p, now, math.max(0L, settleTime(p) - now).toDouble)
    // Each query reaching the partition opens one file instead of the group's: a footer and a predicate chunk each.
    val saved = reads * (group.size - 1) * 2 * cfg.requestCost
    // Read every input (a footer and a chunk per column) and write one output with every column.
    val rewrite = group.size * (1 + cfg.columns) * cfg.requestCost + 2.0 * group.map(_.rows).sum * cfg.columns
    saved > rewrite
  }
}
