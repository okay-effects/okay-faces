package okay.faces.dom

import okay.effects.{Runner, Sum, Void}
import okay.http.{Request, Response}
import org.scalajs.dom
import scala.concurrent.Future
import scala.util.Try

/**
 * THE DOM FACE'S WORLD, as effects: what the face does to the page is operations, and one handler of each does it —
 * the browser's, here. So the face is a program; the DOM is reached in one place; a test may intercept any of them.
 */

/** THE PAGE: the DOM's nodes made, put, changed, read, listened to */
sealed trait Page[X]
object Page {
  /** an element: its tag, its attributes in order (`class` among them, where it is set), its children */
  final case class Element[X](tag: String, attrs: List[(String, String)], children: List[dom.Node],
    answer: dom.html.Element => X) extends Page[X]
  final case class Text[X](s: String, answer: dom.Node => X) extends Page[X]
  /** a place for a node to come: a comment, matched by no selector */
  final case class Place[X](answer: dom.Node => X) extends Page[X]
  final case class Swap[X](old: dom.Node, next: dom.Node, answer: Unit => X) extends Page[X]
  /** whether the page is scrolled to its end (within a line or two): a person reading what comes last */
  final case class AtEnd[X](answer: Boolean => X) extends Page[X]
  /** the page scrolled to its end: what came last in view */
  final case class ToEnd[X](answer: Unit => X) extends Page[X]
  /** `old` made `next` IN PLACE: what is equal kept, the rest changed or replaced (`World.morph`); the node now there */
  final case class Morph[X](old: dom.Node, next: dom.Node, answer: dom.Node => X) extends Page[X]
  /** `child` into `parent`, before `before` or last */
  final case class Insert[X](parent: dom.Node, child: dom.Node, before: Option[dom.Node], answer: Unit => X)
    extends Page[X]
  final case class Remove[X](node: dom.Node, answer: Unit => X) extends Page[X]
  /** a listener; the answer takes it off */
  final case class Listen[X](target: dom.EventTarget, event: String, act: dom.Event => Unit,
    answer: (() => Unit) => X) extends Page[X]
  /** a property read: `value`, `checked` ("true" or "false") */
  final case class Get[X](node: dom.Node, prop: Prop, answer: String => X) extends Page[X]
  final case class Put[X](node: dom.Node, prop: Prop, value: String, answer: Unit => X) extends Page[X]

  /** what `Get` and `Put` touch */
  sealed trait Prop
  case object Value extends Prop
  case object Checked extends Prop
  /** "true" or "false" */
  case object Disabled extends Prop
  /** the style's `display`: "" or "none" */
  case object Display extends Prop
  /** a `<details>` open: "true" or "false" */
  case object Open extends Prop
  /** an attribute; `Put` of "" takes it off */
  final case class Attr(name: String) extends Prop
}

/** THE BROWSER'S HISTORY: a step an entry of it; its back button listened to */
sealed trait History[X]
object History {
  final case class Push[X](answer: Unit => X) extends History[X]
  /** `act` when the browser goes back, while `alive`; the answer takes the listener off */
  final case class Back[X](act: () => Unit, alive: () => Boolean, answer: (() => Unit) => X) extends History[X]
}

/** WHERE THE KEYBOARD IS */
sealed trait Focus[X]
object Focus {
  /** the first element `within` matching `selector` focused; whether there was one */
  final case class First[X](within: dom.Node, selector: String, answer: Boolean => X) extends Focus[X]
}

/** KEYS LISTENED TO */
sealed trait Keys[X]
object Keys {
  /** `act` when `key` is pressed within `target`; the answer takes the listener off */
  final case class On[X](target: dom.EventTarget, key: String, act: () => Unit, answer: (() => Unit) => X)
    extends Keys[X]
}

/** A SERVER: a request sent through the face's transport, its reply — a response or a failure — to `reply` when it
 * comes; the program goes on at once */
sealed trait Server[X]
object Server {
  final case class Send[X](request: Request, reply: Try[Response] => Unit, answer: Unit => X) extends Server[X]
}

/** TIME: `act` after `ms` milliseconds */
sealed trait Timer[X]
object Timer {
  final case class After[X](ms: Int, act: () => Unit, answer: Unit => X) extends Timer[X]
}

/**
 * THE WORLD, the handlers of the faces' effects over an implementation: each answers in place, by the browser's own
 * calls — the DOM, its history, focus, keys, a timer, and `transport` for the server (none, by default: a face in the
 * page alone). `world` runs a program of the faces' row with all of them.
 */
class World[F <: Runner with Singleton](transport: Request => Future[Response] = World.nowhere)(implicit val fx: F) {
  import fx._
  type P1[X] = Sum[Void, Page, X]
  type P2[X] = Sum[P1, History, X]
  type P3[X] = Sum[P2, Focus, X]
  type P4[X] = Sum[P3, Keys, X]
  type P5[X] = Sum[P4, Server, X]
  /** THE FACES' ROW: the page, the history, the focus, the keys, the server, time */
  type W[X] = Sum[P5, Timer, X]

