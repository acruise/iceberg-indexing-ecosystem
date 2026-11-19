package com.cloudera.acruise.histories

/** Where a rule comes from, quoted, so that a reviewer can dispute the reading rather than the result. */
final case class Citation(source: String, anchor: String, text: String) {
  override def toString: String = if (anchor.isEmpty) source else s"$source $anchor"
}

/** Everything a retry condition may consult: the plan the operation made, the snapshot it read, the snapshot it now
  * commits on, and those snapshots committed in between that expiration has left. `historyComplete` is false when
  * expiration has removed any of them, so that a condition about what was added since the base cannot be verified.
  */
final case class RetryContext(
    plan: Plan,
    base: Snapshot,
    current: Snapshot,
    intervening: Vector[Snapshot],
    historyComplete: Boolean = true,
    ownCommitSince: Boolean = false
) {
  def removedAtBase: Vector[DataFile] = plan.removedFiles.toVector.sorted.flatMap(base.files.get)

  def outputDataSeq: Long = plan.outputSeq match {
    case OutputSeq.New  => current.seq + 1
    case OutputSeq.Base => base.seq
  }

  def dvsAddedSince: Vector[DeletionVector] = intervening.flatMap(_.delta.dvs)
  def eqDeletesAddedSince: Vector[EqualityDelete] = intervening.flatMap(_.delta.eqDeletes)
}

sealed trait Verdict
object Verdict {

  /** A governing rule's condition does not hold. */
  final case class MustFail(failed: Vector[Citation]) extends Verdict

  /** Every governing rule's condition holds. */
  final case class MayCommit(by: Vector[Citation]) extends Verdict

  /** No rule governs this operation. */
  final case class Silent(reason: String) extends Verdict

  /** One governing rule says the operation can always be applied and another's condition does not hold. */
  final case class Contradictory(always: Vector[Citation], failed: Vector[Citation]) extends Verdict

  /** No verifiable condition fails, but some condition cannot be verified, and nothing says what the writer does then. */
  final case class Unverifiable(conditions: Vector[Citation]) extends Verdict

  /** A writer of unknown outcome recognizes its own earlier commit and stops. */
  final case class AlreadyApplied(by: Citation) extends Verdict
}

/** How a writer of unknown outcome looks for its own earlier commit among the retained snapshots since its base. */
sealed abstract class Recognition(val citation: Citation) {
  def recognizes(op: Int, plan: Plan, base: Snapshot, since: Vector[Snapshot]): Boolean
}

object Recognition {

  /** By an identity the writer attached to its commit. */
  final case class ByIdentity(c: Citation) extends Recognition(c) {
    def recognizes(op: Int, plan: Plan, base: Snapshot, since: Vector[Snapshot]): Boolean = since.exists(_.commitId.contains(op))
  }

  /** By the recorded read base and the commit's content, without an identity: a snapshot read from the same base that
    * made the same change is taken to be the writer's own.
    */
  final case class ByReadBaseAndContent(c: Citation) extends Recognition(c) {
    def recognizes(op: Int, plan: Plan, base: Snapshot, since: Vector[Snapshot]): Boolean =
      since.exists { s =>
        s.readBase == base.id && s.op == plan.op &&
        s.delta.removedFiles.subsetOf(plan.removedFiles) &&
        s.delta.addedFiles.map(f => f.partition -> f.rows) == plan.addedRows &&
        s.delta.dvs.map(d => d.referencedFile -> d.positions) == plan.dvs.map(d => d.file -> d.positions) &&
        s.delta.eqDeletes.map(e => e.partition -> e.ids) == plan.eqDeletes.map(e => e.partition -> e.ids)
      }
  }
}

sealed trait Rule {
  def citation: Citation
  def governs(plan: Plan): Boolean
}

/** A rule that states a condition to verify. `needsHistory` marks a condition about what was added since the base,
  * which cannot be verified once expiration has removed part of that history.
  */
