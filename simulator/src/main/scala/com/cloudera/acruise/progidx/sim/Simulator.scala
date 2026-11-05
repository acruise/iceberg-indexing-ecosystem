package com.cloudera.acruise.progidx.sim

import scala.collection.mutable
import scala.util.Random

final class KindMetrics {
  var built = 0L
  var rolledForward = 0L
  var buildWork = 0.0
  var probes = 0L
  var exclusions = 0L
  var savings = 0.0 // rows skipped thanks to this kind, net of the cost of probing it
  val probesByColumn: mutable.Map[Int, Long] = mutable.TreeMap[Int, Long]().withDefaultValue(0L)
  val exclusionsByColumn: mutable.Map[Int, Long] = mutable.TreeMap[Int, Long]().withDefaultValue(0L)
  val trueMissesByColumn: mutable.Map[Int, Long] = mutable.TreeMap[Int, Long]().withDefaultValue(0L)
}

/** Rows in files that min/max couldn't prune but in which nothing matched the predicate, by what happened to them. */
final class Coverage {
  var pruned = 0.0 // an index ruled the file out
  var falsePositive = 0.0 // an applicable index was probed but couldn't rule it out
  var mismatch = 0.0 // the file had visible indexes on the column, but none could evaluate this predicate shape
  var missing = 0.0 // no visible index on the column at all
  def prunable: Double = pruned + falsePositive + mismatch + missing
  def prunedFraction: Option[Double] = if (prunable > 0) Some(pruned / prunable) else None
}

final class Metrics {
  var baselineQueryCost = 0.0 // what every query would have cost with file-level min/max as the only pruning
  var queries = 0L
  var requests = 0.0 // ranged reads: footers, column chunks and Puffin files
  var baselineRequests = 0.0
  var queryCost = 0.0
  var indexBuildWork = 0.0 // background, from the budget; includes abortedWork
  var writePathWork = 0.0 // built synchronously with ingest
  var compactionPathWork = 0.0 // built synchronously by compaction, committed with the rewrite
  var syncBuildsSkipped = 0L // builds made with a commit that the shared budget couldn't afford
  var abortedWork = 0.0 // partial builds lost when their file died
  var rollForwardWork = 0.0
  var unpaidWork = 0.0 // build cost of retired indexes not recouped by their savings; cost carried into a merged index counts there
  var puffinRewriteWork = 0.0
  var indexCommits = 0L
  var containersWritten = 0L
  var indexesBuilt = 0L
  var indexesRolledForward = 0L
  var indexesRetired = 0L
  var indexesRetiredUnused = 0L
  var filesWritten = 0L
  var filesCompacted = 0L
  var filesExpired = 0L
  var compactionRows = 0L
  var ingestRows = 0L
  var dataCommits = 0L // one per ingested file (a micro-batch) and one per compaction merge
  var filesPrunedByMinMax = 0L
  var filesPrunedByIndex = 0L
  var wrongExclusions = 0L // files an index ruled out although they matched: must stay 0
  val coverage = new Coverage // over rows that could have been skipped, all queries
  val timeline: mutable.Map[Long, Coverage] = mutable.TreeMap() // by timeline bucket
  val byTagHour: mutable.Map[(String, Long), Coverage] = mutable.HashMap() // shift label, hour since start
  val byKind: mutable.LinkedHashMap[String, KindMetrics] = mutable.LinkedHashMap()
  // end-of-run snapshot
  var liveFiles = 0
  var liveIndexes = 0
  var liveContainers = 0

  def kind(name: String): KindMetrics = byKind.getOrElseUpdate(name, new KindMetrics)

  def querySavings: Double = baselineQueryCost - queryCost

  def net(cfg: SimConfig): Double =
    querySavings - indexBuildWork - writePathWork - compactionPathWork - rollForwardWork - puffinRewriteWork - indexCommits * cfg.commitCost
}

final class Simulator(cfg: SimConfig, scheduler: IndexScheduler, policy: CompactionPolicy) {
  /** `cost` includes any unrecovered cost inherited from the indexes it was merged from; `carried` marks an index whose
    * unrecovered cost moved into a merged index, so it isn't counted as unpaid when its file dies.
    */
  private final class IndexRecord(val kind: IndexKind, val data: IndexData, val cost: Double) {
    var savings = 0.0
    var carried = false
    def unrecovered: Double = math.max(0.0, cost - savings)
  }
  private final class InProgress(val task: IndexTask, var remaining: Double)

