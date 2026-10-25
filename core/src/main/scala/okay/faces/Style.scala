package okay.faces

/**
 * HOW IT LOOKS, said once: a `Theme` is the tokens (a palette, a font, a unit of space, a measure), a `Style` is a
 * rule per `Role` in terms of the tokens, and a `Look` is the two together. Every face interprets a look its way: the
 * web as a stylesheet (`Css`), Swing as fonts, colours and borders, the terminal as ANSI when asked. A rule names
 * TONES, not colours, so one style stands under a light and a dark theme. Nothing here is a layout: where things go is
 * each face's own business; the look says what they are made of.
 */

/** WHAT A PART IS: the vocabulary every face marks its output with — a class on the web, a name in Swing, a paint in
 * the terminal — and the key a style's rules are by. `names` are general to specific: a level-1 heading is a heading */
sealed abstract class Role(val names: List[String]) extends Product with Serializable {
  /** the most specific name: "heading-1" */
  def name: String = names.last
  /** the classes on the web and the name in Swing, "okay-" prefixed: "okay-heading okay-heading-1" */
  def classes: String = names.map("okay-" + _).mkString(" ")
  def cls: String = "okay-" + name
}
object Role {
  /** the face itself: the page's column, the window's panel */
  case object Face extends Role(List("face"))
  /** the current step's view */
  case object View extends Role(List("view"))
  /** a view shown earlier in the dialogue, kept above the current one */
  case object Told extends Role(List("told"))
  case object Text extends Role(List("text"))
  final case class Heading(level: Int) extends Role(List("heading", s"heading-${level min 3 max 1}"))
  final case class Notice(level: okay.faces.View.Notice.Level)
    extends Role(List("notice", "notice-" + level.toString.toLowerCase))
  case object Fields extends Role(List("fields"))
  /** a label: a field's name beside its value, in a view or a form */
  case object Label extends Role(List("label"))
  case object Table extends Role(List("table"))
  case object Items extends Role(List("items"))
  case object Group extends Role(List("group"))
  case object GroupTitle extends Role(List("group-title"))
  case object Ref extends Role(List("ref"))
  /** a page elsewhere: `View.Link` */
  case object Link extends Role(List("link"))
  case object Datum extends Role(List("datum"))
  case object Masked extends Role(List("masked"))
  /** the problems with a rejected choice */
  case object Problems extends Role(List("problems"))
  /** the actions offered, together */
  case object Actions extends Role(List("actions"))
  /** one action: a button */
  case object Action extends Role(List("action"))
  /** an action with parameters: its form */
  case object Form extends Role(List("form"))
  case object Doc extends Role(List("doc"))
  /** one parameter: a label and a control */
  case object Field extends Role(List("field"))
  case object Back extends Role(List("back"))
  /** the fields a form holds back, under "Options": `Action.more` */
  case object More extends Role(List("more"))
  /** a picture the program describes: `View.Picture` */
  case object Picture extends Role(List("picture"))
  /** the application's own: a `View.Marked(mark, _)`; its rule the application's, in its `Style`, so the vocabulary
   * of parts stays the library's and the marks are said where they are styled */
  final case class Mark(mark: String) extends Role(List("mark", "mark-" + mark))

  val all: List[Role] = List(Face, View, Told, Text) ++ (1 to 3).map(Heading(_)) ++
    List(okay.faces.View.Notice.Info, okay.faces.View.Notice.Warning, okay.faces.View.Notice.Problem).map(Notice(_)) ++
    List(Fields, Label, Table, Items, Group, GroupTitle, Ref, Link, Datum, Masked, Problems, Actions, Action, Form, Doc,
      Field, Back, More, Picture)
  /** a role by its most specific class, "okay-heading-1"; a mark by its name, "okay-mark-price" */
  val byClass: Map[String, Role] = all.map(r => r.cls -> r).toMap
  def ofClass(cls: String): Option[Role] =
    byClass.get(cls).orElse(if (cls.startsWith("okay-mark-")) Some(Mark(cls.drop("okay-mark-".length))) else None)
}

