package com.cloudera.acruise.histories

/** Derived structures: something computed from a table as of one snapshot and consulted at a later one. The question
  * for each is the same, across which later snapshots a reader may still trust it, and the slices here put each
  * candidate rule to the ground truth of scanning the source now. A probe is a reader consulting the structure after
  * one step of a history; it is unsafe when the reader trusts the structure and the answer differs from the scan.
  */
object Derived {

  /** A snapshot-pinned index (register entry V14), built at `builtAt` over the files live then; for each covered file,
    * the values at each position. The query is every row, so the index's answer is the table's rows.
    */
  final case class PinnedIndex(builtAt: Int, covered: Map[String, Vector[Int]]) {
    def rows: Vector[Int] = covered.values.flatten.toVector.sorted
  }

  object PinnedIndex {
    def build(s: Snapshot): PinnedIndex = PinnedIndex(s.id, s.liveFiles.map(f => f.path -> s.visibleRowsOf(f)).toMap)
  }

  /** How a reader decides whether, and how, to use a pinned index at the current snapshot. `None` means not usable. */
  sealed abstract class IndexPolicy(val name: String) {
    def answer(idx: PinnedIndex, log: Vector[Snapshot], expired: Set[Int]): Option[Vector[Int]]

    protected def window(idx: PinnedIndex, log: Vector[Snapshot], expired: Set[Int]): Option[Vector[Snapshot]] = {
      val w = log.filter(_.id > idx.builtAt)
      if (w.exists(s => expired(s.id))) None else Some(w)
    }

    /** The index's rows plus a scan of every live file it does not cover. */
    protected def withUncovered(idx: PinnedIndex, current: Snapshot): Vector[Int] =
      (idx.rows ++ current.liveFiles.filterNot(f => idx.covered.contains(f.path)).flatMap(current.visibleRowsOf)).sorted
  }

  object IndexPolicy {
    case object Exact extends IndexPolicy("only at the snapshot it was built at") {
      def answer(idx: PinnedIndex, log: Vector[Snapshot], expired: Set[Int]): Option[Vector[Int]] =
        Option.when(log.last.id == idx.builtAt)(idx.rows)
    }

    case object AcrossAppends extends IndexPolicy("across appends, scanning new files") {
      def answer(idx: PinnedIndex, log: Vector[Snapshot], expired: Set[Int]): Option[Vector[Int]] =
        window(idx, log, expired).filter(_.forall(_.op == SnapshotOp.Append)).map(_ => withUncovered(idx, log.last))
    }

    case object AcrossReplaces extends IndexPolicy("across replaces (the ancestry rule)") {
      def answer(idx: PinnedIndex, log: Vector[Snapshot], expired: Set[Int]): Option[Vector[Int]] =
        window(idx, log, expired).filter(_.forall(_.op == SnapshotOp.Replace)).map(_ => idx.rows)
    }

    case object AcrossBoth extends IndexPolicy("across appends and replaces, scanning new files") {
      def answer(idx: PinnedIndex, log: Vector[Snapshot], expired: Set[Int]): Option[Vector[Int]] =
        window(idx, log, expired)
          .filter(_.forall(s => s.op == SnapshotOp.Append || s.op == SnapshotOp.Replace))
          .map(_ => withUncovered(idx, log.last))
    }

    /** The Puffin proposal's alternative: entries bound to data files, used for each covered file still live, with the
      * current deletes applied by position; every other file is scanned. Always usable; what varies is how much of
      * the answer comes from the index.
      */
    case object FileScoped extends IndexPolicy("bound to data files (Puffin proposal)") {
      def answer(idx: PinnedIndex, log: Vector[Snapshot], expired: Set[Int]): Option[Vector[Int]] = Some(log.last.visibleRows)
    }

    val all: Vector[IndexPolicy] = Vector(Exact, AcrossAppends, AcrossReplaces, AcrossBoth, FileScoped)
  }

