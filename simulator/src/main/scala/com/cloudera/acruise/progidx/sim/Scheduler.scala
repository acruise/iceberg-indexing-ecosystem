package com.cloudera.acruise.progidx.sim

/** `valuePerCost`, when the scheduler valued the task: expected benefit over cost, so above 1 if worth building. */
final case class IndexTask(key: IndexKey, kind: IndexKind, cost: Double, valuePerCost: Double = Double.NaN)

/** Where an index can be built: with an ingest commit, with a compaction commit, or in the background. */
sealed trait BuildPath
object BuildPath {
  case object Ingest extends BuildPath
  case object Compaction extends BuildPath
  case object Background extends BuildPath
}

/** How builds made with a commit (ingest or compaction) are paid for. */
sealed trait SyncBudget
object SyncBudget {
  /** Outside any budget. */
  case object Unlimited extends SyncBudget

  /** From the budget the background also uses, first come, first served. */
  case object Greedy extends SyncBudget

  /** From that budget, and only when worth more per unit of cost than the shadow price: what the marginal
    * background build, the first the budget could not fund, would have returned.
    */
  case object Priced extends SyncBudget
}

final case class SchedulerContext(
    now: Long,
    cfg: SimConfig,
    table: TableState,
    policy: CompactionPolicy,
    stats: AccessStats,
    isCovered: IndexKey => Boolean,
    /** Value per unit cost of the marginal background build, smoothed; 1 while the budget has slack. */
    shadowPrice: Double = 1.0
)

/** Decides which indexes to build, in three places:
  *
  *   - `writePath`: built synchronously as ingest writes files and committed with them, so visible immediately.
  *     The cost lands on the ingest path, outside any budget unless `sharesBudget`.
  *   - `compactionPath`: built while a compaction writes its outputs and committed with the rewrite (the `rebuild`
  *     mode of the proposal's Section 8.3), so visible immediately. The cost lands on the compactor, which is reading
  *     the data anyway, outside any budget unless `sharesBudget`. Indexes rolled forward by merging are already
  *     covered.
  *   - `select`: built in the background from a per-tick budget, in the order given; a task the budget can't finish
  *     carries over and is lost if its file dies first. Results become visible at the next Puffin flush.
  */
trait IndexScheduler {
  def name: String
  def budgetPerTick(cfg: SimConfig): Double = cfg.indexBudgetPerTick
  def writePath(ctx: SchedulerContext): Seq[IndexTask] = Nil
  def compactionPath(ctx: SchedulerContext, outputs: Seq[DataFile]): Seq[IndexTask] = Nil
  def select(ctx: SchedulerContext, capacity: Double): Seq[IndexTask]

  /** Whether builds made with ingest or compaction commits draw on the background budget: one bucket of tokens that
    * refills at `budgetPerTick` and holds at most `compactionBurstTicks` of it, which those builds spend first, in the
    * order given, and background builds spend after. Otherwise they are unbudgeted.
    */
  def sharesBudget: Boolean = false
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
    val probe = CostModel.probe(ctx.cfg, kind.estimatedSizeBytes(col, ctx.stats.settledDistinctForecast(column)).toDouble)
    ctx.stats.applicableShare(kind, column) * ctx.stats.minMaxSurvivalRate(column, f.rows) *
      (ctx.stats.exclusionRate(kind.name, column, f.rows, prior) * CostModel.scanWithoutMatch(ctx.cfg, f) - probe)
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

