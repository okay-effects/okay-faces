package okay.faces.dom

import okay.effects.Runner
import okay.effects.json.Json
import okay.effects.shape.{Problems, Schema}
import okay.faces.{Chosen, Css, Look, Role, View, Words}
import org.scalajs.dom

/**
 * WHAT THE DOM FACES DRAW, as programs over the faces' effects: a view, an action (a button, or a form from its
 * parameters' schema), the problems of a choice, the buttons a call out disables — the Laminar faces' markup, tag for
 * tag, class for class. The local face and the remote one both draw by it; each runs its programs by `world`.
 */
trait Drawing[F <: Runner with Singleton] {
  implicit val fx: F
  import fx._
  /** the handlers of the faces' effects, and their row `W` */
  val handlers: World[fx.type]
  import handlers.W
  /** EVERY PROGRAM OF THE FACE RUNS HERE: by default by `handlers.world`, in the browser; a face that overrides it may
   * intercept any of its effects first — an audit of what the face does to the page, a test's count */
  def world[X](p: X ! W): X = handlers.world(p)

  // ---- THE WORDS OF THE FACES' EFFECTS

  protected def page[X](op: Page[X]): X ! W = perform[Page].apply[W, X](op)
  protected def unit: Unit ! W = pure[Unit, W](())
  protected def later[X](p: => X ! W): X ! W = delay[X, W](p)
  protected def seq[X](ps: List[X ! W]): List[X] ! W =
    ps.foldRight(pure[List[X], W](Nil))((p, acc) => p.flatMap(a => acc.map(a :: _)))
  protected def node(n: dom.Node): dom.Node ! W = pure[dom.Node, W](n)
  /** an element: its tag, its attributes in order, its children made first */
  protected def el(tag: String, attrs: (String, String)*)(children: List[dom.Node ! W]): dom.html.Element ! W =
    seq(children).flatMap(cs => page(Page.Element[dom.html.Element](tag, attrs.toList, cs, e => e)))
  protected def cls(c: String): (String, String) = "class" -> c
  protected def txt(s: String): dom.Node ! W = page(Page.Text[dom.Node](s, n => n))
  protected def place: dom.Node ! W = page(Page.Place[dom.Node](n => n))
  protected def swap(old: dom.Node, next: dom.Node): Unit ! W = page(Page.Swap[Unit](old, next, u => u))
  protected def put(n: dom.Node, prop: Page.Prop, v: String): Unit ! W = page(Page.Put[Unit](n, prop, v, u => u))
  protected def get(n: dom.Node, prop: Page.Prop): String ! W = page(Page.Get[String](n, prop, s => s))
  protected def listen(t: dom.EventTarget, event: String)(act: dom.Event => Unit): Unit ! W =
    page(Page.Listen[Unit](t, event, act, _ => ()))
  protected def focusFirst(within: dom.Node, selector: String): Boolean ! W =
    perform[Focus].apply[W, Boolean](Focus.First[Boolean](within, selector, b => b))
  protected def onKey(target: dom.EventTarget, key: String)(act: () => Unit): Unit ! W =
    perform[Keys].apply[W, Unit](Keys.On[Unit](target, key, act, _ => ()))

  /** the look as a `<style>`: the same stylesheet the HTML and Laminar faces serve */
  def stylesheet(look: Look = Look.default): dom.Node ! W = el("style")(List(txt(Css(look))))

  // ---- WHAT A STEP DREW: its buttons a call out disables, its forms, the place of its problems

  final class Drawn(var busy: Boolean) {
    var buttons: List[dom.Node] = Nil
    var forms: List[Inputs] = Nil
    var problemsAt: Option[dom.Node] = None
  }

  /** the step's buttons disabled or not, and the face told whether it is busy */
  protected def busy(drawn: Drawn, face: dom.Node, b: Boolean): Unit ! W = later {
    drawn.busy = b
    seq(drawn.buttons.map(put(_, Page.Disabled, b.toString))).flatMap(_ =>
      put(face, Page.Attr("aria-busy"), if (b) "true" else ""))
  }

