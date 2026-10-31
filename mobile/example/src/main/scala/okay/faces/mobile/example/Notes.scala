package okay.faces.mobile.example

import okay.effects.json.{Descent, Json}
import okay.effects.machine.Machine.self
import okay.effects.shape.Shape
import okay.faces._
import okay.faces.mobile.{Accounts, Backend, Device, MobileApp, MobileFace}
import okay.faces.mobile.phone._

/**
 * NOTES, KEPT ON THE PHONE: the third example, the one that needs the device — its notes kept between runs (`save`,
 * `load`), okay-faces' page on GitHub fetched (`fetch`), a reminder notified (`remind`), the site opened (`open`).
 * The program says what; the shell's Swift does it. And, signed in to a backend (okay-serverless' accounts, its
 * address the phone's setting `cloud`), the notes SHARED: on every phone the person signs in on.
 */
object Notes {
  type P[X] = Device.Phone[X]

  /** where the notes are kept beyond the phone, and who is signed in there */
  final case class Ctx(backend: Option[Backend], who: Option[String])

  enum Move {
    case Add(text: String)
    case Open(index: Int)
    case Clear, GitHub, Remind, Site, Here, Unlock, Sync, SignOut
  }
  val note: Shape[String] = Shape.text.described("Note", Some("what to remember"))
  val add = Action[String, Move]("add", "Add", Shape.record(Shape.field("text", note))(t => t)(t => t))(Move.Add(_))

