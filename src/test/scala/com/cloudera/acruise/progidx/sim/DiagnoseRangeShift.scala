package com.cloudera.acruise.progidx.sim

/** Temporary: why does survival-weighted never build f16 on status after the equality -> range shift? */
object DiagnoseRangeShift {
  def main(args: Array[String]): Unit = {
    val cfg = Scenarios.drift(SimConfig(indexKinds = Seq(BloomKind(), F16Kind()), indexBudgetPerTick = 4000.0))
    val status = ColumnSpec.indexOf("status", cfg.columnSpecs)
    val shiftAt = 1440L + 720L
    val reportAt = Set(shiftAt - 60, shiftAt + 60, shiftAt + 180, shiftAt + 360)
    val sw = SurvivalWeighted()

    val probe = new IndexScheduler {
      val name = "probe"
      def select(ctx: SchedulerContext, capacity: Double): Seq[IndexTask] = {
        val chosen = sw.select(ctx, capacity)
        if (reportAt(ctx.now)) report(ctx, chosen, status)
        chosen
      }
    }
    new Simulator(cfg, probe, new TieredWithSettle(cfg)).run()
  }

  private def report(ctx: SchedulerContext, chosen: Seq[IndexTask], status: Int): Unit = {
    val cfg = ctx.cfg
    val s = ctx.stats
    val f16 = cfg.indexKinds.find(_.name == "f16").get
    val bloom = cfg.indexKinds.find(_.name == "bloom").get
    println(f"\n== t=${ctx.now} (${ctx.now / 60}h)  status share ${s.columnShare(status)}%.3f  " +
      f"applicable f16 ${s.applicableShare(f16, status)}%.3f bloom ${s.applicableShare(bloom, status)}%.3f")
    val current = ctx.now / cfg.partitionTicks
    val recent = ctx.table.liveFiles.filter(f => current - f.partition <= 1).toSeq.sortBy(f => (f.partition, f.generation))
    println(f"  ${"file"}%6s ${"part"}%5s ${"gen"}%3s ${"rows"}%5s ${"distinct"}%8s ${"static"}%6s ${"mmSurv"}%6s " +
      f"${"excl"}%6s ${"perQ"}%8s ${"life"}%6s ${"window"}%6s ${"direct"}%9s ${"cost"}%7s ${"covered"}%7s ${"rank"}%6s")
    val ranks = chosen.zipWithIndex.map { case (t, i) => t.key -> i }.toMap
    recent.foreach { f =>
      val col = f.columns(status)
      val t = IndexScheduler.task(f, f16, status)
      val untilExpiry = ((f.partition + 1) * cfg.partitionTicks + cfg.retentionTicks - ctx.now).toDouble
      val life = math.min(ctx.policy.expectedRemainingLife(f, ctx.table, ctx.now, ctx.stats), untilExpiry)
      val latency = math.ceil(t.cost / cfg.indexBudgetPerTick) + cfg.puffinMaxDelayTicks
      val window = math.min(cfg.survivalHorizonTicks.toDouble, life) - latency
      val prior = cfg.exclusionPrior * f16.staticUsefulness(col)
      val excl = s.exclusionRate("f16", status, f.rows, prior)
      val perQ = IndexScheduler.expectedSavingsPerQuery(ctx, f, f16, status)
      val direct = s.expectedAccesses(f.partition, ctx.now + latency.toLong, math.max(0, window)) * s.columnShare(status) * perQ
      val cost = t.cost + cfg.commitCost / cfg.puffinBatchBlobs
      println(f"  ${f.id}%6d ${f.partition}%5d ${f.generation}%3d ${f.rows}%5d ${col.distinct}%8d ${f16.staticUsefulness(col)}%6.3f " +
        f"${s.minMaxSurvivalRate(status, f.rows)}%6.3f $excl%6.3f $perQ%8.2f $life%6.0f $window%6.0f $direct%9.1f $cost%7.1f " +
        f"${ctx.isCovered(t.key)}%7s ${ranks.get(t.key).fold("-")(_.toString)}%6s")
    }
    // Ground truth: for status ranges [v, v + 10], how many rows of each generation hold no match (i.e. are prunable)?
    val rng = new scala.util.Random(ctx.now)
    val samples = Seq.fill(2000)(rng.nextInt(1000).toDouble)
    val byGen = ctx.table.liveFiles.toSeq.groupBy(_.generation).toSeq.sortBy(_._1).map { case (g, fs) =>
      val prunableRows = fs.map(f => samples.count(v => !f.columns(status).anyIn(v, v + 10)).toDouble / samples.size * f.rows).sum
      val minLife = fs.map(f => ctx.policy.expectedRemainingLife(f, ctx.table, ctx.now, ctx.stats)).min
      f"gen $g: ${fs.size}%4d files, ${fs.map(_.rows).sum}%8d rows, prunable rows per range query ${prunableRows}%9.1f, min remaining life $minLife%.0f"
    }
    byGen.foreach(l => println("  " + l))
    val byKindColumn = chosen.groupBy(t => (t.kind.name, cfg.columnSpecs(t.key.column).name)).view.mapValues(_.size).toSeq.sortBy(-_._2)
    println(s"  chosen: ${chosen.size} tasks; top kinds/columns: ${byKindColumn.take(6).mkString(", ")}")
  }
}
