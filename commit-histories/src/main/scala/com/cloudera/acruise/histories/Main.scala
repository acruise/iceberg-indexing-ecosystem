package com.cloudera.acruise.histories

object Main {
  private val slices: Vector[(String, Vector[Scenario], Vector[RuleSet])] = Vector(
    ("E1", E1Slice.scenarios, RuleSets.all),
    ("E2", E2Slice.scenarios, RuleSets.all),
    ("E4", E4Slice.scenarios, RuleSets.withRecognition)
  )

  /** Prints, per slice and rule set, the count of each finding and up to `examples` histories for each finding worth
    * reading, then the E3 audit of the same histories. `sbt "commitHistories/run E2 10"` runs one slice with ten
    * examples each.
    */
  def main(args: Array[String]): Unit = {
    val name = args.headOption
    val examples = args.lift(1).map(_.toInt).getOrElse(3)
    slices.filter(s => name.forall(_ == s._1)).foreach { case (slice, scenarios, ruleSets) =>
      val outcomes = Histories.runAll(scenarios, ruleSets)
      report(slice, outcomes, ruleSets, examples)
      audit(slice, outcomes, ruleSets)
    }
    if (name.forall(_ == "V14")) indexReport(examples)
    if (name.forall(_ == "MV")) viewReport(examples)
  }

  /** The derived-structure slices commit under prose fixes 1 and 2, so that what they measure is the reader's rule and
    * not the table's retry conditions.
    */
  private val derivedRules = Vector(RuleSets.specWithFix1And2)

  def indexReport(examples: Int): Unit = {
    val outcomes = Histories.runAll(IndexSlice.scenarios, derivedRules)
    println(s"== V14: snapshot-pinned index, ${outcomes.size} histories")
    Derived.IndexPolicy.all.foreach { p =>
      val t = outcomes.map(Derived.probeIndex(_, IndexSlice.index, p)).foldLeft(Derived.Tally.zero)(_ + _)
      println(f"   ${p.name}%-50s $t")
      outcomes.flatMap(Derived.firstUnsafeIndexStep(_, IndexSlice.index, p)).take(examples).foreach(e => println(s"      $e"))
    }
    val coverage = outcomes.flatMap(_.states.map { case (_, logs, _) => Derived.fileScopedCoverage(IndexSlice.index, logs("t").last) })
    println(f"   file-scoped: share of rows answered from the index, mean over probes: ${100 * coverage.sum / coverage.size}%.1f%%")
    println()
  }

  def viewReport(examples: Int): Unit = {
    val outcomes = Histories.runAll(ViewSlice.scenarios, derivedRules)
    println(s"== MV: refresh state and freshness, ${outcomes.size} histories")
    for {
      rule <- Derived.StateRule.all
      fresh <- Derived.Freshness.all
    } {
      val t = outcomes.map(Derived.probeView(_, rule, fresh)).foldLeft(Derived.Tally.zero)(_ + _)
      println(f"   ${rule.name}%-44s ${fresh.name}%-32s $t")
      outcomes.flatMap(Derived.firstUnsafeViewStep(_, rule, fresh)).take(examples).foreach(e => println(s"      $e"))
    }
    def compactsMv(o: Outcome, carry: Boolean): Boolean = o.scenario.ops.exists {
      case c: Compaction => c.table == "mv" && c.carryForward == carry
      case _             => false
    }
    Vector(
      "storage compacted without carrying the state forward" -> ((o: Outcome) => compactsMv(o, carry = false) && !compactsMv(o, carry = true)),
      "storage compacted, carrying the state forward" -> ((o: Outcome) => compactsMv(o, carry = true) && !compactsMv(o, carry = false))
    ).foreach { case (label, keep) =>
      val sub = outcomes.filter(keep)
      println(s"   -- ${sub.size} histories in which the $label, recorded snapshot is the head:")
      Derived.StateRule.all.foreach { rule =>
        val t = sub.map(Derived.probeView(_, rule, Derived.Freshness.SnapshotEqual)).foldLeft(Derived.Tally.zero)(_ + _)
        println(f"      ${rule.name}%-44s $t")
      }
    }
    println()
  }

  def report(slice: String, outcomes: Vector[Outcome], ruleSets: Vector[RuleSet], examples: Int): Unit =
    ruleSets.foreach { rs =>
      val os = outcomes.filter(_.ruleSet == rs)
      val retries = os.flatMap(o => o.retries.map(o -> _))
      println(s"== $slice under ${rs.name}: ${os.size} histories, ${retries.size} retries")
      retries.groupBy(_._2.finding).toVector.sortBy(_._1.name).foreach { case (f, rs) =>
        println(f"   ${f.name}%-42s ${rs.size}%5d")
      }
      os.flatMap(o => o.misreports.map(o -> _)).groupBy(_._2.describe).toVector.sortBy(_._1).foreach { case (d, ms) =>
        println(f"   writer believes wrongly: $d%-26s ${ms.size}%5d")
      }
      retries.filter(_._2.finding.interesting).groupBy(_._2.finding).toVector.sortBy(_._1.name).foreach { case (f, rs) =>
        println(s"   -- ${f.name}, first ${math.min(examples, rs.size)} of ${rs.size}:")
        rs.take(examples).foreach { case (o, r) =>
          println(s"      ${o.notation}")
          println(s"         retry of ${o.scenario.ops(r.op).label}: ${describe(r.verdict)}${detail(r)}")
        }
      }
      println()
    }

  /** E3: of the histories that end wrong, how many an auditor flags from metadata alone, in each mode. */
  def audit(slice: String, outcomes: Vector[Outcome], ruleSets: Vector[RuleSet]): Unit = {
    println(s"== $slice audited (E3): wrong histories detected / missed; correct histories flagged; snapshots unauditable")
    ruleSets.foreach { rs =>
      val os = outcomes.filter(_.ruleSet == rs)
      Auditor.Mode.all.foreach { mode =>
        val audits = os.map(o => o -> Auditor.audit(o, mode))
        val (wrong, right) = audits.partition(!_._1.isCorrect)
        println(
          f"   ${rs.name}%-44s ${mode.name}%-22s ${wrong.count(_._2.flagged)}%5d / ${wrong.count(!_._2.flagged)}%5d; " +
            f"${right.count(_._2.flagged)}%5d; ${audits.map(_._2.unauditable).sum}%5d"
        )
      }
    }
    println()
  }

  private def detail(r: Retry): String = {
    val parts = Vector(
      Option.when(r.restored.nonEmpty)(s"restored ${r.restored.toSeq.sorted.mkString(",")}"),
      Option.when(r.lost.nonEmpty)(s"lost ${r.lost.toSeq.sorted.mkString(",")}"),
      Option.when(r.duplicated.nonEmpty)(s"duplicated ${r.duplicated.toSeq.sorted.mkString(",")}")
    ).flatten
    if (parts.isEmpty) "" else parts.mkString(" -> ", "; ", "")
  }

  private def describe(v: Verdict): String = v match {
    case Verdict.MustFail(cs)        => s"must fail (${cs.mkString(", ")})"
    case Verdict.MayCommit(cs)       => s"may commit (${cs.mkString(", ")})"
    case Verdict.Silent(reason)      => s"silent: $reason"
    case Verdict.Contradictory(a, f) => s"contradictory (always: ${a.mkString(", ")}; failed: ${f.mkString(", ")})"
    case Verdict.Unverifiable(cs)    => s"unverifiable, and nothing says what to do (${cs.mkString(", ")})"
    case Verdict.AlreadyApplied(c)   => s"recognized as already applied ($c)"
  }
}
