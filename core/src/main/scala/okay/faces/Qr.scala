package okay.faces

/**
 * A QR CODE of a text (ISO/IEC 18004): bytes mode, error correction M, the smallest version that holds it, the mask of
 * the least penalty — `Qr(text)` its modules, row by row, `true` dark; none if the text is too long for a code. Of no
 * platform and no library: `View.qr` makes it a picture every face draws (specs/face-core.md, "A QR code").
 */
object Qr {
  def apply(text: String): Option[Vector[Vector[Boolean]]] = {
    val data = text.getBytes("UTF-8").toVector.map(_ & 0xff)
    (1 to 40).find(v => data.size <= capacity(v)).map(v => encode(data, v))
  }

  // error correction M: per version, the codewords of correction per block, and the blocks
  private val eccPerBlock = Vector(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26,
    26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28)
  private val blocks = Vector(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20,
    21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49)

  private def size(v: Int): Int = v * 4 + 17
  /** the modules for data and correction: the whole less the patterns */
  private def rawModules(v: Int): Int = {
    var r = (16 * v + 128) * v + 64
    if (v >= 2) {
      val n = v / 7 + 2
      r -= (25 * n - 10) * n - 55
      if (v >= 7) r -= 36
    }
    r
  }
  private def dataCodewords(v: Int): Int = rawModules(v) / 8 - eccPerBlock(v) * blocks(v)
  /** the bytes a version holds: its data codewords less the mode (4 bits) and the count (8 or 16) */
  private def capacity(v: Int): Int = (dataCodewords(v) * 8 - 4 - (if (v <= 9) 8 else 16)) / 8

  private def encode(data: Vector[Int], v: Int): Vector[Vector[Boolean]] = {
    // the bits: the mode (bytes), the count, the data, the terminator, padded to whole codewords
    val bits = scala.collection.mutable.ArrayBuffer.empty[Int]
    def put(value: Int, n: Int): Unit = (n - 1 to 0 by -1).foreach(i => bits += (value >>> i) & 1)
    put(4, 4); put(data.size, if (v <= 9) 8 else 16); data.foreach(put(_, 8))
    val capacityBits = dataCodewords(v) * 8
    put(0, math.min(4, capacityBits - bits.size))
    put(0, (8 - bits.size % 8) % 8)
    var pad = 0xec
    while (bits.size < capacityBits) { put(pad, 8); pad ^= 0xec ^ 0x11 }
    val codewords = bits.grouped(8).map(_.foldLeft(0)((a, b) => a << 1 | b)).toVector
    val all = withCorrection(codewords, v)
    val n = size(v)
    val dark = Array.ofDim[Boolean](n, n)
    val fixed = Array.ofDim[Boolean](n, n)
    patterns(v, dark, fixed)
    place(all, dark, fixed)
    val best = (0 until 8).minBy { m =>
      val d = dark.map(_.clone)
      masked(m, d, fixed); format(m, d, None); penalty(d)
    }
    masked(best, dark, fixed); format(best, dark, None)
    dark.toVector.map(_.toVector)
  }

  // ---- correction: Reed–Solomon over GF(256), blocks interleaved

  private def withCorrection(data: Vector[Int], v: Int): Vector[Int] = {
    val nb = blocks(v); val ecc = eccPerBlock(v)
    val raw = rawModules(v) / 8
    val shortLen = raw / nb
    val shorts = nb - raw % nb
    val divisor = generator(ecc)
    var at = 0
    val bs = (0 until nb).map { i =>
      val len = shortLen - ecc + (if (i < shorts) 0 else 1)
      val d = data.slice(at, at + len); at += len
      (d, remainder(d, divisor))
    }
    val out = scala.collection.mutable.ArrayBuffer.empty[Int]
    (0 until shortLen - ecc + 1).foreach(i => bs.foreach { case (d, _) => if (i < d.size) out += d(i) })
    (0 until ecc).foreach(i => bs.foreach { case (_, e) => out += e(i) })
    out.toVector
  }

  private def mul(x: Int, y: Int): Int = {
    var z = 0
    (7 to 0 by -1).foreach { i =>
      z = (z << 1) ^ ((z >>> 7) * 0x11d)
      z ^= ((y >>> i) & 1) * x
    }
    z
  }
  private def generator(degree: Int): Vector[Int] = {
    val r = Array.fill(degree)(0); r(degree - 1) = 1
    var root = 1
    (0 until degree).foreach { _ =>
      (0 until degree).foreach { j =>
        r(j) = mul(r(j), root)
        if (j + 1 < degree) r(j) ^= r(j + 1)
      }
      root = mul(root, 0x02)
    }
    r.toVector
  }
  private def remainder(data: Vector[Int], divisor: Vector[Int]): Vector[Int] = {
    val r = Array.fill(divisor.size)(0)
    data.foreach { b =>
      val factor = b ^ r(0)
      System.arraycopy(r, 1, r, 0, r.length - 1); r(r.length - 1) = 0
      divisor.indices.foreach(i => r(i) ^= mul(divisor(i), factor))
    }
    r.toVector
  }

  // ---- the matrix

