package com.cloudera.acruise.histories

/** The ground truth: a committed history is correct when the rows it leaves, counted as a multiset, are exactly those
  * of some serial order of the operations that committed, each applied once by its logical effect.
  */
object GroundTruth {
  def serialResults(initial: Vector[Int], committed: Vector[Op]): Set[Vector[Int]] =
    committed.permutations.map(_.foldLeft(initial.sorted)((rows, op) => op.effect(rows).sorted)).toSet

  def isCorrect(initial: Vector[Int], committed: Vector[Op], observed: Vector[Int]): Boolean =
    serialResults(initial, committed).contains(observed.sorted)
}

sealed trait Event { def op: Int }
object Event {
  final case class Read(op: Int) extends Event

  /** `attempt` 0 is the operation's commit; attempt 1 is the retry of a commit whose outcome the writer did not learn. */
  final case class Commit(op: Int, attempt: Int = 0) extends Event
}

/** What the judges made of one retry. */
sealed abstract class Finding(val name: String, val interesting: Boolean)
object Finding {
  case object Fine extends Finding("fine", false)
  case object Protected extends Finding("protected", false)
  case object SpecHole extends Finding("spec hole: permitted and wrong", true)
  case object SilentHarmful extends Finding("silent, and committing is wrong", true)
  case object SilentBenign extends Finding("silent, and committing is correct", false)
  case object NeedlessRefusal extends Finding("needless refusal", true)
  case object Contradiction extends Finding("contradiction", true)
  case object UnverifiableHarmful extends Finding("unverifiable, and committing is wrong", true)
  case object UnverifiableBenign extends Finding("unverifiable, and committing is correct", false)
  case object Recognized extends Finding("recognized as already applied", false)
  case object Unjudged extends Finding("not judged: effect depends on another table", false)

  /** The table was already wrong before this retry, so the retry is not to blame for what it leaves. */
  case object Inherited extends Finding("already wrong before this retry", false)

  def classify(verdict: Verdict, correctBefore: Boolean, correctIfCommitted: Boolean): Finding =
    if (!correctBefore) Inherited else classify(verdict, correctIfCommitted)

  def classify(verdict: Verdict, correctIfCommitted: Boolean): Finding = (verdict, correctIfCommitted) match {
    case (_: Verdict.AlreadyApplied, _)   => Recognized
    case (_: Verdict.Contradictory, _)    => Contradiction
    case (_: Verdict.MayCommit, true)     => Fine
    case (_: Verdict.MayCommit, false)    => SpecHole
    case (_: Verdict.MustFail, true)      => NeedlessRefusal
    case (_: Verdict.MustFail, false)     => Protected
    case (_: Verdict.Silent, true)        => SilentBenign
    case (_: Verdict.Silent, false)       => SilentHarmful
    case (_: Verdict.Unverifiable, true)  => UnverifiableBenign
    case (_: Verdict.Unverifiable, false) => UnverifiableHarmful
  }
}

final case class Retry(
    op: Int,
    verdict: Verdict,
    finding: Finding,
    observedIfCommitted: Vector[Int],
    expected: Vector[Int]
) {
  def restored: Set[Int] = observedIfCommitted.toSet -- expected.toSet
  def lost: Set[Int] = expected.toSet -- observedIfCommitted.toSet
  def duplicated: Set[Int] = observedIfCommitted.diff(expected).toSet -- restored
}

/** A writer of unknown outcome that ends believing the opposite of what happened. */
final case class Misreport(op: Int, applied: Boolean) {
  def describe: String = if (applied) "applied, but reported as failed" else "not applied, but reported as committed"
}

/** A scenario names its tables' initial snapshots; single-table scenarios use the table `t`. */
final case class Scenario(name: String, tables: Map[String, Snapshot], ops: Vector[Op]) {
  def initial: Snapshot = tables.getOrElse("t", tables.values.head)
}

object Scenario {
  def apply(name: String, initial: Snapshot, ops: Vector[Op]): Scenario = Scenario(name, Map("t" -> initial), ops)
}

