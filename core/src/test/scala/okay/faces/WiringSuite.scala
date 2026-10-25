package okay.faces

import okay.effects.json.Json
import okay.effects.shape.{Shape, Day}
import okay.effects.shape.Shape._

/** okay-faces stands on okay-simple's `shape` and `json`: the wiring, nothing more, until the faces' own code lands */
class WiringSuite extends munit.FunSuite {
  final case class Who(name: String, born: Day)
  val who: Shape[Who] = record(field("name", text), field("born", date))(Who(_, _))(w => (w.name, w.born))
  test("a picture crosses the wire and back; its SVG text escapes what it says; a band's outline") {
    import View.Picture.{Band, Box, Label}
    val p = View.Picture(300, 120, Vector(Box(0, 10, 12, 100, "start", "the start <0x1>"),
      Band(12, 60, 200, 40.5, 30, "exchange", "120 ETH"), Label(206, 44, "Binance & co", "start")), "a & b")
    assertEquals(ViewJson.decode(ViewJson.encode(p)), Right(p))
    val svg = View.Picture.svg(p, s => s.replace("&", "&amp;").replace("<", "&lt;"))
    assert(svg.startsWith("""<div class="okay-picture-frame"><svg class="okay-picture" viewBox="0 0 300 120">"""), svg)
    assert(svg.contains("<title>the start &lt;0x1></title>") && svg.contains(">Binance &amp; co</text>"), svg)
    assertEquals(View.Picture.band(Band(0, 50, 100, 70, 10, "", "")),
      "M0,45 C50,45 50,65 100,65 L100,75 C50,75 50,55 0,55 Z")
  }

  test("a shape from okay-simple decodes a json from okay-simple") {
    assertEquals(who.decode(Json.obj("name" -> Json.Str("Ann"), "born" -> Json.Str("1985-03-09"))),
      Right(Who("Ann", Day(1985, 3, 9))))
    assertEquals(okay.effects.json.parse(Json.print(who.encode(Who("Bo", Day(2000, 2, 29))))).map(who.decode),
      Right(Right(Who("Bo", Day(2000, 2, 29)))))
  }
}