  /** the problems shown at their place, a live region; the fields they are of marked, the first one focused when the
   * face moves the keyboard */
  protected def problems(drawn: Drawn, ps: Problems, within: dom.Node, focus: Boolean): Unit ! W =
    drawn.problemsAt match {
      case None => unit
      case Some(at) =>
        val made: dom.Node ! W =
          if (ps.isEmpty) place
          else el("ul", cls(Role.Problems.classes), "role" -> "alert")(ps.map(p => el("li")(List(txt(p.render)))))
        made.flatMap(next => swap(at, next).flatMap(_ => later { drawn.problemsAt = Some(next); unit })).flatMap { _ =>
          val wrong = ps.map(_.path).toSet
          seq(drawn.forms.flatMap(_.controls).map { case (path, control) =>
            put(control, Page.Attr("aria-invalid"), if (wrong(path)) "true" else "")
          }).flatMap(_ =>
            // a problem naming a field held back opens its Options; nothing but the person closes them
            seq(drawn.forms.flatMap(f => f.options.toList.map(f.more -> _)).collect {
              case (more, details) if ps.exists(_.path.headOption.exists(more)) => put(details, Page.Open, "true")
            }))
        }.flatMap(_ =>
          if (ps.nonEmpty && focus) focusFirst(within, "[aria-invalid=true]").map(_ => ()) else unit)
    }

  /** a new step's keyboard: its first field, else its first action */
  protected def focusStep(within: dom.Node): Unit ! W =
    focusFirst(within, Drawing.fields).flatMap(found =>
      if (found) unit else focusFirst(within, "nav button").map(_ => ()))

  protected def button(typ: String, c: String, attrs: (String, String)*)(children: List[dom.Node ! W])
    : dom.html.Element ! W = el("button", (Seq("type" -> typ, cls(c)) ++ attrs): _*)(children)

  // ---- AN ACTION: a button, or a form from its parameters' erased schema

  /** what is typed becomes JSON the action's shape decodes — the face does not validate, the shape does, and the
   * problems come back */
  protected def action[X](a: okay.faces.Action[X], choose: Chosen => Unit, drawn: Drawn, words: Words)
    : dom.Node ! W = a.params.erased match {
    case Schema.Record(fields) if fields.isEmpty =>
      button("button", Role.Action.classes, "data-action" -> a.name)(List(txt(a.label))).flatMap { b =>
        later { drawn.buttons = b :: drawn.buttons; put(b, Page.Disabled, drawn.busy.toString) }.flatMap(_ =>
          listen(b, "click")(_ => choose(Chosen.plain(a.name)))).map(_ => b)
      }
    case schema =>
      val inputs = new Inputs(words, a.more, a.long)
      drawn.forms = inputs :: drawn.forms
      for {
        submit <- button("submit", Role.Action.classes)(List(txt(a.label)))
        _ <- later { drawn.buttons = submit :: drawn.buttons; put(submit, Page.Disabled, drawn.busy.toString) }
        f <- el("form", cls(Role.Form.classes), "data-action" -> a.name)(
          a.doc.toList.map(d => el("p", cls(Role.Doc.classes))(List(txt(d)))) ++
            List(inputs.of(Nil, schema), node(submit)))
        _ <- listen(f, "submit") { e =>
          e.preventDefault()
          choose(Chosen(a.name, world(inputs.collect(Nil, schema))))
        }
      } yield f
  }

  /** the inputs of a form, kept by path, read at submit */
  final class Inputs(words: Words, val more: Set[String] = Set.empty, val long: Set[String] = Set.empty) {
    /** the form's Options, the `<details>` of the fields held back, once drawn */
    var options: Option[dom.Node] = None
    private val inputs = scala.collection.mutable.Map.empty[List[String], dom.Node]
    private val selects = scala.collection.mutable.Map.empty[List[String], dom.Node]
    private val areas = scala.collection.mutable.Map.empty[List[String], dom.Node]
    // the fields of long text: a text area each, read whole
    private val longs = scala.collection.mutable.Map.empty[List[String], dom.Node]
    private val items = scala.collection.mutable.Map.empty[List[String], () => Vector[Int]]
    private def id(path: List[String]) = "f" + path.map("-" + _).mkString
    /** the controls by path: where a problem of that path is marked */
    def controls: List[(List[String], dom.Node)] = (inputs ++ selects ++ areas ++ longs).toList