final case class Condition(
    citation: Citation,
    governs0: Plan => Boolean,
    holds: RetryContext => Boolean,
    needsHistory: Boolean = false
) extends Rule {
  def governs(plan: Plan): Boolean = governs0(plan)
}

/** A rule that says an operation can always be applied. */
final case class Always(citation: Citation, governs0: Plan => Boolean) extends Rule {
  def governs(plan: Plan): Boolean = governs0(plan)
}

/** `onUnverifiable` is the rule, if the rule set has one, that says what a writer does when it cannot verify a
  * condition; without it, an unverifiable condition yields `Unverifiable`.
  */
final case class RuleSet(
    name: String,
    rules: Vector[Rule],
    onUnverifiable: Option[Citation] = None,
    recognition: Option[Recognition] = None
) {
  def judge(ctx: RetryContext): Verdict = {
    val governing = rules.filter(_.governs(ctx.plan))
    if (ctx.ownCommitSince && recognition.isDefined) Verdict.AlreadyApplied(recognition.get.citation)
    else if (governing.isEmpty) Verdict.Silent(s"no rule governs `${ctx.plan.op.name}` operations")
    else {
      val conditions = governing.collect { case c: Condition => c }
      val (verifiable, unverifiable) = conditions.partition(c => !c.needsHistory || ctx.historyComplete)
      val always = governing.collect { case a: Always => a.citation }
      val failed = verifiable.filterNot(_.holds(ctx)).map(_.citation)
      if (failed.nonEmpty && always.nonEmpty) Verdict.Contradictory(always, failed)
      else if (failed.nonEmpty) Verdict.MustFail(failed)
      else if (unverifiable.nonEmpty)
        onUnverifiable match {
          case Some(rule) => Verdict.MustFail(rule +: unverifiable.map(_.citation))
          case None       => Verdict.Unverifiable(unverifiable.map(_.citation))
        }
      else Verdict.MayCommit(governing.map(_.citation))
    }
  }
}

object RuleSets {
  private val specAt = "spec@13135ae"
  private val retry = "#commit-conflict-resolution-and-retry"
  private val fix1 = "prose-fixes.md#1"

  private def removedFilesStillLive(ctx: RetryContext): Boolean = ctx.plan.removedFiles.subsetOf(ctx.current.files.keySet)

  val appendRule: Always = Always(
    Citation(
      specAt,
      retry,
      "Append operations have no requirements and can always be applied, other than the constraint requirements below."
    ),
    _.op == SnapshotOp.Append
  )

  val replaceRule: Condition = Condition(
    Citation(specAt, retry, "Replace operations must verify that the files that will be deleted are still in the table."),
    _.op == SnapshotOp.Replace,
    removedFilesStillLive
  )

  /** Reading: a delete that only adds delete files removes no file, so its condition holds vacuously. The sentence on
    * expression-based deletes is not modeled as `Always`, since none of the modeled deletes is one in the sense of its
    * example (`where timestamp < X`).
    */
  val deleteRule: Condition = Condition(
    Citation(
      specAt,
      retry,
      "Delete operations must verify that specific files to delete are still in the table. Delete operations based on expressions can always be applied (e.g., where timestamp < X)."
    ),
    _.op == SnapshotOp.Delete,
    removedFilesStillLive
  )

  /** The retry conditions on apache/iceberg `main` at `13135ae`. They do not mention `overwrite`. */
  val specCurrent: RuleSet = RuleSet("spec (current)", Vector(appendRule, replaceRule, deleteRule))

  private def removesData(plan: Plan): Boolean = plan.op == SnapshotOp.Replace || plan.op == SnapshotOp.Overwrite

  private val fix1VectorsText =
    "Operations that add a deletion vector or a column file for a data file must verify that the data file is still in the table, and that no deletion vector or column file for the same data file and fields has been added since the operation's base version."

