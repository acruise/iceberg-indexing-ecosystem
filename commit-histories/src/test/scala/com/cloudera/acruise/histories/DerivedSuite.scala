package com.cloudera.acruise.histories

import Event.{Commit, Read}
import Derived._

class DerivedSuite extends munit.FunSuite {
  private val rules = RuleSets.specWithFix1And2

  // ---- V14: the snapshot-pinned index

  private def indexAt(ops: Vector[Op], order: Vector[Event]): (Vector[Snapshot], Set[Int]) = {
    val o = Histories.run(Scenario("t", IndexSlice.initial, ops), order, rules)
    (o.logs("t"), o.expiredBy("t"))
  }

  test("V14: after a compaction, scanning uncovered files beside the index double-counts the compacted rows") {
    val (log, exp) = indexAt(Vector(Compaction(OutputSeq.New)), Vector(Read(0), Commit(0)))
    val idx = IndexSlice.index
    assertEquals(IndexPolicy.AcrossBoth.answer(idx, log, exp), Some(Vector(5, 5, 7, 7)))
    assertEquals(IndexPolicy.AcrossReplaces.answer(idx, log, exp), Some(Vector(5, 7)))
    assertEquals(IndexPolicy.AcrossAppends.answer(idx, log, exp), None)
    assertEquals(IndexPolicy.FileScoped.answer(idx, log, exp), Some(log.last.visibleRows))
    assertEquals(fileScopedCoverage(idx, log.last), 0.0)
  }

  test("V14: every policy but the naive combination is safe over the whole slice") {
    val outcomes = Histories.runAll(IndexSlice.scenarios, Vector(rules))
    IndexPolicy.all.foreach { p =>
      val t = outcomes.map(probeIndex(_, IndexSlice.index, p)).foldLeft(Tally.zero)(_ + _)
      if (p == IndexPolicy.AcrossBoth) assert(t.unsafe > 0, p.name) else assertEquals(t.unsafe, 0, p.name)
    }
  }

  // ---- Materialized views

  private def viewAt(ops: Vector[Op], order: Vector[Event]): Outcome =
    Histories.run(Scenario("v", Map("src" -> ViewSlice.source, "mv" -> ViewSlice.storage), ops), order, rules)

  private def usable(o: Outcome, rule: StateRule, fresh: Freshness): Boolean =
    rule.resolve(o.logs("mv"), o.expiredBy("mv")).exists(rs => fresh.fresh(rs, o.logs("src"), o.expiredBy("src")))

  private val once = Vector(Read(0), Commit(0))

  test("MV: a write to the storage table by an unaware engine fools the unconditional walk, not the ancestry rule") {
    val o = viewAt(Vector(DeleteByVector(5, table = "mv")), once)
    assert(o.logs("mv").last.visibleRows != o.logs("src").last.visibleRows)
    assert(usable(o, StateRule.NearestAny, Freshness.SnapshotEqual), "the walk trusts a stale storage table")
    assert(!usable(o, StateRule.AncestryReplace, Freshness.SnapshotEqual))
    assert(!usable(o, StateRule.CurrentOnly, Freshness.SnapshotEqual))
  }

  test("MV: a storage compaction without carry-forward loses the state for the current-only reader, not for the ancestry rule") {
    val o = viewAt(Vector(Compaction(OutputSeq.New, table = "mv")), once)
    assert(!usable(o, StateRule.CurrentOnly, Freshness.SnapshotEqual))
    assert(usable(o, StateRule.AncestryReplace, Freshness.SnapshotEqual))
    val carried = viewAt(Vector(Compaction(OutputSeq.New, table = "mv", carryForward = true)), once)
    assert(usable(carried, StateRule.CurrentOnly, Freshness.SnapshotEqual))
  }

  test("MV: a source compaction makes the view stale under snapshot equality, not under the up-to-replace criterion") {
    val o = viewAt(Vector(Compaction(OutputSeq.New, table = "src")), once)
    assert(!usable(o, StateRule.AncestryReplace, Freshness.SnapshotEqual))
    assert(usable(o, StateRule.AncestryReplace, Freshness.UpToReplace))
  }

  test("MV: once the storage table's history has expired, the ancestry rule finds nothing") {
    val ops = Vector(Compaction(OutputSeq.New, table = "mv"), AppendRow(9, table = "src"), ExpireSnapshots(1, table = "mv"))
    val o = viewAt(ops, Vector(Read(0), Commit(0), Commit(2)))
    assert(!usable(o, StateRule.AncestryReplace, Freshness.SnapshotEqual))
  }

  test("MV: only the unconditional walk is ever unsafe over the whole slice") {
    val outcomes = Histories.runAll(ViewSlice.scenarios, Vector(rules))
    for {
      rule <- StateRule.all
      fresh <- Freshness.all
    } {
      val t = outcomes.map(probeView(_, rule, fresh)).foldLeft(Tally.zero)(_ + _)
      if (rule == StateRule.NearestAny) assert(t.unsafe > 0, rule.name) else assertEquals(t.unsafe, 0, s"${rule.name}, ${fresh.name}")
    }
  }
}