    def of(path: List[String], schema: Schema, field: Option[Schema.Field] = None): dom.Node ! W = schema match {
      case Schema.Record(fs) if path.isEmpty && fs.exists(f => more(f.name)) =>
        // the fields held back, under Options at the end: closed, opened by a problem naming one of them
        val (held, shown) = fs.partition(f => more(f.name))
        for {
          details <- el("details", cls(Role.More.classes))(el("summary")(List(txt(words.options))) ::
            held.toList.map(f => of(path :+ f.name, f.schema, Some(f))))
          _ <- later { options = Some(details); unit }
          set <- el("fieldset")(
            field.flatMap(_.label).orElse(field.map(_.name)).toList.map(l => el("legend")(List(txt(l)))) ++
              shown.toList.map(f => of(path :+ f.name, f.schema, Some(f))) :+ node(details))
        } yield set
      case Schema.Record(fs) => el("fieldset")(
          field.flatMap(_.label).orElse(field.map(_.name)).toList.map(l => el("legend")(List(txt(l)))) ++
            fs.toList.map(f => of(path :+ f.name, f.schema, Some(f))))
      case Schema.Described(of0, label0, doc) =>
        of(path, of0,
          Some(field.getOrElse(Schema.Field(path.lastOption.getOrElse(""), of0, None, None, false, None)).copy(
              label = Some(label0), doc = doc)))
      case Schema.Optional(of0) => of(path, of0, field)
      case Schema.Choice(options) => select(path, options).flatMap(sel => labelled(path, field, sel))
      case Schema.OneOf(cases) =>
        // the case chosen by a select; every case's fields drawn, only the chosen one's shown
        def show(shown: List[(String, dom.Node)], w: String): Unit ! W =
          seq(shown.map { case (n, c) => put(c, Page.Display, if (w == n) "" else "none") }).map(_ => ())
        for {
          sel <- select(path, cases.map(_._1))
          shown <- seq(cases.toList.map { case (n, cs) =>
            el("div", cls("okay-case"), "data-case" -> n)(List(of(path :+ n, cs))).map(c => (n, c: dom.Node))
          })
          _ <- show(shown, cases.headOption.map(_._1).getOrElse(""))
          _ <- listen(sel, "change")(_ => world(get(sel, Page.Value).flatMap(show(shown, _))))
          labelledSel <- labelled(path, field, sel)
          sum <- el("div", cls("okay-sum"))(node(labelledSel) :: shown.map(c => node(c._2)))
        } yield sum
      case Schema.Many(of0, min, max) if !okay.faces.FormJson.leafish(of0) =>
        // a list of records: an item added by a button, removed by one; each item's fields by index
        var next = 0
        var ids = Vector.empty[Int]
        val legendText = field.flatMap(_.label).orElse(field.map(_.name)).orElse(path.lastOption).getOrElse("items")
        def limited(add: dom.Node): Unit ! W = put(add, Page.Disabled, max.exists(ids.size >= _).toString)
        def item(set: dom.Node, add: dom.Node, i: Int): Unit ! W = for {
          remove <- button("button", Role.Back.classes, "data-remove" -> i.toString)(List(txt(words.remove)))
          it <- el("div", cls("okay-item"), "data-item" -> i.toString)(List(of(path :+ i.toString, of0), node(remove)))
          _ <- listen(remove, "click") { _ =>
            ids = ids.filterNot(_ == i)
            world(page(Page.Remove[Unit](it, u => u)).flatMap(_ => limited(add)))
          }
          _ <- page(Page.Insert[Unit](set, it, Some(add), u => u))
        } yield ()
        // the number taken before anything else: an item's key never one already there
        def added(set: dom.Node, add: dom.Node): Unit ! W = later {
          val i = next
          next += 1; ids = ids :+ i
          item(set, add, i).flatMap(_ => limited(add))
        }
        for {
          legend <- el("legend", cls(Role.Label.classes))(List(txt(legendText)))
          add <- button("button", Role.Action.classes, "data-add" -> id(path))(List(txt(words.add)))
          set <- el("fieldset", cls(Role.Field.classes))(List(node(legend), node(add)))
          _ <- seq((0 until (min max 0)).toList.map(_ => added(set, add)))
          _ <- limited(add)
          _ <- listen(add, "click")(_ => world(added(set, add)))
          _ <- later { items(path) = () => ids; unit }
        } yield set
      case Schema.Many(_, _, _) =>
        el("textarea", "id" -> id(path), "placeholder" -> words.onePerLine)(Nil).flatMap { area =>
          later { areas(path) = area; labelled(path, field, area) }
        }
      case Schema.Text(_) if path.size == 1 && long(path.head) =>
        val default = field.flatMap(_.default).map {
          case Json.Str(s) => s
          case other => Json.print(other)
        }
        el("textarea", "id" -> id(path))(default.toList.map(txt)).flatMap { area =>
          later { longs(path) = area; labelled(path, field, area) }
        }
      case leaf =>
        val typ = leaf match {
          case Schema.Integer(_) | Schema.Number(_) => "number"
          case Schema.Bool => "checkbox"
          case Schema.Date => "date"
          case _ => if (field.exists(_.secret)) "password" else "text"
        }
        // A FRACTION IS A NUMBER: without a step a browser takes 1 and refuses 12.5 before the form is sent
        val step = if (leaf match { case Schema.Number(_) => true; case _ => false }) Seq("step" -> "any") else Nil
        for {
          in <- el("input", (Seq("id" -> id(path), "type" -> typ) ++ step): _*)(Nil)
          _ <- field.flatMap(_.default).fold(unit)(d =>
            put(in, Page.Value, d match { case Json.Str(s) => s; case other => Json.print(other) }))
          _ <- later { inputs(path) = in; unit }
          l <- labelled(path, field, in)
        } yield l
    }
    private def select(path: List[String], options: Seq[String]): dom.Node ! W =
      el("select", "id" -> id(path))(options.toList.map(o => el("option", "value" -> o)(List(txt(o))))).flatMap {
        sel => later { selects(path) = sel; node(sel) }
      }
    private def labelled(path: List[String], field: Option[Schema.Field], control: dom.Node): dom.Node ! W = {
      val words: String =
        field.flatMap(_.label).orElse(field.map(_.name)).orElse(path.lastOption).getOrElse("value")
      val doc = field.flatMap(_.doc)
      doc.fold(unit)(d => put(control, Page.Attr("aria-description"), d)).flatMap(_ =>
        el("div", cls(Role.Field.classes))(List(
          el("label", cls(Role.Label.classes), "for" -> id(path))(List(txt(words))), node(control)) ++
          doc.toList.map(d => el("small", cls(Role.Doc.classes))(List(txt(d))))))
    }

