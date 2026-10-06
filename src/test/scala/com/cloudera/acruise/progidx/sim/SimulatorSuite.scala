package com.cloudera.acruise.progidx.sim

class CompactionSuite extends munit.FunSuite {
  private val cfg = SimConfig(filesPerTick = 4, partitionTicks = 60, fanout = 4, maxGeneration = 3, settleDelayTicks = 15)

  private def ingest(table: TableState, now: Long, n: Int): Unit =
    for (_ <- 0 until n) table.add(DataFile(table.newFileId(), now / cfg.partitionTicks, 0, 100, now))

  private def compactFully(policy: CompactionPolicy, table: TableState, now: Long): Unit = {
    var actions = policy.plan(table, now)
    while (actions.nonEmpty) {
      actions.foreach {
        case Finalize(f) => table.replace(f.copy(finalized = true))
        case Merge(inputs, g, fin) =>
          inputs.foreach(f => table.remove(f.id))
          table.add(DataFile(table.newFileId(), inputs.head.partition, g, inputs.map(_.rows).sum, now, fin))
      }
      actions = policy.plan(table, now)
    }
  }

  test("tiered merging cascades through generations within an open partition") {
    val policy = new TieredWithSettle(cfg)
    val table = new TableState
    for (now <- 0L until 16) { ingest(table, now, 4); compactFully(policy, table, now) }
    // 64 gen-0 files at fanout 4 collapse to exactly one gen-3 file.
    assertEquals(table.liveFiles.map(_.generation).toSeq, Seq(3))
  }

  test("a closed partition settles into one finalized file") {
    val policy = new TieredWithSettle(cfg)
    val table = new TableState
    for (now <- 0L until 60) { ingest(table, now, 4); compactFully(policy, table, now) }
    assert(table.fileCount > 1)
    compactFully(policy, table, 75)
    val files = table.liveFiles.toSeq
    assertEquals(files.size, 1)
    assert(files.head.finalized)
    assertEquals(files.head.rows, 60L * 4 * 100)
  }

  test("expected remaining life reflects the policy's plans") {
    val policy = new TieredWithSettle(cfg)
    val table = new TableState
    ingest(table, 10, 1)
    val young = table.liveFiles.head
    // 3 more gen-0 files are needed and they arrive at 4 per tick.
    assertEqualsDouble(policy.expectedRemainingLife(young, table, 10), 0.75, 1e-9)

    val top = DataFile(table.newFileId(), 0, cfg.maxGeneration, 6400, 30)
    table.add(top)
    assertEqualsDouble(policy.expectedRemainingLife(top, table, 30), 45.0, 1e-9) // dies at settle (t = 75)

    val settled = top.copy(id = table.newFileId(), finalized = true)
    assertEquals(policy.expectedRemainingLife(settled, table, 30), Double.PositiveInfinity)
  }
}

class IndexCatalogSuite extends munit.FunSuite {
  private val cfg = SimConfig(puffinBatchBlobs = 3, puffinMaxDelayTicks = 5, puffinGcLiveFraction = 0.5)

  test("staged blobs become visible on a full batch or after the max delay, one commit each") {
    val m = new Metrics
    val c = new IndexCatalog(cfg, m)
    c.stage(IndexKey(1, "bloom", 0), 1.0, now = 0)
    c.tick(1)
    assert(!c.isVisible(IndexKey(1, "bloom", 0)))
    c.tick(5)
    assert(c.isVisible(IndexKey(1, "bloom", 0)))
    assertEquals(m.indexCommits, 1L)

    (2 to 4).foreach(i => c.stage(IndexKey(i.toLong, "bloom", 0), 1.0, now = 6))
    c.tick(6)
    assertEquals(m.indexCommits, 2L)
    assertEquals(c.pendingCount, 0)
  }