  private def patterns(v: Int, dark: Array[Array[Boolean]], fixed: Array[Array[Boolean]]): Unit = {
    val n = size(v)
    def set(x: Int, y: Int, d: Boolean): Unit = { dark(y)(x) = d; fixed(y)(x) = true }
    (0 until n).foreach { i => set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
    def finder(cx: Int, cy: Int): Unit = for (dy <- -4 to 4; dx <- -4 to 4) {
      val d = math.max(math.abs(dx), math.abs(dy)); val x = cx + dx; val y = cy + dy
      if (x >= 0 && x < n && y >= 0 && y < n) set(x, y, d != 2 && d != 4)
    }
    finder(3, 3); finder(n - 4, 3); finder(3, n - 4)
    val align = alignments(v)
    for (i <- align.indices; j <- align.indices
         if !((i == 0 && j == 0) || (i == 0 && j == align.size - 1) || (i == align.size - 1 && j == 0))) {
      for (dy <- -2 to 2; dx <- -2 to 2) set(align(i) + dx, align(j) + dy, math.max(math.abs(dx), math.abs(dy)) != 1)
    }
    format(0, dark, Some(fixed))
    if (v >= 7) {
      var rem = v
      (0 until 12).foreach(_ => rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25))
      val bits = v << 12 | rem
      (0 until 18).foreach { i =>
        val d = ((bits >>> i) & 1) != 0; val a = n - 11 + i % 3; val b = i / 3
        set(a, b, d); set(b, a, d)
      }
    }
  }

  private def alignments(v: Int): Vector[Int] =
    if (v == 1) Vector.empty
    else {
      val num = v / 7 + 2
      val step = if (v == 32) 26 else (v * 4 + num * 2 + 1) / (num * 2 - 2) * 2
      6 +: (0 until num - 1).map(i => size(v) - 7 - i * step).reverse.toVector
    }

  /** the format bits: correction M (00), the mask; twice, as the standard places them */
  private def format(mask: Int, dark: Array[Array[Boolean]], fixed: Option[Array[Array[Boolean]]]): Unit = {
    val n = dark.length
    val data = mask
    var rem = data
    (0 until 10).foreach(_ => rem = (rem << 1) ^ ((rem >>> 9) * 0x537))
    val bits = (data << 10 | rem) ^ 0x5412
    def set(x: Int, y: Int, d: Boolean): Unit = { dark(y)(x) = d; fixed.foreach(_(y)(x) = true) }
    def bit(i: Int) = ((bits >>> i) & 1) != 0
    (0 to 5).foreach(i => set(8, i, bit(i)))
    set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
    (9 until 15).foreach(i => set(14 - i, 8, bit(i)))
    (0 until 8).foreach(i => set(n - 1 - i, 8, bit(i)))
    (8 until 15).foreach(i => set(8, n - 15 + i, bit(i)))
    set(8, n - 8, true)
  }

  private def place(codewords: Vector[Int], dark: Array[Array[Boolean]], fixed: Array[Array[Boolean]]): Unit = {
    val n = dark.length
    var i = 0
    var right = n - 1
    while (right >= 1) {
      if (right == 6) right = 5
      (0 until n).foreach { vert =>
        (0 until 2).foreach { j =>
          val x = right - j
          val upward = ((right + 1) & 2) == 0
          val y = if (upward) n - 1 - vert else vert
          if (!fixed(y)(x) && i < codewords.size * 8) {
            dark(y)(x) = ((codewords(i >>> 3) >>> (7 - (i & 7))) & 1) != 0
            i += 1
          }
        }
      }
      right -= 2
    }
  }

  private def masked(mask: Int, dark: Array[Array[Boolean]], fixed: Array[Array[Boolean]]): Unit = {
    val n = dark.length
    for (y <- 0 until n; x <- 0 until n if !fixed(y)(x)) {
      val invert = mask match {
        case 0 => (x + y) % 2 == 0
        case 1 => y % 2 == 0
        case 2 => x % 3 == 0
        case 3 => (x + y) % 3 == 0
        case 4 => (x / 3 + y / 2) % 2 == 0
        case 5 => x * y % 2 + x * y % 3 == 0
        case 6 => (x * y % 2 + x * y % 3) % 2 == 0
        case _ => ((x + y) % 2 + x * y % 3) % 2 == 0
      }
      if (invert) dark(y)(x) = !dark(y)(x)
    }
  }

  /** the penalty of a masked code: runs, boxes, finder-like patterns, the balance of dark */
  private def penalty(d: Array[Array[Boolean]]): Int = {
    val n = d.length
    def lines(get: (Int, Int) => Boolean): Int = (0 until n).map { a =>
      var p = 0; var run = 1
      (1 until n).foreach { b =>
        if (get(a, b) == get(a, b - 1)) { run += 1; if (run == 5) p += 3 else if (run > 5) p += 1 }
        else run = 1
      }
      val s = (0 until n).map(b => if (get(a, b)) '1' else '0').mkString
      p + 40 * s.sliding(11).count(w => w == "10111010000" || w == "00001011101")
    }.sum
    val runs = lines((y, x) => d(y)(x)) + lines((x, y) => d(y)(x))
    val boxes = (for (y <- 0 until n - 1; x <- 0 until n - 1 if d(y)(x) == d(y)(x + 1) && d(y)(x) == d(y + 1)(x) &&
      d(y)(x) == d(y + 1)(x + 1)) yield 3).sum
    val darkCount = d.map(_.count(identity)).sum
    val total = n * n
    val k = (math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1
    runs + boxes + math.max(0, k) * 10
  }
}
