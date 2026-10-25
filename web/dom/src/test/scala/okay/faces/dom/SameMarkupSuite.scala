package okay.faces.dom

import okay.effects.{Sum, Void}
import okay.effects.machine.Machine
import okay.effects.shape.Shape
import okay.effects.shape.Shape.{field, int, record, sub, sum, text}
import okay.faces.{Action, Face, Faces, Look, View}
import okay.faces.laminar.LaminarFace
import org.scalajs.dom

/**
 * THE SAME MARKUP: every scenario played on the Laminar face, then on the DOM face, the same acts, and the page's markup
 * compared after each — element for element, attribute for attribute — so a look's stylesheet dresses both the same
 * and changing the handler changes nothing that is seen. Laminar's comment nodes (its bookkeeping, matched by no
 * selector) and the DOM face's accessibility attributes (`aria-*`, `role`: no look's selector uses them) are left out
 * of the comparison.
 */
class SameMarkupSuite extends munit.FunSuite {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  val laminar = new LaminarFace[Machine.type]()(Machine)
  val plain = new DomFace[Machine.type]()(Machine)
  import Machine._
  import faces._
  type F[X] = Sum[Void, Face, X]

  /** a scenario: a program's element made by a face, then acts on the page, a snapshot after each */
  final case class Scenario(name: String, start: Face2 => dom.html.Element, acts: List[dom.html.Element => Unit])
  /** the two faces, one interface: the element of a program of steps, or of a program with a look */
  trait Face2 {
    def steps[A](p: A ! F): dom.html.Element
    def handled[A](p: okay.effects.tagless.Program[A, Face], look: Look): dom.html.Element
  }
  object OnLaminar extends Face2 {
    private def mounted(el: com.raquo.laminar.api.L.HtmlElement): dom.html.Element = {
      val c = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      dom.document.body.appendChild(c); com.raquo.laminar.api.L.render(c, el); c
    }
    def steps[A](p: A ! F): dom.html.Element = mounted(laminar.element(faces.steps[A, F, Void](p)))
    def handled[A](p: okay.effects.tagless.Program[A, Face], look: Look): dom.html.Element =
      mounted(laminar.handle(p, LaminarFace.Settings(look = Some(look))))
  }
  object OnDom extends Face2 {
    private def mounted(el: dom.html.Element): dom.html.Element = {
      val c = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      dom.document.body.appendChild(c); c.appendChild(el); c
    }
    def steps[A](p: A ! F): dom.html.Element = mounted(plain.element(faces.steps[A, F, Void](p)))
    def handled[A](p: okay.effects.tagless.Program[A, Face], look: Look): dom.html.Element =
      mounted(plain.handle(p, DomFace.Settings(look = Some(look))))
  }

  /** the markup, Laminar's comment nodes left out */
  def markup(root: dom.Node): String = {
    val copy = root.cloneNode(true)
    def strip(n: dom.Node): Unit = {
      var c = n.firstChild
      while (c != null) {
        val next = c.nextSibling
        if (c.nodeType == dom.Node.COMMENT_NODE) n.removeChild(c)
        else {
          // the DOM face's accessibility: attributes no look's selector uses
          if (c.nodeType == dom.Node.ELEMENT_NODE) {
            val e = c.asInstanceOf[dom.Element]
            val names = (0 until e.attributes.length).map(i => e.attributes(i).name)
            names.filter(a => a.startsWith("aria-") || a == "role").foreach(e.removeAttribute)
          }
          strip(c)
        }
        c = next
      }
    }
    strip(copy)
    copy.asInstanceOf[dom.html.Element].innerHTML
  }
  def played(s: Scenario, face: Face2): List[String] = {
    dom.document.body.innerHTML = ""
    val root = s.start(face)
    markup(root) :: s.acts.map { act => act(root); markup(root) }
  }

  def q(root: dom.Element, selector: String): dom.html.Element = {
    val e = root.querySelector(selector)
    assert(e != null, s"no $selector in: ${root.innerHTML}")
    e.asInstanceOf[dom.html.Element]
  }
  def typeInto(id: String, value: String): dom.html.Element => Unit =
    root => q(root, s"#$id").asInstanceOf[dom.html.Input].value = value
  def submit(action: String): dom.html.Element => Unit = root =>
    q(root, s"form[data-action=$action]").dispatchEvent(new dom.Event("submit",
        new dom.EventInit { bubbles = true; cancelable = true }))
  def press(selector: String): dom.html.Element => Unit = root => q(root, selector).click()
  def both(acts: (dom.html.Element => Unit)*): dom.html.Element => Unit = root => acts.foreach(_(root))

  final case class Who(name: String, age: Int)
  val who: Shape[Who] =
    record(field("name", text).labelled("Name"), field("age", int.check("of age")(_ >= 18)))(Who(_, _))(w =>
      (w.name, w.age))
  def greeting: String ! F =
    fill[Who, F](View.heading("Who are you?"), who, okay.faces.Step.Options.returnable).flatMap { w =>
      confirm[F](View.text(s"Greet ${w.name}, ${w.age}?")).flatMap { yes =>
        if (yes) show[F](View.text(s"Hello, ${w.name}!")).map(_ => s"greeted ${w.name}") else pure[String, F]("left")
      }
    }

  sealed trait Pet
  final case class Dog(name: String) extends Pet
  final case class Fish(litres: Int) extends Pet
  val pet: Shape[Pet] = sum(
    sub[Pet, Dog]("dog", record(field("name", text))(Dog(_))(_.name)) { case d: Dog => Some(d); case _ => None },
    sub[Pet, Fish]("fish", record(field("litres", int))(Fish(_))(_.litres)) { case f: Fish => Some(f); case _ => None })