/** a colour, as the tokens hold it */
final case class Color(r: Int, g: Int, b: Int) {
  def hex: String = f"#$r%02x$g%02x$b%02x"
}
object Color {
  def apply(hex: String): Color = {
    val h = hex.stripPrefix("#");
    Color(Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16),
      Integer.parseInt(h.substring(4, 6), 16))
  }
}

/** a colour's ROLE in the palette: what a rule names instead of a colour */
sealed trait Tone extends Product with Serializable { def name: String = toString.toLowerCase }
object Tone {
  case object Fg extends Tone
  case object Bg extends Tone
  /** text of less weight: docs, hints, back */
  case object Muted extends Tone
  /** a raised surface: a told view, a datum, a notice */
  case object Surface extends Tone
  case object Border extends Tone
  /** what invites: refs, buttons */
  case object Accent extends Tone
  case object Warning extends Tone
  case object Problem extends Tone
  val all: List[Tone] = List(Fg, Bg, Muted, Surface, Border, Accent, Warning, Problem)
}

final case class Palette(fg: Color, bg: Color, muted: Color, surface: Color, border: Color, accent: Color,
  warning: Color, problem: Color) {
  def apply(tone: Tone): Color = tone match {
    case Tone.Fg => fg; case Tone.Bg => bg; case Tone.Muted => muted; case Tone.Surface => surface
    case Tone.Border => border; case Tone.Accent => accent; case Tone.Warning => warning; case Tone.Problem => problem
  }
}

/** the typeface, by kind: each face finds its own */
sealed trait Family extends Product with Serializable
object Family { case object System extends Family; case object Serif extends Family; case object Mono extends Family }

/** THE TOKENS: a palette, a font and its base size (px, points), a unit of space (px), a measure (characters of text
 * across) */
final case class Theme(palette: Palette, font: Family = Family.System, size: Int = 16, space: Int = 8,
  measure: Int = 72)
object Theme {
  val light: Theme = Theme(Palette(fg = Color("#1f2328"), bg = Color("#ffffff"), muted = Color("#636c76"),
      surface = Color("#f6f8fa"),
      border = Color("#d0d7de"), accent = Color("#0969da"), warning = Color("#9a6700"), problem = Color("#cf222e")))
  val dark: Theme = Theme(Palette(fg = Color("#e6edf3"), bg = Color("#0d1117"), muted = Color("#8d96a0"),
      surface = Color("#161b22"),
      border = Color("#30363d"), accent = Color("#58a6ff"), warning = Color("#d29922"), problem = Color("#f85149")))
}

/** children in a column or in a row that wraps */
sealed trait Flow extends Product with Serializable
object Flow { case object Column extends Flow; case object Row extends Flow }

/** A RULE for a role, in tokens: tones, not colours; `size` times the base; `pad` and `gap` in units of space;
 * `centered`, the content in the middle of the part — a cell of a board */
final case class Rule(color: Option[Tone] = None, background: Option[Tone] = None, border: Option[Tone] = None,
  bold: Boolean = false, size: Double = 1.0, mono: Boolean = false, pad: Int = 0, gap: Int = 0,
  flow: Flow = Flow.Column,
  centered: Boolean = false) {
  def isEmpty: Boolean = this == Rule()
}