  def listing(notes: Vector[String])(using c: Ctx): View = View.stack(View.heading("Notes"),
    if notes.isEmpty then View.text("Nothing kept yet.")
    else View.Items(notes.zipWithIndex.map((n, i) => View.Ref((i + 1).toString, n))),
    View.text(c.who.fold(s"${notes.size} kept on this phone")(w => s"${notes.size} shared as ${w.dropWhile(_ != ':')
      .drop(1)}")))

  /** the notes kept: on the phone, and shared when signed in */
  def keep(notes: Vector[String])(using c: Ctx): Unit ! P =
    save[P]("notes", notes.mkString("\n")).flatMap(_ => (c.backend, c.who) match
      case (Some(b), Some(_)) => Accounts.putShared(b, "notes", notes.mkString("\n")).map(_ => ())
      case _ => pure[Unit, P](()))

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

  /** a note's own page, one may go back from: its photo if it has one; remind of it, a photo to it, share it, or
   * delete it */
  def page(notes: Vector[String], i: Int)(using Ctx): Unit ! P =
    val key = s"photo:${notes(i)}"
    load[P](key).flatMap { photo =>
      offer[String, P](View.Group(None, Vector(View.heading("Note"), View.text(notes(i))) ++
          photo.map(p => View.image(p, s"the photo of ${notes(i)}"))), List(
          Action.plain[String]("remind-note", "Remind me about it")("remind"),
          Action.plain[String]("photo", if photo.isEmpty then "Add a photo" else "Another photo")("photo"),
          Action.plain[String]("share-note", "Share")("share"),
          Action.plain[String]("delete", "Delete")("delete"))).flatMap {
        case "delete" =>
          val next = notes.patch(i, Nil, 1)
          forget[P](key).flatMap(_ => keep(next)).flatMap(_ => turn(next))
        case "photo" => pickPhoto[P](800).flatMap {
            case Some(p) => save[P](key, p).flatMap(_ => page(notes, i))
            case None => page(notes, i)
          }
        case "share" => share[P](notes(i)).flatMap(_ => page(notes, i))
        case _ => remind[P]("Notes", notes(i), 5).flatMap(_ => turn(notes))
      }
    }

  def turn(notes: Vector[String])(using c: Ctx): Unit ! P =
    val opening = Action[Int, Move]("ref", "Open", Shape.record(Shape.field("key",
      Shape.choice[Int](notes.indices.map(i => (i + 1).toString -> i)*)))(k => k)(k => k))(Move.Open(_))
    offer[Move, P](listing(notes), List(add, Action.plain[Move]("github", "okay-faces on GitHub")(Move.GitHub),
        Action.plain[Move]("remind", "Remind me in 5 seconds")(Move.Remind),
        Action.plain[Move]("site", "Open okay-effects")(Move.Site),
        Action.plain[Move]("here", "Note where I am")(Move.Here),
        Action.plain[Move]("unlock", "Unlock")(Move.Unlock)) ++
      (c.backend.fold(Nil)(_ =>
        if c.who.isEmpty then List(Action.plain[Move]("sync", "Sync across my phones")(Move.Sync))
        else List(Action.plain[Move]("signout", "Sign out")(Move.SignOut)))) ++
      (if notes.nonEmpty then List(opening, Action.plain[Move]("clear", "Clear")(Move.Clear)) else Nil),
      okay.faces.Step.Options.returnable).flatMap {
      case Move.Open(i) => page(notes, i)
      case Move.Add(t) =>
        val next = notes :+ t.trim
        keep(next).flatMap(_ => turn(next))
      case Move.Clear => keep(Vector.empty).flatMap(_ => turn(Vector.empty))
      case Move.GitHub => fetch[P]("https://api.github.com/repos/okay-effects/okay-faces",
          headers = List("accept" -> "application/vnd.github+json", "user-agent" -> "okay-faces"))
        .flatMap(r => show[P](repository(r))).flatMap(_ => turn(notes))
      case Move.Remind => remind[P]("Notes", notes.lastOption.getOrElse("Nothing kept yet."), 5).flatMap(ok =>
          show[P](if ok then View.info("A reminder in 5 seconds.") else View.warning("Notifications are off.")))
        .flatMap(_ => turn(notes))
      case Move.Site => open[P]("https://okay-effects.github.io").flatMap(_ => turn(notes))
      case Move.Here => locate[P].flatMap {
          case Some(p) =>
            // degrees to four places, the same on every phone whatever its language
            // (BigDecimal, not String.format: Scala Native has no DecimalFormat for a locale's)
            def deg(d: Double): String = BigDecimal(d).setScale(4, BigDecimal.RoundingMode.HALF_UP).toString
            val next = notes :+ s"At ${deg(p.latitude)}, ${deg(p.longitude)}"
            keep(next).flatMap(_ => turn(next))
          case None => show[P](View.warning("Where you are is not known: location is off.")).flatMap(_ => turn(notes))
        }
      case Move.Unlock => verify[P]("Show that it is you").flatMap(ok =>
          show[P](if ok then View.info("It is you.") else View.warning("Not unlocked."))).flatMap(_ => turn(notes))
      // signed in: the notes the cloud has, with this phone's added to them, kept on both
      case Move.Sync => c.backend.fold(turn(notes))(b => Accounts.signIn(b).flatMap { ok =>
          if !ok then turn(notes)
          else Accounts.who.flatMap(w => Accounts.getShared(b, "notes").flatMap { there =>
            val theirs = lines(there)
            val both = theirs ++ notes.filterNot(theirs.contains)
            given Ctx = c.copy(who = w)
            keep(both).flatMap(_ => turn(both))
          })
        })
      case Move.SignOut => c.backend.fold(turn(notes))(b =>
          Accounts.signOut(b).flatMap(_ => turn(notes)(using c.copy(who = None))))
    }

  def lines(s: Option[String]): Vector[String] = s.toVector.flatMap(_.split("\n")).filter(_.nonEmpty)

  /** the backend from the phone's setting `cloud`; the notes the cloud's when signed in there, else the phone's */
  val program: Unit ! P =
    for
      url <- load[P]("cloud")
      apple <- load[P]("apple-client")
      google <- load[P]("google-client")
      backend = url.filter(_.nonEmpty).map(Backend(_, apple.getOrElse(""), google.getOrElse("")))
      who <- backend.fold(pure[Option[String], P](None))(_ => Accounts.who)
      notes <- (backend, who) match
        case (Some(b), Some(_)) => Accounts.getShared(b, "notes").flatMap {
            case Some(n) => pure[Option[String], P](Some(n))
            case None => load[P]("notes")
          }
        case _ => load[P]("notes")
      _ <- turn(lines(notes))(using Ctx(backend, who))
    yield ()
}

class NotesApp extends MobileApp { def dialogue = MobileFace.phone(Notes.program, device) }
