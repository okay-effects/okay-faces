package okay.faces.laminar

import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.ReactiveHtmlElement
import okay.effects.Runner
import okay.effects.tagless.Program
import okay.effects.async.{Async, Asynchrony}
import okay.effects.json.Json
import okay.effects.shape.{Problems, Schema}
import okay.faces.{Face, Action, Chosen, Css, Faces, Look, Rejected, Role, Step, View, Words}
import org.scalajs.dom
import scala.concurrent.ExecutionContext

/**
 * THE LAMINAR FACE: a step machine as a Laminar element. The current step is a `Var`; the view is drawn as elements,
 * a plain action is a button, an action with parameters a form drawn from its erased `Schema`; a click or a submit is
 * `resume`, the rest run, the `Var` set — the event loop gets control back between steps, as a browser needs. Back
 * re-resumes the previous `Waiting`; with `browserHistory` the browser's back button does the same. The rest of a step
 * runs by the `Runner` when the row is done (`element`), or to a `Future` when `Async` remains (`elementAsync`).
 * Every element carries its `Role`'s classes; `LaminarFace.stylesheet(look)` is the `<style>` that dresses them.
 */
class LaminarFace[F <: Runner with Singleton](implicit val fx: F) {
  import fx._
  val faces: Faces[fx.type] = new Faces[fx.type]()(fx)

  /** how the rest of a step is run: at once, or when its future completes */
  trait Lower[M[_]] { def apply[X](m: M[X])(k: X => Unit): Unit }

