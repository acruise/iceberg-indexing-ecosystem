package com.cloudera.acruise.progidx.sim

/** The distinct values of one column within one data file, sorted ascending. Never empty. */
final class ColumnData private (val values: Array[Double]) {
  def distinct: Int = values.length
  def min: Double = values.head
  def max: Double = values.last

  def contains(v: Double): Boolean = java.util.Arrays.binarySearch(values, v) >= 0

  def anyIn(lo: Double, hi: Double): Boolean = {
    val i = java.util.Arrays.binarySearch(values, lo)
    i >= 0 || { val insertion = -i - 1; insertion < values.length && values(insertion) <= hi }
  }

  /** Ground truth: does any row in the file satisfy `p`? */
  def matches(p: Predicate): Boolean = p match {
    case Eq(v)           => contains(v)
    case Between(lo, hi) => anyIn(lo, hi)
  }

  /** For integer-valued columns, the fraction of integers in [min, max] absent from the file: roughly the chance an
    * equality lookup that min/max can't rule out finds nothing. None for non-integer columns.
    */
  def integralGapFraction: Option[Double] =
    if (max - min < 1e15 && values.forall(v => v == math.rint(v))) Some(1.0 - distinct / (max - min + 1))
    else None

  /** What file-level min/max statistics (free in every lake format) can't rule out. */
  def overlapsMinMax(p: Predicate): Boolean = p match {
    case Eq(v)           => v >= min && v <= max
    case Between(lo, hi) => hi >= min && lo <= max
  }
}

object ColumnData {
  def of(raw: Array[Double]): ColumnData = {
    require(raw.nonEmpty && !raw.exists(_.isNaN))
    val sorted = raw.clone()
    java.util.Arrays.sort(sorted)
    val out = Array.newBuilder[Double]
    var i = 0
    while (i < sorted.length) {
      if (i == 0 || sorted(i) != sorted(i - 1)) out += sorted(i)
      i += 1
    }
    new ColumnData(out.result())
  }

  def union(columns: Seq[ColumnData]): ColumnData = of(columns.iterator.flatMap(_.values).toArray)
}

sealed trait Predicate
final case class Eq(value: Double) extends Predicate
final case class Between(lo: Double, hi: Double) extends Predicate

/** An immutable data file. Its id plays the role of an Iceberg data-file path: unique, never reused, never mutated. */
final case class DataFile(
    id: Long,
    partition: Long,
    generation: Int,
    rows: Long,
    createdAt: Long,
    finalized: Boolean = false,
    columns: IndexedSeq[ColumnData] = IndexedSeq.empty
)

/** Identifies one index blob: (data file, index kind, column). Snapshot-agnostic, like an Iceberg v3 deletion vector. */
final case class IndexKey(fileId: Long, kind: String, column: Int)

final case class SimConfig(
    seed: Long = 42L,
    ticks: Long = 3 * 1440L,
    // ingest: one tick ~ one minute; rows are in units of 1000
    filesPerTick: Int = 4,
    rowsPerFile: Long = 100L,
    distinctValuesPerFile: Int = 64,
    partitionTicks: Long = 60L,
    retentionTicks: Long = 7 * 1440L,
    columnSpecs: IndexedSeq[ColumnSpec] = ColumnSpec.defaults,
    dormantColumns: Set[String] = Set("trace_id"),
    // compaction
    fanout: Int = 4,
    maxGeneration: Int = 3,
    settleDelayTicks: Long = 15L,
    // workload
    queriesPerTick: Int = 20,
    columnZipf: Double = 1.1,
    driftEveryTicks: Long = 1440L,
    recentQueryFraction: Double = 0.7,
    recentPartitions: Int = 2,
    historicalAgeZipf: Double = 1.0,
    statsHalfLifeTicks: Double = 240.0,
    shifts: Seq[WorkloadShift] = Seq.empty,
    // indexing
    indexKinds: Seq[IndexKind] = Seq(BloomKind(), F16Kind()),
    indexBudgetPerTick: Double = 1000.0,
    topColumns: Int = 3,
    survivalHorizonTicks: Long = 7 * 1440L,
    exclusionPrior: Double = 0.5,
    /** How many observations the static prior is worth when blended with learned exclusion rates. */
    priorStrength: Double = 10.0,
    /** Columns with at least this share of queries get the hybrid scheduler's write-time indexes. */
    hedgeMinShare: Double = 0.02,
    usefulnessAlpha: Double = 0.01,
    /** Index bytes that cost as much to fetch as scanning one row unit. */
    indexBytesPerCostUnit: Double = 4096.0,
    // Puffin
    puffinBatchBlobs: Int = 64,
    puffinMaxDelayTicks: Long = 10L,
    puffinGcLiveFraction: Double = 0.5,
    puffinRewriteCostPerUnit: Double = 1.0,
    commitCost: Double = 50.0,
    // reporting
    timelineBucketTicks: Long = 360L
) {
  def columns: Int = columnSpecs.size
}