  /** Parquet writers index whatever they write, compaction outputs included. */
  override def compactionPath(ctx: SchedulerContext, outputs: Seq[DataFile]): Seq[IndexTask] =
    IndexScheduler.uncovered(ctx, outputs, ctx.stats.columnsByShare.take(ctx.cfg.topColumns))

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
final case class SurvivalWeighted(
    mergeCarryOver: Double = 0.5,
    paths: Set[BuildPath] = Set(BuildPath.Background),
    budget: SyncBudget = SyncBudget.Unlimited
) extends IndexScheduler {
  import BuildPath._

  val name: String =
    if (paths == Set(Background)) "survival-weighted"
    else if (paths == Set(Background, Compaction) && budget == SyncBudget.Unlimited) "survival+compaction"
    else {
      val where = Seq(Ingest -> "write", Compaction -> "cmp", Background -> "bg").collect { case (p, n) if paths(p) => n }
      val how = budget match {
        case SyncBudget.Unlimited => ""
        case SyncBudget.Greedy    => "/greedy"
        case SyncBudget.Priced    => "/priced"
      }
      s"valued@${where.mkString("+")}$how"
    }

  override def sharesBudget: Boolean = budget != SyncBudget.Unlimited && (paths(Ingest) || paths(Compaction))

  def select(ctx: SchedulerContext, capacity: Double): Seq[IndexTask] =
    if (!paths(Background)) Nil
    else {
      val cfg = ctx.cfg
      val scored = for {
        f <- ctx.table.liveFiles.toSeq
        (life, untilExpiry) = lifeOf(ctx, f)
        column <- 0 until cfg.columns
        kind <- cfg.indexKinds
        t = IndexScheduler.task(f, kind, column)
        if !ctx.isCovered(t.key)
        latency = math.ceil(t.cost / budgetPerTick(cfg)) + cfg.puffinMaxDelayTicks
        v <- valuePerCost(ctx, f, t, life, untilExpiry, latency, t.cost + cfg.commitCost / cfg.puffinBatchBlobs)
      } yield t.copy(valuePerCost = v)
      scored.sortBy(-_.valuePerCost)
    }

  override def writePath(ctx: SchedulerContext): Seq[IndexTask] =
    if (paths(Ingest)) withCommit(ctx, IndexScheduler.writtenNow(ctx)) else Nil

  override def compactionPath(ctx: SchedulerContext, outputs: Seq[DataFile]): Seq[IndexTask] =
    if (paths(Compaction)) withCommit(ctx, outputs) else Nil

  /** The same valuation applied to files a commit is writing, with no latency (the index is committed with them) and
    * no commit cost of its own, best value per cost first; when priced, only what beats the shadow price.
    */
  private def withCommit(ctx: SchedulerContext, files: Seq[DataFile]): Seq[IndexTask] = {
    val floor = if (budget == SyncBudget.Priced) math.max(1.0, ctx.shadowPrice) else 1.0
    val scored = for {
      f <- files
      (life, untilExpiry) = lifeOf(ctx, f)
      column <- 0 until ctx.cfg.columns
      kind <- ctx.cfg.indexKinds
      t = IndexScheduler.task(f, kind, column)
      if !ctx.isCovered(t.key)
      v <- valuePerCost(ctx, f, t, life, untilExpiry, latency = 0.0, cost = t.cost)
      if v >= floor
    } yield t.copy(valuePerCost = v)
    scored.sortBy(-_.valuePerCost)
  }

  /** Predicted ticks until `f` is compacted away or expires, and until it expires. */
  private def lifeOf(ctx: SchedulerContext, f: DataFile): (Double, Double) = {
    val untilExpiry = ((f.partition + 1) * ctx.cfg.partitionTicks + ctx.cfg.retentionTicks - ctx.now).toDouble
    (math.min(ctx.policy.expectedRemainingLife(f, ctx.table, ctx.now, ctx.stats), untilExpiry), untilExpiry)
  }

  /** Expected benefit per unit of cost of building `t` once it is visible after `latency`; None unless benefit > cost. */
  private def valuePerCost(
      ctx: SchedulerContext,
      f: DataFile,
      t: IndexTask,
      life: Double,
      untilExpiry: Double,
      latency: Double,
      cost: Double
  ): Option[Double] = {
    val horizon = ctx.cfg.survivalHorizonTicks.toDouble
    val window = math.min(horizon, life) - latency
    val perQuery = IndexScheduler.expectedSavingsPerQuery(ctx, f, t.kind, t.key.column)
    // A mergeable index carries into the compaction output, but only if it is visible before the file is merged:
    // compaction rolls an index forward only when every input has one. Built with a commit, it always is.
    val carries = t.kind.mergeable && (latency <= 0 || latency < life)
    // A file can die before serving a single query and still be worth indexing, for what it carries forward.
    if (perQuery <= 0 || (window <= 0 && !carries)) None
    else {
      val column = t.key.column
      val direct =
        if (window > 0) ctx.stats.expectedAccesses(column, f.partition, ctx.now + latency.toLong, window) * perQuery else 0.0
      val carried =
        if (carries && life < horizon && untilExpiry > life)
          mergeCarryOver * perQuery *
            ctx.stats.expectedAccesses(column, f.partition, ctx.now + life.toLong, math.min(horizon, untilExpiry) - life)
        else 0.0
      val benefit = direct + carried
      if (benefit > cost) Some(benefit / cost) else None
    }
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

  /** The hedge also covers compaction outputs whose inputs weren't all indexed, so nothing can be merged forward. */
  override def compactionPath(ctx: SchedulerContext, outputs: Seq[DataFile]): Seq[IndexTask] = {
    val kinds = ctx.cfg.indexKinds.filter(k => writeKinds.contains(k.name))
    val columns = (0 until ctx.cfg.columns).filter(c => ctx.stats.columnShare(c) >= ctx.cfg.hedgeMinShare)
    val hedge = IndexScheduler.uncovered(ctx, outputs, columns, kinds)
    hedge ++ background.compactionPath(ctx, outputs).filterNot(t => hedge.exists(_.key == t.key))
  }

  def select(ctx: SchedulerContext, capacity: Double): Seq[IndexTask] = background.select(ctx, capacity)
}