  /** The share of the current rows a file-scoped index answers without a scan. */
  def fileScopedCoverage(idx: PinnedIndex, current: Snapshot): Double = {
    val total = current.visibleRows.size
    if (total == 0) 1.0 else current.liveFiles.filter(f => idx.covered.contains(f.path)).map(current.visibleRowsOf(_).size).sum.toDouble / total
  }

  // ---- Materialized views

  /** How a reader finds the refresh state of a storage table. */
  sealed abstract class StateRule(val name: String) {
    def resolve(log: Vector[Snapshot], expired: Set[Int]): Option[RefreshState]
  }

  object StateRule {
    case object CurrentOnly extends StateRule("current snapshot only") {
      def resolve(log: Vector[Snapshot], expired: Set[Int]): Option[RefreshState] = log.last.refreshState
    }

    /** The literal reading of "the presence of a snapshot with `refresh-state`": the nearest retained ancestor that
      * has one, whatever lies between.
      */
    case object NearestAny extends StateRule("nearest ancestor, unconditionally") {
      def resolve(log: Vector[Snapshot], expired: Set[Int]): Option[RefreshState] =
        log.reverse.takeWhile(s => !expired(s.id)).collectFirst { case s if s.refreshState.isDefined => s.refreshState.get }
    }

    /** apache/iceberg#11041 at 498f7a0, view-spec.md line 202. */
    case object AncestryReplace extends StateRule("nearest ancestor across replaces (498f7a0)") {
      def resolve(log: Vector[Snapshot], expired: Set[Int]): Option[RefreshState] = {
        val back = log.reverse.takeWhile(s => !expired(s.id))
        val i = back.indexWhere(_.refreshState.isDefined)
        if (i < 0 || !back.take(i).forall(_.op == SnapshotOp.Replace)) None else back(i).refreshState
      }
    }

    val all: Vector[StateRule] = Vector(CurrentOnly, NearestAny, AncestryReplace)
  }

  /** How a reader decides that the recorded source snapshot still stands for the source's current contents. */
  sealed abstract class Freshness(val name: String) {
    def fresh(rs: RefreshState, source: Vector[Snapshot], expired: Set[Int]): Boolean
  }

  object Freshness {

    /** Appendix B, Example 2: fresh if the recorded snapshot is still the head. */
    case object SnapshotEqual extends Freshness("recorded snapshot is the head") {
      def fresh(rs: RefreshState, source: Vector[Snapshot], expired: Set[Int]): Boolean = source.last.id == rs.sourceSnapshot
    }

    /** The third example the private note proposes: unchanged up to content-preserving rewrites. */
    case object UpToReplace extends Freshness("unchanged up to replaces") {
      def fresh(rs: RefreshState, source: Vector[Snapshot], expired: Set[Int]): Boolean = {
        val w = source.filter(_.id > rs.sourceSnapshot)
        source.exists(_.id == rs.sourceSnapshot) && w.forall(s => !expired(s.id) && s.op == SnapshotOp.Replace)
      }
    }

    val all: Vector[Freshness] = Vector(SnapshotEqual, UpToReplace)
  }

  // ---- Probing and reporting

  final case class Tally(probes: Int, usable: Int, unsafe: Int) {
    def +(o: Tally): Tally = Tally(probes + o.probes, usable + o.usable, unsafe + o.unsafe)
    override def toString: String = f"$probes%6d probes, usable ${100.0 * usable / math.max(probes, 1)}%5.1f%%, unsafe $unsafe%5d"
  }

  object Tally { val zero: Tally = Tally(0, 0, 0) }

  def probeIndex(o: Outcome, idx: PinnedIndex, policy: IndexPolicy): Tally =
    o.states.foldLeft(Tally.zero) { case (acc, (_, logs, expired)) =>
      val log = logs("t")
      val truth = log.last.visibleRows
      policy.answer(idx, log, expired("t")) match {
        case None    => acc + Tally(1, 0, 0)
        case Some(a) => acc + Tally(1, 1, if (a == truth) 0 else 1)
      }
    }

