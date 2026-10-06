package com.cloudera.acruise.progidx.sim

import scala.util.Random

sealed trait Shape
case object Equality extends Shape
case object RangeShape extends Shape

/** A query with one predicate on one column over a set of partitions. Every live file in them is a candidate.
  * `tag` names the workload shift that produced or reshaped the query, if any.
  */
final case class Query(partitions: Seq[Long], column: Int, predicate: Predicate, tag: Option[String] = None)

/** How a column's values are distributed, and what queries of each shape ask of it. */
sealed trait ColumnSpec {
  def name: String
  def defaultShape: Shape

  /** A value written at `tick`. */
  def value(rng: Random, tick: Long): Double

  /** Predicates from a query over data written in [fromTick, untilTick). */
  def equality(rng: Random, fromTick: Long, untilTick: Long): Eq
  def range(rng: Random, fromTick: Long, untilTick: Long): Between

  def predicate(rng: Random, fromTick: Long, untilTick: Long, shape: Shape): Predicate = shape match {
    case Equality   => equality(rng, fromTick, untilTick)
    case RangeShape => range(rng, fromTick, untilTick)
  }

  def predicate(rng: Random, fromTick: Long, untilTick: Long): Predicate = predicate(rng, fromTick, untilTick, defaultShape)

  protected def tickIn(rng: Random, fromTick: Long, untilTick: Long): Long =
    fromTick + (rng.nextDouble() * math.max(1L, untilTick - fromTick)).toLong
}

/** Continuous measurements rounded to `step`; ranges of width `rangeWidth` by default, or lookups of exact readings. */
final case class GaussianColumn(name: String, mean: Double, std: Double, step: Double, rangeWidth: Double)
    extends ColumnSpec {
  val defaultShape: Shape = RangeShape
  def value(rng: Random, tick: Long): Double = math.rint((mean + std * rng.nextGaussian()) / step) * step
  def equality(rng: Random, fromTick: Long, untilTick: Long): Eq = Eq(value(rng, fromTick))
  def range(rng: Random, fromTick: Long, untilTick: Long): Between = {
    val center = mean + std * rng.nextGaussian()
    Between(center - rangeWidth / 2, center + rangeWidth / 2)
  }
}

/** Uniform integers in [lo, hi]; point lookups by default, or ranges [v, v + rangeWidth]. */
final case class IntColumn(name: String, lo: Long, hi: Long, rangeWidth: Double) extends ColumnSpec {
  val defaultShape: Shape = Equality
  private def draw(rng: Random): Double = (lo + (rng.nextDouble() * (hi - lo + 1)).toLong).toDouble
  def value(rng: Random, tick: Long): Double = draw(rng)
  def equality(rng: Random, fromTick: Long, untilTick: Long): Eq = Eq(draw(rng))
  def range(rng: Random, fromTick: Long, untilTick: Long): Between = {
    val v = draw(rng)
    Between(v, v + rangeWidth)
  }
}

/** Values that drift with write time (perTick * tick + noise); ranges around the queried period by default. */
final case class TrendColumn(name: String, perTick: Double, noise: Double, rangeWidth: Double) extends ColumnSpec {
  val defaultShape: Shape = RangeShape
  def value(rng: Random, tick: Long): Double = perTick * tick + noise * rng.nextGaussian()
  def equality(rng: Random, fromTick: Long, untilTick: Long): Eq = Eq(value(rng, tickIn(rng, fromTick, untilTick)))
  def range(rng: Random, fromTick: Long, untilTick: Long): Between = {
    val center = value(rng, tickIn(rng, fromTick, untilTick))
    Between(center - rangeWidth / 2, center + rangeWidth / 2)
  }
}

object ColumnSpec {
  val defaults: IndexedSeq[ColumnSpec] = IndexedSeq(
    GaussianColumn("temperature", mean = 20, std = 15, step = 0.01, rangeWidth = 0.5),
    IntColumn("status", 0, 999, rangeWidth = 10),
    IntColumn("user_id", 1000000000L, 1100000000L, rangeWidth = 1e5),
    TrendColumn("sensor_drift", perTick = 0.05, noise = 2.0, rangeWidth = 1.0),
    GaussianColumn("price", mean = 1000, std = 300, step = 0.01, rangeWidth = 5.0),
    IntColumn("region", 0, 63, rangeWidth = 2),
    IntColumn("order_id", 5000000000000L, 5010000000000L, rangeWidth = 1e7),
    TrendColumn("elapsed", perTick = 1.0, noise = 30.0, rangeWidth = 20.0),
    IntColumn("trace_id", 0L, 1L << 40, rangeWidth = 1e6) // dormant unless a shift asks about it
  )