    /** what was typed, as JSON along the schema; blank is absent */
    def collect(path: List[String], schema: Schema): Json ! W = schema match {
      case Schema.Record(fs) =>
        seq(fs.toList.map(f => collect(path :+ f.name, f.schema).map(f.name -> _))).map(vs =>
          Json.Obj(vs.filterNot(_._2 == Json.Null).toVector))
      case Schema.Described(of0, _, _) => collect(path, of0)
      case Schema.Optional(of0) => collect(path, of0)
      case Schema.Choice(_) =>
        selects.get(path).fold(pure[Json, W](Json.Null))(s => get(s, Page.Value).map(v => Json.Str(v)))
      case Schema.OneOf(cases) => selects.get(path).fold(pure[Json, W](Json.Null))(s =>
          get(s, Page.Value).flatMap(n => cases.find(_._1 == n) match {
            case Some((n, cs)) => collect(path :+ n, cs).map {
                case o: Json.Obj if Schema.isRecord(cs) => ("type" -> Json.Str(n)) +: o
                case v => Json.Obj.of("type" -> Json.Str(n), "value" -> v)
              }
            case None => pure[Json, W](Json.Null)
          }))
      case Schema.Many(of0, _, _) if items.contains(path) =>
        seq(items(path)().toList.map(i => collect(path :+ i.toString, of0))).map(vs => Json.Arr(vs.toVector))
      case Schema.Many(of0, _, _) => areas.get(path).fold(pure[Json, W](Json.Null))(a => get(a, Page.Value).map(v =>
          Json.Arr(v.split("\n").toVector.map(_.trim).filter(_.nonEmpty).map(l => typed(of0, l)))))
      case Schema.Bool =>
        inputs.get(path).fold(pure[Json, W](Json.Null))(i => get(i, Page.Checked).map(c => Json.Bool(c == "true")))
      case _ if longs.contains(path) =>
        get(longs(path), Page.Value).map(v => if (v.isEmpty) Json.Null else Json.Str(v))
      case leaf => inputs.get(path).fold(pure[Json, W](Json.Null))(i => get(i, Page.Value).map(v =>
          if (v.isEmpty) Json.Null else typed(leaf, v)))
    }
    private def typed(schema: Schema, s: String): Json = schema match {
      case Schema.Integer(_) | Schema.Number(_) =>
        scala.util.Try(BigDecimal(s.trim)).toOption.fold[Json](Json.Str(s))(Json.Num(_))
      case Schema.Bool => Json.Bool(Set("true", "yes", "y", "on").contains(s.trim.toLowerCase))
      case Schema.Described(of0, _, _) => typed(of0, s)
      case _ => Json.Str(s)
    }
  }