  final case class Line(what: String, qty: Int)
  final case class Order(who: String, lines: List[Line], note: Option[String], tags: List[String])
  val line: Shape[Line] = record(field("what", text), field("qty", int))(Line(_, _))(l => (l.what, l.qty))
  val order: Shape[Order] = record(field("who", text).labelled("Who").documented("the customer"),
    field("lines", line.many(1, Some(3))), field("note", text.optional), field("tags", text.many()))(
    Order(_, _, _, _))(o => (o.who, o.lines, o.note, o.tags))

  final case class Item(qty: Int, price: BigDecimal, kind: String, gift: Boolean)
  val item: Shape[Item] = record(field("qty", int), field("price", Shape.ofBigDecimal),
    field("kind", Shape.choice("tea" -> "tea", "milk" -> "milk")), field("gift", Shape.bool))(Item(_, _, _, _))(i =>
    (i.qty, i.price, i.kind, i.gift))

  final case class Trip(to: String, seats: Int, by: String)
  val trip: Shape[Trip] = record(field("to", text).labelled("To"),
    field("seats", int.check("one at least")(_ >= 1)).labelled("Seats").withDefault(1),
    field("by", Shape.choice("train" -> "train", "bus" -> "bus")).labelled("By"))(Trip(_, _, _))(t =>
    (t.to, t.seats, t.by))
  val book: Action[Trip] = Action[Trip, Trip]("book", "Book", trip)(t => t).withMore("seats", "by")

  val scenarios: List[Scenario] = List(
    Scenario("fields held back under Options: closed, a problem naming one opens them, the next step",
      _.steps(offer[Trip, F](View.heading("A trip"), List(book)).flatMap(t => show[F](View.text(t.toString)))),
      List(both(typeInto("f-to", "Lviv"), typeInto("f-seats", "0"), submit("book")),
        both(typeInto("f-seats", "2"), submit("book")))),
    Scenario("a form, a problem kept with what was typed, the next step with back, the end",
      _.steps(greeting),
      List(both(typeInto("f-name", "Ann"), typeInto("f-age", "3"), submit("submit")),
        both(typeInto("f-age", "41"), submit("submit")),
        press("button.okay-back"),
        both(typeInto("f-name", "Bo"), typeInto("f-age", "20"), submit("submit")),
        press("button[data-action=yes]"))),
    Scenario("a picture: boxes, bands and labels, the same <svg> on both faces",
      _.steps(show[F](View.stack(View.heading("Where it went"), View.Picture(320, 100, Vector(
        View.Picture.Box(0, 10, 10, 80, "start", "the start"), View.Picture.Band(10, 50, 300, 30, 20, "exchange", "5"),
        View.Picture.Label(306, 34, "Binance", "end")), "5 to Binance")))),
      Nil),
    Scenario("views: headings, text, notices, fields, a table with and without heads, items, refs, links, masked",
      _.steps(show[F](View.stack(View.heading("One"), View.heading("Two", 2), View.heading("Three", 3),
        View.text("a text"), View.info("i"), View.warning("w"), View.problem("p"),
        View.fields("k" -> View.text("v"), "m" -> View.Masked("•• 12")),
        View.table("a", "b")(Vector(View.text("1"), View.text("2"))), View.table("", "")(Vector(View.text("x"),
          View.Empty)), View.items(View.text("x"), View.marked("price", View.text("12"))),
        View.group("A group")(View.Ref("r1", "Ann")), View.link("elsewhere", "https://example.org/"),
        View.link("here", "./"), View.Datum(okay.effects.json.Json.obj("a" -> okay.effects.json.Json.Num(1)))))),
      Nil),
    Scenario("a sum: the case chosen by its select, the other's fields hidden",
      _.steps(fill[Pet, F](View.text("A pet"), pet)),
      List(root => {
        val sel = q(root, "#f").asInstanceOf[dom.html.Select]
        sel.value = "fish"; sel.dispatchEvent(new dom.Event("change", new dom.EventInit { bubbles = true }))
      })),
    Scenario("a list of records (one at least, three at most), an optional, a list of leaves; items added, removed",
      _.steps(fill[Order, F](View.heading("Order"), order)),
      List(press("button[data-add=f-lines]"), press("button[data-add=f-lines]"), press("button[data-remove='1']"),
        press("button[data-add=f-lines]"))),
    Scenario("leaves: a whole number, a fraction (step any), a choice, a box; a plain action beside the form",
      _.steps(offer[Item, F](View.heading("An item"), List(Action[Item, Item]("add", "Add", item)(i => i),
        Action.plain[Item]("none", "None")(Item(0, 0, "tea", false))))),
      List(both(typeInto("f-qty", "2"), typeInto("f-price", "12.5"), submit("add")))),
    Scenario("a Ref live when the step offers ref",
      _.steps(offer[String, F](View.stack(View.text("records"), View.Ref("r1", "Ann")),
        List(Action[String, String]("ref", "Open", record(field("key", text))(k => k)(k => k))(k => k))).flatMap(k =>
        show[F](View.text(s"opened $k")).map(_ => k))),
      List(press("a.okay-ref"))),
    Scenario("the program handled with a look: its stylesheet first",
      _.handled(okay.faces.confirm[Face](View.text("Sure?"))(okay.faces.splitOne[Face]), Look.default),
      List(press("button[data-action=no]"))))

  scenarios.foreach { s =>
    test(s"the same markup — ${s.name}") {
      val l = played(s, OnLaminar)
      val d = played(s, OnDom)
      assertEquals(d.size, l.size)
      l.zip(d).zipWithIndex.foreach { case ((a, b), i) => assertEquals(b, a, s"after act $i") }
    }
  }
}
