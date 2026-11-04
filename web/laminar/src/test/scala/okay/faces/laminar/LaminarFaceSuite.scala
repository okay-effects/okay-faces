package okay.faces.laminar

import com.raquo.laminar.api.L._
import okay.effects.{Sum, Void}
import okay.effects.machine.Machine
import okay.effects.shape.Shape
import okay.effects.shape.Shape.{field, int, record, sub, sum, text, unit}
import okay.effects.async.Async
import okay.faces.{Action, Face, Faces, View}
import org.scalajs.dom
import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** THE LAMINAR FACE under jsdom: the view drawn, a form filled and submitted, a problem shown, a button pressed, back
 */
class LaminarFaceSuite extends munit.FunSuite {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  val face = new LaminarFace[Machine.type]()(Machine)
  import Machine._
  import faces._
  type F[X] = Sum[Void, Face, X]

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

  def mounted(el: HtmlElement): dom.html.Element = {
    // one face in the document at a time: jsdom answers an id selector from the whole document, then checks the subtree
    dom.document.body.innerHTML = ""
    val container = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(container)
    render(container, el)
    container
  }
  def q(root: dom.Element, selector: String): dom.html.Element = {
    val e = root.querySelector(selector)
    assert(e != null, s"no $selector in: ${root.innerHTML}")
    e.asInstanceOf[dom.html.Element]
  }
  def typeInto(root: dom.Element, id: String, value: String): Unit =
    q(root, s"#$id").asInstanceOf[dom.html.Input].value = value
  def submit(root: dom.Element, action: String): Unit = {
    val form = q(root, s"form[data-action=$action]").asInstanceOf[dom.html.Form]
    form.dispatchEvent(new dom.Event("submit", new dom.EventInit { bubbles = true; cancelable = true }))
  }
  def press(root: dom.Element, action: String): Unit = q(root, s"button[data-action=$action]").click()

  test("a fraction is a number a browser lets through: step any on a decimal field, none on a whole one") {
    final case class Item(qty: Int, price: BigDecimal)
    val item: Shape[Item] =
      record(field("qty", int), field("price", Shape.ofBigDecimal))(Item(_, _))(i => (i.qty, i.price))
    var got = Option.empty[Item]
    val root = mounted(face.element(steps[Item, F, Void](fill[Item, F](View.heading("An item"), item)),
      (i: Item) => got = Some(i)))
    assertEquals(q(root, "#f-price").getAttribute("step"), "any")
    assertEquals(q(root, "#f-qty").getAttribute("step"), null)
    typeInto(root, "f-qty", "2"); typeInto(root, "f-price", "12.5")
    submit(root, "submit")
    assertEquals(got, Some(Item(2, BigDecimal("12.5"))))
  }

