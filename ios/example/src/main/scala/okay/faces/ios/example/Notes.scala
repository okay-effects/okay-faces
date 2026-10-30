package okay.faces.ios.example

import okay.effects.json.{Descent, Json}
import okay.effects.machine.Machine.self
import okay.effects.shape.Shape
import okay.faces._
import okay.faces.ios.{Device, IosApp, IosFace}
import okay.faces.ios.phone._

/**
 * NOTES, KEPT ON THE PHONE: the third example, the one that needs the device — its notes kept between runs (`save`,
 * `load`), okay-faces' page on GitHub fetched (`fetch`), a reminder notified (`remind`), the site opened (`open`).
 * The program says what; the shell's Swift does it.
 */
object Notes {
  type P[X] = Device.Phone[X]

  enum Move {
    case Add(text: String)
    case Clear, GitHub, Remind, Site
  }
  val note: Shape[String] = Shape.text.described("Note", Some("what to remember"))
  val add = Action[String, Move]("add", "Add", Shape.record(Shape.field("text", note))(t => t)(t => t))(Move.Add(_))

  def listing(notes: Vector[String]): View = View.stack(View.heading("Notes"),
    if notes.isEmpty then View.text("Nothing kept yet.") else View.Items(notes.map(View.text)),
    View.text(s"${notes.size} kept on this phone"))

  /** the repository, as GitHub's API says it: a few of its fields */
  def repository(r: Device.Reply): View =
    if !r.ok then View.problem(s"GitHub: ${if r.status == 0 then r.body else r.status.toString}")
    else Descent.parse(r.body).toOption match
      case Some(o: Json.Obj) =>
        def f(k: String): String = o.get(k).map {
          case Json.Str(s) => s; case Json.Num(n) => n.toString; case other => Json.print(other) }.getOrElse("")
        View.group("okay-effects/okay-faces")(View.fields("About" -> View.text(f("description")),
          "Stars" -> View.text(f("stargazers_count")), "Updated" -> View.text(f("pushed_at"))))
      case _ => View.problem("GitHub: not JSON")

  def turn(notes: Vector[String]): Unit ! P =
    offer[Move, P](listing(notes), List(add, Action.plain[Move]("github", "okay-faces on GitHub")(Move.GitHub),
        Action.plain[Move]("remind", "Remind me in 5 seconds")(Move.Remind),
        Action.plain[Move]("site", "Open okay-effects")(Move.Site)) ++
      (if notes.nonEmpty then List(Action.plain[Move]("clear", "Clear")(Move.Clear)) else Nil)).flatMap {
      case Move.Add(t) =>
        val next = notes :+ t.trim
        save[P]("notes", next.mkString("\n")).flatMap(_ => turn(next))
      case Move.Clear => forget[P]("notes").flatMap(_ => turn(Vector.empty))
      case Move.GitHub => fetch[P]("https://api.github.com/repos/okay-effects/okay-faces",
          headers = List("accept" -> "application/vnd.github+json", "user-agent" -> "okay-faces"))
        .flatMap(r => show[P](repository(r))).flatMap(_ => turn(notes))
      case Move.Remind => remind[P]("Notes", notes.lastOption.getOrElse("Nothing kept yet."), 5).flatMap(ok =>
          show[P](if ok then View.info("A reminder in 5 seconds.") else View.warning("Notifications are off.")))
        .flatMap(_ => turn(notes))
      case Move.Site => open[P]("https://okay-effects.github.io").flatMap(_ => turn(notes))
    }

  val program: Unit ! P =
    load[P]("notes").map(_.toVector.flatMap(_.split("\n")).filter(_.nonEmpty)).flatMap(turn)
}

class NotesApp extends IosApp { def dialogue = IosFace.phone(Notes.program, device) }
