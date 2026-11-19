package com.cloudera.acruise.histories

import Event.{Commit, Read}

class E2SliceSuite extends munit.FunSuite {

  /** Operations: 0 rewriter, 1 deleter, 2 append, 3 expiration. The deleter and the append commit inside the
    * rewriter's window, expiration then keeps only the append's snapshot, and the rewriter retries.
    */
  private val expiredWindow =
    Vector(Read(0), Read(1), Commit(1), Read(2), Commit(2), Commit(3), Commit(0))

  private def retryOf(rewriter: Op, deleter: Op, rs: RuleSet): Retry = {
    val s = Scenario("t", E2Slice.initial, Vector(rewriter, deleter, AppendRow(9), ExpireSnapshots(1)))
    val o = Histories.run(s, expiredWindow, rs)
    assertEquals(o.retries.size, 1, o.notation)
    o.retries.head
  }

  test("the current text needs no history, and permits the E1 compaction regardless") {
    assertEquals(retryOf(Compaction(OutputSeq.New), DeleteByVector(7), RuleSets.specCurrent).finding, Finding.SpecHole)
  }

  test("prose fix 1 alone cannot be verified once the delete's snapshot has expired, and says nothing about that") {
    val r = retryOf(Compaction(OutputSeq.New), DeleteByVector(7), RuleSets.specWithFix1)
    assert(r.verdict.isInstanceOf[Verdict.Unverifiable], r.verdict)
    assertEquals(r.finding, Finding.UnverifiableHarmful)
    assertEquals(r.restored, Set(7))
  }

  test("prose fix 2 makes the unverifiable retry fail") {
    assertEquals(retryOf(Compaction(OutputSeq.New), DeleteByVector(7), RuleSets.specWithFix1And2).finding, Finding.Protected)
    assertEquals(retryOf(CopyOnWriteDelete(5), DeleteByEquality(7), RuleSets.specWithFix1And2).finding, Finding.Protected)
  }

  test("with the window intact, the same operations are judged on what was added") {
    val intact = Vector(Read(0), Read(1), Commit(1), Read(2), Commit(2), Commit(0), Commit(3))
    val s = Scenario("t", E2Slice.initial, Vector(Compaction(OutputSeq.New), DeleteByVector(7), AppendRow(9), ExpireSnapshots(1)))
    assertEquals(Histories.run(s, intact, RuleSets.specWithFix1).retries.map(_.finding), Vector(Finding.Protected))
  }

  test("an expired window over a delete in another partition: fix 1 cannot decide, fix 2 refuses a safe commit") {
    Vector(DeleteByVector(13), DeleteByEquality(13, partition = Some(1))).foreach { d =>
      val undecided = retryOf(Compaction(OutputSeq.New), d, RuleSets.specWithFix1)
      assertEquals(undecided.finding, Finding.UnverifiableBenign, d.label)
      assertEquals(retryOf(Compaction(OutputSeq.New), d, RuleSets.specWithFix1And2).finding, Finding.NeedlessRefusal, d.label)
      assertEquals(retryOf(Compaction(OutputSeq.New), d, RuleSets.specCurrent).finding, Finding.Fine, d.label)
    }
  }
}
