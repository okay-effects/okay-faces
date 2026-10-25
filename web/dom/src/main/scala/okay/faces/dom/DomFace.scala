package okay.faces.dom

import okay.effects.Runner
import okay.effects.tagless.Program
import okay.effects.async.{Async, Asynchrony}
import okay.effects.json.Json
import okay.effects.shape.Problems
import okay.faces.{Chosen, Faces, Look, Rejected, Role, Step, View, Words}
import org.scalajs.dom
import scala.concurrent.ExecutionContext

/**
 * THE DOM FACE: a step machine in the page, no library between — the same markup as the Laminar face, element for
 * element, class for class, so a look's stylesheet dresses it the same: only the handler changes.
 *
 * It is a PROGRAM OVER ITS OWN EFFECTS — `Page` (the DOM), `History` (the browser's back), `Focus` (where the keyboard
 * is), `Keys` (keys listened to) — handled inside it by the world's handlers (`World`): the world is reached in one
 * place, and `world` may be overridden to intercept any of them. What it draws is `Drawing`'s, shared with the remote
 * face. A new step is drawn again; a problem or a call out changes only what is shown on it (the problems, the
 * buttons' `disabled`), so what was typed stays. Accessibility by the same effects: the problems a live region, a
 * field with a problem `aria-invalid` and focused, a field's doc its `aria-description`, the face `aria-busy` while a
 * rest runs; Escape is back where the step offers it; a new step's first field focused (`Settings.focus`). The rest
 * of a step runs by the `Runner` when the row is done (`element`), or to a `Future` when `Async` remains
 * (`elementAsync`).
 */
class DomFace[F <: Runner with Singleton](implicit val fx: F) extends Drawing[F] {
  import fx._
  val faces: Faces[fx.type] = new Faces[fx.type]()(fx)
  val handlers: World[fx.type] = new World[fx.type]()(fx)
  import handlers.W

  /** how the rest of a step is run: at once, or when its future completes */
  trait Lower[M[_]] { def apply[X](m: M[X])(k: X => Unit): Unit }