  test("a form from the shape, filled and submitted; a problem shown; the dialogue to its end") {
    var result = Option.empty[String]
    val root = mounted(face.element(steps[String, F, Void](greeting), (a: String) => result = Some(a)))
    assertEquals(q(root, "h1").textContent, "Who are you?")
    assertEquals(q(root, "label[for=f-name]").textContent, "Name")
    assertEquals(q(root, "#f-age").getAttribute("type"), "number")
    typeInto(root, "f-name", "Ann"); typeInto(root, "f-age", "3")
    submit(root, "submit")
    assert(root.innerHTML.contains("age: of age"), root.innerHTML) // the problem, from the shape
    assertEquals(q(root, "h1").textContent, "Who are you?") // the same step
    // what was typed stays: the form is not drawn again for a problem
    assertEquals(q(root, "#f-name").asInstanceOf[dom.html.Input].value, "Ann")
    assertEquals(q(root, "#f-age").asInstanceOf[dom.html.Input].value, "3")
    typeInto(root, "f-age", "41")
    submit(root, "submit")
    assertEquals(q(root, "p").textContent, "Greet Ann, 41?")
    press(root, "yes")
    assert(root.innerHTML.contains("Hello, Ann!"), root.innerHTML)
    assertEquals(result, Some("greeted Ann"))
  }
  test("fields held back under Options: closed, their defaults sent; a problem naming one opens them") {
    final case class Trip(to: String, seats: Int, by: String)
    val trip: Shape[Trip] = record(field("to", text).labelled("To"),
      field("seats", int.check("one at least")(_ >= 1)).labelled("Seats").withDefault(1),
      field("by", Shape.choice("train" -> "train", "bus" -> "bus")).labelled("By"))(Trip(_, _, _))(t =>
      (t.to, t.seats, t.by))
    val book = Action[Trip, Trip]("book", "Book", trip)(t => t).withMore("seats", "by")
    var got = Option.empty[Trip]
    val root = mounted(face.element(steps[Trip, F, Void](offer[Trip, F](View.heading("A trip"), List(book))),
      (t: Trip) => got = Some(t)))
    val details = q(root, "form[data-action=book] > fieldset > details.okay-more")
    assertEquals(q(details, "summary").textContent, "Options")
    assert(!details.asInstanceOf[scala.scalajs.js.Dynamic].open.asInstanceOf[Boolean])
    assert(details.querySelector("#f-to") == null, root.innerHTML) // the field shown is outside
    q(details, "#f-seats"); q(details, "#f-by")
    // last in the fieldset
    assertEquals(details.parentElement.lastElementChild, details: dom.Element)
    typeInto(root, "f-seats", "0")
    typeInto(root, "f-to", "Lviv")
    submit(root, "book")
    assert(root.innerHTML.contains("one at least"), root.innerHTML)
    assert(details.asInstanceOf[scala.scalajs.js.Dynamic].open.asInstanceOf[Boolean], "a problem opens Options")
    typeInto(root, "f-seats", "1")
    submit(root, "book")
    assertEquals(got, Some(Trip("Lviv", 1, "train")))
  }

