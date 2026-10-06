package progidx.index

import scala.util.Random

class F16CodeSuite extends munit.FunSuite {
  private val allHalves: Seq[Float] =
    (0 until F16Code.Count).map(bits => java.lang.Float.float16ToFloat(bits.toShort)).filterNot(_.isNaN)

  test("code order matches numeric order across every non-NaN half") {
    val byValue = allHalves.distinct.sortBy(identity) // Scala's == merges -0f and 0f, which also share a code
    val codes = byValue.map(F16Code.ofFloat)
    assertEquals(codes, codes.sorted)
    assertEquals(codes.distinct.size, codes.size)
  }

  test("code round-trips through toFloat") {
    allHalves.foreach { h =>
      val c = F16Code.ofFloat(h)
      assertEquals(F16Code.ofFloat(F16Code.toFloat(c)), c, s"half $h")
    }
  }

  test("-0 and +0 share a code; overflow saturates to infinity") {
    assertEquals(F16Code.ofFloat(-0.0f), F16Code.ofFloat(0.0f))
    assertEquals(F16Code.ofDouble(1e6), F16Code.ofFloat(Float.PositiveInfinity))
    assertEquals(F16Code.ofDouble(-1e300), F16Code.ofFloat(Float.NegativeInfinity))
  }
}

class F16PresenceSuite extends munit.FunSuite {
  private val rng = new Random(7)

  private def distributions: Seq[(String, Array[Double])] = Seq(
    "gaussian" -> Array.fill(5000)(rng.nextGaussian() * 100),
    "uniform-wide" -> Array.fill(5000)((rng.nextDouble() - 0.5) * 1e5),
    "tiny" -> Array.fill(5000)(rng.nextGaussian() * 1e-6),
    "few-distinct" -> Array.fill(5000)(rng.nextInt(20).toDouble),
    "with-specials" -> (Array.fill(1000)(rng.nextGaussian()) ++ Array(-0.0, 0.0, Double.PositiveInfinity, Double.NaN))
  )

  test("no false negatives for equality") {
    distributions.foreach { case (name, values) =>
      val idx = F16Presence.of(values)
      values.filterNot(_.isNaN).foreach(v => assert(idx.mightEqual(v), s"$name: missed $v"))
    }
  }

  test("no false negatives for ranges") {
    distributions.foreach { case (name, raw) =>
      val values = raw.filterNot(_.isNaN).sorted
      val idx = F16Presence.of(raw)
      for (_ <- 0 until 2000) {
        val a = values(rng.nextInt(values.length)) + rng.nextGaussian()
        val b = a + math.abs(rng.nextGaussian()) * (values.last - values.head) / 100
        val (lo, hi) = (math.min(a, b), math.max(a, b))
        if (values.exists(v => v >= lo && v <= hi)) assert(idx.mightIntersect(lo, hi), s"$name: missed [$lo, $hi]")
      }
    }
  }

  test("prunes values and ranges that are absent") {
    val idx = F16Presence.of(Seq(1.0, 2.0, 3.0, 100.0))
    assert(!idx.mightEqual(50.0))
    assert(!idx.mightIntersect(4.0, 99.0))
    assert(idx.mightIntersect(4.0, 100.0))
    assert(!idx.mightIntersect(5.0, 4.0))
    assertEquals(idx.minQuantized, Some(1.0f))
    assertEquals(idx.maxQuantized, Some(100.0f))
  }

  test("NaN is tracked but never matches") {
    val idx = F16Presence.of(Seq(Double.NaN))
    assert(idx.hasNaN)
    assert(idx.isEmpty)
    assert(!idx.mightEqual(Double.NaN))
    assert(!idx.mightIntersect(Double.NegativeInfinity, Double.PositiveInfinity))
  }

  test("switches from array to bitmap above 4096 codes") {
    val codes = allCodesShuffled
    def idxOf(n: Int) = F16Presence.ofFloats(codes.take(n).map(F16Code.toFloat))
    val sparse = idxOf(Container.ArrayMax)
    val dense = idxOf(Container.ArrayMax + 1)
    assert(!sparse.isBitmap)
    assert(dense.isBitmap)
    assertEquals(sparse.sizeInBytes, F16Presence.HeaderBytes + 2 * Container.ArrayMax)
    assertEquals(dense.sizeInBytes, F16Presence.HeaderBytes + 8192)
  }

  test("bitmap range checks agree with brute force") {
    val members = allCodesShuffled.take(20000).toSet
    val idx = F16Presence.ofFloats(members.toArray.map(F16Code.toFloat))
    assert(idx.isBitmap)
    val (minCode, maxCode) = (F16Code.ofFloat(Float.NegativeInfinity), F16Code.ofFloat(Float.PositiveInfinity))
    for (_ <- 0 until 5000) {
      val a = minCode + rng.nextInt(maxCode - minCode + 1)
      val b = math.min(maxCode, a + rng.nextInt(200))
      if (a != 0x7fff && b != 0x7fff) { // 0x7fff would be -0, which is normalized to +0's code
        val expected = (a to b).exists(members.contains)
        assertEquals(idx.mightIntersect(F16Code.toFloat(a), F16Code.toFloat(b)), expected, s"[$a, $b]")
      }
    }
  }

  test("OR-merge equals the index of the union") {
    val (a, b) = (Array.fill(3000)(rng.nextGaussian()), Array.fill(3000)(rng.nextGaussian() * 50))
    assertEquals(F16Presence.of(a).or(F16Presence.of(b)), F16Presence.of(a ++ b))
  }

  test("serialization round-trips both representations") {
    Seq(F16Presence.of(Seq(1.0, -2.0, Double.NaN)), F16Presence.of(Array.fill(50000)(rng.nextGaussian() * 1000))).foreach {
      idx =>
        val bytes = idx.toBytes
        assertEquals(bytes.length, idx.sizeInBytes)
        assertEquals(F16Presence.fromBytes(bytes), idx)
    }
  }

  private def allCodesShuffled: Array[Int] =
    rng.shuffle((0 until F16Code.Count).filter(c => !F16Code.toFloat(c).isNaN && c != 0x7fff).toVector).toArray
}
