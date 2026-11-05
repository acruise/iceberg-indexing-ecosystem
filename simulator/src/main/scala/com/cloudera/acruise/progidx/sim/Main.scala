package com.cloudera.acruise.progidx.sim

object Scenarios {
  /** No scheduled shifts; column popularity rotates daily. */
  def steady(base: SimConfig): SimConfig = base

  /** Rotation off, so every change in the workload comes from one of three scheduled shifts:
    * a new hot column, a column switching from equality to range predicates, and a burst of lookups on old data
    * against a column nobody had queried before.
    */
  def drift(base: SimConfig): SimConfig = {
    val col = (name: String) => ColumnSpec.indexOf(name, base.columnSpecs)
    val day = 1440L
    base.copy(
      driftEveryTicks = Long.MaxValue,
      shifts = Seq(
        HotColumn("new hot column: order_id lookups", col("order_id"), share = 0.3, from = day),
        ShapeChange("status: equality -> range", col("status"), RangeShape, from = day + day / 2),
        Forensic(
          "forensics: trace_id lookups on 1-2 day old data",
          col("trace_id"),
          share = 0.3,
          minAge = 24,
          maxAge = 47,
          from = 2 * day,
          until = 2 * day + 360
        )
      )
    )
  }
}

object SessionScenario {
  /** Lookups by keys that cluster with insertion order without being derivable from it: a fifth of queries look up a
    * session, a fifth a tenant, from the start; on day 2, a six-hour burst of lookups of sessions one to two days old.
    */
  def apply(base: SimConfig): SimConfig = {
    val col = (name: String) => ColumnSpec.indexOf(name, base.columnSpecs)
    val day = 1440L
    base.copy(
      shifts = Seq(
        HotColumn("session lookups", col("session_id"), share = 0.2, from = 0),
        HotColumn("tenant lookups", col("tenant_id"), share = 0.25, from = 0),
        Forensic(
          "forensics: session lookups on 1-2 day old data",
          col("session_id"),
          share = 0.3,
          minAge = 24,
          maxAge = 47,
          from = 2 * day,
          until = 2 * day + 360
        )
      )
    )
  }
}

object CdcScenario {
  /** Change data captured from an OLTP orders table, merged every five minutes: each MERGE joins the 50 orders being
    * updated now against the whole table, which has no partition filter to help it.
    */
  def apply(base: SimConfig): SimConfig =
    base.copy(shifts =
      Seq(KeyBatch("cdc merges", ColumnSpec.indexOf("order_key", base.columnSpecs), 50, 5, keyWindowTicks = Some(1))))
}

object ErasureScenario {
  /** Right-to-erasure requests, processed four times a day in batches of five: each batch looks for every row of five
    * customers who may have written at any time.
    */
  def apply(base: SimConfig): SimConfig =
    base.copy(shifts =
      Seq(KeyBatch("erasure batches", ColumnSpec.indexOf("customer_id", base.columnSpecs), 5, 360, keyWindowTicks = None)))
}

object StreamingScenario {
  /** The same rows as the other scenarios, written as ten times as many micro-batches of a tenth the size, with one
    * more compaction tier to absorb them: the small-files problem of streaming writes into Iceberg.
    */
  def apply(base: SimConfig): SimConfig =
    base.copy(filesPerTick = base.filesPerTick * 10, rowsPerFile = math.max(1L, base.rowsPerFile / 10), maxGeneration = base.maxGeneration + 1)
}

object StreamingArchiveScenario {
  /** Streaming writes whose readers mostly look at older data, such as next-day reports over streamed logs. */
  def apply(base: SimConfig): SimConfig = StreamingScenario(base).copy(recentQueryFraction = 0.05)
}

object Main {
  val schedulers: Seq[IndexScheduler] = Seq(
    NoIndexing,
    EagerAtWrite(),
    GenerationThreshold(minGeneration = 1),
    GenerationThreshold(minGeneration = 0, finalizedOnly = true),
    SurvivalWeighted(),
    SurvivalWeighted(paths = Set(BuildPath.Compaction, BuildPath.Background)),
    // The same valuation and budget, varying only when builds happen.
    SurvivalWeighted(paths = Set(BuildPath.Ingest), budget = SyncBudget.Priced),
    SurvivalWeighted(paths = Set(BuildPath.Compaction), budget = SyncBudget.Priced),
    SurvivalWeighted(paths = Set(BuildPath.Compaction, BuildPath.Background), budget = SyncBudget.Priced),
    SurvivalWeighted(paths = Set(BuildPath.Ingest, BuildPath.Compaction, BuildPath.Background), budget = SyncBudget.Priced),
    Hybrid()
  )

