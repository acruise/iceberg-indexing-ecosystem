package com.cloudera.acruise.progidx.sim

class CompactionSuite extends munit.FunSuite {
  private val cfg = SimConfig(filesPerTick = 4, partitionTicks = 60, fanout = 4, maxGeneration = 3, settleDelayTicks = 15)

  private def ingest(table: TableState, now: Long, n: Int): Unit =
    for (_ <- 0 until n) table.add(DataFile(table.newFileId(), now / cfg.partitionTicks, 0, 100, now))

  private def compactFully(policy: CompactionPolicy, table: TableState, now: Long): Unit = {
    var actions = policy.plan(table, now, new AccessStats(cfg))
    while (actions.nonEmpty) {
      actions.foreach {
        case Finalize(f) => table.replace(f.copy(finalized = true))
        case Merge(inputs, g, fin) =>
          inputs.foreach(f => table.remove(f.id))
          table.add(DataFile(table.newFileId(), inputs.head.partition, g, inputs.map(_.rows).sum, now, fin))
      }
      actions = policy.plan(table, now, new AccessStats(cfg))
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
    assertEqualsDouble(policy.expectedRemainingLife(young, table, 10, new AccessStats(cfg)), 0.75, 1e-9)

    val top = DataFile(table.newFileId(), 0, cfg.maxGeneration, 6400, 30)
    table.add(top)
    assertEqualsDouble(policy.expectedRemainingLife(top, table, 30, new AccessStats(cfg)), 45.0, 1e-9) // dies at settle (t = 75)

    val settled = top.copy(id = table.newFileId(), finalized = true)
    assertEquals(policy.expectedRemainingLife(settled, table, 30, new AccessStats(cfg)), Double.PositiveInfinity)
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

class CompactionPathSuite extends munit.FunSuite {
  test("ingest and compaction pay for their own indexes: eager rebuilds non-mergeable blooms at every compaction") {
    val cfg = SimConfig(ticks = 720, indexKinds = Seq(BloomKind()))
    val m = new Simulator(cfg, EagerAtWrite(), new TieredWithSettle(cfg)).run()
    val ingestRows = cfg.ticks * cfg.filesPerTick * cfg.rowsPerFile
    assert(m.writePathWork > 0)
    assert(m.writePathWork <= ingestRows * cfg.topColumns, s"write path ${m.writePathWork} vs ingest $ingestRows")
    assert(m.compactionPathWork > m.writePathWork, s"compaction ${m.compactionPathWork} vs write ${m.writePathWork}")
    assertEquals(m.wrongExclusions, 0L)
  }

  test("building in the compaction commit reaches files too short-lived for background indexes") {
    // Session lookups land on young files, which die before a background index on them becomes visible.
    val cfg = SessionScenario(SimConfig(ticks = 900))
    def sessionPruned(s: IndexScheduler): (Double, Metrics) = {
      val m = new Simulator(cfg, s, new TieredWithSettle(cfg)).run()
      val c = m.byTagHour.collect { case (("session lookups", _), cov) => cov }
      (c.map(_.pruned).sum / c.map(_.prunable).sum, m)
    }
    val (background, _) = sessionPruned(SurvivalWeighted())
    val (atCompaction, m) = sessionPruned(SurvivalWeighted(paths = Set(BuildPath.Compaction, BuildPath.Background)))
    assert(atCompaction > background + 0.5, s"at compaction $atCompaction vs background only $background")
    assertEquals(m.writePathWork, 0.0)
    assert(m.compactionPathWork > 0)
    assertEquals(m.wrongExclusions, 0L)
  }

}

class ActiveSetColumnSuite extends munit.FunSuite {
  private val sessions = ColumnSpec.defaults(ColumnSpec.indexOf("session_id")).asInstanceOf[ActiveSetColumn]
  private val tenants = ColumnSpec.defaults(ColumnSpec.indexOf("tenant_id")).asInstanceOf[ActiveSetColumn]
  private val meanSessionLife = 30 * math.exp(0.5 * 0.5 / 2)

  private def keysActiveDuring(c: ActiveSetColumn, from: Long, until: Long): Set[Double] =
    (from until until).flatMap(t => c.activeAt(t)).toSet

  test("the process is a pure function of the tick") {
    assertEquals(sessions.copy().activeAt(500).toSeq, sessions.activeAt(500).toSeq)
  }

  test("the active set settles at arrivals times mean lifetime") {
    val n = sessions.activeAt(1000).length
    assert(math.abs(n - 20 * meanSessionLife) < 0.15 * 20 * meanSessionLife, s"$n active sessions")
    assert(math.abs(tenants.activeAt(1000).length - 2000) < 50)
  }

  test("keys active over a span grow as arrivals times (span + lifetime), not with the span alone") {
    for (span <- Seq(4L, 16L, 60L, 240L)) {
      val n = keysActiveDuring(sessions, 1000, 1000 + span).size
      val expected = 20 * (span + meanSessionLife)
      assert(math.abs(n - expected) < 0.15 * expected, s"span $span: $n keys, expected about $expected")
    }
  }

  test("skewed activity: a few tenants write most rows") {
    val rng = new scala.util.Random(1)
    val counts = Seq.fill(20000)(tenants.value(rng, 1000)).groupBy(identity).view.mapValues(_.size).values.toSeq.sortBy(-_)
    assert(counts.take(20).sum > 0.3 * 20000, s"top 20 tenants wrote ${counts.take(20).sum} of 20000 rows")
    val active = tenants.activeAt(1000).length
    val bottomHalf = counts.drop(active / 2).sum // tenants that wrote nothing count as zero
    assert(bottomHalf < 0.15 * 20000, s"the smaller half of $active tenants wrote $bottomHalf of 20000 rows")
  }

  test("lookups ask for keys that were active during the queried span") {
    val rng = new scala.util.Random(2)
    for (_ <- 0 until 200) {
      val from = 600L + rng.nextInt(600)
      val Eq(v) = sessions.equality(rng, from, from + 60): @unchecked
      assert(keysActiveDuring(sessions, from, from + 60).contains(v))
    }
  }
}

class SizedBloomSuite extends munit.FunSuite {
  private val kind = SizedBloomKind()
  private def col(n: Int, offset: Int = 0) = ColumnData.of(Array.tabulate(n)(i => (offset + i * 7919L).toDouble))

  test("sized for the settled forecast, every generation's filter has the same size and merges exactly") {
    val (a, b) = (col(64), col(64, 1000000))
    val (fa, fb) = (kind.build(a, Some(1800.0)), kind.build(b, Some(1800.0)))
    assertEquals(fa.sizeBytes, fb.sizeBytes)
    val union = ColumnData.union(Seq(a, b))
    assert(kind.worthMerging(Seq(fa, fb), union))
    val merged = kind.merge(Seq(fa, fb), union).asInstanceOf[SizedBloomData]
    assertEquals(merged.distinct, 128)
    assert(merged.falsePositiveRate < 0.001, s"${merged.falsePositiveRate}")
  }

  test("a merge that would overfill the filter is refused, so compaction rebuilds it") {
    val small = Seq(col(64), col(64, 1000000))
    val filters = small.map(kind.build(_, None)) // no forecast yet: sized for 64 values each
    assert(!kind.worthMerging(filters, ColumnData.union(small)))
  }

  test("the false-positive rate formula matches its target at the design size") {
    val d = kind.build(col(1000), None).asInstanceOf[SizedBloomData]
    assert(math.abs(d.falsePositiveRate - 0.01) < 0.002, s"${d.falsePositiveRate}")
  }

  test("theta-sized filters ride compaction instead of being rebuilt at every generation") {
    val cfg = SessionScenario(SimConfig(ticks = 600))
    def run(kinds: Seq[IndexKind]) =
      new Simulator(cfg.copy(indexKinds = kinds), EagerAtWrite(), new TieredWithSettle(cfg)).run()
    val perFile = run(Seq(BloomKind()))
    val sized = run(Seq(SizedBloomKind()))
    assert(sized.kind("sbloom").rolledForward > 0)
    assert(sized.compactionPathWork < 0.5 * perFile.compactionPathWork,
      s"compaction rebuilds: sized ${sized.compactionPathWork} vs per-file ${perFile.compactionPathWork}")
    val pruned = (m: Metrics) => m.byTagHour.collect { case ((t, _), c) if t == "session lookups" => c.pruned }.sum
    assert(pruned(sized) > 0)
    assertEquals(sized.wrongExclusions, 0L)
  }
}

class KeyBatchSuite extends munit.FunSuite {
  test("IN predicates compound a bloom filter's false-positive rate with the batch size") {
    val col = ColumnData.of(Array.tabulate(64)(_.toDouble))
    val kind = BloomKind(falsePositiveRate = 0.01)
    val data = kind.build(col)
    val absent = (0 until 50).map(i => 1e9 + i)
    val passes = (0 until 4000).count(salt => kind.mightMatch(data, col, In(absent.map(_ + salt * 100.0)), salt.toLong))
    val expected = 1 - math.pow(0.99, 50)
    assert(math.abs(passes / 4000.0 - expected) < 0.03, s"${passes / 4000.0} vs $expected")
    assert(kind.mightMatch(data, col, In(Seq(1e12, 5.0)), 0L), "a present key must never be excluded")
  }

  test("key batches arrive on schedule, span every live partition, and name keys that were written") {
    val cfg = CdcScenario(SimConfig())
    val orders = cfg.columnSpecs(ColumnSpec.indexOf("order_key")).asInstanceOf[ActiveSetColumn]
    val w = new Workload(cfg)
    val batches = (0L until 200L).flatMap(t => w.queries(t).filter(_.tag.contains("cdc merges")).map(t -> _))
    assertEquals(batches.map(_._1), (0L until 200L by 5).toSeq)
    batches.foreach { case (t, q) =>
      assertEquals(q.partitions, (0L to t / cfg.partitionTicks).toSeq)
      val In(keys) = q.predicate: @unchecked
      assert(keys.size > 40 && keys.forall(k => orders.activeAt(t).contains(k) || orders.activeAt(math.max(0, t - 1)).contains(k)))
    }
  }

  test("CDC merges: a lower target rate cuts false positives that IN compounding inflates") {
    val cfg = CdcScenario(SimConfig(ticks = 900))
    def fp(kind: SizedBloomKind) = {
      val m = new Simulator(cfg.copy(indexKinds = Seq(kind)), SurvivalWeighted(paths = Set(BuildPath.Compaction, BuildPath.Background)), new TieredWithSettle(cfg)).run()
      assertEquals(m.wrongExclusions, 0L)
      val c = m.byTagHour.collect { case (("cdc merges", _), c) => c }
      c.map(_.falsePositive).sum / c.map(_.prunable).sum
    }
    val (onePercent, tenthPercent) = (fp(SizedBloomKind()), fp(SizedBloomKind(0.001, 0.002)))
    assert(onePercent > 0.05, s"1% filters let through $onePercent of prunable rows")
    assert(tenthPercent < onePercent / 3, s"0.1% filters: $tenthPercent vs 1%: $onePercent")
  }
}

class CostModelSuite extends munit.FunSuite {
  private val cfg = SimConfig(requestCost = 100, rowGroupRows = 1000, projectedColumns = 4)
  private def file(rows: Long) = DataFile(0, 0, 0, rows, 0)
  private val col = ColumnData.of(Array.tabulate(64)(_.toDouble))

  test("a small file costs mostly requests: a footer and one chunk when nothing matches") {
    assertEquals(CostModel.scan(cfg, file(100), col, Eq(1000)), 100 + (100 + 100.0))
    assertEquals(CostModel.scanRequests(cfg, file(100), col, Eq(1000)), 2.0)
  }

  test("late materialization reads the other projected columns only where something matches") {
    val big = file(8000) // eight row groups
    val miss = CostModel.scan(cfg, big, col, Eq(1000))
    val hit = CostModel.scan(cfg, big, col, Eq(5))
    assertEquals(miss, 100 + 8 * 1100.0)
    assertEquals(hit, miss + 3 * 1100.0, "one matching row group, three more columns")
    assertEquals(CostModel.scan(cfg.copy(lateMaterialization = false), big, col, Eq(1000)), 100 + 32 * 1100.0)
  }

  test("matches spread over row groups as values do") {
    val big = file(8000)
    assertEquals(CostModel.matchingRowGroups(cfg, big, col, In(Seq(1000.0, 2000.0))), 0.0)
    val many = CostModel.matchingRowGroups(cfg, big, col, Between(0, 63))
    assert(many > 7.9 && many <= 8.0, s"$many")
    assertEquals(col.countIn(10, 19.5), 10)
  }

  test("demand is tracked per column and partition age, so rare whole-table batches register on old partitions") {
    val c = ErasureScenario(SimConfig(ticks = 1500))
    val m = new Simulator(c, SurvivalWeighted(paths = Set(BuildPath.Compaction, BuildPath.Background)), new TieredWithSettle(c)).run()
    val batches = m.byTagHour.collect { case (("erasure batches", _), cov) => cov }
    assert(batches.map(_.pruned).sum / batches.map(_.prunable).sum > 0.5, "erasure batches should mostly be pruned")
    assertEquals(m.wrongExclusions, 0L)
  }
}

class StreamingSuite extends munit.FunSuite {
  private def run(cfg: SimConfig, policy: CompactionPolicy): Metrics = new Simulator(cfg, NoIndexing, policy).run()
  private def wamp(m: Metrics) = m.compactionRows.toDouble / m.ingestRows

  test("micro-batches multiply commits, and tiered compaction rewrites each row once per tier") {
    val cfg = StreamingScenario(SimConfig(ticks = 600))
    val m = run(cfg, new TieredWithSettle(cfg))
    assertEquals(m.ingestRows, cfg.ticks * cfg.filesPerTick * cfg.rowsPerFile)
    assert(m.dataCommits.toDouble / cfg.ticks > cfg.filesPerTick, s"${m.dataCommits} commits")
    assert(wamp(m) > cfg.maxGeneration - 0.5, s"write amplification ${wamp(m)}")
  }

  test("read-aware compaction merges small files only when enough queries will reach them before they settle") {
    val cfg = StreamingScenario(SimConfig())
    val policy = new ReadAwareTiered(cfg, maxOpenFiles = Int.MaxValue)
    val table = new TableState
    (0 until 8).foreach(_ => table.add(DataFile(table.newFileId(), 0, 0, cfg.rowsPerFile, 0)))
    val quiet = new AccessStats(cfg)
    assertEquals(policy.plan(table, 1, quiet), Nil)

    val busy = new AccessStats(cfg)
    (0L until 30L).foreach(t => busy.observe(t, Seq.fill(20)(Query(Seq(0L), 0, Eq(1)))))
    val merges = policy.plan(table, 30, busy)
    assertEquals(merges.size, 2)

    val tiered = new TieredWithSettle(cfg).plan(table, 1, quiet)
    assertEquals(tiered.size, 2, "plain tiering merges regardless")
  }

  test("read-aware compaction keeps the tiers where queries land") {
    val read = StreamingScenario(SimConfig(ticks = 600))
    val kept = run(read, new ReadAwareTiered(read))
    assert(wamp(kept) > 0.5 * wamp(run(read, new TieredWithSettle(read))), s"read-aware ${wamp(kept)} on hot partitions")
  }


  test("a cap on open files bounds a partition even when nobody reads it") {
    val cfg = StreamingScenario(SimConfig(ticks = 120, recentQueryFraction = 0.0))
    val sim = new Simulator(cfg, NoIndexing, new ReadAwareTiered(cfg, maxOpenFiles = 100))
    (0L until 50L).foreach(sim.step)
    assert(sim.table.filesIn(0).size <= 100 + cfg.filesPerTick, s"${sim.table.filesIn(0).size} files")
  }

  test("reseeding changes active-set keys as well as data and workload") {
    val cfg = SimConfig()
    val sessions = (c: SimConfig) => c.columnSpecs(ColumnSpec.indexOf("session_id")).asInstanceOf[ActiveSetColumn]
    assertNotEquals(sessions(cfg.withSeed(1)).activeAt(100).toSeq, sessions(cfg.withSeed(2)).activeAt(100).toSeq)
    assertEquals(cfg.withSeed(3).seed, 3L)
  }
}

class CompactionBudgetSuite extends munit.FunSuite {
  test("budgeted compaction-time building stays within the background budget") {
    val cfg = SessionScenario(SimConfig(ticks = 900, indexBudgetPerTick = 2000))
    val m = new Simulator(cfg, SurvivalWeighted(paths = Set(BuildPath.Compaction, BuildPath.Background), budget = SyncBudget.Greedy), new TieredWithSettle(cfg)).run()
    val spent = m.indexBuildWork + m.compactionPathWork
    val allowance = cfg.indexBudgetPerTick * cfg.ticks
    assert(spent <= allowance + 1e-6, s"spent $spent of $allowance")
    assert(m.compactionPathWork > 0 && m.indexBuildWork >= 0)
    assertEquals(m.wrongExclusions, 0L)
  }

  test("an unbudgeted run spends more than the budget would allow; a budgeted one skips what it can't afford") {
    val cfg = SessionScenario(SimConfig(ticks = 900, indexBudgetPerTick = 2000))
    val free = new Simulator(cfg, SurvivalWeighted(paths = Set(BuildPath.Compaction, BuildPath.Background)), new TieredWithSettle(cfg)).run()
    val budgeted = new Simulator(cfg, SurvivalWeighted(paths = Set(BuildPath.Compaction, BuildPath.Background), budget = SyncBudget.Greedy), new TieredWithSettle(cfg)).run()
    assert(free.compactionPathWork + free.indexBuildWork > cfg.indexBudgetPerTick * cfg.ticks)
    assert(budgeted.syncBuildsSkipped > 0)
  }
}

class ShadowPriceSuite extends munit.FunSuite {
  private val both = Set[BuildPath](BuildPath.Compaction, BuildPath.Background)

  test("priced builds with a commit stay within the shared budget and leave the background its share") {
    val cfg = ErasureScenario(SimConfig(ticks = 1500, indexBudgetPerTick = 4000, indexKinds = Seq(SizedBloomKind(), F16Kind())))
    def run(s: IndexScheduler) = new Simulator(cfg, s, new TieredWithSettle(cfg)).run()
    val greedy = run(SurvivalWeighted(paths = both, budget = SyncBudget.Greedy))
    val priced = run(SurvivalWeighted(paths = both, budget = SyncBudget.Priced))
    assert(priced.indexBuildWork + priced.compactionPathWork <= cfg.indexBudgetPerTick * cfg.ticks + 1e-6)
    assert(priced.indexBuildWork > greedy.indexBuildWork, s"background ${priced.indexBuildWork} vs greedy ${greedy.indexBuildWork}")
    val erasure = (m: Metrics) => {
      val c = m.byTagHour.collect { case (("erasure batches", _), cov) => cov }
      c.map(_.pruned).sum / c.map(_.prunable).sum
    }
    assert(erasure(priced) > erasure(greedy) + 0.2, s"priced ${erasure(priced)} vs greedy ${erasure(greedy)}")
    assertEquals(priced.wrongExclusions, 0L)
  }

  test("timing is a separate dimension: the same valuation can build at ingest, at compaction or in the background") {
    val s = SurvivalWeighted(paths = Set(BuildPath.Ingest), budget = SyncBudget.Priced)
    assertEquals(s.name, "valued@write/priced")
    val cfg = SessionScenario(SimConfig(ticks = 300))
    val m = new Simulator(cfg, s, new TieredWithSettle(cfg)).run()
    assert(m.writePathWork > 0)
    assertEquals(m.indexBuildWork, 0.0)
    assert(m.writePathWork <= cfg.indexBudgetPerTick * cfg.ticks)
  }
}
