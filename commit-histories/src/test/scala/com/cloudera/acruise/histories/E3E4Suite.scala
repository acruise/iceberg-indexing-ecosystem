package com.cloudera.acruise.histories

import Event.{Commit, Read}
import Auditor.Mode

class E3E4Suite extends munit.FunSuite {
  private val deleterFirst = Vector(Read(0), Read(1), Commit(1), Commit(0))

  private def outcome(ops: Vector[Op], order: Vector[Event], rs: RuleSet, initial: Snapshot = E1Slice.initial): Outcome =
    Histories.run(Scenario("t", initial, ops), order, rs)

  private def flagged(o: Outcome, mode: Mode): Boolean = Auditor.audit(o, mode).flagged

  // ---- E3: what an auditor can see afterward

  test("E3: a compaction that dropped a deletion vector shows in the row count, even with parent pointers only") {
    val o = outcome(Vector(Compaction(OutputSeq.New), DeleteByVector(7)), deleterFirst, RuleSets.specCurrent)
    assert(!o.isCorrect)
    Mode.all.foreach(m => assert(flagged(o, m), m.name))
  }

  test("E3: an equality delete hides the row count, so only the recorded read base reveals the compaction") {
    val o = outcome(Vector(Compaction(OutputSeq.New), DeleteByEquality(7)), deleterFirst, RuleSets.specCurrent)
    assert(!o.isCorrect)
    assert(!flagged(o, Mode.ParentsOnly))
    assert(flagged(o, Mode.WithReadBase))
  }

  test("E3: a copy-on-write delete that dropped a deletion vector is invisible without the read base") {
    val o = outcome(Vector(CopyOnWriteDelete(5), DeleteByVector(7)), deleterFirst, RuleSets.specCurrent)
    assert(!o.isCorrect)
    assert(!flagged(o, Mode.ParentsOnly))
    assert(flagged(o, Mode.WithReadBase))
  }

  test("E3: once the window has expired, the read base no longer helps") {
    // With a deletion vector the parent's state still shows it, and the row count catches the compaction; an equality
    // delete leaves the count unknowable, so only the expired window could have shown the conflict.
    val ops = Vector(Compaction(OutputSeq.New), DeleteByEquality(7), AppendRow(9), ExpireSnapshots(1))
    val order = Vector(Read(0), Read(1), Commit(1), Read(2), Commit(2), Commit(3), Commit(0))
    val o = outcome(ops, order, RuleSets.specWithFix1, E2Slice.initial)
    assert(!o.isCorrect)
    Mode.all.foreach(m => assert(!flagged(o, m), m.name))
    assert(Auditor.audit(o, Mode.WithReadBase).unauditable > 0)
  }

  test("E3: a removal that found its file already gone leaves nothing for any auditor") {
    val ops = Vector(Ambiguous(CopyOnWriteDelete(5), lands = false), Compaction(OutputSeq.New))
    val order = Vector(Read(0), Commit(0), Read(1), Commit(1), Commit(0, 1))
    val o = outcome(ops, order, RuleSets.specCurrent)
    assert(!o.isCorrect)
    Mode.all.foreach(m => assert(!flagged(o, m), m.name))
  }

  // ---- E4: commits of unknown outcome

  private val alone = Vector(Read(0), Commit(0), Commit(0, 1))

  test("E4: a landed append retried blindly is duplicated under the current text; recognition prevents it") {
    val dup = outcome(Vector(Ambiguous(AppendRow(9), lands = true)), alone, RuleSets.specCurrent)
    assertEquals(dup.retries.map(_.finding), Vector(Finding.SpecHole))
    assertEquals(dup.finalRows, Vector(5, 7, 9, 9))
    assert(!flagged(dup, Mode.WithReadBase), "a duplicate append satisfies every retry condition")
    assert(flagged(dup, Mode.WithIdentity), "but one identity committed twice is visible")
    Vector(RuleSets.withReadBaseOnly, RuleSets.withIdentity).foreach { rs =>
      val o = outcome(Vector(Ambiguous(AppendRow(9), lands = true)), alone, rs)
      assertEquals(o.retries.map(_.finding), Vector(Finding.Recognized), rs.name)
      assert(o.isCorrect && o.misreports.isEmpty, rs.name)
    }
  }

  test("E4: fix 1 refuses a landed deletion vector's retry, and the writer reports failure for a commit that landed") {
    val o = outcome(Vector(Ambiguous(DeleteByVector(7), lands = true)), alone, RuleSets.specWithFix1And2)
    assert(o.isCorrect)
    assertEquals(o.misreports, Vector(Misreport(0, applied = true)))
    assert(outcome(Vector(Ambiguous(DeleteByVector(7), lands = true)), alone, RuleSets.withIdentity).misreports.isEmpty)
  }

  test("E4: recognition by read base and content mistakes an identical concurrent commit for the writer's own") {
    val ops = Vector(Ambiguous(AppendRow(9), lands = false), AppendRow(9))
    val order = Vector(Read(0), Read(1), Commit(0), Commit(1), Commit(0, 1))
    val deceived = outcome(ops, order, RuleSets.withReadBaseOnly)
    assert(deceived.isCorrect, "the table is consistent with what committed")
    assertEquals(deceived.misreports, Vector(Misreport(0, applied = false)))
    val honest = outcome(ops, order, RuleSets.withIdentity)
    assert(honest.isCorrect && honest.misreports.isEmpty)
    assertEquals(honest.finalRows, Vector(5, 7, 9, 9))
  }
}