  type PolicyFactory = SimConfig => CompactionPolicy
  val tiered: Seq[(String, PolicyFactory)] = Seq("tiered" -> (c => new TieredWithSettle(c)))
  val tieredAndReadAware: Seq[(String, PolicyFactory)] = tiered :+ ("read-aware" -> (c => new ReadAwareTiered(c)))

  /** One simulation: a scheduler under a compaction policy, named for the report. */
  final case class Arm(name: String, scheduler: IndexScheduler, policy: PolicyFactory)

  def arms(policies: Seq[(String, PolicyFactory)]): Seq[Arm] =
    for ((label, factory) <- policies; s <- schedulers)
      yield Arm(if (policies.size > 1) s"${s.name}/$label" else s.name, s, factory)

  def run(cfg: SimConfig, arm: Arm): Metrics = new Simulator(cfg, arm.scheduler, arm.policy(cfg)).run()

  private def pct(x: Double, of: Double): String = if (of > 0) f"${100.0 * x / of}%5.1f%%" else "    -"

  def report(title: String, cfg: SimConfig, results: Seq[(Arm, Metrics)]): String = {
    val w = math.max(20, results.map(_._1.name.length).max)
    val header = s"%-${w}s".format("scheduler") + f" ${"query cost"}%12s ${"vs none"}%8s ${"reqs/q"}%7s ${"bg build"}%10s ${"write bld"}%10s ${"cmpct bld"}%10s ${"unpaid"}%11s " +
      f"${"rollfwd"}%9s ${"puffin rw"}%9s ${"idx cmts"}%8s ${"wamp"}%5s ${"cmts/t"}%6s ${"net"}%12s   ${"pruned"}%6s ${"fp"}%6s ${"mismatch"}%8s ${"missing"}%7s"
    val rows = results.flatMap { case (arm, m) =>
      val s = summarize(cfg, m)
      val build = f"${m.indexBuildWork}%10.0f ${m.writePathWork}%10.0f ${m.compactionPathWork}%10.0f"
      val c = m.coverage
      val main = s"%-${w}s".format(arm.name) + f" ${m.queryCost}%12.0f ${pct(m.queryCost, m.baselineQueryCost)}%8s ${s("reqs/q")}%7.1f $build " +
        f"${m.unpaidWork}%11.0f ${m.rollForwardWork}%9.0f ${m.puffinRewriteWork}%9.0f ${m.indexCommits}%8d " +
        f"${s("wamp")}%5.2f ${s("commits/tick")}%6.1f ${m.net(cfg)}%12.0f   ${pct(c.pruned, c.prunable)}%6s ${pct(c.falsePositive, c.prunable)}%6s " +
        f"${pct(c.mismatch, c.prunable)}%8s ${pct(c.missing, c.prunable)}%7s"
      val kinds = m.byKind.toSeq.map { case (name, k) =>
        f"    $name%-16s built ${k.built}%6d  rolled ${k.rolledForward}%6d  work ${k.buildWork}%10.0f  " +
          f"probes ${k.probes}%8d  excluded ${pct(k.exclusions.toDouble, k.probes.toDouble)}  savings ${k.savings}%12.0f"
      }
      main +: kinds
    }
    val legend = Seq(
      "(bg build = background budget spent; write bld = paid synchronously by ingest; cmpct bld = paid synchronously by compaction)",
      "(wamp = rows rewritten by compaction per row ingested; cmts/t = data commits per tick, one per micro-batch and per merge)",
      "(pruned/fp/mismatch/missing: shares of rows min/max couldn't prune although nothing in them matched; mismatch = " +
        "indexes on that column exist but none can evaluate the predicate)"
    )
    (Seq(s"== $title", header) ++ rows ++ legend ++ timeline(cfg, results) ++ recovery(cfg, results)).mkString("\n")
  }

  /** The headline numbers of one run, as plain values, so that runs over several seeds can be averaged. */
  def summarize(cfg: SimConfig, m: Metrics): Map[String, Double] = {
    val c = m.coverage
    def share(x: Double, of: Double) = if (of > 0) 100 * x / of else Double.NaN
    Map(
      "query cost %" -> share(m.queryCost, m.baselineQueryCost),
      "reqs/q" -> (if (m.queries > 0) m.requests / m.queries else Double.NaN),
      "bg build" -> m.indexBuildWork,
      "write build" -> m.writePathWork,
      "cmpct build" -> m.compactionPathWork,
      "unpaid" -> m.unpaidWork,
      "net" -> m.net(cfg),
      "pruned %" -> share(c.pruned, c.prunable),
      "fp %" -> share(c.falsePositive, c.prunable),
      "wamp" -> (if (m.ingestRows > 0) m.compactionRows.toDouble / m.ingestRows else Double.NaN),
      "commits/tick" -> m.dataCommits.toDouble / cfg.ticks
    )
  }

