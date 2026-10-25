package okay.faces

import com.google.zxing.{BinaryBitmap, DecodeHintType, MultiFormatReader, RGBLuminanceSource}
import com.google.zxing.common.HybridBinarizer

/** A QR CODE read back by ZXing, a reader of its own: texts of every size, the picture `View.qr` makes of them, and
 * its tones from the look (dark on light in a dark scheme too) */
class QrSuite extends munit.FunSuite {
  /** a picture of boxes as an image, four pixels a unit: what a camera would see */
  def read(p: View.Picture): String = {
    val scale = 4; val w = p.width * scale
    val pixels = Array.fill(w * w)(0xffffff)
    p.shapes.foreach {
      case View.Picture.Box(x, y, bw, bh, "qr-ink", _) =>
        for (yy <- (y * scale).toInt until ((y + bh) * scale).toInt;
             xx <- (x * scale).toInt until ((x + bw) * scale).toInt) pixels(yy * w + xx) = 0
      case _ => ()
    }
    val bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, w, pixels)))
    val hints = new java.util.EnumMap[DecodeHintType, Any](classOf[DecodeHintType])
    hints.put(DecodeHintType.PURE_BARCODE, java.lang.Boolean.TRUE)
    new MultiFormatReader().decode(bitmap, hints).getText
  }

  test("texts of every size read back the same; the smallest version that holds them") {
    val url = "https://okay-guard.sergey-scherbina.workers.dev/#take=" + "0123456789abcdef" * 2
    for (text <- List("a", "okay", url, "x" * 100, "привет, агент " * 10, "y" * 300, "z" * 1000)) {
      View.qr(text) match {
        case p: View.Picture => assertEquals(read(p), text, s"length ${text.length}")
        case other => fail(s"no picture: $other")
      }
    }
    assertEquals(Qr(url).map(_.size), Some(41)) // version 6: a phone's camera at arm's length
    assertEquals(View.qr("q" * 3000), View.Text("q" * 3000))
    assertEquals(View.qr(url, Some("the page that takes the agent")).asInstanceOf[View.Picture].text,
      "the page that takes the agent")
  }

  test("its tones: the look's darker on its lighter, in either scheme") {
    assertEquals(Css.ink(Theme.dark), Theme.dark.palette.bg)
    assertEquals(Css.paper(Theme.dark), Theme.dark.palette.fg)
    assertEquals(Css.ink(Theme.light), Theme.light.palette.fg)
    assert(Css(Look.default).contains("okay-mark-qr-ink"))
  }
}