/** THE RULES, by role; a style is complete: every role has one, `Rule()` where nothing is said */
final case class Style(rules: Map[Role, Rule]) {
  def apply(role: Role): Rule = rules.getOrElse(role, Rule())
  def set(role: Role, rule: Rule): Style = Style(rules.updated(role, rule))
  /** the roles' rules changed the same way: `adjust(Heading(1), Table, Actions)(_.copy(centered = true))` */
  def adjust(roles: Role*)(f: Rule => Rule): Style = roles.foldLeft(this)((s, r) => s.set(r, f(s(r))))
}
object Style {
  import Role._
  import Tone._
  val plain: Style = Style(Map.empty)
  /** what a dialogue needs to be read: emphasis by role, surfaces for what is told and shown, tones for what warns */
  val default: Style = Style(Map(
      Face -> Rule(color = Some(Fg), background = Some(Bg), pad = 2, gap = 2),
      View -> Rule(gap = 1),
      Told -> Rule(background = Some(Surface), pad = 1, gap = 1),
      Heading(1) -> Rule(bold = true, size = 1.6),
      Heading(2) -> Rule(bold = true, size = 1.3),
      Heading(3) -> Rule(bold = true, size = 1.1),
      Notice(okay.faces.View.Notice.Info) -> Rule(color = Some(Muted), background = Some(Surface), pad = 1),
      Notice(okay.faces.View.Notice.Warning) -> Rule(color = Some(Warning), border = Some(Warning), pad = 1),
      Notice(okay.faces.View.Notice.Problem) -> Rule(color = Some(Problem), border = Some(Problem), pad = 1),
      Fields -> Rule(gap = 1),
      Label -> Rule(bold = true),
      Table -> Rule(border = Some(Border), pad = 1),
      Group -> Rule(gap = 1),
      GroupTitle -> Rule(bold = true, color = Some(Muted)),
      Ref -> Rule(color = Some(Accent)),
      Link -> Rule(color = Some(Accent)),
      Datum -> Rule(mono = true, background = Some(Surface), pad = 1),
      Masked -> Rule(mono = true, color = Some(Muted)),
      Problems -> Rule(color = Some(Problem)),
      Actions -> Rule(flow = Flow.Row, gap = 2),
      Action -> Rule(color = Some(Bg), background = Some(Accent), bold = true, pad = 1),
      Form -> Rule(border = Some(Border), pad = 2, gap = 1),
      Doc -> Rule(color = Some(Muted)),
      Field -> Rule(gap = 0),
      Back -> Rule(color = Some(Muted), background = Some(Surface), pad = 1)))
}

/** a theme and a style: what a face is given */
final case class Look(theme: Theme, style: Style, words: Words = Words.english)
object Look {
  val default: Look = Look(Theme.light, Style.default)
  val dark: Look = Look(Theme.dark, Style.default)
}

/** THE FACES' OWN WORDS to a person — a button's, a hint's, a message's; what a program says is the program's, these
 * are what a face adds. English by default; an application gives its own in its `Look` */
final case class Words(back: String = "Back", help: String = "Help", done: String = "done", add: String = "Add",
  remove: String = "Remove", startAgain: String = "Start again", noSuchSession: String = "No such session.",
  keyHint: String = "a [key]", backHint: String = "< back", helpHint: String = "? help", quitHint: String = "q quit",
  typeAKey: String = "type a [key]", oneOf: String = "problem: one of", howMany: String = "how many",
  optional: String = "empty for none", default: String = "default", hidden: String = "hidden",
  problem: String = "problem", onePerLine: String = "one per line", options: String = "Options") {
  /** the notice a face shows when the dialogue is over */
  def over: View = View.info(done)
}
object Words {
  val english: Words = Words()
}

/** THE WEB'S INTERPRETER: a look as a stylesheet. The tokens as custom properties on `:root`, so a page may override
 * them; a block per role, by its specific class, saying only what the rule says. The HTML and Laminar faces mark the
 * same classes, so one stylesheet serves both */