  /** the row done: the rest run at once */
  def element[A](first: Step[A, ({ type L[X] = X ! Pure })#L] ! Pure, done: A => Unit = (_: A) => (),
    settings: DomFace.Settings = DomFace.Settings()): dom.html.Element = {
    type M[X] = X ! Pure
    new Machine[A, M](new Lower[M] { def apply[X](m: M[X])(k: X => Unit): Unit = k(run(m)) }, done, settings)
      .element(first)
  }

  /** THE HANDLER: a program of the face as an element — the dialogue run here, in the browser, with its look as a
   * `<style>` when one is given; `mount` puts it into `#app` or the body: `new DomFace().mount(Noughts.games)` */
  def handle[A](p: Program[A, okay.faces.Face], settings: DomFace.Settings = DomFace.Settings(),
    done: A => Unit = (_: A) => ()): dom.html.Element = {
    val face = element[A](faces.steps[A, okay.faces.Face, Pure](faces.of(p)), done, settings)
    world(el("div")(settings.look.toList.map(l => stylesheet(l)) :+ node(face)))
  }
  def mount[A](p: Program[A, okay.faces.Face], settings: DomFace.Settings = DomFace.Settings(),
    done: A => Unit = (_: A) => ()): Unit =
    DomFace.whenLoaded { () =>
      val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
      val shown = handle[A](p, settings, done)
      world(page(Page.Insert[Unit](root, shown, None, u => u)).flatMap(_ =>
        // in the page now: the first field takes the keyboard
        if (settings.focus) focusStep(shown) else unit))
    }

  /** `Async` remaining: the rest run to a `Future` — a program that waits (a fetch, a timer) between steps */
  def elementAsync[A](first: Step[A, ({ type L[X] = X ! Async })#L] ! Async, done: A => Unit = (_: A) => (),
    settings: DomFace.Settings = DomFace.Settings())(implicit ec: ExecutionContext): dom.html.Element = {
    type M[X] = X ! Async
    val asynchrony = new Asynchrony[fx.type]()(fx, ec)
    new Machine[A, M](new Lower[M] { def apply[X](m: M[X])(k: X => Unit): Unit = asynchrony.toFuture(m).foreach(k) },
      done, settings).element(first)
  }

  final case class Shown[A, M[_]](told: Vector[View], step: Step[A, M], history: List[Step.Waiting[A, M]],
    problems: Problems, busy: Boolean)

  // ---- THE MACHINE: one per element — what is shown, how it runs a rest, what it does at the end

  /** ONE FACE, PROGRAM AFTER PROGRAM: its element made once; each `show` runs a program's steps in it, the page kept
   * where the next step offers what the last offered (specs/web.md, keeping what is on the page) */
  final class Session[A, M[_]] private[DomFace] (machine: Machine[A, M]) {
    def element: dom.html.Element = machine.face
    def show(first: M[Step[A, M]], done: A => Unit): Unit = machine.show(first, done)
  }
  /** a session whose rest runs at once */
  def session[A](settings: DomFace.Settings = DomFace.Settings()): Session[A, ({ type L[X] = X ! Pure })#L] = {
    type M[X] = X ! Pure
    val m = new Machine[A, M](new Lower[M] { def apply[X](m: M[X])(k: X => Unit): Unit = k(run(m)) }, _ => (), settings)
    m.session = true
    m.open()
    new Session[A, M](m)
  }

  final class Machine[A, M[_]](lower: Lower[M], private var done: A => Unit, settings: DomFace.Settings) {
    private val words = settings.look.map(_.words).getOrElse(Words.english)
    private var shown: Shown[A, M] =
      Shown(Vector.empty, Step.Told(View.Empty, null.asInstanceOf[M[Step[A, M]]]), Nil, Nil, busy = true)
    // the face, the step drawn in it, what that step drew
    private[DomFace] var face: dom.html.Element = null
    // one program after another: a program's end not drawn — the next one's first step changes the last in place
    private[DomFace] var session = false
    private var at: dom.Node = null
    // the step's view, where it is drawn: changed in place when the next step offers the same
    private var viewAt: dom.Node = null
    private var drawn: Drawn = new Drawn(true)
    private var unlisten: () => Unit = () => ()
    // a press while the face was busy with an event: kept for the next step, if it offers the same
    private var pressed: Option[Chosen] = None

    def element(first: M[Step[A, M]]): dom.html.Element = {
      open()
      lower(first)(settle(_, Vector.empty, Nil))
      face
    }
    /** another program in the same face */
    def show(first: M[Step[A, M]], done: A => Unit): Unit = {
      this.done = done
      lower(first)(settle(_, Vector.empty, Nil))
    }
    private[DomFace] def open(): Unit =
      world(for {
        f <- el("div", cls("okay-face"), "aria-busy" -> "true")(Nil)
        d <- place
        _ <- page(Page.Insert[Unit](f, d, None, u => u))
        _ <- later { face = f; at = d; unit }
        // Escape is back, where the step offers it
        _ <- onKey(f, "Escape")(() => back())
        _ <- if (settings.browserHistory)
          perform[History].apply[W, Unit](History.Back[Unit](() => back(), () => face.isConnected, _ => ()))
        else unit
      } yield ())

    /** a new state: drawn again when the step is another, else its problems and buttons changed in place */
    private def set(s: Shown[A, M]): Unit ! W = later {
      val before = shown
      shown = s
      if (!(before.step eq s.step) && kept(before, s)) {
        // the same actions: the view changed in place, the forms untouched — what is typed stays
        val w = s.step.asInstanceOf[Step.Waiting[A, M]]
        for {
          next <- viewOf(w)
          now <- page(Page.Morph[dom.Node](viewAt, next, n => n))
          _ <- later { viewAt = now; problems(drawn, s.problems, at, focus = false) }
          _ <- busy(drawn, face, s.busy)
          // a press made meanwhile: on this step, which offers what the one pressed offered
          _ <- later { if (!s.busy) pressed.foreach { c => pressed = None; chooseNow(c) }; unit }
        } yield ()
      } else if (!(before.step eq s.step) || !(before.history eq s.history) || !(before.told eq s.told)) {
        pressed = None
        render(s).flatMap(next => swap(at, next).flatMap(_ => later { at = next; busy(drawn, face, s.busy) }))
          .flatMap(_ => s.step match {
            // a new step takes the keyboard, where the face is in the page
            case _: Step.Waiting[A, M] @unchecked if settings.focus && face.isConnected => focusStep(at)
            case _ => unit
          })
      }
      else
        (if (before.problems != s.problems) problems(drawn, s.problems, at, settings.focus && face.isConnected)
         else unit).flatMap(_ => if (before.busy != s.busy) busy(drawn, face, s.busy) else unit)
    }

    /** `Told`s run through, their views kept; a `Waiting` or a `Done` is shown */
    def settle(step: Step[A, M], told: Vector[View], history: List[Step.Waiting[A, M]]): Unit = step match {
      case Step.Told(view, next) =>
        val kept = if (view == View.Empty) told else told :+ view
        world(set(Shown(kept, step, history, Nil, busy = true)))
        lower(next)(settle(_, kept, history))
      case w: Step.Waiting[A, M] @unchecked =>
        world(set(Shown(told, w, history, Nil, busy = false)).flatMap(_ =>
          if (settings.browserHistory && history.nonEmpty) perform[History].apply[W, Unit](History.Push[Unit](u => u))
          else unit))
        // the step's event: when it fires and this is still the step, the rest runs as a choice's would
        unlisten()
        unlisten = w.listen { next =>
          val s = shown
          if ((s.step eq w) && !s.busy) {
            // busy, but nothing disabled: an event is no press — a press made meanwhile goes to the step it brings
            shown = s.copy(busy = true)
            lower(next)(settle(_, Vector.empty, if (w.back) w :: s.history else s.history))
          }
        }
      case Step.Done(a) if session => done(a)
      case Step.Done(a) =>
        world(set(Shown(told, step, history, Nil, busy = false))); done(a)
    }
    def back(): Unit = {
      val s = shown
      if (s.history.nonEmpty && !s.busy) {
        unlisten(); world(set(Shown(Vector.empty, s.history.head, s.history.tail, Nil, busy = false)))
      }
    }
    /** a choice: rejected, the same step with the problems; taken, the rest run */
    def choose(w: Step.Waiting[A, M], chosen: Chosen): Unit = {
      val s = shown
      if (!s.busy) w.resume(chosen) match {
        case Left(Rejected(ps)) => world(set(s.copy(problems = ps)))
        case Right(next) =>
          unlisten(); world(set(s.copy(busy = true)))
          lower(next)(settle(_, Vector.empty, if (w.back) w :: s.history else s.history))
      }
    }

    /** whether a step may be shown by changing the one shown in place: both wait, with the same actions, the same
     * history and told views, the face drawn */
    private def kept(before: Shown[A, M], s: Shown[A, M]): Boolean = (before.step, s.step) match {
      case (b: Step.Waiting[A, M] @unchecked, n: Step.Waiting[A, M] @unchecked) =>
        viewAt != null && (before.history eq s.history) && before.told == s.told &&
          b.actions.map(DomFace.signature) == n.actions.map(DomFace.signature)
      case _ => false
    }
    /** a step's view, drawn: a ref chooses on the step shown when it is clicked */
    private def viewOf(w: Step.Waiting[A, M]): dom.Node ! W = {
      val ref: Option[String => Unit] = if (w.actions.exists(_.name == "ref"))
        Some(key => chooseNow(Chosen("ref", Json.obj("key" -> Json.Str(key)))))
      else None
      el("section", cls(Role.View.classes))(List(view(w.view, ref)))
    }
    /** a choice on the step shown now: a button kept from an earlier step of the same actions means the same; made
     * while the face is busy with an event, kept for the step that comes of it */
    private def chooseNow(chosen: Chosen): Unit = shown.step match {
      case w: Step.Waiting[A, M] @unchecked if !shown.busy => choose(w, chosen)
      case _ if shown.busy => pressed = Some(chosen)
      case _ => ()
    }

    def render(s: Shown[A, M]): dom.Node ! W = later {
      drawn = new Drawn(s.busy)
      val notices = s.told.toList.map(v => el("section", cls(Role.Told.classes))(List(view(v, None))))
      s.step match {
        case Step.Done(_) =>
          el("div")(notices :+ el("div", cls("okay-done"))(List(view(words.over, None))))
        case Step.Told(_, _) => el("div")(notices :+ el("div", cls("okay-busy"))(List(txt("…"))))
        case w: Step.Waiting[A, M] @unchecked =>
          val actions: List[dom.Node ! W] =
            w.actions.filterNot(_.name == "ref").map(a => action(a, chooseNow, drawn, words))
          val backButton: List[dom.Node ! W] =
            if (s.history.isEmpty) Nil
            else List(button("button", Role.Back.classes)(List(txt(words.back))).flatMap(b =>
              listen(b, "click")(_ => back()).map(_ => b)))
          for {
            v <- viewOf(w)
            _ <- later { viewAt = v; unit }
            problemsAt <- place
            nav <- el("nav", cls(Role.Actions.classes))(actions ++ backButton)
            step <- el("div")(notices ++ List(node(v), node(problemsAt), node(nav)))
            _ <- later { drawn.problemsAt = Some(problemsAt); problems(drawn, s.problems, step, focus = false) }
          } yield step
      }
    }
  }
}

object DomFace {
  /** what an action offers, all but what it makes: two steps of equal signatures offer the same */
  def signature(a: okay.faces.Action[_]): (String, String, Option[String], okay.effects.shape.Schema, Set[String],
    Set[String]) = (a.name, a.label, a.doc, a.params.erased, a.more, a.long)
  /** THE FACE'S SETTINGS: the Laminar face's — how it looks (a `<style>` and the face's words), whether a step is an
   * entry of the browser's history (its back button then goes back a step) — and whether the face moves the keyboard
   * (a new step's first field, a problem's field) */
  final case class Settings(look: Option[Look] = None, browserHistory: Boolean = false, focus: Boolean = true)
  /** over the implementation in scope */
  def apply[F <: Runner with Singleton]()(implicit fx: F): DomFace[F] = new DomFace[F]()(fx)
  /** THE HANDLER, whole, in the page: `DomFace.mount(Noughts.games)` */
  def mount[F <: Runner with Singleton, A](p: Program[A, okay.faces.Face], settings: Settings = Settings(),
    done: A => Unit = (_: A) => ())(implicit fx: F): Unit =
    new DomFace[F]()(fx).mount[A](p, settings, done)
  /** `act` once the document is there: at once, or when it has loaded */
  private[dom] def whenLoaded(act: () => Unit): Unit =
    if (dom.document.readyState == "loading") dom.document.addEventListener("DOMContentLoaded", (_: dom.Event) => act())
    else act()
}