  val metrics = new Metrics
  /** Budget available to a scheduler whose compaction-time builds share it with background builds. */
  private var tokens = 0.0
  /** Smoothed value per unit cost of the marginal background build: what a unit of shared budget is worth. */
  private var shadowPrice = 1.0
  val table = new TableState
  val stats = new AccessStats(cfg)
  val catalog = new IndexCatalog(cfg, metrics)
  private val workload = new Workload(cfg)
  // One stream per column, so that adding a column leaves the others' data unchanged.
  private val columnRngs = cfg.columnSpecs.indices.map(i => new Random(cfg.seed + 1 + 1000003L * i))
  private val kindsByName = cfg.indexKinds.map(k => k.name -> k).toMap
  private val records = mutable.HashMap[IndexKey, IndexRecord]()
  private val keysByFile = mutable.HashMap[Long, mutable.Set[IndexKey]]()
  private val inProgress = mutable.LinkedHashMap[IndexKey, InProgress]()

  require(kindsByName.size == cfg.indexKinds.size, "index kind names must be unique")

  def run(): Metrics = {
    var now = 0L
    while (now < cfg.ticks) {
      step(now)
      now += 1
    }
    metrics.liveFiles = table.fileCount
    metrics.liveIndexes = records.size
    metrics.liveContainers = catalog.containerCount
    metrics
  }

  def step(now: Long): Unit = {
    if (scheduler.sharesBudget)
      tokens = math.min(scheduler.budgetPerTick(cfg) * cfg.compactionBurstTicks, tokens + scheduler.budgetPerTick(cfg))
    ingest(now)
    writePathIndex(now)
    compact(now)
    expire(now)
    query(now)
    backgroundIndex(now)
    catalog.tick(now)
  }

  def hasIndex(k: IndexKey): Boolean = records.contains(k)
  def indexData(k: IndexKey): Option[IndexData] = records.get(k).map(_.data)

  private def ingest(now: Long): Unit = {
    val partition = now / cfg.partitionTicks
    for (_ <- 0 until cfg.filesPerTick) {
      val columns = cfg.columnSpecs.indices.map { c =>
        ColumnData.of(Array.fill(cfg.distinctValuesPerFile)(cfg.columnSpecs(c).value(columnRngs(c), now)))
      }
      table.add(DataFile(table.newFileId(), partition, 0, cfg.rowsPerFile, now, columns = columns))
      metrics.filesWritten += 1
      metrics.ingestRows += cfg.rowsPerFile
      metrics.dataCommits += 1
    }
  }

  private def compact(now: Long): Unit = {
    var actions = policy.plan(table, now, stats)
    while (actions.nonEmpty) {
      val outputs = actions.flatMap {
        case Finalize(f) =>
          table.replace(f.copy(finalized = true))
          observeSettled(f)
          None
        case m: Merge => Some(merge(m, now))
      }
      compactionPathIndex(now, outputs) // the compactor indexes its outputs as it writes them, before they can be merged again
      actions = policy.plan(table, now, stats)
    }
  }

  private def merge(m: Merge, now: Long): DataFile = {
    val p = m.inputs.head.partition
    require(m.inputs.forall(_.partition == p), "merges stay within a partition")
    val rows = m.inputs.map(_.rows).sum
    val columns = (0 until cfg.columns).map(c => ColumnData.union(m.inputs.map(_.columns(c))))
    val out = DataFile(table.newFileId(), p, m.generation, rows, now, m.finalized, columns)
    table.add(out)
    if (m.finalized) observeSettled(out)
    metrics.filesWritten += 1
    metrics.compactionRows += rows
    metrics.dataCommits += 1

    // Mergeable indexes visible on every input are composed into the output and committed with the compaction.
    val rolled = for {
      kind <- cfg.indexKinds if kind.mergeable
      column <- 0 until cfg.columns
      inputKeys = m.inputs.map(f => IndexKey(f.id, kind.name, column))
      if inputKeys.forall(catalog.isVisible)
      inputRecords = inputKeys.map(records)
      if kind.worthMerging(inputRecords.map(_.data), out.columns(column))
    } yield {
      val data = kind.merge(inputRecords.map(_.data), out.columns(column))
      val cost = inputRecords.map(_.data.sizeBytes).sum / cfg.indexBytesPerCostUnit
      val k = IndexKey(out.id, kind.name, column)
      register(k, kind, data, cost + inputRecords.map(_.unrecovered).sum)
      inputRecords.foreach(_.carried = true)
      metrics.rollForwardWork += cost
      metrics.indexesRolledForward += 1
      metrics.kind(kind.name).rolledForward += 1
      k -> blobSize(data)
    }
    catalog.commitWith(rolled)

    m.inputs.foreach { f =>
      kill(f)
      metrics.filesCompacted += 1
    }
    out
  }

