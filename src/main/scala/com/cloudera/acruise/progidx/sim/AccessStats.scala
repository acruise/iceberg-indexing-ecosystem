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
  private val columnRate = Array.fill(cfg.columns)(1.0 / cfg.columns)
  private val equalityShare = Array.fill(cfg.columns)(0.5)
  private val minMaxSurvival = mutable.HashMap[(Int, Int), Double]()
  private val exclusion = mutable.HashMap[(String, Int, Int), (Double, Double)]() // decayed (exclusions, probes)
  private val alpha = 1.0 - math.pow(0.5, 1.0 / cfg.statsHalfLifeTicks)

  def observe(now: Long, queries: Seq[Query]): Unit = {
    val current = now / cfg.partitionTicks
    val ageCounts = new Array[Double](maxAge + 1)
    val columnCounts = new Array[Double](cfg.columns)
    queries.foreach { q =>
      columnCounts(q.column) += 1
      val isEq = q.predicate match { case _: Eq => 1.0; case _ => 0.0 }
      equalityShare(q.column) += cfg.usefulnessAlpha * (isEq - equalityShare(q.column))
      q.partitions.foreach { p =>
        val age = (current - p).toInt
        if (age >= 0 && age <= maxAge) ageCounts(age) += 1
      }
    }
    for (a <- ageRate.indices) ageRate(a) += alpha * (ageCounts(a) - ageRate(a))
    if (queries.nonEmpty)
      for (c <- columnRate.indices) columnRate(c) += alpha * (columnCounts(c) / queries.size - columnRate(c))
  }

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

  /** Expected number of queries touching `partition` during [now, now + duration). */
  def expectedAccesses(partition: Long, now: Long, duration: Double): Double = {
    val end = now + duration
    var t = now.toDouble
    var total = 0.0
    while (t < end) {
      val bucket = math.floor(t / cfg.partitionTicks).toLong
      val age = bucket - partition
      if (age > maxAge) return total
      val next = math.min((bucket + 1).toDouble * cfg.partitionTicks, end)
      total += rateAtAge(age.toInt) * (next - t)
      t = next
    }
    total
  }
}

object AccessStats {
  /** Files within a factor of 4 in rows share a class. */
  def sizeClass(rows: Long): Int = (math.log(rows.toDouble) / math.log(4)).toInt
}