/** The state of every table after one event of a history: how many snapshots each log held, and which had expired. */
final case class Step(event: Option[Event], lengths: Map[String, Int], expired: Map[String, Set[Int]])

final case class Outcome(
    scenario: Scenario,
    order: Vector[Event],
    ruleSet: RuleSet,
    retries: Vector[Retry],
    committed: Vector[Int],
    logs: Map[String, Vector[Snapshot]],
    expiredBy: Map[String, Set[Int]],
    misreports: Vector[Misreport],
    steps: Vector[Step]
) {
  private def primary: String = if (logs.contains("t")) "t" else logs.keys.min

  /** The primary table's log, expirations and final rows, for single-table slices and the auditor. */
  def log: Vector[Snapshot] = logs(primary)
  def expired: Set[Int] = expiredBy(primary)
  def finalRows: Vector[Int] = log.last.visibleRows

  def notation: String =
    order
      .map {
        case Event.Read(i)      => s"r:${scenario.ops(i).label}"
        case Event.Commit(i, 0) => s"c:${scenario.ops(i).label}"
        case Event.Commit(i, a) => s"c${a + 1}:${scenario.ops(i).label}"
      }
      .mkString(" ")

  /** Every table whose committed operations all have known effects holds the rows of some serial order of them. */
  def isCorrect: Boolean =
    logs.forall { case (t, l) =>
      val ops = committed.distinct.map(scenario.ops).filter(_.table == t)
      !ops.forall(_.effectKnown) || GroundTruth.isCorrect(scenario.tables(t).visibleRows, ops, l.last.visibleRows)
    }

  /** The logs, and the expired snapshots, as they stood after each step. */
  def states: Vector[(Option[Event], Map[String, Vector[Snapshot]], Map[String, Set[Int]])] =
    steps.map(st => (st.event, logs.map { case (t, l) => t -> l.take(st.lengths(t)) }, st.expired))
}

object Histories {

  /** Every order of the `n` operations' read and commit events in which each operation reads before it commits. */
  def interleavings(n: Int): Vector[Vector[Event]] = interleavings(Vector.fill(n)(true))

  /** As above, for operations of which only those marked in `reads` have a read point. */
  def interleavings(reads: Vector[Boolean]): Vector[Vector[Event]] =
    interleave(reads.zipWithIndex.map { case (r, i) => (if (r) Vector(Event.Read(i)) else Vector.empty) :+ Event.Commit(i) })

  /** Every order of the scenario's events that keeps each operation's own events in order. */
  def interleavingsOf(ops: Vector[Op]): Vector[Vector[Event]] =
    interleave(ops.zipWithIndex.map { case (op, i) =>
      (if (op.reads) Vector[Event](Event.Read(i)) else Vector.empty[Event]) ++ (0 until op.attempts).map(a => Event.Commit(i, a))
    })

  /** All merges of the given sequences that preserve the order within each. */
  def interleave(seqs: Vector[Vector[Event]]): Vector[Vector[Event]] = {
    def go(pos: Vector[Int], acc: Vector[Event]): Vector[Vector[Event]] = {
      val open = pos.indices.filter(i => pos(i) < seqs(i).size)
      if (open.isEmpty) Vector(acc)
      else open.toVector.flatMap(i => go(pos.updated(i, pos(i) + 1), acc :+ seqs(i)(pos(i))))
    }
    go(Vector.fill(seqs.size)(0), Vector.empty)
  }

