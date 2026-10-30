package okay.faces.ios

import okay.effects.Runner
import okay.effects.json.{Descent, Json}
import okay.effects.shape.{Problem, Schema}
import okay.effects.tagless.Program
import okay.faces._

/**
 * WHAT THE SHELL ASKS OF AN APPLICATION (specs/ios.md): screens and choices as JSON text, nothing typed — what crosses
 * from Scala Native to Swift is a string each way. `open` the first screen, `act` a choice and the screen after,
 * `back` the step before; `look` how it looks; `onMoved` told when an event moved the dialogue on by itself.
 */
trait IosDialogue {
  def open(): String
  def act(choice: String): String
  def back(): String
  def look: String
  def onMoved(moved: () => Unit): Unit
}

/**
 * THE iOS FACE, its Scala half: a program of `Face` as a `Session`, each screen the step's view with its actions
 * LOWERED to fields by dotted name (`IosForm`), each choice the fields typed, read back by core's `FormJson` as the
 * HTML face reads a posted form. The other half, the drawing, is Swift's (`ios/swift`), and knows no application.
 */
class IosFace[F <: Runner with Singleton](implicit val fx: F) {
  import fx._
  val faces: Faces[fx.type] = new Faces[fx.type]()(fx)

  /** HOW THE REST OF A STEP IS RUN: `Pure` at once; a program on a phone with its device handled in place */
  trait Lower[R[_]] { def apply[X](p: X ! R): X }
  val atOnce: Lower[Pure] = new Lower[Pure] { def apply[X](p: X ! Pure): X = run(p) }
  /** the device's operations answered by `port`, each in place, then run */
  def onDevice(port: Device.Port): Lower[Device.Rest] = new Lower[Device.Rest] {
    def apply[X](p: X ! Device.Rest): X = run(handle[Device, Device.Rest, Pure, X, X](p,
      new TailHandler[Device, Pure, X, X] {
        def ret(x: X) = pure(x)
        def answer[Y](op: Device[Y]): Y = Device.answer(op, port.call(Device.request(op)))
      })(splitLast[Pure, Device]))
  }

  /** the program as a dialogue for the shell, in `look` */
  def dialogue[A](p: Program[A, okay.faces.Face], look: Look = Look.default): IosDialogue =
    dialogueIn[A, Pure](faces.steps[A, okay.faces.Face, Pure](faces.of(p)), atOnce, look)

  /** A PROGRAM ON A PHONE: its screens and its device — the device's operations answered by `port` (the shell's) */
  def phone[A](p: Program[A, Device.Phone], port: Device.Port, look: Look = Look.default): IosDialogue =
    dialogueIn[A, Device.Rest](faces.steps[A, Device.Phone, Device.Rest](p.on(fx))(splitLast[Device.Rest,
      okay.faces.Face]), onDevice(port), look)

  /**
   * A PROGRAM THAT IS TAKEN UP WHERE IT WAS (`Durable`): each point it passes kept on the phone under `key`, and the
   * next run started there — iOS may end an app in the background at any time, and the person finds it as they left
   * it. A point that no longer reads (the program changed) starts the program afresh.
   */
  def durable[A](d: Durable[A], port: Device.Port, look: Look = Look.default,
    key: String = "okay-faces.point"): IosDialogue = {
    val kept = Device.answer(Device.Load[Option[String]](key, v => v),
      port.call(Device.request(Device.Load[Option[String]](key, v => v))))
    val point = kept.flatMap(t => okay.effects.json.Descent.parse(t).toOption).flatMap {
      case o: Json.Obj => for (Json.Str(n) <- o.get("name"); s <- o.get("state")) yield Step.Point(n, s)
      case _ => None
    }
    val program = point.flatMap(d.from).getOrElse(d.program)
    dialogueIn[A, Pure](faces.steps[A, okay.faces.Face, Pure](faces.of(program)), atOnce, look, p => {
      val text = Json.print(Json.obj("name" -> Json.Str(p.name), "state" -> p.state))
      port.call(Device.request(Device.Save[Unit](key, Some(text), u => u))); ()
    })
  }

