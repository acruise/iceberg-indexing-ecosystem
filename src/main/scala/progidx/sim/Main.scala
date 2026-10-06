package progidx.sim

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

object Main {
  val schedulers: Seq[IndexScheduler] = Seq(
    NoIndexing,
    EagerAtWrite(),
    GenerationThreshold(minGeneration = 1),
    GenerationThreshold(minGeneration = 0, finalizedOnly = true),
    SurvivalWeighted(),
    Hybrid()
  )

  def runAll(cfg: SimConfig): Seq[(IndexScheduler, Metrics)] =
    schedulers.map(s => s -> new Simulator(cfg, s, new TieredWithSettle(cfg)).run())

  private def pct(x: Double, of: Double): String = if (of > 0) f"${100.0 * x / of}%5.1f%%" else "    -"

  def report(title: String, cfg: SimConfig, results: Seq[(IndexScheduler, Metrics)], perColumn: Boolean): String = {
    val header = f"${"scheduler"}%-20s ${"query cost"}%12s ${"vs none"}%8s ${"bg build"}%10s ${"write bld"}%10s ${"unpaid"}%11s " +
      f"${"rollfwd"}%9s ${"puffin rw"}%9s ${"idx cmts"}%8s ${"net"}%12s   ${"pruned"}%6s ${"fp"}%6s ${"mismatch"}%8s ${"missing"}%7s"
    val rows = results.flatMap { case (s, m) =>
      val build = f"${m.indexBuildWork}%10.0f ${m.writePathWork}%10.0f"
      val c = m.coverage
      val main = f"${s.name}%-20s ${m.queryCost}%12.0f ${pct(m.queryCost, m.baselineQueryCost)}%8s $build " +
        f"${m.unpaidWork}%11.0f ${m.rollForwardWork}%9.0f ${m.puffinRewriteWork}%9.0f ${m.indexCommits}%8d " +
        f"${m.net(cfg)}%12.0f   ${pct(c.pruned, c.prunable)}%6s ${pct(c.falsePositive, c.prunable)}%6s " +
        f"${pct(c.mismatch, c.prunable)}%8s ${pct(c.missing, c.prunable)}%7s"
      val kinds = m.byKind.toSeq.map { case (name, k) =>
        f"    $name%-16s built ${k.built}%6d  rolled ${k.rolledForward}%6d  work ${k.buildWork}%10.0f  " +
          f"probes ${k.probes}%8d  excluded ${pct(k.exclusions.toDouble, k.probes.toDouble)}  savings ${k.savings}%12.0f"
      }
      main +: kinds
    }
    val legend = Seq(
      "(bg build = background budget spent; write bld = paid synchronously on the write path)",
      "(pruned/fp/mismatch/missing: shares of rows min/max couldn't prune although nothing in them matched; mismatch = " +
        "indexes on that column exist but none can evaluate the predicate)"
    )
    (Seq(s"== $title", header) ++ rows ++ legend ++ (if (perColumn) columnBreakdown(cfg, results) else Nil) ++
      timeline(cfg, results) ++ recovery(cfg, results)).mkString("\n")
  }

  private def columnBreakdown(cfg: SimConfig, results: Seq[(IndexScheduler, Metrics)]): Seq[String] =
    results.collectFirst { case (_: EagerAtWrite, m) => m }.toSeq.flatMap { m =>
      "  per column, eager-at-write (excluded / probes; 'prunable' = probes where nothing in the file matched):" +:
        m.byKind.toSeq.flatMap { case (name, k) =>
          k.probesByColumn.toSeq.map { case (c, n) =>
            f"    $name%-6s ${cfg.columnSpecs(c).name}%-14s probes $n%8d  " +
              f"excluded ${pct(k.exclusionsByColumn(c).toDouble, n.toDouble)}  prunable ${pct(k.trueMissesByColumn(c).toDouble, n.toDouble)}"
          }
        }
    }

  private def timeline(cfg: SimConfig, results: Seq[(IndexScheduler, Metrics)]): Seq[String] = {
    val buckets = (0L until (cfg.ticks + cfg.timelineBucketTicks - 1) / cfg.timelineBucketTicks).toSeq
    val hours = cfg.timelineBucketTicks / 60
    val marks = buckets.map { b =>
      val (from, until) = (b * cfg.timelineBucketTicks, (b + 1) * cfg.timelineBucketTicks)
      if (cfg.shifts.exists(s => s.from >= from && s.from < until)) "*" else " "
    }
    val head = f"  pruned share by ${hours}h bucket (* = a shift starts)".padTo(42, ' ') +
      buckets.zip(marks).map { case (b, m) => f"$m${b * hours}%4dh" }.mkString(" ")
    head +: results.filterNot(_._1 == NoIndexing).map { case (s, m) =>
      f"    ${s.name}%-36s  " + buckets.map(b => m.timeline.get(b).flatMap(_.prunedFraction).fold("     -")(f => f"${100 * f}%5.0f%%")).mkString(" ")
    }
  }

  private def recovery(cfg: SimConfig, results: Seq[(IndexScheduler, Metrics)]): Seq[String] =
    cfg.shifts.flatMap { shift =>
      val first = shift.from / cfg.partitionTicks
      val last = (math.min(shift.until, cfg.ticks) - 1) / cfg.partitionTicks
      val header = f"  shift @${shift.from / 60}h: ${shift.label}"
      header +: results.filterNot(_._1 == NoIndexing).map { case (s, m) =>
        val hourly = (first to last).map(h => h -> m.byTagHour.get((shift.label, h)))
        def total(hs: Seq[(Long, Option[Coverage])]): Coverage = {
          val c = new Coverage
          hs.flatMap(_._2).foreach { x =>
            c.pruned += x.pruned; c.falsePositive += x.falsePositive; c.mismatch += x.mismatch; c.missing += x.missing
          }
          c
        }
        val firstSix = total(hourly.take(6))
        val all = total(hourly)
        val t50 = hourly.collectFirst { case (h, Some(c)) if c.prunedFraction.exists(_ >= 0.5) => s"${h - first}h" }
        f"    ${s.name}%-20s pruned first 6h ${pct(firstSix.pruned, firstSix.prunable)}  whole shift ${pct(all.pruned, all.prunable)}  " +
          f"fp ${pct(all.falsePositive, all.prunable)}  mismatch ${pct(all.mismatch, all.prunable)}  " +
          f"missing ${pct(all.missing, all.prunable)}  " +
          f"time to 50%% pruned ${t50.getOrElse("never")}%6s"
      }
    }

  /** Usage: run [budget ...]; background index budgets per tick to sweep (default 4000). */
  def main(args: Array[String]): Unit = {
    val budgets = if (args.nonEmpty) args.toSeq.map(_.toDouble) else Seq(4000.0)
    val kindSets = Seq[(String, Seq[IndexKind])](
      "bloom" -> Seq(BloomKind()),
      "f16" -> Seq(F16Kind()),
      "bloom + f16" -> Seq(BloomKind(), F16Kind())
    )
    for {
      (scenario, build) <- Seq[(String, SimConfig => SimConfig)]("steady" -> Scenarios.steady, "drift" -> Scenarios.drift)
      (label, kinds) <- kindSets
      budget <- budgets
    } {
      val cfg = build(SimConfig(indexKinds = kinds, indexBudgetPerTick = budget))
      println(report(f"$scenario workload, $label, background budget $budget%.0f/tick", cfg, runAll(cfg), perColumn = false))
      println()
    }
  }
}
