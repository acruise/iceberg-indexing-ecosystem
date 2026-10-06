package progidx.index

/** A set of 16-bit codes, stored like a Roaring container: a sorted array while sparse, a 65536-bit bitmap (8 KiB)
  * once the array would be bigger than that.
  */
private[index] sealed trait Container {
  def cardinality: Int
  def contains(code: Int): Boolean

  /** True if any member lies in [lo, hi]. Requires lo <= hi. */
  def intersects(lo: Int, hi: Int): Boolean
  def min: Int
  def max: Int
  def words: Array[Long]
  def sizeInBytes: Int
}

private[index] object Container {
  /** Above this many codes the array (2 bytes each) is bigger than the bitmap. */
  val ArrayMax: Int = 4096
  val Words: Int = F16Code.Count / 64

  def fromWords(words: Array[Long]): Container = {
    require(words.length == Words)
    val cardinality = words.iterator.map(java.lang.Long.bitCount).sum
    if (cardinality > ArrayMax) new BitmapContainer(words.clone(), cardinality)
    else {
      val codes = new Array[Char](cardinality)
      var n = 0
      for (w <- words.indices) {
        var bits = words(w)
        while (bits != 0) {
          codes(n) = (w * 64 + java.lang.Long.numberOfTrailingZeros(bits)).toChar
          n += 1
          bits &= bits - 1
        }
      }
      new ArrayContainer(codes)
    }
  }

  def fromCodes(codes: Array[Char]): Container = {
    val words = new Array[Long](Words)
    codes.foreach(c => words(c >>> 6) |= 1L << c)
    fromWords(words)
  }
}

private[index] final class ArrayContainer(val codes: Array[Char]) extends Container {
  def cardinality: Int = codes.length
  def contains(code: Int): Boolean = java.util.Arrays.binarySearch(codes, code.toChar) >= 0

  def intersects(lo: Int, hi: Int): Boolean = {
    val i = java.util.Arrays.binarySearch(codes, lo.toChar)
    if (i >= 0) true
    else {
      val insertion = -i - 1
      insertion < codes.length && codes(insertion) <= hi
    }
  }

  def min: Int = codes.head.toInt
  def max: Int = codes.last.toInt

  def words: Array[Long] = {
    val w = new Array[Long](Container.Words)
    codes.foreach(c => w(c >>> 6) |= 1L << c)
    w
  }

  def sizeInBytes: Int = 2 * codes.length
}

private[index] final class BitmapContainer(val words: Array[Long], val cardinality: Int) extends Container {
  def contains(code: Int): Boolean = ((words(code >>> 6) >>> code) & 1L) != 0

  def intersects(lo: Int, hi: Int): Boolean = {
    val (wl, wh) = (lo >>> 6, hi >>> 6)
    val loMask = -1L << lo // Java masks long shift distances to 6 bits
    val hiMask = -1L >>> (63 - (hi & 63))
    if (wl == wh) (words(wl) & loMask & hiMask) != 0
    else
      (words(wl) & loMask) != 0 ||
      (wl + 1 until wh).exists(words(_) != 0) ||
      (words(wh) & hiMask) != 0
  }

  def min: Int = {
    val w = words.indexWhere(_ != 0)
    w * 64 + java.lang.Long.numberOfTrailingZeros(words(w))
  }

  def max: Int = {
    val w = words.lastIndexWhere(_ != 0)
    w * 64 + 63 - java.lang.Long.numberOfLeadingZeros(words(w))
  }

  def sizeInBytes: Int = 8 * Container.Words
}