  test("sparse containers are rewritten and empty ones dropped") {
    val m = new Metrics
    val c = new IndexCatalog(cfg, m)
    val keys = (1 to 3).map(i => IndexKey(i.toLong, "bloom", 0))
    keys.foreach(c.stage(_, 1.0, now = 0))
    c.tick(0)
    c.drop(keys(0))
    c.tick(1) // 2/3 live: kept
    assertEquals(m.puffinRewriteWork, 0.0)
    c.drop(keys(1))
    c.tick(2) // 1/3 live: rewritten
    assertEquals(m.puffinRewriteWork, 1.0)
    assert(c.isVisible(keys(2)))
    c.drop(keys(2))
    c.tick(3)
    assertEquals(c.containerCount, 0)
  }
}

class ModelSuite extends munit.FunSuite {
  test("column data is sorted, distinct, and answers predicates exactly") {
    val col = ColumnData.of(Array(5.0, 1.0, 3.0, 3.0, -2.0))
    assertEquals(col.values.toSeq, Seq(-2.0, 1.0, 3.0, 5.0))
    assert(col.matches(Eq(3.0)))
    assert(!col.matches(Eq(2.0)))
    assert(col.matches(Between(1.5, 3.0)))
    assert(!col.matches(Between(1.5, 2.5)))
    assert(col.overlapsMinMax(Between(1.5, 2.5)))
    assert(!col.overlapsMinMax(Eq(6.0)))
    assertEquals(ColumnData.union(Seq(col, ColumnData.of(Array(2.0, 9.0)))).values.toSeq, Seq(-2.0, 1.0, 2.0, 3.0, 5.0, 9.0))
  }

  test("no index kind ever rules out a file that matches") {
    val rng = new scala.util.Random(3)
    val kinds = Seq(BloomKind(), F16Kind())
    for (spec <- ColumnSpec.defaults; trial <- 0 until 20) {
      val col = ColumnData.of(Array.fill(64)(spec.value(rng, trial * 10L)))
      kinds.foreach { kind =>
        val data = kind.build(col)
        for (_ <- 0 until 200; shape <- Seq(Equality, RangeShape)) {
          val p = spec.predicate(rng, trial * 10L, trial * 10L + 10, shape)
          if (kind.supports(p) && col.matches(p)) assert(kind.mightMatch(data, col, p, salt = trial.toLong), s"${kind.name} ${spec.name} $p")
        }
        // Values present in the column must always pass equality probes.
        col.values.foreach(v => assert(kind.mightMatch(data, col, Eq(v), salt = trial.toLong)))
      }
    }
  }

  test("bloom false-positive rate is close to its target") {
    val col = ColumnData.of(Array.tabulate(1000)(_.toDouble))
    val kind = BloomKind(falsePositiveRate = 0.01)
    val data = kind.build(col)
    val fp = (0 until 100000).count(i => kind.mightMatch(data, col, Eq(1e6 + i), salt = 1))
    assert(fp > 500 && fp < 1500, s"false positives: $fp")
  }
}

class SimulatorSuite extends munit.FunSuite {
  private val cfg = SimConfig(ticks = 720)

  private def run(c: SimConfig, s: IndexScheduler): (Simulator, Metrics) = {
    val sim = new Simulator(c, s, new TieredWithSettle(c))
    (sim, sim.run())
  }

  test("runs are deterministic") {
    val a = run(cfg, SurvivalWeighted())._2
    val b = run(cfg, SurvivalWeighted())._2
    assertEquals(a.queryCost, b.queryCost)
    assertEquals(a.indexBuildWork, b.indexBuildWork)
  }

  test("every scheduler sees the same query stream") {
    val baselines = Main.schedulers.map(s => run(cfg, s)._2.baselineQueryCost).distinct
    assertEquals(baselines.size, 1)
  }

  test("no indexing means query cost equals baseline") {
    val m = run(cfg, NoIndexing)._2
    assertEquals(m.queryCost, m.baselineQueryCost)
    assertEquals(m.indexBuildWork, 0.0)
  }

  test("f16 indexes roll forward through compaction, identical to building from the merged data") {
    val c = cfg.copy(indexKinds = Seq(F16Kind()))
    val (sim, m) = run(c, EagerAtWrite())
    assert(m.indexesRolledForward > 0)
    val rolled = for {
      f <- sim.table.liveFiles.filter(_.finalized).toSeq
      col <- 0 until c.columns
      data <- sim.indexData(IndexKey(f.id, "f16", col))
    } yield (f, col, data)
    assert(rolled.nonEmpty)
    rolled.foreach { case (f, col, data) => assertEquals(data, F16Kind().build(f.columns(col))) }
  }

