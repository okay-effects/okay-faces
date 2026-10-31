package okay.faces.term

import okay.effects.{Sum, Void}
import okay.effects.machine.Machine
import okay.effects.world.Console
import okay.faces.{Face, Faces, Look, Paint, Role, View}

/** THE LOOK in a terminal: SGR around a role's lines when painting, nothing when not */
class AnsiSuite extends munit.FunSuite {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  import Machine._
  import faces._
  type C[X] = Sum[Void, Console, X]
  type CF[X] = Sum[C, Face, X]
  val program: Unit ! CF =
    confirm[CF](View.stack(View.heading("Who"), View.problem("No"), View.fields("pin" -> View.Masked("••")))).map(_ =>
      ())
  def talk(paint: Paint): List[String] = {
    val term = new Terminal[Machine.type](Terminal.Settings(paint = paint))(Machine)
    Machine.run(term.io.console(List("1"))(term.terminal[Unit, C](steps[Unit, CF, C](program))))._1
  }
  test("painted: bold and the tone's colour, reset after; the heading's rule, the problem's, the label's") {
    val out = talk(Ansi(Look.default))
    assert(out.contains("\u001b[1mWHO\u001b[0m") && out.contains("\u001b[1m===\u001b[0m"), out)
    assert(out.exists(_.startsWith("\u001b[38;2;207;34;46mproblem: No\u001b[0m")), out)
    assert(out.exists(_.startsWith("\u001b[1mpin\u001b[0m: \u001b[38;2;99;108;118m••\u001b[0m")), out)
    assert(out.exists(l => l.contains("\u001b[1;38;2;255;255;255;48;2;9;105;218m1) Yes\u001b[0m")), out)
  }
  test("plain: no escape anywhere") {
    val out = talk(Paint.none)
    assert(out.forall(!_.contains("\u001b")), out)
    assert(out.contains("WHO"))
    assertEquals(Ansi.sgr(Look.default.style(Role.Text), Look.default), Nil)
  }
  test("an image in words: its alt, as a terminal cannot show it") {
    assertEquals(Render.view(okay.faces.View.image("data:image/png;base64,iVBOR", "a cat")), List("[image: a cat]"))
  }
}
