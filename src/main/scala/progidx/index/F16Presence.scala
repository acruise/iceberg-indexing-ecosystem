package progidx.index

import java.nio.ByteBuffer

/** Which half-precision codes occur in a column: a filter for equality and range predicates with no false negatives.
  *
  * At most 8 KiB (less while sparse), with min/max in O(1) for the array form. Two indexes merge by OR, so an index on
  * a compacted file can be built from its inputs' indexes without rereading data.
  *
  * Precision is fixed by f16: about 0.1% relative, and everything above 65504 in magnitude collapses into infinity.
  * Columns of large-magnitude values (timestamps, ids, amounts in cents) need a different monotone quantizer.
  */
final class F16Presence private (private val container: Container, val hasNaN: Boolean) {
  def isEmpty: Boolean = container.cardinality == 0
  def distinctCodes: Int = container.cardinality
  def isBitmap: Boolean = container.isInstanceOf[BitmapContainer]

  /** False only if no value in the column can equal `v`. NaN equals nothing. */
  def mightEqual(v: Double): Boolean = !v.isNaN && !isEmpty && container.contains(F16Code.ofDouble(v))

  /** False only if no value in the column lies in [lo, hi]. Open-ended ranges use infinities. Exclusive bounds must be
    * passed as inclusive: a quantized bound's bucket can hold values on either side of it.
    */
  def mightIntersect(lo: Double, hi: Double): Boolean = {
    require(!lo.isNaN && !hi.isNaN, "range bounds must not be NaN")
    lo <= hi && !isEmpty && container.intersects(F16Code.ofDouble(lo), F16Code.ofDouble(hi))
  }

  /** The smallest value present, rounded to half precision. */
  def minQuantized: Option[Float] = if (isEmpty) None else Some(F16Code.toFloat(container.min))

  /** The largest value present, rounded to half precision. */
  def maxQuantized: Option[Float] = if (isEmpty) None else Some(F16Code.toFloat(container.max))

  def or(that: F16Presence): F16Presence = {
    val words = container.words.clone()
    val other = that.container.words
    for (i <- words.indices) words(i) |= other(i)
    new F16Presence(Container.fromWords(words), hasNaN || that.hasNaN)
  }

  def sizeInBytes: Int = F16Presence.HeaderBytes + container.sizeInBytes

  /** Serialized form, suitable as a Puffin blob payload:
    * version (1 byte), flags (1 byte: bit 0 hasNaN, bit 1 bitmap), count (4 bytes, big-endian), then either `count`
    * sorted big-endian u16 codes or 1024 big-endian 64-bit bitmap words.
    */
  def toBytes: Array[Byte] = {
    val buf = ByteBuffer.allocate(sizeInBytes)
    buf.put(F16Presence.Version)
    buf.put(((if (hasNaN) 1 else 0) | (if (isBitmap) 2 else 0)).toByte)
    buf.putInt(container.cardinality)
    container match {
      case a: ArrayContainer  => a.codes.foreach(buf.putChar)
      case b: BitmapContainer => b.words.foreach(buf.putLong)
    }
    buf.array()
  }

  override def equals(other: Any): Boolean = other match {
    case that: F16Presence =>
      hasNaN == that.hasNaN && java.util.Arrays.equals(container.words, that.container.words)
    case _ => false
  }

  override def hashCode: Int = java.util.Arrays.hashCode(container.words) * 31 + hasNaN.hashCode

  override def toString: String =
    s"F16Presence(codes=$distinctCodes, ${if (isBitmap) "bitmap" else "array"}, nan=$hasNaN, " +
      s"min=${minQuantized.getOrElse("-")}, max=${maxQuantized.getOrElse("-")})"
}

object F16Presence {
  val Version: Byte = 1
  val HeaderBytes: Int = 6

  final class Builder {
    private val words = new Array[Long](Container.Words)
    private var nan = false

    def add(v: Float): this.type = {
      if (v.isNaN) nan = true
      else {
        val c = F16Code.ofFloat(v)
        words(c >>> 6) |= 1L << c
      }
      this
    }

    def add(v: Double): this.type = add(v.toFloat)

    def result(): F16Presence = new F16Presence(Container.fromWords(words), nan)
  }

  def builder: Builder = new Builder

  def of(values: IterableOnce[Double]): F16Presence = {
    val b = builder
    values.iterator.foreach(b.add)
    b.result()
  }

  def ofFloats(values: Array[Float]): F16Presence = {
    val b = builder
    values.foreach(b.add)
    b.result()
  }

  def fromBytes(bytes: Array[Byte]): F16Presence = {
    val buf = ByteBuffer.wrap(bytes)
    val version = buf.get()
    require(version == Version, s"unsupported F16Presence version $version")
    val flags = buf.get()
    val count = buf.getInt()
    val container =
      if ((flags & 2) != 0) {
        val words = Array.fill(Container.Words)(buf.getLong())
        new BitmapContainer(words, count)
      } else new ArrayContainer(Array.fill(count)(buf.getChar()))
    require(!buf.hasRemaining, "trailing bytes")
    new F16Presence(container, (flags & 1) != 0)
  }
}