  /** the row done: the rest run at once */
  def element[A](first: Step[A, ({ type L[X] = X ! Pure })#L] ! Pure, done: A => Unit = (_: A) => (),
    settings: LaminarFace.Settings = LaminarFace.Settings()): HtmlElement = {
    type M[X] = X ! Pure
    new Machine[A, M](new Lower[M] { def apply[X](m: M[X])(k: X => Unit): Unit = k(run(m)) }, done, settings)
      .element(first)
  }

  /** THE HANDLER: a program of the face as an element — the dialogue run here, in the browser, with its look as a
   * `<style>`
   * when one is given; `mount` puts it into `#app` or the body: `new LaminarFace().mount(Noughts.games)` */
  def handle[A](p: Program[A, okay.faces.Face], settings: LaminarFace.Settings = LaminarFace.Settings(),
    done: A => Unit = (_: A) => ()): HtmlElement =
    div(settings.look.map(LaminarFace.stylesheet).toList,
      element[A](faces.steps[A, okay.faces.Face, Pure](faces.of(p)), done, settings))
  def mount[A](p: Program[A, okay.faces.Face], settings: LaminarFace.Settings = LaminarFace.Settings(),
    done: A => Unit = (_: A) => ()): Unit = {
    val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
    renderOnDomContentLoaded(root, handle[A](p, settings, done))
  }

  /** `Async` remaining: the rest run to a `Future` — a program that waits (a fetch, a timer) between steps */
  def elementAsync[A](first: Step[A, ({ type L[X] = X ! Async })#L] ! Async, done: A => Unit = (_: A) => (),
    settings: LaminarFace.Settings = LaminarFace.Settings())(implicit ec: ExecutionContext): HtmlElement = {
    type M[X] = X ! Async
    val asynchrony = new Asynchrony[fx.type]()(fx, ec)
    new Machine[A, M](new Lower[M] { def apply[X](m: M[X])(k: X => Unit): Unit = asynchrony.toFuture(m).foreach(k) },
      done, settings).element(first)
  }

  final case class Shown[A, M[_]](told: Vector[View], step: Step[A, M], history: List[Step.Waiting[A, M]],
    problems: Problems, busy: Boolean)

  /** one machine per element: its state, how it runs a rest, what it does at the end */
  final class Machine[A, M[_]](lower: Lower[M], done: A => Unit, settings: LaminarFace.Settings) {
    private val browserHistory = settings.browserHistory
    private val words = settings.look.map(_.words).getOrElse(Words.english)
    val state: Var[Shown[A, M]] =
      Var(Shown(Vector.empty, Step.Told(View.Empty, null.asInstanceOf[M[Step[A, M]]]), Nil, Nil, busy = true))

    def element(first: M[Step[A, M]]): HtmlElement = {
      lower(first)(settle(_, Vector.empty, Nil))
      val popstate: dom.PopStateEvent => Unit = _ => back()
      div(cls := "okay-face",
        onMountCallback(_ => if (browserHistory) dom.window.addEventListener("popstate", popstate)),
        onUnmountCallback(_ => {
          unlisten(); if (browserHistory) dom.window.removeEventListener("popstate", popstate)
        }),
        // the step drawn again only when the step is another: a problem or a call out changes what is shown on it,
        // not its forms — what was typed stays
        child <-- state.signal.distinctByFn((a, b) => (a.step eq b.step) && (a.history eq b.history) &&
          (a.told eq b.told)).map(render))
    }

    /** `Told`s run through, their views kept; a `Waiting` or a `Done` is shown */
    def settle(step: Step[A, M], told: Vector[View], history: List[Step.Waiting[A, M]]): Unit = step match {
      case Step.Told(view, next) =>
        val shown = if (view == View.Empty) told else told :+ view
        state.set(Shown(shown, step, history, Nil, busy = true))
        lower(next)(settle(_, shown, history))
      case w: Step.Waiting[A, M] @unchecked =>
        state.set(Shown(told, w, history, Nil, busy = false))
        if (browserHistory && history.nonEmpty) dom.window.history.pushState(null, "")
        // the step's event: when it fires and this is still the step, the rest runs as a choice's would
        unlisten();
        unlisten = w.listen { next =>
          val s = state.now();
          if ((s.step eq w) && !s.busy) {
            state.set(s.copy(busy = true));
            lower(next)(settle(_, Vector.empty, if (w.back) w :: s.history else s.history))
          }
        }
      case Step.Done(a) =>
        state.set(Shown(told, step, history, Nil, busy = false)); done(a)
    }
    private var unlisten: () => Unit = () => ()
    def back(): Unit = {
      val s = state.now();
      if (s.history.nonEmpty && !s.busy) {
        unlisten(); state.set(Shown(Vector.empty, s.history.head, s.history.tail, Nil, busy = false))
      }
    }
    /** a choice: rejected, the same step with the problems; taken, the rest run */
    def choose(w: Step.Waiting[A, M], chosen: Chosen): Unit = {
      val s = state.now();
      if (!s.busy) w.resume(chosen) match {
        case Left(Rejected(ps)) => state.set(s.copy(problems = ps))
        case Right(next) =>
          unlisten(); state.set(s.copy(busy = true));
          lower(next)(settle(_, Vector.empty, if (w.back) w :: s.history else s.history))
      }
    }

    def render(s: Shown[A, M]): HtmlElement = {
      val notices = s.told.map(v => sectionTag(cls := Role.Told.classes, Views.element(v)))
      s.step match {
        case Step.Done(_) => div(notices, div(cls := "okay-done", Views.element(words.over)))
        case Step.Told(_, _) => div(notices, div(cls := "okay-busy", "…"))
        case w: Step.Waiting[A, M] @unchecked =>
          val problems = child <-- state.signal.map(_.problems).distinct.map(ps =>
            if (ps.isEmpty) emptyNode else ul(cls := Role.Problems.classes, ps.map(p => li(p.render))))
          val busy = state.signal.map(_.busy).distinct
          val backButton = if (s.history.isEmpty) emptyNode
          else
            button(typ := "button", cls := Role.Back.classes, words.back,
              onClick --> Observer[dom.MouseEvent](_ => back()))
          val ref: Option[String => Unit] = if (w.actions.exists(_.name == "ref"))
            Some(key => choose(w, Chosen("ref", Json.obj("key" -> Json.Str(key)))))
          else None
          div(notices, sectionTag(cls := Role.View.classes, Views.element(w.view, ref)), problems,
            navTag(cls := Role.Actions.classes,
              w.actions.filterNot(_.name == "ref").map(a =>
                Forms.action(a, choose(w, _), busy, state.signal.map(_.problems).distinct, words)), backButton))
      }
    }
  }
}

/** a `View` as Laminar elements: semantic tags, classes for a stylesheet, nothing else */
object Views {
  def element(v: View): HtmlElement = element(v, None)
  /** `ref`: what a `Ref` does when the step offers the action `ref` — a click chooses it with the key */
  def element(v: View, ref: Option[String => Unit]): HtmlElement = v match {
    case View.Empty => span()
    case View.Text(s) => p(cls := Role.Text.classes, s)
    case View.Heading(1, s) => h1(cls := Role.Heading(1).classes, s)
    case View.Heading(2, s) => h2(cls := Role.Heading(2).classes, s)
    case View.Heading(l, s) => h3(cls := Role.Heading(l).classes, s)
    case View.Notice(level, s) => p(cls := Role.Notice(level).classes, s)
    case View.Fields(rows) => dl(cls := Role.Fields.classes,
        rows.flatMap { case (k, x) => List(dt(cls := Role.Label.classes, k), dd(element(x, ref))) })
    case View.Table(columns, rows) => table(cls := Role.Table.classes,
        if (columns.forall(_.isEmpty)) emptyNode else thead(tr(columns.map(c => th(c)))),
        tbody(rows.map(r => tr(r.map(c => td(element(c, ref)))))))
    case View.Items(items) => ul(cls := Role.Items.classes, items.map(i => li(element(i, ref))))
    case View.Group(title, children) => sectionTag(cls := Role.Group.classes,
        title.map(t => h4(cls := Role.GroupTitle.classes, t)), children.map(element(_, ref)))
    case View.Ref(key, label) => a(cls := Role.Ref.classes, href := s"#$key", label,
        ref.map(r => onClick.preventDefault --> Observer[dom.MouseEvent](_ => r(key))))
    // a page ELSEWHERE (another site): a new tab, and the page opened is told nothing of this one; a page of this
    // site (a relative address, `./`): opened in place, as a link within a site is
    case View.Link(label, url) =>
      if (url.contains("://")) a(cls := Role.Link.classes, href := url, target := "_blank", rel := "noopener noreferrer",
        label)
      else a(cls := Role.Link.classes, href := url, label)
    case View.Datum(json, _) => pre(cls := Role.Datum.classes, Json.pretty(json))
    case View.Masked(hint) => span(cls := Role.Masked.classes, hint)
    // A PICTURE THE PROGRAM DESCRIBES: an <svg> of its shapes, the markup of `View.Picture.svg`
    case p: View.Picture =>
      import View.Picture.{Band, Box, Label, cls => mark, n}
      div(cls := "okay-picture-frame", svg.svg(svg.cls := "okay-picture", svg.viewBox := s"0 0 ${p.width} ${p.height}",
        svg.desc(p.text),
        p.shapes.map {
          case Box(x, y, w, h, m, t) => svg.rect(svg.cls := mark(m), svg.x := n(x), svg.y := n(y), svg.width := n(w),
              svg.height := n(h), svg.titleTag(t))
          case b @ Band(_, _, _, _, _, m, t) =>
            svg.path(svg.cls := s"${mark(m)} okay-picture-band", svg.d := View.Picture.band(b), svg.titleTag(t))
          case Label(x, y, t, a) => svg.text(svg.cls := "okay-picture-label", svg.x := n(x), svg.y := n(y),
              svg.textAnchor := a, t)
        }))
    case View.Marked(mark, x) => span(cls := Role.Mark(mark).classes, element(x, ref))
  }
}

/** an action as a button, or as a FORM drawn from its parameters' erased schema: a leaf an input, a record a fieldset,
 * a choice a select, a sum a select of its cases with the chosen case's fields, an optional its inner input left blank
 * for none, a list of leaves a textarea one per line. What is typed becomes JSON the action's shape decodes — the face
 * does not validate, the shape does, and the problems come back */
object Forms {
  def action[A](a: Action[A], choose: Chosen => Unit, busy: Boolean, words: Words): HtmlElement =
    action(a, choose, Val(busy), words)
  def action[A](a: Action[A], choose: Chosen => Unit, busy: Boolean): HtmlElement =
    action(a, choose, Val(busy), Words.english)
  /** `busy` a signal: a call going out disables the form, and does not draw it again — what was typed stays */
  def action[A](a: Action[A], choose: Chosen => Unit, busy: Signal[Boolean], words: Words): HtmlElement =
    action(a, choose, busy, Val(Nil), words)
  /** `problems` the step's: one naming a field the form holds back opens its Options */
  def action[A](a: Action[A], choose: Chosen => Unit, busy: Signal[Boolean], problems: Signal[Problems],
    words: Words): HtmlElement =
    a.params.erased match {
      case Schema.Record(fields) if fields.isEmpty =>
        button(typ := "button", cls := Role.Action.classes, dataAttr("action") := a.name, disabled <-- busy, a.label,
          onClick --> Observer[dom.MouseEvent](_ => choose(Chosen.plain(a.name))))
      case schema =>
        val inputs = new Inputs(words, a.more, problems, a.long)
        val body = inputs.of(Nil, schema)
        form(cls := Role.Form.classes, dataAttr("action") := a.name, a.doc.map(d => p(cls := Role.Doc.classes, d)),
          body,
          button(typ := "submit", cls := Role.Action.classes, disabled <-- busy, a.label),
          onSubmit.preventDefault --> Observer[dom.Event](_ => choose(Chosen(a.name, inputs.collect(Nil, schema)))))
    }

  /** the inputs of a form, kept by path, read at submit */
  final class Inputs(words: Words = Words.english, more: Set[String] = Set.empty,
    problems: Signal[Problems] = Val(Nil), long: Set[String] = Set.empty) {
    private val inputs = scala.collection.mutable.Map.empty[List[String], ReactiveHtmlElement[dom.html.Input]]
    private val selects = scala.collection.mutable.Map.empty[List[String], ReactiveHtmlElement[dom.html.Select]]
    private val areas = scala.collection.mutable.Map.empty[List[String], ReactiveHtmlElement[dom.html.TextArea]]
    // the fields of long text: a text area each, read whole
    private val longs = scala.collection.mutable.Map.empty[List[String], ReactiveHtmlElement[dom.html.TextArea]]
    private val items = scala.collection.mutable.Map.empty[List[String], () => Vector[Int]]
    private def id(path: List[String]) = "f" + path.map("-" + _).mkString

    def of(path: List[String], schema: Schema, field: Option[Schema.Field] = None): HtmlElement = schema match {
      case Schema.Record(fs) if path.isEmpty && fs.exists(f => more(f.name)) =>
        // the fields held back, under Options at the end: closed, opened by a problem naming one of them
        val (held, shown) = fs.partition(f => more(f.name))
        fieldSet(field.flatMap(_.label).orElse(field.map(_.name)).map(l => legend(l)),
          shown.map(f => of(path :+ f.name, f.schema, Some(f))),
          detailsTag(cls := Role.More.classes, summaryTag(words.options),
            held.map(f => of(path :+ f.name, f.schema, Some(f))),
            inContext(d => problems --> Observer[Problems](ps =>
              if (ps.exists(_.path.headOption.exists(more)))
                d.ref.asInstanceOf[scala.scalajs.js.Dynamic].open = true))))
      case Schema.Record(fs) => fieldSet(field.flatMap(_.label).orElse(field.map(_.name)).map(l => legend(l)),
          fs.map(f => of(path :+ f.name, f.schema, Some(f))))
      case Schema.Described(of0, label0, doc) =>
        of(path, of0,
          Some(field.getOrElse(Schema.Field(path.lastOption.getOrElse(""), of0, None, None, false, None)).copy(
              label = Some(label0), doc = doc)))
      case Schema.Optional(of0) => of(path, of0, field)
      case Schema.Choice(options) =>
        val sel = select(idAttr := id(path), options.map(o => option(value := o, o)))
        selects(path) = sel
        labelled(path, field, sel)
      case Schema.OneOf(cases) =>
        // the case chosen by a select; every case's fields drawn, only the chosen one's shown
        val which = Var(cases.headOption.map(_._1).getOrElse(""))
        val sel =
          select(idAttr := id(path), cases.map { case (n, _) => option(value := n, n) }, onChange.mapToValue --> which)
        selects(path) = sel
        div(cls := "okay-sum", labelled(path, field, sel),
          cases.map { case (n, cs) =>
            div(cls := "okay-case", dataAttr("case") := n,
              display <-- which.signal.map(w => if (w == n) "" else "none"), of(path :+ n, cs))
          })
      case Schema.Many(of0, min, max) if !okay.faces.FormJson.leafish(of0) =>
        // a list of records: an item added by a button, removed by one; each item's fields by index
        var next = 0
        val ids = Var(Vector.empty[Int])
        // the number taken NOW: an update made while a step is drawn runs later, in Airstream's next transaction, when
        // `next` has moved on — the first item was 1, not 0, and an added one a key already there (shown once)
        def add(): Unit = { val i = next; next += 1; ids.update(_ :+ i) }
        (0 until (min max 0)).foreach(_ => add())
        items(path) = () => ids.now()
        val legendText = field.flatMap(_.label).orElse(field.map(_.name)).orElse(path.lastOption).getOrElse("items")
        fieldSet(cls := Role.Field.classes, legend(cls := Role.Label.classes, legendText),
          children <-- ids.signal.split(identity) { (i, _, _) =>
            div(cls := "okay-item", dataAttr("item") := i.toString, of(path :+ i.toString, of0),
              button(typ := "button", cls := Role.Back.classes, dataAttr("remove") := i.toString, words.remove,
                onClick --> Observer[dom.MouseEvent](_ => ids.update(_.filterNot(_ == i)))))
          },
          button(typ := "button", cls := Role.Action.classes, dataAttr("add") := id(path),
            disabled <-- ids.signal.map(xs => max.exists(xs.size >= _)), words.add,
            onClick --> Observer[dom.MouseEvent](_ => add())))
      case Schema.Many(_, _, _) =>
        val area = textArea(idAttr := id(path), placeholder := words.onePerLine)
        areas(path) = area
        labelled(path, field, area)
      case Schema.Text(_) if path.size == 1 && long(path.head) =>
        val area = textArea(idAttr := id(path), field.flatMap(_.default).map(d =>
          defaultValue := (d match { case Json.Str(s) => s; case other => Json.print(other) })))
        longs(path) = area
        labelled(path, field, area)
      case leaf =>
        val in = input(idAttr := id(path),
          typ := (leaf match {
            case Schema.Integer(_) | Schema.Number(_) => "number"
            case Schema.Bool => "checkbox"
            case Schema.Date => "date"
            case _ => if (field.exists(_.secret)) "password" else "text"
          }),
          field.flatMap(_.default).map(d =>
            value := (d match { case Json.Str(s) => s; case other => Json.print(other) })),
          // A FRACTION IS A NUMBER: without a step a browser takes 1 and refuses 12.5 before the form is sent
          // (jsdom checks nothing, so only a real browser showed it — okay-ui's reference app, 2026-10-23)
          Option.when(leaf match { case Schema.Number(_) => true; case _ => false })(stepAttr := "any"))
        inputs(path) = in
        labelled(path, field, in)
    }
    private def labelled(path: List[String], field: Option[Schema.Field], control: HtmlElement): HtmlElement = {
      val words: String = field.flatMap(_.label).orElse(field.map(_.name)).orElse(path.lastOption).getOrElse("value")
      div(cls := Role.Field.classes, label(cls := Role.Label.classes, forId := id(path), words), control,
        field.flatMap(_.doc).map(d => small(cls := Role.Doc.classes, d)))
    }

    /** what was typed, as JSON along the schema; blank is absent */
    def collect(path: List[String], schema: Schema): Json = schema match {
      case Schema.Record(fs) => Json.Obj(fs.flatMap(f =>
            collect(path :+ f.name, f.schema) match { case Json.Null => None; case v => Some(f.name -> v) }))
      case Schema.Described(of0, _, _) => collect(path, of0)
      case Schema.Optional(of0) => collect(path, of0)
      case Schema.Choice(_) => selects.get(path).map(s => Json.Str(s.ref.value)).getOrElse(Json.Null)
      case Schema.OneOf(cases) => selects.get(path).map(_.ref.value).flatMap(n => cases.find(_._1 == n)) match {
          case Some((n, cs)) => collect(path :+ n, cs) match {
              case o: Json.Obj if Schema.isRecord(cs) => ("type" -> Json.Str(n)) +: o
              case v => Json.Obj.of("type" -> Json.Str(n), "value" -> v)
            }
          case None => Json.Null
        }
      case Schema.Many(of0, _, _) if items.contains(path) =>
        Json.Arr(items(path)().map(i => collect(path :+ i.toString, of0)))
      case Schema.Many(of0, _, _) => areas.get(path).map(a =>
          Json.Arr(a.ref.value.split("\n").toVector.map(_.trim).filter(_.nonEmpty).map(l => typed(of0, l)))).getOrElse(
          Json.Null)
      case Schema.Bool => inputs.get(path).map(i => Json.Bool(i.ref.checked)).getOrElse(Json.Null)
      case _ if longs.contains(path) => Option(longs(path).ref.value).filter(_.nonEmpty).map(Json.Str(_))
          .getOrElse(Json.Null)
      case leaf => inputs.get(path).map(_.ref.value).filter(_.nonEmpty).map(typed(leaf, _)).getOrElse(Json.Null)
    }
    private def typed(schema: Schema, s: String): Json = schema match {
      case Schema.Integer(_) | Schema.Number(_) =>
        scala.util.Try(BigDecimal(s.trim)).toOption.fold[Json](Json.Str(s))(Json.Num(_))
      case Schema.Bool => Json.Bool(Set("true", "yes", "y", "on").contains(s.trim.toLowerCase))
      case Schema.Described(of0, _, _) => typed(of0, s)
      case _ => Json.Str(s)
    }
  }
}

object LaminarFace {
  /** THE FACE'S SETTINGS, one value: how it looks (a `<style>` and the face's words), whether a step is an entry of
   * the browser's history (its back button then goes back a step) */
  final case class Settings(look: Option[Look] = None, browserHistory: Boolean = false)
  /** over the implementation in scope */
  def apply[F <: Runner with Singleton]()(implicit fx: F): LaminarFace[F] = new LaminarFace[F]()(fx)
  /** THE HANDLER, whole, in the page: `LaminarFace.mount(Noughts.games)` */
  def mount[F <: Runner with Singleton, A](p: Program[A, okay.faces.Face], settings: Settings = Settings(),
    done: A => Unit = (_: A) => ())(implicit fx: F): Unit =
    new LaminarFace[F]()(fx).mount[A](p, settings, done)
  /** the look as a `<style>` for the document: the same stylesheet the HTML face serves */
  def stylesheet(look: Look = Look.default): HtmlElement = styleTag(Css(look))

}