  test("back re-resumes the previous step") {
    val root = mounted(face.element(steps[String, F, Void](greeting)))
    typeInto(root, "f-name", "Bo"); typeInto(root, "f-age", "20")
    submit(root, "submit")
    assertEquals(q(root, "p").textContent, "Greet Bo, 20?")
    q(root, "button.okay-back").click()
    assertEquals(q(root, "h1").textContent, "Who are you?")
    assert(root.querySelector("button.okay-back") == null)
  }
  test("views drawn: table, fields, items, masked, a link") {
    val v = View.stack(View.table("a", "b")(Vector(View.text("1"), View.Masked("••"))),
      View.fields("k" -> View.text("v")), View.items(View.text("x")), View.link("the source", "https://example.org/"))
    val root = mounted(face.element(steps[Unit, F, Void](show[F](v))))
    val link = q(root, "a.okay-link").asInstanceOf[dom.html.Anchor]
    assertEquals((link.textContent, link.getAttribute("href"), link.target),
      ("the source", "https://example.org/", "_blank"))
    // a page of this site opens in place
    val within = mounted(face.element(steps[Unit, F, Void](show[F](View.link("start again", "./")))))
    assertEquals(q(within, "a.okay-link").getAttribute("target"), null)
    assertEquals(root.querySelectorAll("th").length, 2)
    assertEquals(q(root, "span.okay-masked").textContent, "••")
    assertEquals(q(root, "dt").textContent, "k")
    assertEquals(q(root, "li").textContent, "x")
  }
  test("a sum in a form: the case chosen by a select, its fields the ones collected") {
    sealed trait Pet
    final case class Dog(name: String) extends Pet
    final case class Fish(litres: Int) extends Pet
    val pet: Shape[Pet] = sum(
      sub[Pet, Dog]("dog", record(field("name", text))(Dog(_))(_.name)) { case d: Dog => Some(d); case _ => None },
      sub[Pet, Fish]("fish", record(field("litres", int))(Fish(_))(_.litres)) {
        case f: Fish => Some(f); case _ => None
      })
    var got = Option.empty[Pet]
    val root =
      mounted(face.element(steps[Pet, F, Void](fill[Pet, F](View.text("A pet"), pet)), (p: Pet) => got = Some(p)))
    val sel = q(root, "#f").asInstanceOf[dom.html.Select]
    sel.value = "fish"; sel.dispatchEvent(new dom.Event("change", new dom.EventInit { bubbles = true }))
    typeInto(root, "f-fish-litres", "20")
    submit(root, "submit")
    assertEquals(got, Some(Fish(20)))
  }
  test("Async in the rest: a step that waits, the next view when it is done") {
    type AF[X] = Sum[Async, Face, X]
    val asynchrony = new okay.effects.async.Asynchrony[Machine.type]()(Machine, queue)
    def waiting: String ! AF =
      offer[Unit, AF](View.text("Press"), List(Action.plain[Unit]("go", "Go")(()))).flatMap(_ =>
        asynchrony.sleep[AF](5)).flatMap(_ => show[AF](View.text("Waited")).map(_ => "done"))
    val ended = Promise[String]()
    val root = mounted(face.elementAsync(steps[String, AF, Async](waiting), (a: String) => ended.success(a)))
    // the first step arrives when its future completes: a tick later
    Future(()).flatMap { _ =>
      assertEquals(q(root, "p").textContent, "Press")
      press(root, "go")
      // while the rest runs, the step stays shown with its actions disabled
      assert(q(root, "button[data-action=go]").hasAttribute("disabled"), root.innerHTML)
      ended.future.map { a =>
        assertEquals(a, "done")
        assert(root.innerHTML.contains("Waited"), root.innerHTML)
      }
    }
  }
  test("an event while the step waits: a future completes, the step moves on; a choice first cancels the listening") {
    type AF[X] = Sum[Async, Face, X]
    val p = Promise[Int]()
    def program: String ! AF = offer[Int, AF](View.text("waiting"), List(Action.plain[Int]("skip", "Skip")(0)),
      okay.faces.Meanwhile.fromFuture(p.future)).flatMap(n => show[AF](View.text(s"got $n")).map(_ => s"$n"))
    val ended = Promise[String]()
    val root = mounted(face.elementAsync(steps[String, AF, Async](program), (a: String) => ended.success(a)))
    Future(()).flatMap { _ =>
      assertEquals(q(root, "p").textContent, "waiting")
      p.success(4)
      ended.future.map { a => assertEquals(a, "4"); assert(root.innerHTML.contains("got 4"), root.innerHTML) }
    }
  }
  test("an event heard is listened to no more while the rest still works (specs/face-core.md, Open)") {
    type AF[X] = Sum[Async, Face, X]
    val asynchrony = new okay.effects.async.Asynchrony[Machine.type]()(Machine, queue)
    val bus = okay.faces.Meanwhile.manual[Int]
    var during = -1
    def program: String ! AF = offer[Int, AF](View.text("waiting"), List(Action.plain[Int]("skip", "Skip")(0)),
      bus.meanwhile).flatMap(n => asynchrony.sleep[AF](5).flatMap { _ =>
        during = bus.listening
        show[AF](View.text(s"got $n")).map(_ => s"$n")
      })
    val ended = Promise[String]()
    mounted(face.elementAsync(steps[String, AF, Async](program), (a: String) => ended.success(a)))
    Future(()).flatMap { _ =>
      assertEquals(bus.listening, 1)
      bus.fire(3)
      assertEquals(bus.listening, 0, "heard: listened to no more at once")
      ended.future.map { a => assertEquals(a, "3"); assertEquals(during, 0) }
    }
  }
  test("a list of records in a form: items added and removed by buttons, submitted as the records") {
    final case class Line(what: String, qty: Int)
    final case class Order(who: String, lines: List[Line])
    val line = record(field("what", text), field("qty", int))(Line(_, _))(l => (l.what, l.qty))
    val order: Shape[Order] =
      record(field("who", text), field("lines", line.many()))(Order(_, _))(o => (o.who, o.lines))
    var got = Option.empty[Order]
    val root = mounted(face.element(steps[Order, F, Void](fill[Order, F](View.heading("Order"), order)),
        (o: Order) => got = Some(o)))
    typeInto(root, "f-who", "ann")
    val add = q(root, "button[data-add=f-lines]")
    add.click(); add.click(); add.click()
    typeInto(root, "f-lines-0-what", "tea"); typeInto(root, "f-lines-0-qty", "2")
    typeInto(root, "f-lines-1-what", "gone"); typeInto(root, "f-lines-1-qty", "9")
    typeInto(root, "f-lines-2-what", "milk"); typeInto(root, "f-lines-2-qty", "1")
    q(root, "button[data-remove='1']").click()
    assert(root.querySelector("#f-lines-1-what") == null, "the item removed")
    submit(root, "submit")
    assertEquals(got, Some(Order("ann", List(Line("tea", 2), Line("milk", 1)))))
  }
  test("a list of at least one: its first item is there at once, each one added is another") {
    final case class Line(what: String, qty: Int)
    val line = record(field("what", text), field("qty", int))(Line(_, _))(l => (l.what, l.qty))
    var got = Option.empty[List[Line]]
    val root = mounted(face.element(steps[List[Line], F, Void](fill[List[Line], F](View.heading("Lines"),
      line.many(1, None))), (ls: List[Line]) => got = Some(ls)))
    def items = (0 until root.querySelectorAll(".okay-item").length).map(i =>
      root.querySelectorAll(".okay-item")(i).getAttribute("data-item")).toList
    assertEquals(items, List("0"))
    q(root, "button[data-add=f]").click(); q(root, "button[data-add=f]").click()
    assertEquals(items, List("0", "1", "2"))
    typeInto(root, "f-0-what", "tea"); typeInto(root, "f-0-qty", "1")
    typeInto(root, "f-1-what", "milk"); typeInto(root, "f-1-qty", "2")
    q(root, "button[data-remove='2']").click()
    submit(root, "submit")
    assertEquals(got, Some(List(Line("tea", 1), Line("milk", 2))))
  }
  test("a Ref is live when the step offers ref: a click chooses it with the key") {
    def listing: String ! F = offer[String, F](View.stack(View.text("records"), View.Ref("r1", "Ann")),
      List(Action[String, String]("ref", "Open", record(field("key", text))(k => k)(k => k))(k => k))).flatMap(k =>
      show[F](View.text(s"opened $k")).map(_ => k))
    val root = mounted(face.element(steps[String, F, Void](listing)))
    q(root, "a.okay-ref").click()
    assert(root.innerHTML.contains("opened r1"), root.innerHTML)
  }
  test("the browser's back: a popstate re-resumes the previous step") {
    val root =
      mounted(face.element(steps[String, F, Void](greeting), settings = LaminarFace.Settings(browserHistory = true)))
    typeInto(root, "f-name", "Di"); typeInto(root, "f-age", "33")
    submit(root, "submit")
    assertEquals(q(root, "p").textContent, "Greet Di, 33?")
    dom.window.dispatchEvent(new dom.Event("popstate"))
    assertEquals(q(root, "h1").textContent, "Who are you?")
  }
}

