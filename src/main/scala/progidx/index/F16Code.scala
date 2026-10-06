package progidx.index

/** Order-preserving 16-bit codes for half-precision floats.
  *
  * Values are quantized with `Float.floatToFloat16` (round to nearest even, saturating to infinity above 65504) and the
  * resulting bits are remapped so that unsigned code order equals numeric order: negative values have all bits
  * flipped, non-negative values have the sign bit set. -0 is normalized to +0 first so they share a code.
  *
  * Quantization is monotone non-decreasing, so for any x in [lo, hi], code(lo) <= code(x) <= code(hi). That is the
  * whole no-false-negatives argument. It survives the double rounding in `ofDouble` (double -> float -> half) because
  * a composition of monotone functions is monotone; data and query bounds must simply take the same path.
  *
  * NaN has no code; callers track it separately.
  */
object F16Code {
  val Count: Int = 1 << 16

  def ofFloat(x: Float): Int = {
    require(!x.isNaN, "NaN has no code")
    val bits = java.lang.Float.floatToFloat16(x) & 0xffff
    val normalized = if (bits == 0x8000) 0 else bits
    if ((normalized & 0x8000) != 0) ~normalized & 0xffff else normalized | 0x8000
  }

  def ofDouble(x: Double): Int = ofFloat(x.toFloat)

  /** The half-precision value a code stands for. Not a bound on the original values, which were rounded to it. */
  def toFloat(code: Int): Float = {
    require(code >= 0 && code < Count, s"code out of range: $code")
    val bits = if ((code & 0x8000) != 0) code & 0x7fff else ~code & 0xffff
    java.lang.Float.float16ToFloat(bits.toShort)
  }
}