  test("indexes never cause a matching file to be skipped") {
    val c = cfg.copy(ticks = 300)
    val sim = new Simulator(c, EagerAtWrite(), new TieredWithSettle(c))
    val m = sim.run()
    assert(m.filesPrunedByIndex > 0)
    assert(m.byKind("bloom").exclusions > 0 && m.byKind("f16").exclusions > 0)
    assertEquals(m.wrongExclusions, 0L)
  }

  test("survival-weighted wastes far less build work than eager indexing") {
    val eager = run(cfg, EagerAtWrite())._2
    val survival = run(cfg, SurvivalWeighted())._2
    assert(survival.unpaidWork < eager.unpaidWork / 4, s"survival=${survival.unpaidWork} eager=${eager.unpaidWork}")
    assert(survival.queryCost < run(cfg, NoIndexing)._2.queryCost)
  }
}

class WorkloadSuite extends munit.FunSuite {
  private val base = SimConfig(driftEveryTicks = Long.MaxValue)
  private val status = ColumnSpec.indexOf("status")
  private val orderId = ColumnSpec.indexOf("order_id")
  private val traceId = ColumnSpec.indexOf("trace_id")

  private def stream(cfg: SimConfig, ticks: Range): Seq[(Long, Query)] = {
    val w = new Workload(cfg)
    (0L until ticks.end.toLong).flatMap(t => w.queries(t).map(t -> _)).filter { case (t, _) => t >= ticks.start }
  }

  test("a hot-column shift sends its share of queries to the column, tagged, only while active") {
    val cfg = base.copy(shifts = Seq(HotColumn("hot", orderId, share = 0.3, from = 100, until = 200)))
    val qs = stream(cfg, 0 until 300)
    val during = qs.filter { case (t, _) => t >= 100 && t < 200 }.map(_._2)
    val tagged = during.count(_.tag.contains("hot"))
    assert(math.abs(tagged.toDouble / during.size - 0.3) < 0.03, s"$tagged of ${during.size}")
    assert(during.filter(_.tag.contains("hot")).forall(_.column == orderId))
    assert(!qs.exists { case (t, q) => (t < 100 || t >= 200) && q.tag.isDefined })
  }

  test("a shape change switches predicates on the column and tags them") {
    val cfg = base.copy(shifts = Seq(ShapeChange("shape", status, RangeShape, from = 100)))
    val onStatus = stream(cfg, 0 until 200).filter(_._2.column == status)
    assert(onStatus.filter(_._1 < 100).forall(_._2.predicate.isInstanceOf[Eq]))
    val after = onStatus.filter(_._1 >= 100).map(_._2)
    assert(after.nonEmpty && after.forall(q => q.predicate.isInstanceOf[Between] && q.tag.contains("shape")))
  }

  test("forensic queries target the requested ages") {
    val cfg = base.copy(shifts = Seq(Forensic("f", traceId, share = 0.5, minAge = 24, maxAge = 47, from = 2880, until = 2940)))
    val forensic = stream(cfg, 2880 until 2940).collect { case (t, q) if q.tag.contains("f") => (t, q) }
    assert(forensic.nonEmpty)
    forensic.foreach { case (t, q) =>
      val age = t / cfg.partitionTicks - q.partitions.head
      assert(q.partitions.size == 1 && age >= 24 && age <= 47 && q.column == traceId, s"age $age")
    }
  }

  test("dormant columns are never queried without a shift") {
    assert(!stream(base, 0 until 500).exists(_._2.column == traceId))
  }

  test("without shifts the query stream is unchanged by the shift machinery") {
    assertEquals(stream(base, 0 until 50), stream(base.copy(shifts = Nil), 0 until 50))
  }
}

class CoverageSuite extends munit.FunSuite {
  private val cfg = Scenarios.drift(SimConfig(ticks = 1800))

  private def run(s: IndexScheduler, c: SimConfig = cfg): Metrics = new Simulator(c, s, new TieredWithSettle(c)).run()