object Css {
  /** the tokens of a theme, as custom properties */
  def tokens(t: Theme): String = {
    val font = t.font match {
      case Family.System => "system-ui, sans-serif"; case Family.Serif => "Georgia, serif";
      case Family.Mono => "ui-monospace, monospace"
    }
    Tone.all.map(tone => s"--okay-${tone.name}:${t.palette(tone).hex}").mkString(";") +
      s";--okay-font:$font;--okay-mono:ui-monospace, monospace;--okay-size:${t.size}px;--okay-space:${t.space}px;" +
      s"--okay-measure:${t.measure}ch"
  }
  /** one stylesheet for two themes: the light one's tokens, the dark one's under `prefers-color-scheme: dark`; the
   * rules are the light look's — a style names tones, so they stand under both */
  def apply(light: Look, dark: Look): String =
    apply(light) + s"\n@media (prefers-color-scheme: dark){:root{${tokens(dark.theme)}}}"
  def apply(look: Look): String = {
    val t = look.theme
    val font = t.font match {
      case Family.System => "system-ui, sans-serif"; case Family.Serif => "Georgia, serif";
      case Family.Mono => "ui-monospace, monospace"
    }
    val vars = Tone.all.map(tone => s"--okay-${tone.name}:${t.palette(tone).hex}").mkString(";") +
      s";--okay-font:$font;--okay-mono:ui-monospace, monospace;--okay-size:${t.size}px;--okay-space:${t.space}px;" +
      s"--okay-measure:${t.measure}ch"
    val base = s":root{$vars}" +
      "body{margin:0;background:var(--okay-bg)}" +
      ".okay-face{font:var(--okay-size)/1.5 var(--okay-font);max-width:var(--okay-measure);margin:0 auto;" +
      "box-sizing:border-box}" +
      ".okay-face h1,.okay-face h2,.okay-face h3,.okay-face h4,.okay-face p,.okay-face dl,.okay-face ul," +
      ".okay-face pre{margin:0}" +
      ".okay-table{border-collapse:collapse}.okay-form,.okay-field{display:flex;flex-direction:column}" +
      ".okay-field{align-items:flex-start}" +
      ".okay-action{border:0;font:inherit;cursor:pointer}.okay-back{border:0;font:inherit;cursor:pointer}" +
      ".okay-more>summary{cursor:pointer}" +
      ".okay-picture{display:block;width:100%;height:auto}.okay-picture-shape{fill:currentColor}" +
      ".okay-picture-band{fill-opacity:.55}.okay-picture-label{fill:var(--okay-fg);font-size:12px}"
    val marks = look.style.rules.keys.collect { case m: Role.Mark => m }.toList.sortBy(_.mark)
    val blocks = (Role.all ++ marks).flatMap { role =>
      val r = look.style(role)
      val cells = if (role == Role.Table)
        Some(s".okay-table th,.okay-table td{${declarations(r.copy(background = None))}}")
      else None
      val own = declarations(if (role == Role.Table) r.copy(border = None, pad = 0) else r)
      (if (own.isEmpty) Nil else List(s".${selector(role)}{$own}")) ++ cells
    }
    (base :: blocks).mkString("\n")
  }

  /** a role's class as a selector: a qualified mark's dot escaped, `okay-mark-shop\\.price` */
  def selector(role: Role): String = role.cls.replace(".", "\\.")

  def declarations(r: Rule): String = {
    val ds = List(
      r.color.map(t => s"color:var(--okay-${t.name})"),
      r.background.map(t => s"background:var(--okay-${t.name})"),
      r.border.map(t => s"border:1px solid var(--okay-${t.name})"),
      if (r.bold) Some("font-weight:bold") else None,
      if (r.size != 1.0) Some(s"font-size:${r.size}em") else None,
      if (r.mono) Some("font-family:var(--okay-mono)") else None,
      if (r.pad > 0) Some(s"padding:calc(${r.pad} * var(--okay-space))") else None,
      if (r.gap > 0 || r.flow == Flow.Row)
        Some((if (r.flow == Flow.Row) "display:flex;flex-wrap:wrap;align-items:center"
              else "display:flex;flex-direction:column") + s";gap:calc(${r.gap} * var(--okay-space))")
      else None,
      if (r.centered) Some("justify-content:center;align-items:center;text-align:center") else None
    ).flatten
    ds.mkString(";")
  }
}

/** A TEXT FACE'S INTERPRETER: a role's text, dressed — ANSI in a terminal, nothing in a pipe */
trait Paint { def apply(role: Role, s: String): String }
object Paint {
  val none: Paint = new Paint { def apply(role: Role, s: String): String = s }
}