  def page[R[_], Rest[_], A](p: A ! R)(implicit s: Split[Page, R, Rest]): A ! Rest =
    handle[Page, R, Rest, A, A](p, new TailHandler[Page, Rest, A, A] {
      def ret(a: A): A ! Rest = pure(a)
      def answer[X](op: Page[X]): X = op match {
        case Page.Element(tag, attrs, children, answer) =>
          val e = dom.document.createElement(tag).asInstanceOf[dom.html.Element]
          attrs.foreach { case (k, v) => e.setAttribute(k, v) }
          children.foreach(e.appendChild)
          answer(e)
        case Page.Text(s, answer) => answer(dom.document.createTextNode(s))
        case Page.Place(answer) => answer(dom.document.createComment(""))
        case Page.Swap(old, next, answer) =>
          if (old.parentNode != null) old.parentNode.replaceChild(next, old); answer(())
        case Page.AtEnd(answer) =>
          val doc = dom.document.documentElement
          answer(dom.window.innerHeight + dom.window.pageYOffset >= doc.scrollHeight - 96)
        case Page.ToEnd(answer) =>
          dom.window.scrollTo(0, dom.document.documentElement.scrollHeight); answer(())
        case Page.Morph(old, next, answer) =>
          val kept = World.morph(old, next)
          if (!(kept eq old) && old.parentNode != null) old.parentNode.replaceChild(kept, old)
          answer(kept)
        case Page.Insert(parent, child, before, answer) =>
          before.fold(parent.appendChild(child))(b => parent.insertBefore(child, b)); answer(())
        case Page.Remove(node, answer) => if (node.parentNode != null) node.parentNode.removeChild(node); answer(())
        case Page.Listen(target, event, act, answer) =>
          val f: scala.scalajs.js.Function1[dom.Event, Unit] = (e: dom.Event) => act(e)
          target.addEventListener(event, f)
          World.listened.add(target)
          answer(() => target.removeEventListener(event, f))
        case Page.Get(node, prop, answer) => answer(prop match {
            case Page.Value => node.asInstanceOf[scala.scalajs.js.Dynamic].value.asInstanceOf[String]
            case Page.Checked => node.asInstanceOf[dom.html.Input].checked.toString
            case Page.Disabled => node.asInstanceOf[dom.html.Button].disabled.toString
            case Page.Display => node.asInstanceOf[dom.html.Element].style.display
            case Page.Open => node.asInstanceOf[scala.scalajs.js.Dynamic].open.asInstanceOf[Boolean].toString
            case Page.Attr(name) => Option(node.asInstanceOf[dom.Element].getAttribute(name)).getOrElse("")
          })
        case Page.Put(node, prop, value, answer) =>
          prop match {
            case Page.Value => node.asInstanceOf[scala.scalajs.js.Dynamic].value = value
            case Page.Checked => node.asInstanceOf[dom.html.Input].checked = value == "true"
            case Page.Disabled => node.asInstanceOf[dom.html.Button].disabled = value == "true"
            case Page.Display => node.asInstanceOf[dom.html.Element].style.display = value
            case Page.Open => node.asInstanceOf[scala.scalajs.js.Dynamic].open = value == "true"
            case Page.Attr(name) =>
              if (value.isEmpty) node.asInstanceOf[dom.Element].removeAttribute(name)
              else node.asInstanceOf[dom.Element].setAttribute(name, value)
          }
          answer(())
      }
    })

  def history[R[_], Rest[_], A](p: A ! R)(implicit s: Split[History, R, Rest]): A ! Rest =
    handle[History, R, Rest, A, A](p, new TailHandler[History, Rest, A, A] {
      def ret(a: A): A ! Rest = pure(a)
      def answer[X](op: History[X]): X = op match {
        case History.Push(answer) => dom.window.history.pushState(null, ""); answer(())
        case History.Back(act, alive, answer) =>
          lazy val f: scala.scalajs.js.Function1[dom.PopStateEvent, Unit] = (_: dom.PopStateEvent) =>
            // the face gone from the page: its listener with it
            if (alive()) act() else dom.window.removeEventListener("popstate", f)
          dom.window.addEventListener("popstate", f)
          answer(() => dom.window.removeEventListener("popstate", f))
      }
    })

  def focus[R[_], Rest[_], A](p: A ! R)(implicit s: Split[Focus, R, Rest]): A ! Rest =
    handle[Focus, R, Rest, A, A](p, new TailHandler[Focus, Rest, A, A] {
      def ret(a: A): A ! Rest = pure(a)
      def answer[X](op: Focus[X]): X = op match {
        case Focus.First(within, selector, answer) =>
          within.asInstanceOf[dom.Element].querySelector(selector) match {
            case null => answer(false)
            case e => e.asInstanceOf[dom.html.Element].focus(); answer(true)
          }
      }
    })

