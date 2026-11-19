package com.cloudera.acruise.histories

import Event.{Commit, Read}

class E1SliceSuite extends munit.FunSuite {

  /** Operation 0 is the rewriter, operation 1 the deleter. */
  private def retryOf(rewriter: Op, deleter: Op, order: Vector[Event], rs: RuleSet): Retry = {
    val o = Histories.run(Scenario("t", E1Slice.initial, Vector(rewriter, deleter)), order, rs)
    assertEquals(o.retries.size, 1, o.notation)
    o.retries.head
  }

  private val deleterFirst = Vector(Read(0), Read(1), Commit(1), Commit(0)) // the rewriter retries
  private val rewriterFirst = Vector(Read(1), Read(0), Commit(0), Commit(1)) // the deleter retries

  test("two operations have six interleavings") {
    assertEquals(Histories.interleavings(2).size, 6)
    assertEquals(Histories.interleavings(3).size, 90)
    assertEquals(Histories.interleavings(Vector(true, true, true, false)).size, 630)
  }

  test("a deletion vector retried over a concurrent one for the same file replaces it unless condition 1 refuses") {
    val r = retryOf(DeleteByVector(5), DeleteByVector(7), deleterFirst, RuleSets.specCurrent)
    assertEquals(r.finding, Finding.SpecHole)
    assertEquals(r.restored, Set(7))
    assertEquals(retryOf(DeleteByVector(5), DeleteByVector(7), deleterFirst, RuleSets.specWithFix1).finding, Finding.Protected)
  }

  test("E1: a compaction retried over a concurrent deletion vector restores the row under the current text") {
    val r = retryOf(Compaction(OutputSeq.New), DeleteByVector(7), deleterFirst, RuleSets.specCurrent)
    assertEquals(r.finding, Finding.SpecHole)
    assertEquals(r.restored, Set(7))
    assertEquals(
      retryOf(Compaction(OutputSeq.New), DeleteByVector(7), deleterFirst, RuleSets.specWithFix1).finding,
      Finding.Protected
    )
  }

  test("a deletion vector retried after its file was compacted away dangles under the current text") {
    val r = retryOf(Compaction(OutputSeq.New), DeleteByVector(7), rewriterFirst, RuleSets.specCurrent)
    assertEquals(r.finding, Finding.SpecHole)
    assertEquals(r.restored, Set(7))
    assertEquals(
      retryOf(Compaction(OutputSeq.New), DeleteByVector(7), rewriterFirst, RuleSets.specWithFix1).finding,
      Finding.Protected
    )
  }

  test("the race of apache/iceberg#18846: the current text is silent on overwrite; prose fix 1 condition 3 governs it") {
    val r = retryOf(CopyOnWriteDelete(5), DeleteByEquality(7), deleterFirst, RuleSets.specCurrent)
    assertEquals(r.finding, Finding.SilentHarmful)
    assertEquals(r.restored, Set(7))
    assertEquals(
      retryOf(CopyOnWriteDelete(5), DeleteByEquality(7), deleterFirst, RuleSets.specWithFix1).finding,
      Finding.Protected
    )
  }

  test("a copy-on-write delete retried after a compaction duplicates and restores rows unless overwrites check liveness") {
    val r = retryOf(CopyOnWriteDelete(5), Compaction(OutputSeq.New), deleterFirst, RuleSets.specCurrent)
    assertEquals(r.finding, Finding.SilentHarmful)
    assertEquals(r.restored, Set(5))
    assertEquals(r.duplicated, Set(7))
    assertEquals(
      retryOf(CopyOnWriteDelete(5), Compaction(OutputSeq.New), deleterFirst, RuleSets.specWithFix1).finding,
      Finding.Protected
    )
  }

  test("a compaction at its starting sequence number stays within a concurrent equality delete's reach") {
    RuleSets.all.foreach { rs =>
      assertEquals(retryOf(Compaction(OutputSeq.Base), DeleteByEquality(7), deleterFirst, rs).finding, Finding.Fine, rs.name)
    }
  }

  test("a compaction at a new sequence number escapes a concurrent equality delete; fix 1 condition 3 catches it") {
    assertEquals(
      retryOf(Compaction(OutputSeq.New), DeleteByEquality(7), deleterFirst, RuleSets.specCurrent).finding,
      Finding.SpecHole
    )
    assertEquals(
      retryOf(Compaction(OutputSeq.New), DeleteByEquality(7), deleterFirst, RuleSets.specWithFix1).finding,
      Finding.Protected
    )
  }

  test("the bounds test in condition 3 is conservative: a delete of an absent value inside the bounds is refused") {
    assertEquals(
      retryOf(Compaction(OutputSeq.New), DeleteByEquality(6), deleterFirst, RuleSets.specWithFix1).finding,
      Finding.NeedlessRefusal
    )
    assertEquals(
      retryOf(Compaction(OutputSeq.New), DeleteByEquality(6), deleterFirst, RuleSets.specCurrent).finding,
      Finding.Fine
    )
  }

  test("histories without a retry are always correct") {
    Histories.runAll(E1Slice.scenarios, RuleSets.all).filter(_.retries.isEmpty).foreach { o =>
      val ops = o.committed.map(o.scenario.ops)
      assert(GroundTruth.isCorrect(E1Slice.initial.visibleRows, ops, o.finalRows), o.notation)
    }
  }
}