  /** the dialogue of a step machine whose rests are run by `lower`; `marked` told each point the program passes */
  def dialogueIn[A, R[_]](first: Step[A, ({ type L[X] = X ! R })#L] ! R, lower: Lower[R],
    look: Look = Look.default, marked: Step.Point => Unit = _ => ()): IosDialogue = {
    val session = faces.session[A, R](first, look.words.over)
    session.marked = marked
    val looks = Json.print(IosLook.json(look, IosLook.dark(look)))
    new IosDialogue {
      /** the last choice's problems, while its step waits */
      private var problems: List[Problem] = Nil
      private def screen(): String = {
        val (told, view) = lower(session.shown)
        val actions = lower(session.actions)
        Json.print(IosFace.screen(told, view, actions, problems, session.canGoBack, lower(session.done),
          lower(session.listening)))
      }
      def open(): String = synchronized(IosFace.guarded(screen()))
      def act(choice: String): String = synchronized(IosFace.guarded {
        val (name, form) = IosFace.choice(choice)
        val actions = lower(session.actions)
        val args = actions.find(_.name == name).fold[Json](Json.Obj.empty)(a =>
          FormJson.collect(a.params.erased, form))
        problems = lower(session.choose(Chosen(name, args))).fold(_.problems, _ => Nil)
        screen()
      })
      def back(): String = synchronized(IosFace.guarded { if (session.back()) problems = Nil; screen() })
      def look: String = looks
      def onMoved(moved: () => Unit): Unit = session.moved = moved
    }
  }
}

object IosFace {
  /** the dialogue of a program written over `import okay.faces._`, on the implementation in scope */
  def apply[F <: Runner with Singleton, A](p: Program[A, okay.faces.Face], look: Look = Look.default)(
    implicit fx: F): IosDialogue = new IosFace[F]()(fx).dialogue(p, look)
  /** the dialogue of a program on a phone, its device answered by `port` — on an iPhone, `IosExports.device` */
  def phone[F <: Runner with Singleton, A](p: Program[A, Device.Phone], port: Device.Port,
    look: Look = Look.default)(implicit fx: F): IosDialogue = new IosFace[F]()(fx).phone(p, port, look)
  /** the dialogue of a program taken up where it was, its points kept on the phone (`IosFace#durable`) */
  def durable[F <: Runner with Singleton, A](d: Durable[A], port: Device.Port, look: Look = Look.default)(
    implicit fx: F): IosDialogue = new IosFace[F]()(fx).durable(d, port, look)

  /** THE SCREEN as the shell reads it */
  def screen(told: Vector[View], view: View, actions: List[Action[_]], problems: List[Problem], back: Boolean,
    done: Boolean, listening: Boolean): Json =
    Json.Obj(View.title(told :+ view).map(t => "title" -> Json.Str(t)).toVector ++ Vector(
      "told" -> Json.Arr(told.map(ViewJson.encode)),
      "view" -> ViewJson.encode(view),
      "actions" -> Json.Arr(actions.toVector.map(IosForm.action)),
      "problems" -> Json.Arr(problems.toVector.map(p =>
        Json.obj("path" -> Json.Arr(p.path.toVector.map(Json.Str(_))), "message" -> Json.Str(p.message)))),
      "back" -> Json.Bool(back), "done" -> Json.Bool(done), "listening" -> Json.Bool(listening)))

  /** a choice: `{"action": name, "form": {"a.b": ["v"] or "v"}}` — the action's name and the fields typed */
  def choice(text: String): (String, Map[String, List[String]]) = Descent.parse(text).getOrElse(Json.Null) match {
    case o: Json.Obj =>
      val name = o.get("action").collect { case Json.Str(n) => n }.getOrElse("")
      val form = o.get("form").collect { case f: Json.Obj => f.fields }.getOrElse(Vector.empty).map {
        case (k, Json.Arr(vs)) => k -> vs.toList.map(leaf)
        case (k, v) => k -> List(leaf(v))
      }.toMap
      (name, form)
    case _ => ("", Map.empty)
  }
  private def leaf(v: Json): String = v match { case Json.Str(s) => s; case other => Json.print(other) }

  /** what went wrong inside, as a screen the shell shows rather than a crash of the application */
  def guarded(screen: => String): String =
    try screen
    catch {
      case e: Throwable =>
        Json.print(Json.obj("error" -> Json.Str(Option(e.getMessage).getOrElse(e.getClass.getName))))
    }
}

/**
 * AN ACTION'S FORM, LOWERED for a shell that reads no `Schema`: its fields in order, each by its DOTTED NAME — the
 * HTML face's, so core's `FormJson.collect` reads them back — with a kind a native control is chosen by. What the
 * schema's walk needs is done here, once, and tested on the JVM; the shell draws a flat list (specs/ios.md).
 */
object IosForm {
  def action(a: Action[_]): Json = Json.Obj(Vector("name" -> Json.Str(a.name), "label" -> Json.Str(a.label)) ++
    a.doc.map(d => "doc" -> Json.Str(d)) ++ Vector("fields" -> Json.Arr(fields(a)),
      "ref" -> Json.Bool(a.name == "ref")))

  def fields(a: Action[_]): Vector[Json] = a.params.erased match {
    case Schema.Record(fs) => fs.flatMap(f => lower(List(f.name), f.schema, Some(f), a.long, a.more(f.name), true))
    case schema => lower(Nil, schema, None, a.long, false, true)
  }

  private def name(path: List[String]): String = path.mkString(".")

