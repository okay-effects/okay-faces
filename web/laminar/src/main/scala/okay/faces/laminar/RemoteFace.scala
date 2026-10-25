package okay.faces.laminar

import com.raquo.laminar.api.L._
import okay.effects.json.Json
import okay.effects.shape.{Problem, Problems}
import okay.faces.{Action, Chosen, Resource, Role, Step, View}
import okay.http.{Request, Response}
import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}

/**
 * THE REMOTE FACE: a dialogue that lives on a server behind the REST face, drawn in the browser by Laminar. The client
 * knows no application — it draws whatever view comes, draws a form from whatever schema comes, and sends a `Chosen`
 * back — so an application written once as a program runs on the server with a rich page in front of it and not a line
 * of its own in the browser. The wire is the REST face's: `POST base` opens a session, its data is the view, the
 * affordances, whether back is possible and whether it is done; `POST self/act/{name}` with the arguments is a choice,
 * `400` with problems leaves the step; `POST self/back`. A choice is checked against the schema before it travels.
 * The transport is a function, `Browser.fetch()` in a page, the server's own service in a test.
 *
 * PUSHED, where the host can (`push`, `RemoteFace.socket`): a socket to the session (`self/socket`) says when it moved
 * on without this page — an event while it waited, another counterpart's choice — and the page asks again; while the
 * socket is up, no polling. Where there is none (the JVM's server), the socket fails and the page polls as before:
 * the same page for every host.
 */
class RemoteFace(transport: Request => Future[Response], poll: Int = 1000,
  words: okay.faces.Words = okay.faces.Words.english, push: Option[RemoteFace.Push] = None)(
  implicit ec: ExecutionContext) {
  import RemoteFace.Shown
  private val nothing = Shown("", View.Empty, Nil, back = false, done = false, Nil, busy = true)

  /** the element: a session opened at mount, the step shown as it comes */
  def element(base: String = "/sessions"): HtmlElement = {
    val state: Var[Shown] = Var(nothing)
    def failed(ps: Problems): Unit = state.update(_.copy(problems = ps, busy = false))
    def arrived(self: String)(r: Response): Unit = r.status match {
      case 200 | 201 =>
        RemoteFace.shown(r.headers.get("Location").orElse(r.headers.get("location")).getOrElse(self), r.body) match {
          case Right(s) => state.set(s)
          case Left(e) => failed(List(Problem(Nil, e)))
        }
      case 400 => failed(RemoteFace.problems(r.body))
      case other => failed(List(Problem(Nil, s"$other from the server")))
    }
    def call(req: Request, self: String): Unit = {
      state.update(_.copy(busy = true));
      transport(req).map(arrived(self)).recover { case e => failed(List(Problem(Nil, e.getMessage))) }
    }
    def choose(s: Shown, chosen: Chosen): Unit = if (!s.busy) Step.take(chosen, s.actions) match {
      case Left(rejected) => failed(rejected.problems)
      case Right(args) => call(Request.json("POST", s"${s.self}/act/${chosen.action}", args), s.self)
    }
    def back(s: Shown): Unit = if (!s.busy && s.back) call(Request("POST", s"${s.self}/back"), s.self)
    // the session's socket, where the host has one: up, it says when the session moved; then the page asks again
    var watched: Option[(String, () => Unit)] = None
    var pushed = false
    def watch(self: String): Unit = push.foreach { listen =>
      if (self.nonEmpty && !watched.exists(_._1 == self)) {
        watched.foreach(_._2())
        pushed = false
        watched = Some(self -> listen(self, () => {
          val now = state.now(); if (!now.busy && now.self == self) call(Request("GET", self), self)
        }, up => pushed = up))
      }
    }
    // a step that listens on the server: asked again every `poll` ms, until it moves on — unless pushed
    def again(s: Shown): Unit = {
      watch(s.self)
      if (s.listening && !s.busy) dom.window.setTimeout(() => {
          val now = state.now()
          if ((now eq s) && now.listening && !now.busy && !pushed) call(Request("GET", now.self), now.self)
          else if ((now eq s) && pushed) again(now)
        }, poll.toDouble)
    }

    div(cls := Role.Face.classes,
      onMountCallback(_ => call(Request("POST", base), base)),
      state.signal --> Observer[Shown](again),
      // the step drawn again only when another arrives: a problem or a call out changes what is shown on it, not its
      // forms — what was typed stays
      child <-- state.signal.distinctByFn((a, b) => a.copy(problems = Nil, busy = false) ==
        b.copy(problems = Nil, busy = false)).map { s =>
        val problems = child <-- state.signal.map(_.problems).distinct.map(ps =>
          if (ps.isEmpty) emptyNode else ul(cls := Role.Problems.classes, ps.map(p => li(p.render))))
        val busy = state.signal.map(_.busy).distinct
        val backButton = if (!s.back) emptyNode
        else button(typ := "button", cls := Role.Back.classes, words.back, disabled <-- busy,
          onClick --> Observer[dom.MouseEvent](_ => back(state.now())))
        val ref: Option[String => Unit] = if (s.actions.exists(_.name == "ref"))
          Some(key => choose(s, Chosen("ref", Json.obj("key" -> Json.Str(key)))))
        else None
        div(sectionTag(cls := Role.View.classes, Views.element(s.view, ref)), problems,
          if (s.done) div(cls := "okay-done", words.done)
          else navTag(cls := Role.Actions.classes,
            s.actions.filterNot(_.name == "ref").map(a =>
              Forms.action(a, choose(state.now(), _), busy, state.signal.map(_.problems).distinct, words)),
            backButton))
      })
  }
}