  // ---- A VIEW: semantic tags, classes for a stylesheet, nothing else — the Laminar face's, tag for tag

  /** a `View` drawn; `ref`: what a `Ref` does when the step offers the action `ref` — a click chooses it with the key */
  def view(v: View, ref: Option[String => Unit]): dom.Node ! W = v match {
    case View.Empty => el("span")(Nil)
    case View.Text(s) => el("p", cls(Role.Text.classes))(List(txt(s)))
    case View.Heading(1, s) => el("h1", cls(Role.Heading(1).classes))(List(txt(s)))
    case View.Heading(2, s) => el("h2", cls(Role.Heading(2).classes))(List(txt(s)))
    case View.Heading(l, s) => el("h3", cls(Role.Heading(l).classes))(List(txt(s)))
    case View.Notice(level, s) => el("p", cls(Role.Notice(level).classes))(List(txt(s)))
    case View.Fields(rows) => el("dl", cls(Role.Fields.classes))(rows.toList.flatMap { case (k, x) =>
        List(el("dt", cls(Role.Label.classes))(List(txt(k))), el("dd")(List(view(x, ref))))
      })
    case View.Table(columns, rows) =>
      val body = el("tbody")(rows.toList.map(r => el("tr")(r.toList.map(c => el("td")(List(view(c, ref)))))))
      el("table", cls(Role.Table.classes))(
        (if (columns.forall(_.isEmpty)) Nil
         else List(el("thead")(List(el("tr")(columns.toList.map(c => el("th")(List(txt(c))))))))) :+ body)
    case View.Items(items) => el("ul", cls(Role.Items.classes))(items.toList.map(i => el("li")(List(view(i, ref)))))
    case View.Group(title, children) => el("section", cls(Role.Group.classes))(
        title.toList.map(t => el("h4", cls(Role.GroupTitle.classes))(List(txt(t)))) ++
          children.toList.map(view(_, ref)))
    case View.Ref(key, label) =>
      el("a", cls(Role.Ref.classes), "href" -> s"#$key")(List(txt(label))).flatMap { a =>
        ref.fold(node(a))(r => listen(a, "click") { e => e.preventDefault(); r(key) }.map(_ => a))
      }
    // a page ELSEWHERE (another site): a new tab, and the page opened is told nothing of this one; a page of this
    // site (a relative address, `./`): opened in place, as a link within a site is
    case View.Link(label, url) =>
      if (url.contains("://"))
        el("a", cls(Role.Link.classes), "href" -> url, "target" -> "_blank", "rel" -> "noopener noreferrer")(
          List(txt(label)))
      else el("a", cls(Role.Link.classes), "href" -> url)(List(txt(label)))
    case View.Datum(json, _) => el("pre", cls(Role.Datum.classes))(List(txt(Json.pretty(json))))
    case View.Masked(hint) => el("span", cls(Role.Masked.classes))(List(txt(hint)))
    // A PICTURE THE PROGRAM DESCRIBES: an <svg> of its shapes, the Laminar face's markup
    case p: View.Picture =>
      import View.Picture.{Band, Box, Label, cls => mark, n}
      el("div", cls("okay-picture-frame"))(List(el("svg:svg", cls("okay-picture"),
        "viewBox" -> s"0 0 ${p.width} ${p.height}")(el("svg:desc")(List(txt(p.text))) :: p.shapes.toList.map {
        case Box(x, y, w, h, m, t) => el("svg:rect", cls(mark(m)), "x" -> n(x), "y" -> n(y), "width" -> n(w),
            "height" -> n(h))(List(el("svg:title")(List(txt(t)))))
        case b @ Band(_, _, _, _, _, m, t) => el("svg:path", cls(s"${mark(m)} okay-picture-band"),
            "d" -> View.Picture.band(b))(List(el("svg:title")(List(txt(t)))))
        case Label(x, y, t, a) => el("svg:text", cls("okay-picture-label"), "x" -> n(x), "y" -> n(y),
            "text-anchor" -> a)(List(txt(t)))
      })))
    case View.Marked(mark, x) => el("span", cls(Role.Mark(mark).classes))(List(view(x, ref)))
  }
  /** a `View` as a node, drawn now */
  def drawn(v: View): dom.Node = world(view(v, None))
}

object Drawing {
  /** the controls a person types into */
  val fields: String = "input, select, textarea"
}
