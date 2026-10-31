package okay.faces.html

import okay.effects.{Sum, Void}
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.effects.shape.{Schema, Shape}
import okay.effects.shape.Shape.{field, int, record, text}
import okay.faces.{Face, Faces, View}
import okay.http.{Http, Https, Query, Request}

class HtmlFaceSuite extends munit.FunSuite {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  val face = new HtmlFace[Machine.type]()(Machine)
  val https: Https[Machine.type] = new Https[Machine.type]()(Machine)
  import Machine._
  import faces._
  type H[X] = Sum[Void, Http, X]
  type HF[X] = Sum[H, Face, X]

  final case class Who(name: String, age: Int)
  val who: Shape[Who] =
    record(field("name", text).labelled("Name"), field("age", int.check("of age")(_ >= 18)))(Who(_, _))(w =>
      (w.name, w.age))
  def greeting: String ! HF =
    fill[Who, HF](View.heading("Who are you?"), who, okay.faces.Step.Options.returnable).flatMap { w =>
      confirm[HF](View.text(s"Greet <${w.name}>, ${w.age}?")).flatMap { yes =>
        if (yes) show[HF](View.text(s"Hello, ${w.name}!")).map(_ => s"greeted ${w.name}") else pure[String, HF]("left")
      }
    }
  def form(kv: (String, String)*): Request = Request.post("/s/s1/act/submit",
    Query.print(kv.toList.groupBy(_._1).map { case (k, vs) => k -> vs.map(_._2) }), "application/x-www-form-urlencoded")
  def talk(requests: Request*): List[okay.http.Response] = {
    val pages = face.pages[String, H](() => faces.session[String, H](steps[String, HF, H](greeting)), "Greeting",
      HtmlFace.Settings(ids = okay.faces.Sessions.counting("s")))
    Machine.run(https.scripted(requests.toList)(https.serve[H](pages.service)))._1
  }