  test("with no indexes, every prunable row is missing an index") {
    val m = run(NoIndexing)
    assert(m.coverage.prunable > 0)
    assertEquals(m.coverage.missing, m.coverage.prunable)
  }

  test("the timeline and the totals agree") {
    val m = run(SurvivalWeighted())
    assertEqualsDouble(m.timeline.values.map(_.prunable).sum, m.coverage.prunable, 1e-6)
    assertEqualsDouble(m.timeline.values.map(_.pruned).sum, m.coverage.pruned, 1e-6)
  }

  test("bloom filters built for equality show up as mismatches once queries switch to ranges") {
    val c = cfg.copy(ticks = 2600, indexKinds = Seq(BloomKind()))
    val m = run(EagerAtWrite(), c)
    val shape = m.byTagHour.collect { case ((tag, _), cov) if tag.startsWith("status") => cov }
    assert(shape.map(_.mismatch).sum > 0)
    assertEquals(shape.map(_.pruned).sum, 0.0)
  }
}

class PriorSuite extends munit.FunSuite {
  private val rng = new scala.util.Random(11)
  private def col(name: String, n: Int = 64): ColumnData = {
    val spec = ColumnSpec.defaults(ColumnSpec.indexOf(name))
    ColumnData.of(Array.fill(n)(spec.value(rng, 100)))
  }

  test("f16 is useless on values beyond its range") {
    Seq("user_id", "order_id", "trace_id").foreach(n => assertEquals(F16Kind().staticUsefulness(col(n)), 0.0, n))
  }

  test("both kinds see nothing to gain when every integer in [min, max] is present") {
    val dense = ColumnData.of(Array.tabulate(64)(_.toDouble))
    assertEquals(BloomKind().staticUsefulness(dense), 0.0)
    assertEquals(F16Kind().staticUsefulness(dense), 0.0)
  }

  test("sparse and continuous columns look useful to the kinds that suit them") {
    assert(BloomKind().staticUsefulness(col("status")) > 0.9)
    assert(F16Kind().staticUsefulness(col("status")) > 0.9)
    assert(F16Kind().staticUsefulness(col("temperature")) > 0.9)
    assert(BloomKind().staticUsefulness(col("trace_id")) > 0.99)
  }

  test("learned exclusion rates start at the prior and move toward observations") {
    val stats = new AccessStats(SimConfig())
    assertEqualsDouble(stats.exclusionRate("f16", 0, 100, prior = 0.4), 0.4, 1e-12)
    (0 until 200).foreach(_ => stats.observeProbe("f16", 0, 100, excluded = false))
    assert(stats.exclusionRate("f16", 0, 100, prior = 0.4) < 0.05)
    assertEqualsDouble(stats.exclusionRate("f16", 0, 10000, prior = 0.4), 0.4, 1e-12) // other size class untouched
  }
}

class HybridSuite extends munit.FunSuite {
  test("write-time f16 rides compaction: the write path pays for ingest files, not rewrites") {
    val cfg = SimConfig(ticks = 720, indexKinds = Seq(F16Kind()))
    val m = new Simulator(cfg, Hybrid(), new TieredWithSettle(cfg)).run()
    val ingestRows = cfg.ticks * cfg.filesPerTick * cfg.rowsPerFile
    assert(m.kind("f16").rolledForward > 0)
    assert(m.writePathWork > 0)
    // Rebuilding every compaction output would cost several times ingestRows per column.
    assert(m.writePathWork <= 1.05 * ingestRows * cfg.columns, s"write path ${m.writePathWork} vs ingest $ingestRows")
    assert(m.unpaidWork < 0.5 * m.writePathWork, s"unpaid ${m.unpaidWork}: merged indexes should carry their inputs' cost")
    assertEquals(m.wrongExclusions, 0L)
  }

  test("the write-time hedge covers a column whose predicates switch from equality to range") {
    val cfg = Scenarios.drift(SimConfig(ticks = 2600))
    val m = new Simulator(cfg, Hybrid(), new TieredWithSettle(cfg)).run()
    val shape = m.byTagHour.collect { case ((tag, _), c) if tag.startsWith("status") => c }
    assert(shape.map(_.pruned).sum > 0)
  }
}