  def lower(path: List[String], s: Schema, f: Option[Schema.Field], long: Set[String], more: Boolean,
    required: Boolean): Vector[Json] = {
    def field(kind: String, extra: (String, Json)*): Vector[Json] = {
      val label = f.flatMap(_.label).orElse(f.map(_.name)).orElse(path.lastOption).getOrElse("value")
      val default = f.flatMap(_.default).map(leaf)
      Vector(Json.Obj(Vector("name" -> Json.Str(name(path)), "kind" -> Json.Str(kind), "label" -> Json.Str(label)) ++
        f.flatMap(_.doc).map(d => "doc" -> Json.Str(d)) ++ default.map(d => "default" -> Json.Str(d)) ++
        Vector("required" -> Json.Bool(required && default.isEmpty && kind != "bool"), "more" -> Json.Bool(more)) ++
        extra))
    }
    s match {
      case Schema.Described(of, label, doc) =>
        lower(path, of, Some(f.getOrElse(Schema.Field(path.lastOption.getOrElse(""), of, None, None, false, None))
          .copy(label = Some(label), doc = doc)), long, more, required)
      case Schema.Optional(of) => lower(path, of, f, long, more, false)
      case Schema.Record(fs) =>
        field("group", "fields" -> Json.Arr(fs.flatMap(x => lower(path :+ x.name, x.schema, Some(x), Set.empty,
          false, required))))
      case Schema.OneOf(cases) =>
        field("oneof", "cases" -> Json.Arr(cases.map { case (n, cs) =>
          Json.obj("name" -> Json.Str(n), "fields" -> Json.Arr(inner(path :+ n, cs)))
        }))
      case Schema.Many(of, min, max) if !FormJson.leafish(of) =>
        field("many", Vector("min" -> Json.num(min), "item" -> Json.Arr(inner(path :+ "#", of))) ++
          max.map(m => "max" -> Json.num(m)): _*)
      case Schema.Many(_, _, _) => field("lines")
      case Schema.Text(_) if path.size == 1 && long(path.head) => field("long")
      case Schema.Text(_) => field(if (f.exists(_.secret)) "secret" else "text")
      case Schema.Integer(_) => field("integer")
      case Schema.Number(_) => field("number")
      case Schema.Bool => field("bool")
      case Schema.Date => field("date")
      case Schema.Choice(options) => field("choice", "options" -> Json.Arr(options.toVector.map(Json.Str(_))))
      case _ => field("text")
    }
  }
  /** a case's or an item's fields: a record's own, at its path, not a group of them */
  private def inner(path: List[String], s: Schema): Vector[Json] = s match {
    case Schema.Record(fs) => fs.flatMap(x => lower(path :+ x.name, x.schema, Some(x), Set.empty, false, true))
    case Schema.Described(of, _, _) if Schema.isRecord(of) => inner(path, of)
    case other => lower(path, other, None, Set.empty, false, true)
  }
  private def leaf(v: Json): String = v match { case Json.Str(s) => s; case other => Json.print(other) }
}

/** A LOOK AS THE SHELL READS IT: the tokens, the rules by the role's most specific name, the face's words */
object IosLook {
  /** the look at night: the same style and words on the dark tokens, when the look is on the light ones; a look of
   * its own tokens is its own at night too */
  def dark(look: Look): Option[Look] = if (look.theme == Theme.light) Some(look.copy(theme = Theme.dark)) else None

  /** the look, and its palette at night (`dark`) when it has one: the shell takes the one the phone is set to */
  def json(look: Look, night: Option[Look]): Json = json(look) match {
    case o: Json.Obj =>
      night.fold[Json](o)(n => Json.Obj(o.fields :+ ("dark" -> Json.obj("palette" -> palette(n.theme)))))
    case other => other
  }
  private def palette(t: Theme): Json =
    Json.Obj(Tone.all.toVector.map(tone => tone.name -> Json.Str(t.palette(tone).hex)))

  def json(look: Look): Json = {
    val t = look.theme
    def font(f: Family): String =
      f match { case Family.Serif => "serif"; case Family.Mono => "mono"; case _ => "system" }
    def rule(r: Rule): Json = Json.Obj(r.color.map(c => "color" -> Json.Str(c.name)).toVector ++
      r.background.map(c => "background" -> Json.Str(c.name)) ++ r.border.map(c => "border" -> Json.Str(c.name)) ++
      Vector("bold" -> Json.Bool(r.bold), "size" -> Json.Num(BigDecimal(r.size)), "mono" -> Json.Bool(r.mono),
        "pad" -> Json.num(r.pad), "gap" -> Json.num(r.gap),
        "flow" -> Json.Str(if (r.flow == Flow.Row) "row" else "column"), "centered" -> Json.Bool(r.centered)))
    val words = look.words
    Json.obj(
      "theme" -> Json.obj(
        "palette" -> palette(t),
        "font" -> Json.Str(font(t.font)), "size" -> Json.num(t.size), "space" -> Json.num(t.space),
        "measure" -> Json.num(t.measure)),
      "rules" -> Json.Obj(look.style.rules.toVector.sortBy(_._1.name).map { case (role, r) => role.name -> rule(r) }),
      "words" -> Json.Obj(words.productElementNames.toVector.zip(words.productIterator.toVector).collect {
        case (k, v: String) => k -> Json.Str(v)
      }))
  }
}
