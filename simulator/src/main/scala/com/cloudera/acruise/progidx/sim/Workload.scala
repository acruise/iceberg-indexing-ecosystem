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

/** Identifiers drawn from a population that turns over with time but cannot be derived from it: sessions, traces,
  * tenants, SKUs. Min/max is useless (identifiers are random 52-bit integers) and no partition transform can express
  * which keys are active when, yet a file covering a short span holds only the keys active during it.
  *
  * `initialKeys` are alive at tick 0; new keys arrive at `arrivalsPerTick`; every key lives a lognormal time with
  * median `medianLifetimeTicks` and log-scale `lifetimeSigma` (capped at e^(3 sigma) times the median). Rows written at
  * a tick are drawn from the keys active then, uniformly or, with `activityAlpha`, in proportion to Pareto(alpha)
  * weights, so a few keys write most rows. Lookups pick uniformly among the keys active at a tick of the queried
  * span, as when every tenant runs its own dashboards, so most lookups are for keys that appear in few files.
  *
  * The process is a pure function of (salt, tick), so every simulator run sees the same keys.
  */
final case class ActiveSetColumn(
    name: String,
    initialKeys: Int,
    arrivalsPerTick: Double,
    medianLifetimeTicks: Double,
    lifetimeSigma: Double,
    activityAlpha: Option[Double] = None,
    salt: Long = 0x5e55L
) extends ColumnSpec {
  require(initialKeys > 0 || arrivalsPerTick > 0)
  val defaultShape: Shape = Equality
  private val maxLifetime = medianLifetimeTicks * math.exp(3 * lifetimeSigma)

  private final class Active(val ids: Array[Double], val cumulativeWeight: Array[Double])
  /** Keys arriving at one tick (tick -1 holds the initial keys), with their activity weights and departure times. */
  private final class Cohort(val ids: Array[Double], val weights: Array[Double], val departures: Array[Double])

  private val cohorts = new java.util.concurrent.ConcurrentHashMap[Long, Cohort]()
  // Active sets are cheap to recompute from the cohorts, and a large population makes each one big, so keep few.
  // Access-ordered, so even get() mutates it: every use is synchronized, since specs are shared between simulations.
  private val cache = new java.util.LinkedHashMap[Long, Active](16, 0.75f, true) {
    override def removeEldestEntry(e: java.util.Map.Entry[Long, Active]): Boolean = size > 8
  }

  private def mix(a: Long, b: Long, c: Long): Long = {
    var z = salt * 0x9e3779b97f4a7c15L + a * 0xbf58476d1ce4e5b9L + b * 0x94d049bb133111ebL + c
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)
  }
  private def uniform(a: Long, b: Long, c: Long): Double = ((mix(a, b, c) >>> 11) + 0.5) * (1.0 / (1L << 53))

  private def lifetime(birth: Long, i: Long): Double = {
    val g = math.sqrt(-2 * math.log(uniform(birth, i, 1))) * math.cos(2 * math.Pi * uniform(birth, i, 2))
    math.min(maxLifetime, medianLifetimeTicks * math.exp(lifetimeSigma * g))
  }

  private def arrivals(tick: Long): Int =
    if (tick < 0) initialKeys
    else {
      val whole = math.floor(arrivalsPerTick).toInt
      whole + (if (uniform(tick, -1, 0) < arrivalsPerTick - whole) 1 else 0)
    }

  private def cohort(birth: Long): Cohort =
    cohorts.computeIfAbsent(
      birth,
      b => {
        val n = arrivals(b)
        new Cohort(
          Array.tabulate(n)(i => (mix(b, i.toLong, 3) & ((1L << 52) - 1)).toDouble),
          Array.tabulate(n)(i => activityAlpha.fold(1.0)(a => math.pow(uniform(b, i.toLong, 4), -1.0 / a))),
          Array.tabulate(n)(i => b + lifetime(b, i.toLong))
        )
      }
    )

  /** Keys alive at `tick`. */
  def activeAt(tick: Long): Array[Double] = active(tick).ids

  private def active(tick: Long): Active = {
    val hit = cache.synchronized(cache.get(tick))
    if (hit != null) return hit
    val ids = Array.newBuilder[Double]
    val weights = Array.newBuilder[Double]
    def collect(c: Cohort): Unit =
      for (i <- c.ids.indices if c.departures(i) > tick) {
        ids += c.ids(i)
        weights += c.weights(i)
      }
    collect(cohort(-1L))
    for (b <- math.max(0L, tick - maxLifetime.toLong) to tick) collect(cohort(b))
    val result = new Active(ids.result(), weights.result().scanLeft(0.0)(_ + _).tail)
    cache.synchronized(cache.put(tick, result))
    result
  }

  def value(rng: Random, tick: Long): Double = {
    val a = active(tick)
    val i = java.util.Arrays.binarySearch(a.cumulativeWeight, rng.nextDouble() * a.cumulativeWeight.last)
    a.ids(math.min(if (i >= 0) i else -i - 1, a.ids.length - 1))
  }

  def equality(rng: Random, fromTick: Long, untilTick: Long): Eq = {
    val ids = activeAt(tickIn(rng, fromTick, untilTick))
    Eq(ids(rng.nextInt(ids.length)))
  }

  /** Identifiers have no meaningful order, so a "range" is a lookup of one. */
  def range(rng: Random, fromTick: Long, untilTick: Long): Between = {
    val v = equality(rng, fromTick, untilTick).value
    Between(v, v)
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
    IntColumn("trace_id", 0L, 1L << 40, rangeWidth = 1e6), // dormant unless a shift asks about it
    // Sessions: ~600 active, 20 new a minute, median life half an hour; every session writes alike.
    ActiveSetColumn("session_id", initialKeys = 600, arrivalsPerTick = 20, medianLifetimeTicks = 30, lifetimeSigma = 0.5),
    // Tenants: 2000 active, a new one every three days, median life two years; a few write most rows.
    ActiveSetColumn(
      "tenant_id",
      initialKeys = 2000,
      arrivalsPerTick = 1.0 / (3 * 1440),
      medianLifetimeTicks = 2 * 365 * 1440,
      lifetimeSigma = 1.0,
      activityAlpha = Some(1.1)
    ),
    // Orders: ~6000 open, 30 new a minute, updated for a median of two hours; the primary key of a CDC stream.
    ActiveSetColumn("order_key", initialKeys = 6000, arrivalsPerTick = 30, medianLifetimeTicks = 120, lifetimeSigma = 1.0),
    // Customers: 100,000, slow churn, heavily skewed activity; the subjects of erasure requests.
    ActiveSetColumn(
      "customer_id",
      initialKeys = 100000,
      arrivalsPerTick = 100000.0 / (2 * 365 * 1440),
      medianLifetimeTicks = 2 * 365 * 1440,
      lifetimeSigma = 1.0,
      activityAlpha = Some(1.5)
    )
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

/** Every `everyTicks`, one query for a batch of `keysPerBatch` keys of `column` across every live partition, as a
  * MERGE of change data or a batch of erasure requests runs, with no partition filter. Keys are ones written during
  * the last `keyWindowTicks` (or ever, when None): the rows being updated now, or users who wrote at any time.
  */
final case class KeyBatch(
    label: String,
    column: Int,
    keysPerBatch: Int,
    everyTicks: Long,
    keyWindowTicks: Option[Long],
    from: Long = 0,
    until: Long = Long.MaxValue
) extends WorkloadShift

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
    } ++ active.collect { case b: KeyBatch if (now - b.from) % b.everyTicks == 0 => batch(b, now, current) }
  }

  private def batch(b: KeyBatch, now: Long, current: Long): Query = {
    val spec = cfg.columnSpecs(b.column)
    val from = b.keyWindowTicks.fold(0L)(w => math.max(0L, now - w))
    val keys = Seq.fill(b.keysPerBatch)(spec.equality(rng, from, now + 1).value).distinct
    val oldest = math.max(0L, current - cfg.retentionTicks / cfg.partitionTicks + 1)
    Query((oldest to current).toSeq, b.column, In(keys), Some(b.label))
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
