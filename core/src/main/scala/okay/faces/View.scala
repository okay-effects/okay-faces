package okay.faces

import okay.effects.json.Json
import okay.effects.shape.Schema

/**
 * WHAT IS SHOWN: a representation, semantic and nothing else — no layout, no colour, no widths, as semantic HTML or
 * Markdown are and CSS is not. A VALUE: no function inside, so it has equality and crosses a wire; and no secret the
 * program did not put there — `Masked` is how a secret is present without being shown. Every face draws it its way.
 */
sealed trait View
object View {
  case object Empty extends View
  /** a paragraph */
  final case class Text(s: String) extends View
  /** 1 to 3 */
  final case class Heading(level: Int, s: String) extends View
  final case class Notice(level: Notice.Level, s: String) extends View
  object Notice {
    sealed trait Level
    case object Info extends Level
    case object Warning extends Level
    case object Problem extends Level
  }
  /** a record shown: label to value */
  final case class Fields(rows: Vector[(String, View)]) extends View
  final case class Table(columns: Vector[String], rows: Vector[Vector[View]]) extends View
  /** a list */
  final case class Items(items: Vector[View]) extends View
  final case class Group(title: Option[String], children: Vector[View]) extends View
  /** names an entity; a face may make it a link */
  final case class Ref(key: String, label: String) extends View
  /** a page elsewhere, by its address — a source to check, a document: a face draws it as its links are drawn, an
   * anchor on the web, `label <url>` in text; never an action */
  final case class Link(label: String, url: String) extends View
  /** a structured value shown as it is, its schema if known */
  final case class Datum(json: Json, schema: Option[Schema] = None) extends View
  /** a secret NOT shown: "•••• 4821" */
  final case class Masked(hint: String) extends View
  /** a view marked with the application's own word — "price", "overdue" — for a style's rule by `Role.Mark(mark)`;
   * a face shows it as the view within, marked, and the style says what the mark looks like */
  final case class Marked(mark: String, view: View) extends View
  /** A PICTURE THE PROGRAM DESCRIBES, as data (specs/face-core.md "View"): shapes on a `width` × `height` canvas, each
   * marked with the application's word for its look; `text` the picture in words, for a face with no pictures */
  final case class Picture(width: Int, height: Int, shapes: Vector[Picture.Shape], text: String) extends View
  object Picture {
    sealed trait Shape
    /** a box: its corner, its size */
    final case class Box(x: Double, y: Double, w: Double, h: Double, mark: String, title: String) extends Shape
    /** a flow from (x0, y0) to (x1, y1), `thickness` wide; its curve is the face's */
    final case class Band(x0: Double, y0: Double, x1: Double, y1: Double, thickness: Double, mark: String,
      title: String) extends Shape
    /** a text at (x, y), anchored at its `start`, `middle` or `end` */
    final case class Label(x: Double, y: Double, text: String, anchor: String = "start") extends Shape

    /** a number as an SVG attribute writes it: at most two decimals, no exponent */
    def n(d: Double): String = {
      val r = BigDecimal(d).setScale(2, BigDecimal.RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString
      if (r == "-0") "0" else r
    }
    /** a band's outline as SVG path data: its top edge a curve, down its end, its bottom edge back */
    def band(b: Band): String = {
      val h = b.thickness / 2
      val mx = (b.x0 + b.x1) / 2
      s"M${n(b.x0)},${n(b.y0 - h)} C${n(mx)},${n(b.y0 - h)} ${n(mx)},${n(b.y1 - h)} ${n(b.x1)},${n(b.y1 - h)} " +
        s"L${n(b.x1)},${n(b.y1 + h)} C${n(mx)},${n(b.y1 + h)} ${n(mx)},${n(b.y0 + h)} ${n(b.x0)},${n(b.y0 + h)} Z"
    }
    /** THE PICTURE AS SVG TEXT — the HTML face's, and the markup the Laminar and DOM faces make: `esc` escapes text */
    def svg(p: Picture, esc: String => String): String =
      s"""<div class="okay-picture-frame"><svg class="okay-picture" viewBox="0 0 ${p.width} ${p.height}">""" +
        s"""<desc>${esc(p.text)}</desc>""" +
        p.shapes.map {
          case Box(x, y, w, h, m, t) => s"""<rect class="${cls(m)}" x="${n(x)}" y="${n(y)}" width="${n(w)}" """ +
              s"""height="${n(h)}"><title>${esc(t)}</title></rect>"""
          case b @ Band(_, _, _, _, _, m, t) =>
            s"""<path class="${cls(m)} okay-picture-band" d="${band(b)}"><title>${esc(t)}</title></path>"""
          case Label(x, y, t, a) => s"""<text class="okay-picture-label" x="${n(x)}" y="${n(y)}" """ +
              s"""text-anchor="$a">${esc(t)}</text>"""
        }.mkString + "</svg></div>"

    /** a mark's class, as `Marked`'s */
    def cls(mark: String): String = if (mark.isEmpty) "okay-picture-shape" else s"okay-picture-shape okay-mark-$mark"
  }

  def text(s: String): View = Text(s)
  def heading(s: String, level: Int = 1): View = Heading(level, s)
  def info(s: String): View = Notice(Notice.Info, s)
  def warning(s: String): View = Notice(Notice.Warning, s)
  def problem(s: String): View = Notice(Notice.Problem, s)
  def fields(rows: (String, View)*): View = Fields(rows.toVector)
  def table(columns: String*)(rows: Vector[View]*): View = Table(columns.toVector, rows.toVector)
  def items(xs: View*): View = Items(xs.toVector)
  def group(title: String)(children: View*): View = Group(Some(title), children.toVector)
  def stack(children: View*): View = Group(None, children.toVector)
  def marked(mark: String, view: View): View = Marked(mark, view)
  def link(label: String, url: String): View = Link(label, url)
  /** the title of what is shown: the first level-1 heading, depth first — a window's, a page's, a server's name */
  def title(views: Seq[View]): Option[String] = views.iterator.map(title).collectFirst { case Some(t) => t }
  def title(v: View): Option[String] = v match {
    case Heading(1, s) => Some(s)
    case Group(_, cs) => title(cs)
    case Marked(_, x) => title(x)
    case _ => None
  }
}
