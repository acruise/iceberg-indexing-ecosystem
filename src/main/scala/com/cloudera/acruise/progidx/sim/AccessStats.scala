package com.cloudera.acruise.progidx.sim

import scala.collection.mutable

/** Observed read pressure and index usefulness.
  *
  * Rates are exponentially-weighted per tick. Partition heat is modelled by *age* (partitions since the current one)
  * rather than per partition, so the estimate for a partition's future is the rate observed for the ages it is about
  * to pass through.
  *
  * Usefulness is learned per file size class, since bigger files hold more distinct values and are harder to rule out:
  * how often min/max fails to prune a file, and how often a probe of each index kind then rules it out.
  */
final class AccessStats(cfg: SimConfig) {
  private val maxAge = (cfg.retentionTicks / cfg.partitionTicks).toInt + 1
  private val ageRate = Array.fill(maxAge + 1)(0.0)
  /** Queries per tick on each column touching partitions of each age: whole-table batches show up on old partitions
    * even when they are rare, because they touch every age.
    */
  private val columnAgeRate = Array.fill(cfg.columns, maxAge + 1)(0.0)
  /** Queries per tick on each column that touch every live partition (no partition predicate, as a scan report
    * shows): demand on a partition whatever its age, including ages no query has reached yet.
    */
  private val wholeTableRate = Array.fill(cfg.columns)(0.0)
  private val retainedPartitions = cfg.retentionTicks / cfg.partitionTicks
  private val columnRate = Array.fill(cfg.columns)(1.0 / cfg.columns)
  // Decayed (equality-shaped, all) predicate counts per column, so that rarely queried columns learn quickly.
  private val shapeCounts = Array.fill(cfg.columns)((0.0, 0.0))
  private val shapePriorWeight = 2.0
  private def equalityShare(column: Int): Double = {
    val (eq, n) = shapeCounts(column)
    (0.5 * shapePriorWeight + eq) / (shapePriorWeight + n)
  }
  private val minMaxSurvival = mutable.HashMap[(Int, Int), Double]()
  private val exclusion = mutable.HashMap[(String, Int, Int), (Double, Double)]() // decayed (exclusions, probes)
  private val alpha = 1.0 - math.pow(0.5, 1.0 / cfg.statsHalfLifeTicks)

  def observe(now: Long, queries: Seq[Query]): Unit = {
    val current = now / cfg.partitionTicks
    val ageCounts = new Array[Double](maxAge + 1)
    val columnCounts = new Array[Double](cfg.columns)
    val columnAgeCounts = Array.fill(cfg.columns, maxAge + 1)(0.0)
    val wholeTableCounts = new Array[Double](cfg.columns)
    val oldestRetained = math.max(0L, current - retainedPartitions + 1)
    queries.foreach { q =>
      columnCounts(q.column) += 1
      val isEq = q.predicate match { case _: Eq | _: In => 1.0; case _ => 0.0 }
      val (eq, n) = shapeCounts(q.column)
      val keep = 1.0 - cfg.usefulnessAlpha
      shapeCounts(q.column) = (eq * keep + isEq, n * keep + 1.0)
      val wholeTable = q.partitions.size >= current - oldestRetained + 1
      if (wholeTable) wholeTableCounts(q.column) += 1
      q.partitions.foreach { p =>
        val age = (current - p).toInt
        if (age >= 0 && age <= maxAge) {
          ageCounts(age) += 1
          if (!wholeTable) columnAgeCounts(q.column)(age) += 1
        }
      }
    }
    for (a <- ageRate.indices) ageRate(a) += alpha * (ageCounts(a) - ageRate(a))
    for (c <- columnAgeRate.indices; a <- ageRate.indices)
      columnAgeRate(c)(a) += alpha * (columnAgeCounts(c)(a) - columnAgeRate(c)(a))
    for (c <- wholeTableRate.indices) wholeTableRate(c) += alpha * (wholeTableCounts(c) - wholeTableRate(c))
    if (queries.nonEmpty)
      for (c <- columnRate.indices) columnRate(c) += alpha * (columnCounts(c) / queries.size - columnRate(c))
  }