  /** Condition 1's first clause, which the current snapshot alone decides. */
  val fix1VectorsLive: Condition = Condition(
    Citation(fix1, "condition 1, first clause", fix1VectorsText),
    _.dvs.nonEmpty,
    ctx => ctx.plan.dvs.forall(d => ctx.current.files.contains(d.file))
  )

  /** Condition 1's second clause, which needs the history since the base. */
  val fix1VectorsUnique: Condition = Condition(
    Citation(fix1, "condition 1, second clause", fix1VectorsText),
    _.dvs.nonEmpty,
    ctx => ctx.plan.dvs.forall(d => !ctx.dvsAddedSince.exists(_.referencedFile == d.file)),
    needsHistory = true
  )


  /** The exception for a compactor that carries concurrent deletes forward is not modeled: no modeled operation does. */
  val fix1ReplaceVectors: Condition = Condition(
    Citation(
      fix1,
      "condition 2",
      "Replace and overwrite operations must also verify that no deletion vector, position delete file or column file has been added since the operation's base version for any of the data files they remove, unless ..."
    ),
    removesData,
    ctx => !ctx.dvsAddedSince.exists(dv => ctx.plan.removedFiles(dv.referencedFile)),
    needsHistory = true
  )

  val fix1ReplaceEquality: Condition = Condition(
    Citation(
      fix1,
      "condition 3",
      "Replace and overwrite operations must also verify that no equality delete file added since the operation's base version applies to any of the data files they remove, unless that delete file also applies to the operation's output files ..., or the operation can show that the delete file matches no row of the removed data file, for instance because the delete file's bounds for its equality fields do not overlap the data file's."
    ),
    removesData,
    ctx =>
      ctx.eqDeletesAddedSince.forall { e =>
        ctx.removedAtBase.forall { f =>
          !e.appliesTo(f) ||
          ctx.outputDataSeq < e.dataSeq ||
          e.upper < f.lower || f.upper < e.lower
        }
      },
    needsHistory = true
  )

  val fix1OverwriteLive: Condition = Condition(
    Citation(
      fix1,
      "condition 4",
      "Overwrite operations must verify that the files they remove are still in the table, as replace operations must."
    ),
    _.op == SnapshotOp.Overwrite,
    removedFilesStillLive
  )

  /** The current conditions plus the four of prose fix 1. */
  val specWithFix1: RuleSet =
    RuleSet(
      "spec + prose fix 1",
      specCurrent.rules ++ Vector(fix1VectorsLive, fix1VectorsUnique, fix1ReplaceVectors, fix1ReplaceEquality, fix1OverwriteLive)
    )

  val fix2: Citation = Citation(
    "prose-fixes.md#2",
    "",
    "The conditions are verified against every commit between the version the update was based on and the new current version. A writer that cannot verify a condition must not commit."
  )

  val specWithFix1And2: RuleSet = specWithFix1.copy(name = "spec + prose fixes 1 and 2", onUnverifiable = Some(fix2))

  val withIdentity: RuleSet = specWithFix1And2.copy(
    name = "spec + fixes 1, 2 + read base and identity",
    recognition = Some(
      Recognition.ByIdentity(
        Citation(
          "contracts/README.md",
          "#recording-the-read-base",
          "A mutation that carries its read base and an identity lets a catalog, or the client, recognize that it has already been committed."
        )
      )
    )
  )

  val withReadBaseOnly: RuleSet = specWithFix1And2.copy(
    name = "spec + fixes 1, 2 + read base only",
    recognition = Some(
      Recognition.ByReadBaseAndContent(
        Citation(
          "v4-asks.md#2",
          "",
          "It also gives commits of unknown outcome, such as a timeout, a way to recognize that they have already been applied."
        )
      )
    )
  )

  /** The rule sets for slices without commits of unknown outcome, where recognition never arises. */
  val all: Vector[RuleSet] = Vector(specCurrent, specWithFix1, specWithFix1And2)

  val withRecognition: Vector[RuleSet] = all ++ Vector(withReadBaseOnly, withIdentity)
}