  /** A settled file's per-column distinct counts, as its theta sketches would report them. */
  private def observeSettled(f: DataFile): Unit =
    f.columns.indices.foreach(c => stats.observeSettledDistinct(c, f.columns(c).distinct))

  private def expire(now: Long): Unit =
    table.partitionIds
      .takeWhile(p => (p + 1) * cfg.partitionTicks + cfg.retentionTicks <= now)
      .foreach { p =>
        table.filesIn(p).foreach { f =>
          kill(f)
          metrics.filesExpired += 1
        }
      }

  private def kill(f: DataFile): Unit = {
    table.remove(f.id)
    inProgress.values.filter(_.task.key.fileId == f.id).toList.foreach { ip =>
      metrics.abortedWork += ip.task.cost - ip.remaining
      inProgress.remove(ip.task.key)
    }
    keysByFile.remove(f.id).foreach(_.foreach { k =>
      val rec = records.remove(k).get
      metrics.indexesRetired += 1
      if (!rec.carried) {
        metrics.unpaidWork += rec.unrecovered
        if (rec.savings == 0.0) metrics.indexesRetiredUnused += 1
      }
      catalog.drop(k)
    })
  }

  private def query(now: Long): Unit = {
    val queries = workload.queries(now)
    stats.observe(now, queries)
    metrics.queries += queries.size
    for (q <- queries) {
      val fetched = mutable.HashSet[Long]() // Puffin files this query has read; each serves all its blobs
      for (p <- q.partitions; f <- table.filesIn(p)) {
        val col = f.columns(q.column)
        val survives = col.overlapsMinMax(q.predicate)
        stats.observeMinMax(q.column, f.rows, survives)
        if (!survives) metrics.filesPrunedByMinMax += 1
        else {
          val scan = CostModel.scan(cfg, f, col, q.predicate)
          val scanRequests = CostModel.scanRequests(cfg, f, col, q.predicate)
          metrics.baselineQueryCost += scan
          metrics.baselineRequests += scanRequests
          // Probe visible, applicable indexes smallest first; stop at the first that rules the file out.
          val probes = cfg.indexKinds.iterator
            .filter(_.supports(q.predicate))
            .map(kind => IndexKey(f.id, kind.name, q.column))
            .filter(catalog.isVisible)
            .map(records)
            .toSeq
            .sortBy(_.data.sizeBytes)
          val salt = f.id * 31 + q.column
          var cost = 0.0
          var excluded = false
          val it = probes.iterator
          while (!excluded && it.hasNext) {
            val rec = it.next()
            val key = IndexKey(f.id, rec.kind.name, q.column)
            val newContainer = catalog.containerOf(key).exists(fetched.add)
            if (newContainer) metrics.requests += 1
            val probeCost = rec.data.sizeBytes / cfg.indexBytesPerCostUnit + (if (newContainer) cfg.requestCost else 0.0)
            val km = metrics.kind(rec.kind.name)
            cost += probeCost
            rec.savings -= probeCost
            km.savings -= probeCost
            km.probes += 1
            km.probesByColumn(q.column) += 1
            if (!col.matches(q.predicate)) km.trueMissesByColumn(q.column) += 1
            excluded = !rec.kind.mightMatch(rec.data, col, q.predicate, salt)
            stats.observeProbe(rec.kind.name, q.column, f.rows, excluded)
            if (excluded) {
              rec.savings += scan
              km.savings += scan
              km.exclusions += 1
              km.exclusionsByColumn(q.column) += 1
              metrics.filesPrunedByIndex += 1
              if (col.matches(q.predicate)) metrics.wrongExclusions += 1
            }
          }
          if (!excluded) {
            cost += scan
            metrics.requests += scanRequests
          }
          metrics.queryCost += cost
          if (!col.matches(q.predicate)) {
            val hasAnyIndex = cfg.indexKinds.exists(k => catalog.isVisible(IndexKey(f.id, k.name, q.column)))
            val buckets = Seq(metrics.coverage, metrics.timeline.getOrElseUpdate(now / cfg.timelineBucketTicks, new Coverage)) ++
              q.tag.map(t => metrics.byTagHour.getOrElseUpdate((t, now / cfg.partitionTicks), new Coverage))
            buckets.foreach { c =>
              if (excluded) c.pruned += f.rows
              else if (probes.nonEmpty) c.falsePositive += f.rows
              else if (hasAnyIndex) c.mismatch += f.rows
              else c.missing += f.rows
            }
          }
        }
      }
    }
  }