  /** Distinct values per column in recently settled files: what their theta sketches report, used to size mergeable
    * indexes for the file they will eventually be merged into. Exact here; a real sketch is within a few percent.
    */
  private val settledDistinct = mutable.HashMap[Int, Double]()

  def observeSettledDistinct(column: Int, distinct: Int): Unit =
    settledDistinct(column) = settledDistinct.get(column).fold(distinct.toDouble)(old => old + 0.2 * (distinct - old))

  def settledDistinctForecast(column: Int): Option[Double] = settledDistinct.get(column)

  def observeMinMax(column: Int, rows: Long, survived: Boolean): Unit =
    ewma(minMaxSurvival, (column, AccessStats.sizeClass(rows)), 1.0, survived)

  def observeProbe(kind: String, column: Int, rows: Long, excluded: Boolean): Unit = {
    val key = (kind, column, AccessStats.sizeClass(rows))
    val (hits, n) = exclusion.getOrElse(key, (0.0, 0.0))
    val keep = 1.0 - cfg.usefulnessAlpha
    exclusion(key) = (hits * keep + (if (excluded) 1.0 else 0.0), n * keep + 1.0)
  }

  private def ewma[K](table: mutable.HashMap[K, Double], key: K, prior: Double, hit: Boolean): Unit = {
    val old = table.getOrElse(key, prior)
    table(key) = old + cfg.usefulnessAlpha * ((if (hit) 1.0 else 0.0) - old)
  }

  /** Fraction of queries that filter on `column`. */
  def columnShare(column: Int): Double = columnRate(column)

  def columnsByShare: Seq[Int] = columnRate.indices.sortBy(c => -columnRate(c))

  /** Fraction of queries on `column` whose predicate `kind` can evaluate. */
  def applicableShare(kind: IndexKind, column: Int): Double =
    (if (kind.supportsEquality) equalityShare(column) else 0.0) +
      (if (kind.supportsRange) 1.0 - equalityShare(column) else 0.0)

  /** Fraction of file-level probes of `column` that min/max statistics fail to prune. */
  def minMaxSurvivalRate(column: Int, rows: Long): Double =
    minMaxSurvival.getOrElse((column, AccessStats.sizeClass(rows)), 1.0)

  /** Estimated fraction of probes of `kind` on `column` (past min/max) that rule the file out: recent observations
    * for files of this size, blended with `prior` as though it were `priorStrength` observations.
    */
  def exclusionRate(kind: String, column: Int, rows: Long, prior: Double): Double = {
    val (hits, n) = exclusion.getOrElse((kind, column, AccessStats.sizeClass(rows)), (0.0, 0.0))
    (prior * cfg.priorStrength + hits) / (cfg.priorStrength + n)
  }

  def rateAtAge(age: Int): Double = if (age < 0 || age > maxAge) 0.0 else ageRate(age)

  /** Expected number of queries on `column` touching `partition` during [now, now + duration). */
  def expectedAccesses(column: Int, partition: Long, now: Long, duration: Double): Double =
    accumulate(partition, now, duration, age => columnAgeRate(column)(age) + wholeTableRate(column))

  /** Expected number of queries touching `partition` during [now, now + duration). */
  def expectedAccesses(partition: Long, now: Long, duration: Double): Double = accumulate(partition, now, duration, rateAtAge)

  private def accumulate(partition: Long, now: Long, duration: Double, rate: Int => Double): Double = {
    val end = now + duration
    var t = now.toDouble
    var total = 0.0
    while (t < end) {
      val bucket = math.floor(t / cfg.partitionTicks).toLong
      val age = bucket - partition
      if (age > maxAge) return total
      val next = math.min((bucket + 1).toDouble * cfg.partitionTicks, end)
      total += (if (age < 0) 0.0 else rate(age.toInt)) * (next - t)
      t = next
    }
    total
  }
}

object AccessStats {
  /** Files within a factor of 4 in rows share a class. */
  def sizeClass(rows: Long): Int = (math.log(rows.toDouble) / math.log(4)).toInt
}