  /** Run one order under one rule set. A retry judged `MustFail` aborts; `MayCommit`, `Silent` and `Unverifiable`
    * commit, so a silent specification is read permissively and the cost of that reading is what the finding records.
    * `AlreadyApplied` ends the operation, the writer believing it committed.
    *
    * An operation of unknown outcome (`Ambiguous`) either lands or is lost at its first commit, and in both cases its
    * writer is told it failed and retries once from its original base, without re-reading.
    */
  def run(scenario: Scenario, order: Vector[Event], ruleSet: RuleSet): Outcome = {
    var logs: Map[String, Vector[Snapshot]] = scenario.tables.map { case (t, s) => t -> Vector(s) }
    var expired: Map[String, Set[Int]] = scenario.tables.map { case (t, _) => t -> Set.empty[Int] }
    var bases = Map.empty[Int, (Snapshot, Option[Plan])]
    var committed = Vector.empty[Int]
    var retries = Vector.empty[Retry]
    var unknown = Set.empty[Int] // writers that do not know whether their first commit landed
    var believed = Map.empty[Int, Boolean] // for those writers, whether each finally believes it committed
    var steps = Vector(Step(None, logs.map { case (t, l) => t -> l.size }, expired))

    def ops(table: String, is: Vector[Int]): Vector[Op] = is.distinct.map(scenario.ops).filter(_.table == table)

    order.foreach { event =>
      event match {
        case Event.Read(i) =>
          val op = scenario.ops(i)
          val base = logs(op.table).last
          bases += i -> (base -> op.planIn(base, logs.map { case (t, l) => t -> l.last }))

        case Event.Commit(i, _) if !scenario.ops(i).reads =>
          scenario.ops(i) match {
            case ExpireSnapshots(retain, t) => expired += t -> (expired(t) ++ logs(t).dropRight(retain).map(_.id))
            case other                      => throw new IllegalStateException(s"no commit semantics for ${other.label}")
          }

        case Event.Commit(i, attempt) if attempt > 0 && !unknown(i) => () // the first attempt had a definite answer

        case Event.Commit(i, attempt) =>
          val op = scenario.ops(i)
          val t = op.table
          val (base, planned) = bases(i)
          val lostInFlight = attempt == 0 && (op match {
            case a: Ambiguous => !a.lands
            case _            => false
          })
          if (lostInFlight) { unknown += i; believed += i -> false }
          else
            planned.foreach { plan =>
              val log = logs(t)
              val current = log.last
              val candidate = Plan.commit(current, base, plan, op.label, Some(i))

              def land(): Unit = {
                logs += t -> (log :+ candidate)
                committed :+= i
                if (attempt == 0 && op.attempts > 1) { unknown += i; believed += i -> false }
                else if (attempt > 0) believed += i -> true
              }

              if (current.id == base.id) land()
              else {
                val since = log.filter(_.id > base.id)
                val retained = since.filterNot(s => expired(t)(s.id))
                val ownSince = attempt > 0 && ruleSet.recognition.exists(_.recognizes(i, plan, base, retained))
                val ctx = RetryContext(plan, base, current, retained, since.forall(s => !expired(t)(s.id)), ownSince)
                val verdict = ruleSet.judge(ctx)
                val initialRows = scenario.tables(t).visibleRows
                val after = ops(t, committed :+ i)
                val observed = candidate.visibleRows
                val judged = after.forall(_.effectKnown)
                val correct = !judged || GroundTruth.isCorrect(initialRows, after, observed)
                val correctBefore = !judged || GroundTruth.isCorrect(initialRows, ops(t, committed), current.visibleRows)
                val expected = after.foldLeft(initialRows)((rows, o) => o.effect(rows)).sorted
                val finding = if (judged) Finding.classify(verdict, correctBefore, correct) else Finding.Unjudged
                retries :+= Retry(i, verdict, finding, observed, expected)
                verdict match {
                  case _: Verdict.MustFail       => if (attempt > 0) believed += i -> false
                  case _: Verdict.AlreadyApplied => believed += i -> true
                  case _                         => land()
                }
              }
            }
      }
      steps :+= Step(Some(event), logs.map { case (t, l) => t -> l.size }, expired)
    }
    val misreports = believed.toVector.sortBy(_._1).collect {
      case (i, b) if b != committed.contains(i) => Misreport(i, committed.contains(i))
    }
    Outcome(scenario, order, ruleSet, retries, committed, logs, expired, misreports, steps)
  }

  def runAll(scenarios: Vector[Scenario], ruleSets: Vector[RuleSet]): Vector[Outcome] =
    for {
      rs <- ruleSets
      s <- scenarios
      order <- interleavingsOf(s.ops)
    } yield run(s, order, rs)
}