  private def shiftCoverage(cfg: SimConfig, m: Metrics, shift: WorkloadShift): (Seq[(Long, Option[Coverage])], Coverage, Coverage) = {
    val first = shift.from / cfg.partitionTicks
    val last = (math.min(shift.until, cfg.ticks) - 1) / cfg.partitionTicks
    val hourly = (first to last).map(h => (h - first) -> m.byTagHour.get((shift.label, h)))
    def total(hs: Seq[(Long, Option[Coverage])]): Coverage = {
      val c = new Coverage
      hs.flatMap(_._2).foreach { x =>
        c.pruned += x.pruned; c.falsePositive += x.falsePositive; c.mismatch += x.mismatch; c.missing += x.missing
      }
      c
    }
    (hourly, total(hourly.take(6)), total(hourly))
  }

  /** Per shift: pruned share in its first six hours, and over the whole shift. */
  def shiftSummary(cfg: SimConfig, m: Metrics): Seq[(String, Double, Double)] =
    cfg.shifts.map { shift =>
      val (_, firstSix, all) = shiftCoverage(cfg, m, shift)
      (shift.label, firstSix.prunedFraction.fold(Double.NaN)(100 * _), all.prunedFraction.fold(Double.NaN)(100 * _))
    }

  private def timeline(cfg: SimConfig, results: Seq[(Arm, Metrics)]): Seq[String] = {
    val buckets = (0L until (cfg.ticks + cfg.timelineBucketTicks - 1) / cfg.timelineBucketTicks).toSeq
    val hours = cfg.timelineBucketTicks / 60
    val marks = buckets.map { b =>
      val (from, until) = (b * cfg.timelineBucketTicks, (b + 1) * cfg.timelineBucketTicks)
      if (cfg.shifts.exists(s => s.from >= from && s.from < until)) "*" else " "
    }
    val head = f"  pruned share by ${hours}h bucket (* = a shift starts)".padTo(42, ' ') +
      buckets.zip(marks).map { case (b, m) => f"$m${b * hours}%4dh" }.mkString(" ")
    head +: results.filterNot(_._1.scheduler == NoIndexing).map { case (arm, m) =>
      f"    ${arm.name}%-36s  " + buckets.map(b => m.timeline.get(b).flatMap(_.prunedFraction).fold("     -")(f => f"${100 * f}%5.0f%%")).mkString(" ")
    }
  }

  private def recovery(cfg: SimConfig, results: Seq[(Arm, Metrics)]): Seq[String] =
    cfg.shifts.flatMap { shift =>
      val header = f"  shift @${shift.from / 60}h: ${shift.label}"
      header +: results.filterNot(_._1.scheduler == NoIndexing).map { case (arm, m) =>
        val (hourly, firstSix, all) = shiftCoverage(cfg, m, shift)
        val t50 = hourly.collectFirst { case (h, Some(c)) if c.prunedFraction.exists(_ >= 0.5) => s"${h}h" }
        f"    ${arm.name}%-20s pruned first 6h ${pct(firstSix.pruned, firstSix.prunable)}  whole shift ${pct(all.pruned, all.prunable)}  " +
          f"fp ${pct(all.falsePositive, all.prunable)}  mismatch ${pct(all.mismatch, all.prunable)}  " +
          f"missing ${pct(all.missing, all.prunable)}  " +
          f"time to 50%% pruned ${t50.getOrElse("never")}%6s"
      }
    }

  private def meanSd(xs: Seq[Double]): String = {
    val v = xs.filterNot(_.isNaN)
    if (v.isEmpty) "-"
    else {
      val mean = v.sum / v.size
      val sd = if (v.size > 1) math.sqrt(v.map(x => (x - mean) * (x - mean)).sum / (v.size - 1)) else 0.0
      if (math.abs(mean) >= 1e5) f"${mean / 1e6}%.2fM ±${sd / 1e6}%.2f" else f"$mean%.1f ±$sd%.1f"
    }
  }

