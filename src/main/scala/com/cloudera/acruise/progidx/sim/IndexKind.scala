package com.cloudera.acruise.progidx.sim

import com.cloudera.acruise.progidx.f16presence.{F16Code, F16Presence}

import scala.util.hashing.MurmurHash3

/** A built index for one column of one data file. */
sealed trait IndexData { def sizeBytes: Int }
final case class BloomData(sizeBytes: Int) extends IndexData
final case class F16Data(index: F16Presence) extends IndexData { def sizeBytes: Int = index.sizeInBytes }

/** A kind of index that can be built for one column of one data file and stored as a Puffin blob. */
sealed trait IndexKind {
  def name: String
  def buildCostPerRow: Double
  def supportsEquality: Boolean
  def supportsRange: Boolean
  def mergeable: Boolean

  def supports(p: Predicate): Boolean = p match {
    case _: Eq      => supportsEquality
    case _: Between => supportsRange
  }

  /** How well this kind could possibly discriminate on `col`, in [0, 1], judged from cheap per-file statistics
    * (min, max, distinct count) before building it. Scales the prior exclusion rate.
    */
  def staticUsefulness(col: ColumnData): Double

  /** Size the index would have, knowable from cheap per-file statistics (distinct count) before building it. */
  def estimatedSizeBytes(col: ColumnData): Int

  def build(col: ColumnData): IndexData

  /** Compose the indexes of a compaction's inputs into the output's index without rereading data. */
  def merge(inputs: Seq[IndexData]): IndexData = throw new UnsupportedOperationException(s"$name is not mergeable")

  /** False only if the index proves no row matches `p`. `col` is the ground truth, which only probabilistic
    * structures consult, and `salt` makes their randomness deterministic per (file, column).
    */
  def mightMatch(data: IndexData, col: ColumnData, p: Predicate, salt: Long): Boolean
}

/** Equality-only; sized for its false-positive rate at the file's distinct count, so not mergeable across files. */
final case class BloomKind(falsePositiveRate: Double = 0.01, buildCostPerRow: Double = 1.0) extends IndexKind {
  val name = "bloom"
  val supportsEquality = true
  val supportsRange = false
  val mergeable = false
  private val bitsPerValue = -math.log(falsePositiveRate) / (math.log(2) * math.log(2))

  def staticUsefulness(col: ColumnData): Double = col.integralGapFraction.getOrElse(1.0)
  def estimatedSizeBytes(col: ColumnData): Int = math.ceil(col.distinct * bitsPerValue / 8).toInt
  def build(col: ColumnData): IndexData = BloomData(estimatedSizeBytes(col))

  def mightMatch(data: IndexData, col: ColumnData, p: Predicate, salt: Long): Boolean = p match {
    case Eq(v) => col.contains(v) || BloomKind.uniform(salt, v) < falsePositiveRate
    case _     => true
  }
}

object BloomKind {
  private[sim] def uniform(salt: Long, v: Double): Double = {
    import MurmurHash3._
    val bits = java.lang.Double.doubleToLongBits(v)
    val h = mixLast(mix(mix(mix(0x9747b28c, salt.toInt), (salt >>> 32).toInt), bits.toInt), (bits >>> 32).toInt)
    (finalizeHash(h, 4).toLong & 0xffffffffL).toDouble / 4294967296.0
  }
}

/** The f16 presence index: equality and range, at most 8 KiB, merged by OR. */
final case class F16Kind(buildCostPerRow: Double = 1.0) extends IndexKind {
  val name = "f16"
  val supportsEquality = true
  val supportsRange = true
  val mergeable = true

  /** The fraction of f16 codes between min and max that the file's values can't occupy. Zero when the values collapse
    * into a single code (e.g. everything above 65504 becomes infinity), or when there are more values than codes.
    * For integer columns it can't beat the fraction of integers absent from [min, max].
    */
  def staticUsefulness(col: ColumnData): Double = {
    val span = F16Code.ofDouble(col.max) - F16Code.ofDouble(col.min) + 1
    val codeGap = 1.0 - math.min(col.distinct, span).toDouble / span
    col.integralGapFraction.fold(codeGap)(math.min(codeGap, _))
  }

  def estimatedSizeBytes(col: ColumnData): Int = F16Presence.HeaderBytes + math.min(2 * col.distinct, 8192)
  def build(col: ColumnData): IndexData = F16Data(F16Presence.of(col.values))

  override def merge(inputs: Seq[IndexData]): IndexData =
    F16Data(inputs.map { case F16Data(i) => i; case other => sys.error(s"not an f16 index: $other") }.reduce(_ or _))

  def mightMatch(data: IndexData, col: ColumnData, p: Predicate, salt: Long): Boolean = (data, p) match {
    case (F16Data(idx), Eq(v))           => idx.mightEqual(v)
    case (F16Data(idx), Between(lo, hi)) => idx.mightIntersect(lo, hi)
    case _                               => sys.error(s"not an f16 index: $data")
  }
}
