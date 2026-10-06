package progidx.sim

final case class IndexTask(key: IndexKey, kind: IndexKind, cost: Double)

final case class SchedulerContext(
    now: Long,
    cfg: SimConfig,
    table: TableState,
    policy: CompactionPolicy,
    stats: AccessStats,
    isCovered: IndexKey => Boolean
)

/** Decides which indexes to build, in two places:
  *
  *   - `writePath`: built synchronously as files are written (by ingest or compaction) and committed with them, so
  *     visible immediately. Unbudgeted, because the cost lands on the write path.
  *   - `select`: built in the background from a per-tick budget, in the order given; a task the budget can't finish
  *     carries over and is lost if its file dies first. Results become visible at the next Puffin flush.
  */
trait IndexScheduler {
  def name: String
  def budgetPerTick(cfg: SimConfig): Double = cfg.indexBudgetPerTick
  def writePath(ctx: SchedulerContext): Seq[IndexTask] = Nil
  def select(ctx: SchedulerContext, capacity: Double): Seq[IndexTask]
}

object IndexScheduler {
  /** Below this, a kind isn't worth building on a column of a file, or for the queries a column gets. */
  val MinUsefulness = 0.05

  def task(f: DataFile, kind: IndexKind, column: Int): IndexTask =
    IndexTask(IndexKey(f.id, kind.name, column), kind, kind.buildCostPerRow * f.rows)

  /** Uncovered tasks for `files` x `columns` x `kinds`, skipping kinds that can't evaluate the predicates queries
    * actually use on a column (e.g. bloom filters on range-queried columns) or can't discriminate on a file's values
    * (e.g. f16 on values beyond its range).
    */
  def uncovered(ctx: SchedulerContext, files: Seq[DataFile], columns: Seq[Int], kinds: Seq[IndexKind]): Seq[IndexTask] =
    for {
      f <- files
      column <- columns
      kind <- kinds
      if ctx.stats.applicableShare(kind, column) > MinUsefulness
      if kind.staticUsefulness(f.columns(column)) > MinUsefulness
      t = task(f, kind, column)
      if !ctx.isCovered(t.key)
    } yield t

  def uncovered(ctx: SchedulerContext, files: Seq[DataFile], columns: Seq[Int]): Seq[IndexTask] =
    uncovered(ctx, files, columns, ctx.cfg.indexKinds)

  def writtenNow(ctx: SchedulerContext): Seq[DataFile] = ctx.table.liveFiles.filter(_.createdAt == ctx.now).toSeq

  /** Expected cost saved per query on `column` that reaches `f`, from learned usefulness blended with a prior scaled
    * by the kind's static usefulness on this file, net of the cost of probing the index.
    */
  def expectedSavingsPerQuery(ctx: SchedulerContext, f: DataFile, kind: IndexKind, column: Int): Double = {
    val col = f.columns(column)
    val prior = ctx.cfg.exclusionPrior * kind.staticUsefulness(col)
    val probe = kind.estimatedSizeBytes(col) / ctx.cfg.indexBytesPerCostUnit
    ctx.stats.applicableShare(kind, column) * ctx.stats.minMaxSurvivalRate(column, f.rows) *
      (ctx.stats.exclusionRate(kind.name, column, f.rows, prior) * f.rows - probe)
  }
}

object NoIndexing extends IndexScheduler {
  val name = "none"
  override def budgetPerTick(cfg: SimConfig): Double = 0.0
  def select(ctx: SchedulerContext, capacity: Double): Seq[IndexTask] = Nil
}

/** Parquet-style: every file written is indexed on the currently-popular columns as part of the write. */
final case class EagerAtWrite() extends IndexScheduler {
  val name = "eager-at-write"
  override def budgetPerTick(cfg: SimConfig): Double = 0.0

  override def writePath(ctx: SchedulerContext): Seq[IndexTask] =
    IndexScheduler.uncovered(ctx, IndexScheduler.writtenNow(ctx), ctx.stats.columnsByShare.take(ctx.cfg.topColumns))

  def select(ctx: SchedulerContext, capacity: Double): Seq[IndexTask] = Nil
}