  /** Every arm over several seeds, run in parallel; means and sample standard deviations of the headline numbers. */
  def sweep(title: String, cfg: SimConfig, armsToRun: Seq[Arm], seeds: Seq[Long], pool: java.util.concurrent.ExecutorService): String = {
    val futures = for (arm <- armsToRun; s <- seeds) yield {
      val seeded = cfg.withSeed(s)
      arm -> pool.submit(() => { val m = run(seeded, arm); (summarize(seeded, m), shiftSummary(seeded, m)) })
    }
    val results = futures.groupBy(_._1).view.mapValues(_.map(_._2.get())).toMap
    val columns = Seq("query cost %", "reqs/q", "bg build", "write build", "cmpct build", "unpaid", "net", "pruned %", "fp %", "wamp", "commits/tick")
    val w = math.max(20, armsToRun.map(_.name.length).max)
    val header = s"%-${w}s".format("scheduler") + columns.map(c => f" $c%16s").mkString
    val rows = armsToRun.map { arm =>
      s"%-${w}s".format(arm.name) + columns.map(c => f" ${meanSd(results(arm).map(_._1(c)))}%16s").mkString
    }
    val shifts = cfg.shifts.indices.flatMap { i =>
      f"  ${cfg.shifts(i).label} (pruned share: first 6h, whole shift)" +:
        armsToRun.filterNot(_.scheduler == NoIndexing).map { arm =>
          val (firstSix, whole) = results(arm).map(r => (r._2(i)._2, r._2(i)._3)).unzip
          s"    %-${w}s".format(arm.name) + f" ${meanSd(firstSix)}%16s ${meanSd(whole)}%16s"
        }
    }
    (Seq(s"== $title, ${seeds.size} seeds (mean ±sd)", header) ++ rows ++ shifts).mkString("\n")
  }

  /** Usage: run [--scenarios=name,...] [--kinds=label,...] [--schedulers=name,...] [--seeds=N] [budget ...], with
    * kind-set labels written without spaces. Background index budgets per tick
    * to sweep (default 4000); optionally which scenarios and kind sets to run; with --seeds, every configuration runs
    * over N seeds in parallel and only means and standard deviations are reported.
    */
  def main(args: Array[String]): Unit = {
    val (options, numbers) = args.toSeq.partition(_.startsWith("--"))
    def option(name: String): Option[String] = options.collectFirst { case o if o.startsWith(s"--$name=") => o.stripPrefix(s"--$name=") }
    val budgets = if (numbers.nonEmpty) numbers.map(_.toDouble) else Seq(4000.0)
    val onlyScenarios = option("scenarios").map(_.split(',').toSet)
    // Kind-set labels are matched without spaces, since sbt splits run arguments on them: --kinds=bloom+f16,sbloom+f16
    val onlyKinds = option("kinds").map(_.split(',').map(_.replace(" ", "")).toSet)
    val seeds = option("seeds").map(n => (1L to n.toLong).map(_ * 7919L))
    val onlySchedulers = option("schedulers").map(_.split(',').toSet)
    def selected(policies: Seq[(String, PolicyFactory)]): Seq[Arm] =
      arms(policies).filter(a => onlySchedulers.forall(_(a.scheduler.name)))
    val kindSets = Seq[(String, Seq[IndexKind])](
      "bloom" -> Seq(BloomKind()),
      "f16" -> Seq(F16Kind()),
      "bloom + f16" -> Seq(BloomKind(), F16Kind()),
      "sbloom + f16" -> Seq(SizedBloomKind(), F16Kind()),
      "sbloom(0.1%) + f16" -> Seq(SizedBloomKind(falsePositiveRate = 0.001, maxMergedFalsePositiveRate = 0.002), F16Kind())
    )
    val keyed = Set("bloom + f16", "sbloom + f16", "sbloom(0.1%) + f16")
    val scenarios = Seq[(String, SimConfig => SimConfig, Set[String], Seq[(String, PolicyFactory)])](
      ("steady", Scenarios.steady, kindSets.map(_._1).toSet - "sbloom(0.1%) + f16", tiered),
      ("drift", Scenarios.drift, kindSets.map(_._1).toSet - "sbloom(0.1%) + f16", tiered),
      ("sessions", SessionScenario(_), Set("bloom + f16", "sbloom + f16"), tiered),
      ("cdc", CdcScenario(_), keyed, tiered),
      ("erasure", ErasureScenario(_), keyed, tiered),
      ("streaming", StreamingScenario(_), Set("bloom + f16", "sbloom + f16"), tieredAndReadAware),
      ("streaming-archive", StreamingArchiveScenario(_), Set("sbloom + f16"), tieredAndReadAware)
    )
    val pool = java.util.concurrent.Executors.newFixedThreadPool(math.max(1, Runtime.getRuntime.availableProcessors - 2))
    try
      for {
        (scenario, build, wanted, policies) <- scenarios if onlyScenarios.forall(_(scenario))
        (label, kinds) <- kindSets if wanted(label) && onlyKinds.forall(_(label.replace(" ", "")))
        budget <- budgets
      } {
        val cfg = build(SimConfig(indexKinds = kinds, indexBudgetPerTick = budget))
        val title = f"$scenario workload, $label, background budget $budget%.0f/tick"
        seeds match {
          case Some(ss) => println(sweep(title, cfg, selected(policies), ss, pool))
          case None =>
            val results = selected(policies).map(a => a -> pool.submit(() => run(cfg, a))).map { case (a, f) => a -> f.get() }
            println(report(title, cfg, results))
        }
        println()
      }
    finally pool.shutdown()
  }
}
