package com.cloudera.acruise.histories

/** Register entry E3: whether anyone but the writer can tell, afterward, that a commit was invalid. The auditor sees only
  * retained table metadata (snapshots, their changes, file paths, partitions, sequence numbers, record counts and
  * deletion vector cardinalities), never the rows, and judges each snapshot against its parent.
  *
  * With parent pointers only, as today, it can catch a deletion vector that names a file not live in its own snapshot,
  * and a `replace` that changed the live row count where no equality delete makes that count unknowable from metadata.
  * With the read base recorded (version 4 ask 2) it can also re-run the retry conditions of prose fix 1 over the window
  * from the read base to the parent, which needs that window to be retained. With an identity recorded as well, it can
  * see one operation committed twice. A commit's intent is not recorded, so a removal that found its file already gone
  * leaves no trace in any mode.
  */
object Auditor {
  sealed abstract class Mode(val name: String)
  object Mode {
    case object ParentsOnly extends Mode("parent pointers only")
    case object WithReadBase extends Mode("read base recorded")
    case object WithIdentity extends Mode("read base and identity")
    val all: Vector[Mode] = Vector(ParentsOnly, WithReadBase, WithIdentity)
  }

  final case class Flag(snapshot: Int, reason: String)
  final case class Audit(flags: Vector[Flag], unauditable: Int) {
    def flagged: Boolean = flags.nonEmpty
  }

  /** The live row count as metadata alone gives it, or `None` when an equality delete applies to a live file. */
  def metadataCount(s: Snapshot): Option[Int] =
    if (s.eqDeletes.exists(e => s.liveFiles.exists(e.appliesTo))) None
    else
      Some(s.liveFiles.map { f =>
        val deleted = s.dvs.get(f.path).filter(dv => dv.partition == f.partition && f.dataSeq <= dv.dataSeq)
        f.rows.size - deleted.map(_.positions.size).getOrElse(0)
      }.sum)

  /** The plan a snapshot's recorded changes imply. Removals that found nothing to remove are not recorded. */
  def reconstruct(s: Snapshot): Plan =
    Plan(
      op = s.op,
      removedFiles = s.delta.removedFiles,
      addedRows = s.delta.addedFiles.map(f => f.partition -> f.rows),
      outputSeq = if (s.delta.addedFiles.exists(_.dataSeq < s.seq)) OutputSeq.Base else OutputSeq.New,
      dvs = s.delta.dvs.map(d => PlannedDv(d.referencedFile, d.partition, d.positions)),
      eqDeletes = s.delta.eqDeletes.map(e => PlannedEq(e.partition, e.ids))
    )

  def audit(o: Outcome, mode: Mode): Audit = {
    val byId = o.log.map(s => s.id -> s).toMap
    var flags = Vector.empty[Flag]
    var unauditable = 0
    o.log.sliding(2).foreach {
      case Vector(p, s) if o.expired(p.id) || o.expired(s.id) => unauditable += 1
      case Vector(p, s) =>
        if (s.delta.dvs.exists(d => !s.files.contains(d.referencedFile)))
          flags :+= Flag(s.id, "deletion vector for a file that is not live")
        (metadataCount(p), metadataCount(s)) match {
          case (Some(a), Some(b)) if s.op == SnapshotOp.Replace && a != b =>
            flags :+= Flag(s.id, s"replace changed the live row count from $a to $b")
          case _ => ()
        }
        if (mode == Mode.WithIdentity && s.commitId.exists(id => o.log.exists(x => x.id < s.id && !o.expired(x.id) && x.commitId.contains(id))))
          flags :+= Flag(s.id, "an operation committed twice")
        if (mode != Mode.ParentsOnly && s.readBase != p.id) {
          val window = o.log.filter(x => x.id > s.readBase && x.id <= p.id)
          if (o.expired(s.readBase) || window.exists(x => o.expired(x.id))) unauditable += 1
          else
            RuleSets.specWithFix1.judge(RetryContext(reconstruct(s), byId(s.readBase), p, window)) match {
              case Verdict.MustFail(cs) => flags :+= Flag(s.id, s"fails ${cs.mkString(", ")} against its read base")
              case _                    => ()
            }
        }
      case _ => ()
    }
    Audit(flags, unauditable)
  }
}
