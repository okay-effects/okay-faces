package okay.faces.dom

import okay.effects.Runner
import okay.effects.json.Json
import okay.effects.shape.{Problem, Problems}
import okay.faces.{Chosen, Resource, Role, Step, View, Words}
import okay.http.{Request, Response}
import org.scalajs.dom
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * THE REMOTE FACE BY THE DOM: a dialogue that lives on a server behind the REST face, drawn in the browser with no
 * library — the Laminar remote face's page, markup for markup. The page knows no application: it draws whatever view
 * comes, a form from whatever schema comes, and sends a `Chosen` back. The wire is the REST face's: `POST base` opens a
 * session; `POST self/act/{name}` with the arguments is a choice, `400` with problems leaves the step; `POST
 * self/back`; a step that listens on the server asked again every `poll` ms (`GET self`). A choice is checked against
 * the schema before it travels.
 *
 * Its world is effects, as the local face's: the page, the keys, the focus — and the server (`Server`, through
 * `transport`: okay-http's `Browser.fetch()` in a page, the server's own service in a test) and time (`Timer`), all
 * handled by `World`. What was typed stays when a choice is refused, here or by the server; the problems are a live
 * region, their fields marked and focused; Escape is the server's back where it offers one.
 *
 * PUSHED, where the host can (`push`, `World.socket`; the server's `Listen`): told when the session moved on without
 * this page, it asks again; while listening works, no polling — as the Laminar remote face.
 */
class DomRemoteFace[F <: Runner with Singleton](transport: Request => Future[Response], poll: Int = 1000,
  words: Words = Words.english, settings: DomRemoteFace.Settings = DomRemoteFace.Settings(),
  push: Option[World.Push] = None)(implicit val fx: F)
    extends Drawing[F] {
  import fx._
  val handlers: World[fx.type] = new World[fx.type](transport, push)(fx)
  import handlers.W

  /** the element: a session opened at once, the step shown as it comes */
  def element(base: String = "/sessions"): dom.html.Element = new Session(base).element()

  /** what the page shows: the step as it came, the problems of the last choice, whether a call is out */
  final case class Shown(resource: Resource, problems: Problems, busy: Boolean)

  final class Session(base: String) {
    private var shown = Shown(Resource("", View.Empty, Nil, back = false, done = false, listening = false), Nil,
      busy = true)
    private var face: dom.html.Element = null
    private var at: dom.Node = null
    private var drawn: Drawn = new Drawn(true)

    def element(): dom.html.Element = {
      world(for {
        f <- el("div", cls(Role.Face.classes), "aria-busy" -> "true")(Nil)
        d <- place
        _ <- page(Page.Insert[Unit](f, d, None, u => u))
        _ <- later { face = f; at = d; unit }
        // Escape is the server's back, where it offers one
        _ <- onKey(f, "Escape")(() => back())
        first <- render(shown)
        _ <- swap(at, first).flatMap(_ => later { at = first; unit })
      } yield ())
      call(Request("POST", base), base)
      face
    }

    /** a new state: drawn again when another step has come, else its problems and buttons changed in place; a step
     * that listens asked again */
    private def set(s: Shown): Unit ! W = later {
      val before = shown
      shown = s
      (if (!(before.resource eq s.resource))
         render(s).flatMap(next => swap(at, next).flatMap(_ => later { at = next; busy(drawn, face, s.busy) }))
           .flatMap(_ => if (settings.focus && face.isConnected && !s.resource.done) focusStep(at) else unit)
       else
         (if (before.problems != s.problems) problems(drawn, s.problems, at, settings.focus && face.isConnected)
          else unit).flatMap(_ => if (before.busy != s.busy) busy(drawn, face, s.busy) else unit)
      ).flatMap(_ => again(s))
    }

    private def failed(ps: Problems): Unit = world(set(shown.copy(problems = ps, busy = false)))

    private def arrived(self: String)(reply: Try[Response]): Unit = reply match {
      case Success(r) => r.status match {
          case 200 | 201 =>
            val location = r.headers.get("Location").orElse(r.headers.get("location")).getOrElse(self)
            Resource.decode(location, r.body) match {
              case Right(resource) => world(set(Shown(resource, Nil, busy = false)))
              case Left(e) => failed(List(Problem(Nil, e)))
            }
          case 400 => failed(Resource.problems(r.body))
          case other => failed(List(Problem(Nil, s"$other from the server")))
        }
      case Failure(e) => failed(List(Problem(Nil, e.getMessage)))
    }

    private def call(req: Request, self: String): Unit =
      world(set(shown.copy(busy = true)).flatMap(_ =>
        perform[Server].apply[W, Unit](Server.Send[Unit](req, arrived(self), u => u))))

    def choose(chosen: Chosen): Unit = {
      val s = shown
      if (!s.busy) Step.take(chosen, s.resource.actions) match {
        case Left(rejected) => failed(rejected.problems)
        case Right(args) =>
          call(Request.json("POST", s"${s.resource.self}/act/${chosen.action}", args), s.resource.self)
      }
    }
    def back(): Unit = {
      val s = shown
      if (!s.busy && s.resource.back) call(Request("POST", s"${s.resource.self}/back"), s.resource.self)
    }
    // the session listened to, where the host can push: told it moved, the page asks again
    private var watched: Option[(String, () => Unit)] = None
    private var pushed = false
    private def watch(self: String): Unit ! W =
      if (self.isEmpty || watched.exists(_._1 == self)) unit
      else later { watched.foreach(_._2()); pushed = false; unit }.flatMap(_ =>
        perform[Server].apply[W, () => Unit](Server.Listen[() => Unit](self, () => {
          val now = shown; if (!now.busy && now.resource.self == self) call(Request("GET", self), self)
        }, up => pushed = up, stop => stop)).flatMap(stop => later { watched = Some(self -> stop); unit }))
    // a step that listens on the server: asked again every `poll` ms, until it moves on — unless pushed
    private def again(s: Shown): Unit ! W = watch(s.resource.self).flatMap(_ =>
      if (s.resource.listening && !s.busy)
        perform[Timer].apply[W, Unit](Timer.After[Unit](poll, () => {
          val now = shown
          if ((now eq s) && now.resource.listening && !now.busy && !pushed)
            call(Request("GET", now.resource.self), now.resource.self)
          else if ((now eq s) && pushed) world(again(now))
        }, u => u))
      else unit)

    def render(s: Shown): dom.Node ! W = later {
      drawn = new Drawn(s.busy)
      val r = s.resource
      val ref: Option[String => Unit] = if (r.actions.exists(_.name == "ref"))
        Some(key => choose(Chosen("ref", Json.obj("key" -> Json.Str(key)))))
      else None
      val end: dom.Node ! W =
        if (r.done) el("div", cls("okay-done"))(List(txt(words.done)))
        else {
          val backButton: List[dom.Node ! W] =
            if (!r.back) Nil
            else List(button("button", Role.Back.classes)(List(txt(words.back))).flatMap { b =>
              later { drawn.buttons = b :: drawn.buttons; put(b, Page.Disabled, s.busy.toString) }.flatMap(_ =>
                listen(b, "click")(_ => back())).map(_ => b)
            })
          el("nav", cls(Role.Actions.classes))(
            r.actions.filterNot(_.name == "ref").map(a => action(a, choose, drawn, words)) ++ backButton)
        }
      for {
        v <- el("section", cls(Role.View.classes))(List(view(r.view, ref)))
        problemsAt <- place
        e <- end
        step <- el("div")(List(node(v), node(problemsAt), node(e)))
        _ <- later { drawn.problemsAt = Some(problemsAt); problems(drawn, s.problems, step, focus = false) }
      } yield step
    }
  }
}

object DomRemoteFace {
  /** whether the face moves the keyboard: a new step's first field, a problem's field */
  final case class Settings(focus: Boolean = true)
  def apply[F <: Runner with Singleton](transport: Request => Future[Response], poll: Int = 1000)(
    implicit fx: F): DomRemoteFace[F] = new DomRemoteFace[F](transport, poll)(fx)
  /** THE THIN PAGE, whole: `DomRemoteFace.mount(Browser.fetch())` — the dialogue on the server behind its REST face at
   * `base`, this page knowing no application; into `#app` or the body */
  def mount[F <: Runner with Singleton](transport: Request => Future[Response], base: String = "/sessions",
    poll: Int = 1000, push: Option[World.Push] = Some(World.socket))(implicit fx: F): Unit =
    DomFace.whenLoaded { () =>
      val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
      root.appendChild(new DomRemoteFace[F](transport, poll, Words.english, Settings(), push)(fx).element(base))
    }
}

/** THE THIN PAGE AS A SCRIPT, with no library: a Scala.js project whose main this is (`Compile / mainClass :=
 * Some("okay.faces.dom.DomRemotePage")`) links the remote face alone, on okay-simple's machine — a dialogue on the
 * server behind its REST face at `/sessions`, this page knowing no application */
object DomRemotePage {
  def main(args: Array[String]): Unit = {
    import okay.effects.machine.Machine.self
    DomRemoteFace.mount(okay.http.Browser.fetch()(scala.scalajs.concurrent.JSExecutionContext.queue))
  }
}