  test("a page with the form from the shape; post, redirect, get; the problems shown; the button; the end; escaped") {
    val rs = talk(Request.get("/"), Request.get("/s/s1"), form("name" -> "Ann", "age" -> "3"), Request.get("/s/s1"),
      form("name" -> "Ann", "age" -> "41"), Request.get("/s/s1"),
      Request.post("/s/s1/act/yes", "", "application/x-www-form-urlencoded"), Request.get("/s/s1"),
      Request.get("/s/zz"))
    assertEquals(rs.map(_.status), List(303, 200, 303, 200, 303, 200, 303, 200, 404))
    assertEquals(rs.head.headers("Location"), "/s/s1")
    assert(
      rs(1).body.contains("""<h1 class="okay-heading okay-heading-1">Who are you?</h1>""") && rs(1).body.contains(
        """<input type="number" name="age" id="f-age"/>""") && rs(1).body.contains(
        """<label class="okay-label" for="f-name">Name</label>"""), rs(1).body)
    assert(rs(1).body.contains("""<link rel="stylesheet" href="/okay.css"/>""") && !rs(1).body.contains(
        "<style") && !rs(1).body.contains("style="), rs(1).body)
    rs.filter(_.status != 303).foreach(r => assertEquals(WellFormed(r.body), Nil, r.body))
    assert(rs(3).body.contains("<li>age: of age</li>") && rs(3).body.contains("Who are you?"), rs(3).body)
    // the form as it was filled, not blank: what was typed stays
    assert(rs(3).body.contains("""<input type="number" name="age" id="f-age" value="3"/>""") &&
      rs(3).body.contains("id=\"f-name\" value=\""), rs(3).body)
    assert(rs(5).body.contains("Greet &lt;Ann&gt;, 41?") && rs(5).body.contains("""value="yes""""), rs(5).body)
    assert(rs(5).body.contains("/s/s1/back"))
    assert(rs(7).body.contains("Hello, Ann!") && rs(7).body.contains("okay-notice-info") && !rs(7).body.contains(
        "greeted Ann"), rs(7).body)
  }
  test("a form's fields to JSON along the schema: nested, optional blank, checkbox, choice, list, sum") {
    val schema = Schema.Record(Vector(
        Schema.Field("name", Schema.Text(Nil), None, None, false, None),
        Schema.Field("age", Schema.Optional(Schema.Integer(Nil)), None, None, false, None),
        Schema.Field("ok", Schema.Bool, None, None, false, None),
        Schema.Field("pet", Schema.Choice(List("dog", "cat")), None, None, false, None),
        Schema.Field("tags", Schema.Many(Schema.Text(Nil), 0, None), None, None, false, None),
        Schema.Field("home", Schema.Record(Vector(Schema.Field("city", Schema.Text(Nil), None, None, false, None))),
          None, None, false, None),
        Schema.Field("kind",
          Schema.OneOf(Vector("a" -> Schema.Record(Vector(Schema.Field("x", Schema.Integer(Nil), None, None, false,
                    None))), "b" -> Schema.Text(Nil))), None, None, false, None)))
    val form = Map("name" -> List("Ann"), "age" -> List(""), "pet" -> List("cat"), "tags" -> List("x\ny\n"),
      "home.city" -> List("Kyiv"), "kind.type" -> List("a"), "kind.a.x" -> List("7"))
    assertEquals(Json.print(Forms.collect(schema, form)),
      """{"name":"Ann","ok":false,"pet":"cat","tags":["x","y"],"home":{"city":"Kyiv"},"kind":{"type":"a","x":7}}""")
    assert(Forms.field(Nil, schema, None).contains("""<select name="kind.type""""))
  }
  test("fields held back under Options, last in the fieldset; closed, open when a problem names one") {
    final case class Trip(to: String, seats: Int)
    val trip: Shape[Trip] = record(field("to", text).labelled("To"), field("seats", int).labelled("Seats")
      .withDefault(1))(Trip(_, _))(t => (t.to, t.seats))
    val book = okay.faces.Action[Trip, Trip]("book", "Book", trip)(t => t).withMore("seats")
    val closed = Forms.action(book, "/s/s1")
    assert(closed.contains("""<details class="okay-more"><summary>Options</summary>"""), closed)
    assert(closed.indexOf("f-to") < closed.indexOf("<details"), closed)
    assert(closed.indexOf("f-seats") > closed.indexOf("<details"), closed)
    assert(closed.contains("</details></fieldset>"), closed)
    val opened = Forms.action(book, "/s/s1", problems = List(okay.effects.shape.Problem(List("seats"), "no")))
    assert(opened.contains("""<details class="okay-more" open="">"""), opened)
    // a problem of a field shown leaves Options closed
    assert(!Forms.action(book, "/s/s1", problems = List(okay.effects.shape.Problem(List("to"), "no")))
      .contains("open="))
    // the wire carries it, and back
    val a = book.affordance
    assertEquals(okay.faces.Affordance.decode(a.json), Right(a))
    assertEquals(a.more, Set("seats"))
    assertEquals(a.action.more, Set("seats"))
  }
  test("the stylesheet served, compiled from the look; a dark look is another stylesheet") {
    val css = talk(Request.get("/okay.css")).head
    assertEquals(css.status, 200); assertEquals(css.headers("Content-Type"), "text/css; charset=utf-8")
    assert(css.body.contains("--okay-bg:#ffffff") && css.body.contains(".okay-heading-1{"), css.body)
    val dark = face.pages[String, H](
      () => faces.session[String, H](steps[String, HF, H](greeting)), "Greeting",
      HtmlFace.Settings(look = Some(okay.faces.Look.dark)))
    assert(
      Machine.run(https.scripted(List(Request.get("/okay.css")))(https.serve[H](dark.service)))._1.head.body.contains(
        "--okay-bg:#0d1117"))
  }
  test("a list of records in a form: slots, min and one spare, items by index; the spare left blank is no item") {
    final case class Line(what: String, qty: Int)
    val line = record(field("what", text), field("qty", int))(Line(_, _))(l => (l.what, l.qty))
    val lines: Shape[List[Line]] = line.many(min = 1)
    val html = Forms.field(List("lines"), lines.erased,
      Some(Schema.Field("lines", lines.erased, Some("Lines"), None, false, None)))
    assert(html.contains("""data-item="0"""") && html.contains("""data-item="1"""") && !html.contains(
        """data-item="2""""), html)
    assert(html.contains("""name="lines.0.what"""") && html.contains("""name="lines.1.qty""""), html)
    val wrapped: Shape[List[Line]] = record(field("lines", lines))(l => l)(l => l)
    assertEquals(wrapped.decode(Forms.collect(wrapped.erased,
          Map("lines.0.what" -> List("tea"), "lines.0.qty" -> List("2"), "lines.1.what" -> List(""),
            "lines.1.qty" -> List("")))), Right(List(Line("tea", 2))))
    assertEquals(WellFormed("<main>" + html + "</main>"), Nil)
  }
  test("a Ref is a live link when the step offers ref: followed by GET, the action chosen with the key") {
    def listing: String ! HF =
      offer[String, HF](View.stack(View.text("records"), View.Ref("r1", "Ann"), View.Ref("r2", "Bo")),
        List(okay.faces.Action[String, String]("ref", "Open", record(field("key", text))(k => k)(k => k))(k =>
            k))).flatMap(k => show[HF](View.text(s"opened $k")).map(_ => k))
    val pages = face.pages[String, H](
      () => faces.session[String, H](steps[String, HF, H](listing)), "Refs",
      HtmlFace.Settings(ids = okay.faces.Sessions.counting("s")))
    val rs = Machine.run(https.scripted(List(Request.get("/"), Request.get("/s/s1"), Request.get("/s/s1/ref/r2"),
          Request.get("/s/s1")))(https.serve[H](pages.service)))._1
    assert(rs(1).body.contains("""href="/s/s1/ref/r2""""), rs(1).body)
    assertEquals(rs(2).status, 303); assert(rs(3).body.contains("opened r2"), rs(3).body)
    val plain = Html.view(View.Ref("k", "K"))
    assert(plain.contains("""href="#k""""), "no ref offered: an anchor")
    assertEquals(Html.view(View.link("a <source>", "https://example.org/?a=1&b=2")),
      """<a class="okay-link" href="https://example.org/?a=1&amp;b=2" target="_blank" rel="noopener noreferrer">""" +
        "a &lt;source&gt;</a>")
    assertEquals(Html.view(View.image("https://example.org/a.png?x=1&y=2", "a \"cat\"")),
      """<img class="okay-image" src="https://example.org/a.png?x=1&amp;y=2" alt="a &quot;cat&quot;"/>""")
  }
}

/** well-formed as XML: every tag closed in order, void elements self-closed; what is wrong, as a list */
object WellFormed {
  val voids = Set("meta", "link", "input", "br", "hr", "img")
  def apply(html: String): List[String] = {
    val tag = "<(/?)([a-zA-Z][a-zA-Z0-9]*)([^<>]*?)(/?)>".r
    val body = html.replaceFirst("(?s)^\\s*<!DOCTYPE[^>]*>", "")
    val (open, wrong) =
      tag.findAllMatchIn(body).foldLeft((List.empty[String], List.empty[String])) { case ((stack, bad), m) =>
        val (closing, name, selfClosed) = (m.group(1) == "/", m.group(2).toLowerCase, m.group(4) == "/")
        if (closing)
          stack match { case `name` :: rest => (rest, bad); case _ => (stack, s"unexpected </$name>" :: bad) }
        else if (selfClosed) (stack, bad)
        else if (voids(name)) (stack, s"<$name> not self-closed" :: bad)
        else (name :: stack, bad)
      }
    (wrong ++ open.map(n => s"<$n> not closed")).reverse
  }
}