  private def context(now: Long): SchedulerContext =
    SchedulerContext(now, cfg, table, policy, stats, k => records.contains(k) || inProgress.contains(k), shadowPrice)

  private def complete(task: IndexTask, now: Long): IndexData = {
    val f = table.get(task.key.fileId).get
    val data = task.kind.build(f.columns(task.key.column), stats.settledDistinctForecast(task.key.column))
    register(task.key, task.kind, data, task.cost)
    metrics.indexesBuilt += 1
    metrics.kind(task.kind.name).built += 1
    data
  }

  /** Indexes built with the files just written, committed with them, so visible before anything else can happen to
    * those files.
    */
  private def writePathIndex(now: Long): Unit = {
    val ctx = context(now)
    val built = affordable(scheduler.writePath(ctx).filter(t => !ctx.isCovered(t.key))).map { t =>
      metrics.writePathWork += t.cost
      metrics.kind(t.kind.name).buildWork += t.cost
      t.key -> blobSize(complete(t, now))
    }
    catalog.commitWith(built)
  }

  /** Indexes built by the compactor on its outputs, committed with the rewrite. */
  private def compactionPathIndex(now: Long, outputs: Seq[DataFile]): Unit = {
    if (outputs.isEmpty) return
    val ctx = context(now)
    val built = affordable(scheduler.compactionPath(ctx, outputs).filter(t => !ctx.isCovered(t.key))).map { t =>
      metrics.compactionPathWork += t.cost
      metrics.kind(t.kind.name).buildWork += t.cost
      t.key -> blobSize(complete(t, now))
    }
    catalog.commitWith(built)
  }

  /** Builds made with a commit, in the order given, as far as the shared budget allows when they share it. */
  private def affordable(tasks: Seq[IndexTask]): Seq[IndexTask] =
    if (!scheduler.sharesBudget) tasks
    else
      tasks.filter { t =>
        val fits = t.cost <= tokens
        if (fits) tokens -= t.cost else metrics.syncBuildsSkipped += 1
        fits
      }

  private def backgroundIndex(now: Long): Unit = {
    var capacity = scheduler.budgetPerTick(cfg)
    if (scheduler.sharesBudget) capacity = math.min(capacity, tokens)
    val available = capacity
    // The value per cost of the first build the budget could not fund; 1 when nothing was turned away.
    var marginal = 1.0

    def work(ip: InProgress): Unit = {
      val w = math.min(capacity, ip.remaining)
      ip.remaining -= w
      capacity -= w
      metrics.indexBuildWork += w
      metrics.kind(ip.task.kind.name).buildWork += w
      if (ip.remaining <= 1e-9) {
        inProgress.remove(ip.task.key)
        catalog.stage(ip.task.key, blobSize(complete(ip.task, now)), now)
      }
    }

    inProgress.values.toList.foreach(ip => if (capacity > 0) work(ip))

    val ctx = context(now)
    val it = scheduler.select(ctx, capacity).iterator.filter(t => !ctx.isCovered(t.key) && table.get(t.key.fileId).isDefined)
    while (capacity > 0 && it.hasNext) {
      val t = it.next()
      val ip = new InProgress(t, t.cost)
      inProgress(t.key) = ip
      work(ip)
    }
    if (it.hasNext) marginal = math.max(1.0, it.next().valuePerCost match { case v if v.isNaN => 1.0; case v => v })
    shadowPrice += 0.1 * (marginal - shadowPrice)
    if (scheduler.sharesBudget) tokens -= available - capacity
  }

  /** Blob size in cost units, which is what Puffin rewrites are charged in. */
  private def blobSize(data: IndexData): Double = data.sizeBytes / cfg.indexBytesPerCostUnit

  private def register(k: IndexKey, kind: IndexKind, data: IndexData, cost: Double): Unit = {
    records(k) = new IndexRecord(kind, data, cost)
    keysByFile.getOrElseUpdate(k.fileId, mutable.Set[IndexKey]()) += k
  }
}