object RemoteFace {
  /** THE THIN PAGE, whole: `RemoteFace.mount()` — the dialogue on the server behind its REST face at `base`, this page
   * knowing no application; into `#app` or the body */
  def mount(transport: Request => Future[Response], base: String = "/sessions", poll: Int = 1000,
    push: Option[Push] = Some(socket))(implicit ec: ExecutionContext): Unit = {
    val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
    renderOnDomContentLoaded(root, new RemoteFace(transport, poll, okay.faces.Words.english, push).element(base))
  }

  /** a session listened to: `(self, moved, up) => stop` — `moved` when it moved on, `up` whether listening works */
  type Push = (String, () => Unit, Boolean => Unit) => (() => Unit)
  /** THE SESSION'S SOCKET: a WebSocket at `self/socket` on this page's host, each message a move; none (refused,
   * closed), and `up` is false — the page polls */
  val socket: Push = (self, moved, up) => {
    val loc = dom.window.location
    val scheme = if (loc.protocol == "https:") "wss" else "ws"
    scala.util.Try(new dom.WebSocket(s"$scheme://${loc.host}$self/socket")).toOption match {
      case None => up(false); () => ()
      case Some(ws) =>
        ws.onopen = (_: dom.Event) => up(true)
        ws.onmessage = (_: dom.MessageEvent) => moved()
        ws.onclose = (_: dom.CloseEvent) => up(false)
        ws.onerror = (_: dom.Event) => up(false)
        () => { ws.onclose = null; ws.close() }
    }
  }
  def apply(transport: Request => Future[Response], poll: Int = 1000)(implicit ec: ExecutionContext): RemoteFace =
    new RemoteFace(transport, poll)
  /** what the page shows: the session's URL, the step as it came, the problems of the last choice, whether a call is
   * out */
  final case class Shown(self: String, view: View, actions: List[Action[Json]], back: Boolean, done: Boolean,
    problems: Problems, busy: Boolean, listening: Boolean = false)

  /** the REST face's resource: core's `Resource`, as this face shows it */
  def shown(fallbackSelf: String, body: String): Either[String, Shown] =
    Resource.decode(fallbackSelf, body).map(r => Shown(r.self, r.view, r.actions, r.back, r.done, Nil, busy = false,
      r.listening))
  /** a `Trouble`'s problems, else its title */
  def problems(body: String): Problems = Resource.problems(body)
}
