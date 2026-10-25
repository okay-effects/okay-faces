package okay.faces.dom

import okay.effects.{Sum, Void}
import okay.effects.machine.Machine
import okay.effects.shape.Shape
import okay.effects.shape.Shape.{field, record, text}
import okay.faces.{Action, Face, Faces, Meanwhile, View}
import org.scalajs.dom

/** KEEPING WHAT IS ON THE PAGE (specs/web.md): a step that offers what the last offered changes the view in place —
 * what is typed stays, nodes stay, a kept button chooses on the step shown now; long text a text area */
class KeepingSuite extends munit.FunSuite {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  val face = new DomFace[Machine.type]()(Machine)
  import Machine._
  import faces._
  type F[X] = Sum[Void, Face, X]

  sealed trait Got
  final case class Tick(n: Int) extends Got
  final case class Note(text: String, n: Int) extends Got
  final case class Pressed(n: Int) extends Got

  final case class Typed(title: String, body: String)
  val typed: Shape[Typed] = record(field("title", text), field("body", text))(Typed(_, _))(t => (t.title, t.body))

  /** a timeline that grows by an event; a form to write a note; a button that says on which step it was pressed */
  def timeline(bus: Meanwhile.Manual[Int], n: Int): String ! F = {
    val view = View.stack(View.heading("Timeline") +: (1 to n).toVector.map(i => View.text(s"line $i")): _*)
    val actions: List[Action[Got]] = List(
      Action[Typed, Got]("note", "Write", typed)(t => Note(s"${t.title}: ${t.body}", n)).withLong("body"),
      Action.plain[Got]("press", "Press")(Pressed(n)))
    offer[Got, F](view, actions, bus.meanwhile.map(i => Tick(i): Got)).flatMap {
      case Tick(i) => timeline(bus, i)
      case Note(t, at) => pure[String, F](s"$t at $at")
      case Pressed(at) => pure[String, F](s"pressed at $at")
    }
  }

  def mounted(el: dom.html.Element): dom.html.Element = {
    dom.document.body.innerHTML = ""
    val container = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(container)
    container.appendChild(el)
    container
  }
  def q(root: dom.Element, selector: String): dom.html.Element = {
    val e = root.querySelector(selector)
    assert(e != null, s"no $selector in: ${root.innerHTML}")
    e.asInstanceOf[dom.html.Element]
  }

  test("an event: the view grows in place, what was typed stays, the nodes there before are the same nodes") {
    val bus = Meanwhile.manual[Int]
    var got = Option.empty[String]
    val root = mounted(face.element(steps[String, F, Void](timeline(bus, 1)), (s: String) => got = Some(s)))
    val title = q(root, "#f-title").asInstanceOf[dom.html.Input]
    title.value = "half typed"
    val firstLine = q(root, ".okay-view p")
    bus.fire(2)
    bus.fire(3)
    assertEquals(root.querySelectorAll(".okay-view p").length, 3)
    assert(q(root, ".okay-view p") eq firstLine, "the first line is the node it was")
    assert(q(root, "#f-title") eq title, "the form is the one typed into")
    assertEquals(title.value, "half typed")
    // a long field is a text area, read whole
    val body = q(root, "textarea#f-body").asInstanceOf[dom.html.TextArea]
    body.value = "line one\nline two"
    q(root, "form[data-action=note]").dispatchEvent(
      new dom.Event("submit", new dom.EventInit { bubbles = true; cancelable = true }))
    assertEquals(got, Some("half typed: line one\nline two at 3"))
  }

  test("a button kept from an earlier step chooses on the step shown now") {
    val bus = Meanwhile.manual[Int]
    var got = Option.empty[String]
    val root = mounted(face.element(steps[String, F, Void](timeline(bus, 1)), (s: String) => got = Some(s)))
    val press = q(root, "button[data-action=press]")
    bus.fire(5)
    assert(q(root, "button[data-action=press]") eq press, "the button is kept")
    press.click()
    assertEquals(got, Some("pressed at 5"))
  }

  test("another step with other actions is drawn anew") {
    val bus = Meanwhile.manual[Int]
    val root = mounted(face.element(steps[String, F, Void](timeline(bus, 1).flatMap(s =>
      show[F](View.text(s"done: $s")).map(_ => s)))))
    q(root, "button[data-action=press]").click()
    assert(root.textContent.contains("done: pressed at 1"), root.innerHTML)
    assertEquals(root.querySelectorAll("form").length, 0)
  }

  test("a session: program after program in one face, the next one's step changing the last in place") {
    val session = face.session[Any]()
    val root = mounted(session.element)
    def one(n: Int): Unit = session.show(steps[Any, F, Void](timeline(Meanwhile.manual[Int], n)
      .asInstanceOf[Any ! F]), (a: Any) => one(n + 1))
    one(1)
    val title = q(root, "#f-title").asInstanceOf[dom.html.Input]
    title.value = "kept"
    val firstLine = q(root, ".okay-view p")
    q(root, "button[data-action=press]").click()
    q(root, "button[data-action=press]").click()
    assertEquals(root.querySelectorAll(".okay-view p").length, 3)
    assert(q(root, ".okay-view p") eq firstLine, "the first line kept")
    assertEquals(q(root, "#f-title").asInstanceOf[dom.html.Input].value, "kept")
    assert(root.querySelector(".okay-done") == null, "no end drawn between programs")
  }

  test("a press while the face is busy with an event is not lost: it is made on the step that comes of it") {
    val session = face.session[Any]()
    val root = mounted(session.element)
    val bus = Meanwhile.manual[Int]
    var got = List.empty[Any]
    var later: Option[() => Unit] = None
    // one screen a program, answered by a press or the event — the next program comes later, as a future's would
    def once(n: Int): Got ! F = offer[Got, F](View.text(s"at $n"), List(Action.plain[Got]("press", "Press")(Pressed(n))),
      bus.meanwhile.map(i => Tick(i): Got))
    def show(n: Int): Unit = session.show(steps[Any, F, Void](once(n).asInstanceOf[Any ! F]), (a: Any) => {
      got :+= a
      later = Some(() => show(n + 1))
    })
    show(1)
    bus.fire(7) // the screen answered by the event; the face busy until the next program shows
    assertEquals(got, List(Tick(7)))
    q(root, "button[data-action=press]").click() // pressed meanwhile
    assertEquals(got, List(Tick(7)), "not on the old step")
    later.foreach(_())
    assertEquals(got, List(Tick(7), Pressed(2)), "made on the next")
  }
}