class LifecycleSuite extends munit.FunSuite {
  import okay.effects.machine.Machine
  import okay.effects.async.Async
  import scala.concurrent.Promise
  import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
  // the words: `map` on a program by name — Scala 3 would otherwise find it twice, by `Machine` and by `Machine.self`
  import Machine._
  val life = new Lifecycle[Machine.type]()(Machine, queue)
  import life.asynchrony.sleep
  def mount(el: HtmlElement): RootNode = {
    val c = dom.document.createElement("div").asInstanceOf[dom.html.Element]; dom.document.body.appendChild(c);
    render(c, el)
  }

  test("a program runs when the element mounts, and its result arrives") {
    val got = Promise[Int]()
    mount(div(life.runOnMount(sleep[Async](1).map(_ => 7))(r => got.complete(r.toTry))))
    got.future.map(assertEquals(_, 7))
  }
  test("unmounted before it is done: cancelled, nothing arrives") {
    var arrived = false
    val root = mount(div(life.runOnMount(sleep[Async](30).map(_ => 1))(_ => arrived = true)))
    root.unmount() // Laminar's own unmount: the callbacks fire (a removal by innerHTML would not tell Laminar)
    life.asynchrony.toFuture(sleep[Async](50)).map(_ => assert(!arrived))
  }
  test("the stylesheet element from the look, the roles' classes on the elements") {
    assert(LaminarFace.stylesheet().ref.textContent.contains(".okay-face{"))
    val el = Views.element(View.stack(View.heading("T"), View.problem("P")))
    assertEquals(el.ref.querySelector("h1").getAttribute("class"), "okay-heading okay-heading-1")
    assertEquals(el.ref.querySelector("p").getAttribute("class"), "okay-notice okay-notice-problem")
  }
}