  def keys[R[_], Rest[_], A](p: A ! R)(implicit s: Split[Keys, R, Rest]): A ! Rest =
    handle[Keys, R, Rest, A, A](p, new TailHandler[Keys, Rest, A, A] {
      def ret(a: A): A ! Rest = pure(a)
      def answer[X](op: Keys[X]): X = op match {
        case Keys.On(target, key, act, answer) =>
          val f: scala.scalajs.js.Function1[dom.KeyboardEvent, Unit] = (e: dom.KeyboardEvent) =>
            if (e.key == key) { e.preventDefault(); act() }
          target.addEventListener("keydown", f)
          answer(() => target.removeEventListener("keydown", f))
      }
    })

  def server[R[_], Rest[_], A](p: A ! R)(implicit s: Split[Server, R, Rest]): A ! Rest =
    handle[Server, R, Rest, A, A](p, new TailHandler[Server, Rest, A, A] {
      def ret(a: A): A ! Rest = pure(a)
      def answer[X](op: Server[X]): X = op match {
        case Server.Send(request, reply, answer) =>
          // the reply on the browser's own queue, as an event of the page would come
          transport(request).onComplete(reply)(scala.scalajs.concurrent.JSExecutionContext.queue); answer(())
      }
    })

  def timer[R[_], Rest[_], A](p: A ! R)(implicit s: Split[Timer, R, Rest]): A ! Rest =
    handle[Timer, R, Rest, A, A](p, new TailHandler[Timer, Rest, A, A] {
      def ret(a: A): A ! Rest = pure(a)
      def answer[X](op: Timer[X]): X = op match {
        case Timer.After(ms, act, answer) => dom.window.setTimeout(() => act(), ms.toDouble); answer(())
      }
    })

  /** a program of the faces' row, run in the browser */
  def world[A](p: A ! W): A = run(page[P1, Void, A](history[P2, P1, A](focus[P3, P2, A](keys[P4, P3, A](
    server[P5, P4, A](timer[W, P5, A](p)))))))
}

object World {
  /** no server: a face in the page alone sends nothing */
  val nowhere: Request => Future[Response] =
    r => Future.failed(new IllegalStateException(s"no server to send ${r.method} ${r.path} to"))

  /** the nodes listened to: in a morph, each is kept whole or replaced whole */
  private[dom] val listened: scala.scalajs.js.Dynamic =
    scala.scalajs.js.Dynamic.newInstance(scala.scalajs.js.Dynamic.global.WeakSet)()
  private def listens(n: dom.Node): Boolean = listened.has(n).asInstanceOf[Boolean]

  /**
   * `old` made like `next`, keeping what it can: a node equal to the new one is kept; text of a text node changed; an
   * element of the same tag, neither listened to, keeps its node — its attributes made the new ones' (a `<details>`
   * keeps whether a person opened it), its children morphed position by position, the new ones appended, the extra
   * ones removed; anything else is the new node. The answer: the node to stand where `old` stood.
   */
  def morph(old: dom.Node, next: dom.Node): dom.Node =
    if (old.isEqualNode(next)) old
    else if (old.nodeType == dom.Node.TEXT_NODE && next.nodeType == dom.Node.TEXT_NODE) {
      old.textContent = next.textContent; old
    } else if (old.nodeType == dom.Node.ELEMENT_NODE && next.nodeType == dom.Node.ELEMENT_NODE &&
      old.nodeName == next.nodeName && !listens(old) && !listens(next)) {
      val o = old.asInstanceOf[dom.Element]
      val n = next.asInstanceOf[dom.Element]
      val details = o.nodeName.equalsIgnoreCase("details")
      val gone = (0 until o.attributes.length).map(i => o.attributes(i).name).filterNot(n.hasAttribute)
      gone.filterNot(a => details && a == "open").foreach(o.removeAttribute)
      (0 until n.attributes.length).map(i => n.attributes(i)).foreach { a =>
        if (!(details && a.name == "open") && o.getAttribute(a.name) != a.value) o.setAttribute(a.name, a.value)
      }
      val olds = (0 until o.childNodes.length).map(o.childNodes(_)).toVector
      val news = (0 until n.childNodes.length).map(n.childNodes(_)).toVector
      olds.zip(news).foreach { case (x, y) =>
        val kept = morph(x, y)
        if (!(kept eq x)) o.replaceChild(kept, x)
      }
      news.drop(olds.size).foreach(o.appendChild)
      olds.drop(news.size).foreach(o.removeChild)
      old
    } else next
}