  def indexOf(name: String, specs: IndexedSeq[ColumnSpec] = defaults): Int = {
    val i = specs.indexWhere(_.name == name)
    require(i >= 0, s"no column named $name")
    i
  }
}

/** A scheduled change in what queries ask, active during [from, until). */
sealed trait WorkloadShift {
  def label: String
  def from: Long
  def until: Long
  def activeAt(tick: Long): Boolean = tick >= from && tick < until
}

/** A fraction `share` of queries go to `column`, over the usual partitions. */
final case class HotColumn(label: String, column: Int, share: Double, from: Long, until: Long = Long.MaxValue)
    extends WorkloadShift

/** Queries on `column` switch to predicates of `shape`. */
final case class ShapeChange(label: String, column: Int, shape: Shape, from: Long, until: Long = Long.MaxValue)
    extends WorkloadShift

/** A fraction `share` of queries ask about `column` in partitions aged [minAge, maxAge]. */
final case class Forensic(label: String, column: Int, share: Double, minAge: Int, maxAge: Int, from: Long, until: Long)
    extends WorkloadShift

/** Mostly-recent queries plus a long tail of historical ones; column popularity is Zipf and rotates every
  * `driftEveryTicks` (dormant columns are never queried); scheduled `shifts` override that. Owns its own RNG so that every scheduler sees the identical
  * query stream.
  */
final class Workload(cfg: SimConfig) {
  private val rng = new Random(cfg.seed)
  private val usualColumns = (0 until cfg.columns).filterNot(c => cfg.dormantColumns(cfg.columnSpecs(c).name))
  private val columnZipf = new Zipf(usualColumns.size, cfg.columnZipf)
  private var ageZipf: Option[Zipf] = None

  def queries(now: Long): Seq[Query] = {
    val current = now / cfg.partitionTicks
    val epoch = (now / cfg.driftEveryTicks).toInt
    val oldestAge = math.min(current, cfg.retentionTicks / cfg.partitionTicks - 1).toInt
    val historicalAges = oldestAge - cfg.recentPartitions + 1
    val active = cfg.shifts.filter(_.activeAt(now))
    val forensics = active.collect { case f: Forensic => f }
    val hot = active.collect { case h: HotColumn => h }
    val shapes = active.collect { case s: ShapeChange => s }

    def usualPartitions(): Seq[Long] =
      if (historicalAges <= 0 || rng.nextDouble() < cfg.recentQueryFraction)
        (0 until cfg.recentPartitions).map(current - _).filter(_ >= 0)
      else Seq(current - cfg.recentPartitions - historicalZipf(historicalAges).sample(rng))

    def usualColumn(): Int = usualColumns((columnZipf.sample(rng) + epoch) % usualColumns.size)

    Seq.fill(cfg.queriesPerTick) {
      val (column, partitions, tag) = forensics.find(f => rng.nextDouble() < f.share) match {
        case Some(f) =>
          val age = f.minAge + rng.nextInt(f.maxAge - f.minAge + 1)
          (f.column, Seq(math.max(0L, current - age)), Some(f.label))
        case None =>
          hot.find(h => rng.nextDouble() < h.share) match {
            case Some(h) => (h.column, usualPartitions(), Some(h.label))
            case None    => (usualColumn(), usualPartitions(), None)
          }
      }
      val reshape = shapes.reverseIterator.find(_.column == column)
      val shape = reshape.map(_.shape).getOrElse(cfg.columnSpecs(column).defaultShape)
      val from = partitions.min * cfg.partitionTicks
      val until = math.min((partitions.max + 1) * cfg.partitionTicks, now + 1)
      val predicate = cfg.columnSpecs(column).predicate(rng, from, until, shape)
      Query(partitions, column, predicate, tag.orElse(reshape.map(_.label)))
    }
  }

  private def historicalZipf(n: Int): Zipf = ageZipf match {
    case Some(z) if z.n == n => z
    case _ =>
      val z = new Zipf(n, cfg.historicalAgeZipf)
      ageZipf = Some(z)
      z
  }
}

/** Zipf over ranks 0 until n. */
final class Zipf(val n: Int, s: Double) {
  require(n > 0)
  private val cdf: Array[Double] = {
    val weights = (1 to n).map(k => 1.0 / math.pow(k.toDouble, s))
    val total = weights.sum
    weights.scanLeft(0.0)(_ + _).tail.map(_ / total).toArray
  }

  def sample(rng: Random): Int = {
    val i = java.util.Arrays.binarySearch(cdf, rng.nextDouble())
    math.min(if (i >= 0) i else -i - 1, n - 1)
  }
}