  def probeView(o: Outcome, rule: StateRule, freshness: Freshness, source: String = "src", storage: String = "mv"): Tally =
    o.states.foldLeft(Tally.zero) { case (acc, (_, logs, expired)) =>
      val truth = logs(source).last.visibleRows
      val usable = rule.resolve(logs(storage), expired(storage)).exists(rs => freshness.fresh(rs, logs(source), expired(source)))
      if (!usable) acc + Tally(1, 0, 0)
      else acc + Tally(1, 1, if (logs(storage).last.visibleRows == truth) 0 else 1)
    }

  /** A probe that shows a policy unsafe, for the report: the history and the step after which it was unsafe. */
  def firstUnsafeIndexStep(o: Outcome, idx: PinnedIndex, policy: IndexPolicy): Option[String] =
    o.states.collectFirst {
      case (ev, logs, expired) if policy.answer(idx, logs("t"), expired("t")).exists(_ != logs("t").last.visibleRows) =>
        s"${o.notation}   [unsafe after ${ev.fold("start")(e => o.notation.split(" ")(o.order.indexOf(e)))}]"
    }

  def firstUnsafeViewStep(o: Outcome, rule: StateRule, freshness: Freshness): Option[String] =
    o.states.collectFirst {
      case (ev, logs, expired)
          if rule.resolve(logs("mv"), expired("mv")).exists(rs => freshness.fresh(rs, logs("src"), expired("src"))) &&
            logs("mv").last.visibleRows != logs("src").last.visibleRows =>
        s"${o.notation}   [unsafe after ${ev.fold("start")(e => o.notation.split(" ")(o.order.indexOf(e)))}]"
    }
}

/** V14: a snapshot-pinned index on one file, built at the initial snapshot, under appends, rewrites, deletes and
  * expiration, two and three at a time.
  */
object IndexSlice {
  val initial: Snapshot = E1Slice.initial
  val index: Derived.PinnedIndex = Derived.PinnedIndex.build(initial)

  val pool: Vector[Op] =
    Vector(AppendRow(9), Compaction(OutputSeq.New), DeleteByVector(7), CopyOnWriteDelete(5), ExpireSnapshots(1))

  val scenarios: Vector[Scenario] =
    (1 to 3).toVector.flatMap(k => pool.combinations(k).toVector).map(ops => Scenario(ops.map(_.label).mkString(" + "), initial, ops))
}

/** A materialized view `SELECT * FROM src` with storage table `mv`, refreshed at the start. Source writes, refreshes,
  * storage compactions with and without carrying the refresh state forward, a write to the storage table by an engine
  * that does not know it is one, and expiration on either table, one to three at a time.
  */
object ViewSlice {
  val source: Snapshot = Snapshot.initial(DataFile("F", 0, Vector(5, 7), 1))
  val storage: Snapshot = Snapshot.initial(DataFile("M", 0, Vector(5, 7), 1)).copy(refreshState = Some(RefreshState("src", 1)))

  val pool: Vector[Op] = Vector(
    AppendRow(9, table = "src"),
    Compaction(OutputSeq.New, table = "src"),
    DeleteByVector(7, table = "src"),
    ExpireSnapshots(1, table = "src"),
    Refresh(),
    Compaction(OutputSeq.New, table = "mv"),
    Compaction(OutputSeq.New, table = "mv", carryForward = true),
    DeleteByVector(5, table = "mv"),
    ExpireSnapshots(1, table = "mv")
  )

  val scenarios: Vector[Scenario] =
    (1 to 3).toVector
      .flatMap(k => pool.combinations(k).toVector)
      .map(ops => Scenario(ops.map(_.label).mkString(" + "), Map("src" -> source, "mv" -> storage), ops))
}