/** Background indexing of files that have reached a compaction generation (or, with `finalizedOnly`, been settled),
  * newest first, on the currently-popular columns.
  */
final case class GenerationThreshold(minGeneration: Int, finalizedOnly: Boolean = false) extends IndexScheduler {
  val name: String = if (finalizedOnly) "finalized-only" else s"generation>=$minGeneration"

  def select(ctx: SchedulerContext, capacity: Double): Seq[IndexTask] = {
    val eligible = ctx.table.liveFiles
      .filter(f => f.finalized || (!finalizedOnly && f.generation >= minGeneration))
      .toSeq
      .sortBy(f => -f.createdAt)
    IndexScheduler.uncovered(ctx, eligible, ctx.stats.columnsByShare.take(ctx.cfg.topColumns))
  }
}

/** Compaction-aware: value each candidate by the queries it is expected to serve between becoming visible (after its
  * build and the next Puffin flush) and its file being compacted away (as predicted by the compaction policy) or
  * expiring, net of build and amortized commit cost, and spend the budget on the best value per unit of build work.
  *
  * Mergeable kinds also earn credit for surviving compaction: their value continues into the compacted file.
  * This is approximated by extending their window to the horizon, discounted by `mergeCarryOver`.
  */
final case class SurvivalWeighted(mergeCarryOver: Double = 0.5) extends IndexScheduler {
  val name = "survival-weighted"

  def select(ctx: SchedulerContext, capacity: Double): Seq[IndexTask] = {
    val cfg = ctx.cfg
    val horizon = cfg.survivalHorizonTicks.toDouble
    val scored = for {
      f <- ctx.table.liveFiles.toSeq
      untilExpiry = ((f.partition + 1) * cfg.partitionTicks + cfg.retentionTicks - ctx.now).toDouble
      life = math.min(ctx.policy.expectedRemainingLife(f, ctx.table, ctx.now), untilExpiry)
      column <- 0 until cfg.columns
      kind <- cfg.indexKinds
      t = IndexScheduler.task(f, kind, column)
      if !ctx.isCovered(t.key)
      latency = math.ceil(t.cost / budgetPerTick(cfg)) + cfg.puffinMaxDelayTicks
      start = ctx.now + latency.toLong
      window = math.min(horizon, life) - latency
      if window > 0
      perQuery = IndexScheduler.expectedSavingsPerQuery(ctx, f, kind, column)
      if perQuery > 0
      share = ctx.stats.columnShare(column)
      direct = ctx.stats.expectedAccesses(f.partition, start, window) * share * perQuery
      carried =
        if (kind.mergeable && life < horizon && untilExpiry > life)
          mergeCarryOver * ctx.stats.expectedAccesses(f.partition, ctx.now + life.toLong, math.min(horizon, untilExpiry) - life) *
            share * perQuery
        else 0.0
      benefit = direct + carried
      cost = t.cost + cfg.commitCost / cfg.puffinBatchBlobs
      if benefit > cost
    } yield (t, benefit / cost)
    scored.sortBy(-_._2).map(_._1)
  }
}

/** A cheap, general, mergeable index (by default f16) built at write time on every column that sees queries, as a
  * hedge against predicates nobody has asked yet; survival-weighted background indexing on top.
  *
  * Because the write-time kind is mergeable, a compaction output whose inputs were all indexed inherits the index by
  * composition, so the write path pays for new ingest files rather than for every rewrite.
  */
final case class Hybrid(writeKinds: Seq[String] = Seq("f16"), background: SurvivalWeighted = SurvivalWeighted())
    extends IndexScheduler {
  val name = "hybrid"

  override def writePath(ctx: SchedulerContext): Seq[IndexTask] = {
    val kinds = ctx.cfg.indexKinds.filter(k => writeKinds.contains(k.name))
    val columns = (0 until ctx.cfg.columns).filter(c => ctx.stats.columnShare(c) >= ctx.cfg.hedgeMinShare)
    IndexScheduler.uncovered(ctx, IndexScheduler.writtenNow(ctx), columns, kinds)
  }

  def select(ctx: SchedulerContext, capacity: Double): Seq[IndexTask] = background.select(ctx, capacity)
}
