package com.cloudera.acruise.progidx.sim

/** What a query pays to read one data file, in units of transferring one row unit of one column.
  *
  * Reads are ranged requests against an object store, where a request's latency is worth `requestCost` units of
  * transfer. A file that survives planning costs a footer request; then, per row group, the predicate column's chunk;
  * then the other projected columns' chunks, for every row group or, with `lateMaterialization` (Trino, Impala), only
  * for row groups holding a match. Row groups are never skipped by footer statistics: the values are not clustered.
  */
object CostModel {
  def rowGroups(cfg: SimConfig, f: DataFile): Int = math.max(1, math.ceil(f.rows.toDouble / cfg.rowGroupRows).toInt)

  /** Distinct values of `col` that satisfy `p`. */
  def matchingValues(col: ColumnData, p: Predicate): Int = p match {
    case Eq(v)           => if (col.contains(v)) 1 else 0
    case In(vs)          => vs.count(col.contains)
    case Between(lo, hi) => col.countIn(lo, hi)
  }

  /** Expected row groups holding a match, if each matching value sits in one row group chosen at random. */
  def matchingRowGroups(cfg: SimConfig, f: DataFile, col: ColumnData, p: Predicate): Double = {
    val m = matchingValues(col, p)
    val g = rowGroups(cfg, f).toDouble
    if (m == 0) 0.0 else g * (1 - math.pow(1 - 1 / g, m.toDouble))
  }

  private def otherColumnGroups(cfg: SimConfig, f: DataFile, matchedGroups: Double): Double =
    (cfg.projectedColumns - 1) * (if (cfg.lateMaterialization) matchedGroups else rowGroups(cfg, f).toDouble)

  /** Requests a scan of `f` makes. */
  def scanRequests(cfg: SimConfig, f: DataFile, col: ColumnData, p: Predicate): Double =
    1 + rowGroups(cfg, f) + otherColumnGroups(cfg, f, matchingRowGroups(cfg, f, col, p))

  /** What scanning `f` for `p` costs. */
  def scan(cfg: SimConfig, f: DataFile, col: ColumnData, p: Predicate): Double = {
    val g = rowGroups(cfg, f)
    val chunk = cfg.requestCost + f.rows.toDouble / g
    cfg.requestCost + (g + otherColumnGroups(cfg, f, matchingRowGroups(cfg, f, col, p))) * chunk
  }

  /** What excluding a file with no match saves: everything a scan of it would have read. */
  def scanWithoutMatch(cfg: SimConfig, f: DataFile): Double = {
    val g = rowGroups(cfg, f)
    val chunk = cfg.requestCost + f.rows.toDouble / g
    cfg.requestCost + (g + otherColumnGroups(cfg, f, 0.0)) * chunk
  }

  /** The expected cost of probing an index of `sizeBytes`: its bytes, and a share of the request for its Puffin
    * file, which serves every blob a query needs from it.
    */
  def probe(cfg: SimConfig, sizeBytes: Double): Double =
    sizeBytes / cfg.indexBytesPerCostUnit + cfg.requestCost * cfg.indexRequestShare
}
