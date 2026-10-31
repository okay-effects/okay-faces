package okay.faces.swing

import java.awt.{BorderLayout, Component, Font, GridLayout}
import javax.swing._
import javax.swing.table.DefaultTableModel
import okay.effects.Runner
import okay.effects.tagless.{Program, Tagless}
import okay.effects.json.Json
import okay.effects.shape.{Problems, Schema}
import okay.faces.{Face, Action, Chosen, Faces, Look, Rejected, Role, Step, View, Words}
import scala.collection.mutable

/**
 * THE SWING FACE: a step machine as a Swing panel. The view drawn as components, a plain action a button, an action
 * with
 * parameters a form drawn from its erased schema; a click is `resume`, the rest run, the panel rebuilt. Back
 * re-resumes the previous `Waiting`. `panel` is the face; `window` puts it in a frame on the event dispatch thread.
 * The rest of a step runs by the `Runner`: the row is done before the face. With a `Look`, the components are dressed
 * by their roles (`Looks`) after each rebuild; without one, the platform's look and feel stands.
 * `F` is the implementation's singleton type (`Machine.type`): the faces come implicitly, from `Faces.default` over the
 * implicit implementation, and the `Singleton` bound keeps the type exact when Scala 3 infers it — `new SwingFace()`
 * with `import okay.effects.machine.Machine._`, on both compilers.
 */
class SwingFace[F <: Runner with Singleton](implicit val fx: F) {
  import fx._
  val faces: Faces[fx.type] = new Faces[fx.type]()(fx)
  type M[X] = X ! Pure

  /** HOW THE REST OF A STEP IS RUN, when the program leaves effects in the row beside `Face`: a window runs the rest
   * by it, on the event dispatch thread. `Pure` by `run`, `atOnce`;
   * a program with `Console` in its row (one written for the
   * terminal too) by the real console, `io.realConsole` then `run` */
  trait Lower[R[_]] { def apply[X](p: X ! R): X }
  val atOnce: Lower[Pure] = new Lower[Pure] { def apply[X](p: X ! Pure): X = run(p) }

  final case class Shown[A, N[_]](told: Vector[View], step: Step[A, N], history: List[Step.Waiting[A, N]],
    problems: Problems)

  /** the face as a panel, rebuilt at each step; `done` with the value at the end */
  def panel[A](first: Step[A, M] ! Pure, done: A => Unit = (_: A) => (),
    settings: SwingFace.Settings = SwingFace.Settings()): JPanel = panelIn[A, Pure](first, atOnce, done, settings)

  /** the same over a program that leaves `R` in its row: `lower` runs each rest */
  def panelIn[A, R[_]](first: Step[A, ({ type L[X] = X ! R })#L] ! R, lower: Lower[R], done: A => Unit = (_: A) => (),
    settings: SwingFace.Settings = SwingFace.Settings()): JPanel = {
    type N[X] = X ! R
    val look = settings.look
    val root = new JPanel(new BorderLayout)
    root.setName(Role.Face.cls)
    def settle(step: Step[A, N], told: Vector[View], history: List[Step.Waiting[A, N]]): Shown[A, N] = step match {
      case Step.Told(view, next) => settle(lower(next), told :+ view, history)
      case other => Shown(told, other, history, Nil)
    }
    var current: Option[Step[A, N]] = None
    var unlisten: () => Unit = () => ()
    /** the window grows with its content, within the screen, and never shrinks: a step that adds a line adds no
     * scrollbar */
    def grow(): Unit = Option(SwingUtilities.getWindowAncestor(root)).foreach { w =>
      val viewport = Option(SwingUtilities.getAncestorOfClass(classOf[JViewport], root)).map(_.asInstanceOf[JViewport])
      viewport.foreach { v =>
        val need = root.getPreferredSize
        val screen = java.awt.Toolkit.getDefaultToolkit.getScreenSize
        val h = math.min(w.getHeight - v.getHeight + need.height + 8, screen.height - 120)
        val wd = math.min(w.getWidth - v.getWidth + need.width + 8, screen.width - 80)
        if (h > w.getHeight || wd > w.getWidth) w.setSize(math.max(wd, w.getWidth), math.max(h, w.getHeight))
      }
    }
    def show(s: Shown[A, N]): Unit = {
      current = Some(s.step)
      root.removeAll()
      root.add(render[A, N](s, show, settle, lower(_), look.map(_.words).getOrElse(Words.english),
        () => { look.foreach(l => Looks(l)(root)); root.revalidate(); root.repaint(); grow() }), BorderLayout.CENTER)
      look.foreach(l => Looks(l)(root))
      root.revalidate(); root.repaint()
      grow()
      unlisten()
      s.step match {
        case Step.Done(a) => done(a)
        case w: Step.Waiting[A, N] @unchecked =>
          // the step's event: when it fires and this is still the step, the rest runs on the event dispatch thread as a
          // click's would
          unlisten = w.listen(next =>
            SwingUtilities.invokeLater(new Runnable {
              def run(): Unit =
                if (current.exists(_ eq w)) show(settle(lower(next), Vector.empty, w :: s.history))
            }))
        case _ => ()
      }
    }
    show(settle(lower(first), Vector.empty, Nil))
    root
  }

  /** THE HANDLER: a program of the face in a window — titled by the first level-1 heading it shows, dressed by `look`;
   * what the program tells before its first offer (`Step.opening`) is the window's HELP, behind the `?` at the top
   * right,
   * not a line above the first step that the next step scrolls away; the result the dialogue's, or `None` when the
   * window is closed first: `run(new SwingFace().handle(Noughts.games))` */
  def handle[A](p: Program[A, okay.faces.Face], settings: SwingFace.Settings = SwingFace.Settings())
    : Option[A] ! Pure = {
    val steps = faces.steps[A, okay.faces.Face, Pure](faces.of(p))
    pure(()).flatMap { _ =>
      val opening = Step.opening[A, M](steps, run)
      val outcome = new java.util.concurrent.LinkedBlockingQueue[Option[A]](1)
      window(opening.title.getOrElse("okay"), pure[Step[A, M], Pure](opening.first),
        (a: A) => outcome.offer(Some(a)),
        settings = settings, closed = () => outcome.offer(None), help = opening.intro)
      pure[Option[A], Pure](outcome.take())
    }
  }

  /** the face in a window, on the event dispatch thread; with `pages` (core's `Navigation` in memory) the window's
   * title
   * follows the program's current page */
  def window[A](title: String, first: Step[A, M] ! Pure, done: A => Unit = (_: A) => (),
    pages: Option[(okay.faces.Pages[_], Any => String)] = None, settings: SwingFace.Settings = SwingFace.Settings(),
    closed: () => Unit = () => sys.exit(0), help: Vector[View] = Vector.empty): Unit =
    windowIn[A, Pure](title, first, atOnce, done, pages, settings, closed, help)

  /** the same over a program that leaves `R` in its row: `lower` runs each rest */
  def windowIn[A, R[_]](title: String, first: Step[A, ({ type L[X] = X ! R })#L] ! R, lower: Lower[R],
    done: A => Unit = (_: A) => (), pages: Option[(okay.faces.Pages[_], Any => String)] = None,
    settings: SwingFace.Settings = SwingFace.Settings(), closed: () => Unit = () => sys.exit(0),
    help: Vector[View] = Vector.empty): Unit = {
    val look = settings.look
    SwingUtilities.invokeLater(new Runnable {
      def run(): Unit = {
        val frame = new JFrame(title)
        pages.foreach { case (ps, name) =>
          ps.asInstanceOf[okay.faces.Pages[Any]].onChange(p => frame.setTitle(s"$title — ${name(p)}"))
        }
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
        frame.addWindowListener(new java.awt.event.WindowAdapter {
          override def windowClosed(e: java.awt.event.WindowEvent): Unit = closed()
        })
        frame.getContentPane.add(new JScrollPane(Views.fitting(panelIn[A, R](first, lower, done, settings)),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER))
        // the help, when there is one: a `?` at the top right, the views told before the first step in a dialog
        if (help.nonEmpty) {
          val bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 2))
          val button = new JButton("?"); button.setName(SwingFace.helpName)
          button.setToolTipText(look.map(_.words).getOrElse(Words.english).help)
          button.setMargin(new java.awt.Insets(0, 0, 0, 0)); button.setPreferredSize(new java.awt.Dimension(28, 28));
          button.setFocusable(false)
          button.addActionListener((_: java.awt.event.ActionEvent) =>
            JOptionPane.showMessageDialog(frame, SwingFace.help(help, look), title, JOptionPane.PLAIN_MESSAGE))
          bar.add(button); frame.getContentPane.add(bar, BorderLayout.NORTH)
        }
        // the window sized to its content, within the screen; it grows no further — a longer dialogue scrolls
        frame.pack()
        val screen = java.awt.Toolkit.getDefaultToolkit.getScreenSize
        frame.setSize(math.max(480, math.min(frame.getWidth + 24, screen.width - 80)),
          math.max(360, math.min(frame.getHeight + 24, screen.height - 120)))
        frame.setLocationRelativeTo(null); frame.setVisible(true)
      }
    })
  }

  private def render[A, N[_]](s: Shown[A, N], show: Shown[A, N] => Unit,
    settle: (Step[A, N], Vector[View], List[Step.Waiting[A, N]]) => Shown[A, N], lower: N[Step[A, N]] => Step[A, N],
    words: Words, refresh: () => Unit): JComponent = {
    val box = new JPanel; box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS))
    s.told.foreach(v => box.add(named(Views.component(v), Role.Told.cls)))
    s.step match {
      case Step.Done(_) => box.add(named(Views.component(words.over), "okay-done"))
      case Step.Told(_, _) => ()
      case w: Step.Waiting[A, N] @unchecked =>
        // the problems of a rejected choice shown here, the step's forms kept as they are: what was typed stays
        val problems = new JPanel(new GridLayout(0, 1)); problems.setName(Role.Problems.cls)
        def told(ps: Problems): Unit = {
          problems.removeAll(); ps.foreach(pr => problems.add(label(pr.render, Role.Problems.cls)))
          problems.setVisible(ps.nonEmpty)
        }
        def choose(chosen: Chosen): Unit = w.resume(chosen) match {
          case Left(Rejected(ps)) => told(ps); refresh()
          case Right(next) => show(settle(lower(next), Vector.empty, if (w.back) w :: s.history else s.history))
        }
        val ref: Option[String => Unit] = if (w.actions.exists(_.name == "ref"))
          Some(key => choose(Chosen("ref", Json.obj("key" -> Json.Str(key)))))
        else None
        box.add(Views.component(w.view, ref))
        told(s.problems); box.add(problems)
        val actions = new JPanel; actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        actions.setName(Role.Actions.cls)
        w.actions.filterNot(_.name == "ref").foreach(a => actions.add(Forms.action(a, choose, words)))
        if (s.history.nonEmpty) {
          val b = new JButton(words.back); b.setName(Role.Back.cls)
          b.addActionListener(_ => show(Shown(Vector.empty, s.history.head, s.history.tail, Nil)))
          actions.add(b)
        }
        box.add(actions)
    }
    box
  }
  private def label(text: String, name: String): JLabel = { val l = new JLabel(text); l.setName(name); l }
  private def named[C <: JComponent](c: C, name: String): C = { if (c.getName == null) c.setName(name); c }
}

/** a `View` as Swing components */
object SwingFace {
  /** the name of the window's `?` button */
  val helpName = "okay-help"
  /** the help as a component: the views told before the first step, dressed by `look`, wrapped to a readable width and
   * scrolling past a height */
  def help(views: Vector[View], look: Option[Look] = None): JComponent = {
    val box = new JPanel; box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS)); box.setName(Role.Told.cls)
    views.foreach(v => box.add(Views.component(v)))
    look.foreach(l => Looks(l)(box))
    val fit = Views.fitting(box)
    val sp = new JScrollPane(fit, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
      ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
    sp.setBorder(BorderFactory.createEmptyBorder()); sp.setName(Role.Told.cls)
    // the width fixed, so the text wraps there; the height what the wrapped text then needs, up to a readable page —
    // a wrapped text area knows its height only once laid out at a width, so lay the box out at that width first
    def layout(c: Component): Unit =
      c match { case p: java.awt.Container => p.doLayout(); p.getComponents.foreach(layout); case _ => () }
    def invalidate(c: Component): Unit = {
      c.invalidate(); c match { case p: java.awt.Container => p.getComponents.foreach(invalidate); case _ => () }
    }
    fit.setSize(440, 4000); layout(fit);
    invalidate(fit) // the layouts' cached sizes dropped, so the box asks its wrapped texts again
    val h = box.getPreferredSize.height
    sp.setPreferredSize(new java.awt.Dimension(460, math.min(h + 16, 360).max(60)))
    sp
  }
  /** THE FACE'S SETTINGS, one value: how it looks (`None`: the platform's look and feel) */
  final case class Settings(look: Option[Look] = None)

  /** AN IMAGE, read from its source — the bytes of a data: URI, or an address — at most 480 wide; its words when it
   * does not read */
  def image(source: String, alt: String): JLabel = {
    val read = scala.util.Try {
      val bytes =
        if (source.startsWith("data:")) java.util.Base64.getDecoder.decode(source.substring(source.indexOf(',') + 1))
        else { val in = new java.net.URI(source).toURL.openStream(); try in.readAllBytes() finally in.close() }
      javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))
    }.toOption.flatMap(Option(_))
    read.fold(new JLabel(s"[image: $alt]")) { i =>
      val w = math.min(480, i.getWidth); val h = i.getHeight * w / math.max(1, i.getWidth)
      val l = new JLabel(new javax.swing.ImageIcon(i.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH)))
      l.setToolTipText(alt); l
    }
  }
  /** over the implementation in scope */
  def apply[F <: Runner with Singleton]()(implicit fx: F): SwingFace[F] = new SwingFace[F]()(fx)
  /** THE HANDLER, whole: `run(SwingFace.handle(Noughts.games))` — a program of nothing, run on the implementation in
   * scope */
  def handle[F <: Runner with Singleton, A](p: Program[A, okay.faces.Face], settings: Settings = Settings())(
    implicit fx: F): Program[Option[A], Tagless.Pure] = {
    val face = new SwingFace[F]()(fx)
    Faces.running(face.fx.run[Option[A]](face.handle[A](p, settings)))
  }
}

object Views {
  /** the face in a scroll pane: as wide as the window, scrolling down only */
  def fitting(c: JComponent): JComponent = {
    val p = new JPanel(new BorderLayout) with Scrollable {
      def getPreferredScrollableViewportSize: java.awt.Dimension = getPreferredSize
      def getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int): Int = 16
      def getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int): Int = r.height
      def getScrollableTracksViewportWidth: Boolean = true
      def getScrollableTracksViewportHeight: Boolean = false
    }
    p.add(c, BorderLayout.NORTH); p
  }
  def component(v: View): JComponent = component(v, None)
  /** `ref`: what a `Ref` does when the step offers the action `ref` — a button, and a table of such a grid of them */
  def component(v: View, ref: Option[String => Unit]): JComponent = v match {
    case View.Empty => new JPanel
    case View.Text(s) => named(wrapped(s), Role.Text.cls)
    case View.Heading(level, s) =>
      val l = new JLabel(s);
      l.setFont(l.getFont.deriveFont(Font.BOLD,
          l.getFont.getSize2D * (level match { case 1 => 1.6f; case 2 => 1.3f; case _ => 1.1f })));
      named(l, Role.Heading(level).cls)
    case View.Notice(level, s) => named(new JLabel(s"${level.toString.toLowerCase}: $s"), Role.Notice(level).cls)
    case View.Fields(rows) =>
      val p = new JPanel(new GridLayout(0, 2, 8, 2))
      rows.foreach { case (k, x) => p.add(named(new JLabel(k + ":"), Role.Label.cls)); p.add(component(x, ref)) }
      named(p, Role.Fields.cls)
    // a table with no column names is a GRID of cells — a board — in the middle, its cells square: as wide as the
    // window
    // allows, up to one and a half times a cell's own height; so is one of live refs
    case View.Table(columns, rows) if columns.forall(_.isEmpty) || (ref.isDefined && rows.exists(_.exists(hasRef))) =>
      val cols = rows.headOption.fold(1)(_.size) max 1
      val holder = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0)); holder.setOpaque(false)
      val grid = new JPanel(new GridLayout(rows.size, cols, 4, 4)) {
        override def getPreferredSize: java.awt.Dimension = {
          val own = super.getPreferredSize
          val cap = (own.height.toDouble / rows.size * 1.5).toInt
          val fit = if (holder.getWidth > 0) holder.getWidth / cols else cap
          val side = math.max(own.width / cols, math.min(fit, cap))
          new java.awt.Dimension(side * cols, side * rows.size)
        }
      }
      holder.addComponentListener(new java.awt.event.ComponentAdapter {
        override def componentResized(e: java.awt.event.ComponentEvent): Unit = grid.revalidate()
      })
      rows.foreach(_.foreach(c => grid.add(cell(c, ref)))); named(grid, Role.Table.cls)
      holder.add(grid); holder
    case View.Table(columns, rows) =>
      val model = new DefaultTableModel(
        rows.map(_.map(c => text(c): AnyRef).toArray).toArray, columns.map(c => c: AnyRef).toArray) {
        override def isCellEditable(r: Int, c: Int): Boolean = false
      }
      val t = new JTable(model); t.setName(Role.Table.cls)
      val sp = new JScrollPane(t);
      sp.setPreferredSize(new java.awt.Dimension(400, math.min(300, 24 * (rows.size + 2)))); sp
    case View.Items(items) =>
      // each item its own component, so a `Ref` in one is a button where refs are live, not a line of text
      val p = new JPanel; p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS))
      items.foreach(i => p.add(component(i, ref))); named(p, Role.Items.cls)
    case View.Group(title, children) =>
      val p = new JPanel; p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS))
      title.foreach(t => p.setBorder(BorderFactory.createTitledBorder(t)))
      children.foreach(c => p.add(component(c, ref))); named(p, Role.Group.cls)
    case View.Ref(key, l) => ref match {
        case Some(r) =>
          val b = new JButton(l); b.setName(Role.Ref.cls); b.setToolTipText(key);
          b.addActionListener((_: java.awt.event.ActionEvent) => r(key)); b
        case None => named(new JLabel(s"$l [$key]"), Role.Ref.cls)
      }
    case View.Datum(json, _) =>
      val a = new JTextArea(Json.pretty(json)); a.setEditable(false);
      a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); named(a, Role.Datum.cls)
    case View.Link(l, url) => val c = new JLabel(s"$l <$url>"); c.setToolTipText(url); named(c, Role.Link.cls)
    case View.Masked(hint) => named(new JLabel(hint), Role.Masked.cls)
    case View.Image(source, alt) => named(SwingFace.image(source, alt), Role.Image.cls)
    // A PICTURE IN WORDS, as the terminal shows it
    case View.Picture(_, _, _, text) =>
      named(new JLabel("<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>"),
        Role.Picture.cls)
    case View.Marked(mark, x) =>
      val c = component(x, ref); c.setName(Role.Mark(mark).cls); c // the mark's rule dresses it, not its own
  }
  private def hasRef(v: View): Boolean =
    v match { case View.Ref(_, _) => true; case View.Marked(_, x) => hasRef(x); case _ => false }
  /** a cell of a grid: a live Ref its button, a Ref not live its label, anything else a label — in the middle, square;
   * the mark's name kept for the look */
  private def cell(v: View, ref: Option[String => Unit]): JComponent = v match {
    case View.Marked(mark, x) => val c = cell(x, ref); c.setName(Role.Mark(mark).cls); c
    case View.Ref(key, l) if ref.isDefined =>
      val b = new JButton(l)
      b.setName(Role.Ref.cls); b.setToolTipText(key); b.setHorizontalAlignment(SwingConstants.CENTER);
      b.addActionListener((_: java.awt.event.ActionEvent) => ref.get(key)); b
    case other =>
      val l = new JLabel(other match { case View.Ref(_, lbl) => lbl; case _ => text(other) }, SwingConstants.CENTER)
      named(l, other match { case View.Ref(_, _) => Role.Ref.cls; case _ => Role.Text.cls })
  }
  /** a view in one line */
  def text(v: View): String = v match {
    case View.Empty => ""
    case View.Text(s) => s
    case View.Heading(_, s) => s
    case View.Notice(_, s) => s
    case View.Fields(rows) => rows.map { case (k, x) => s"$k: ${text(x)}" }.mkString(", ")
    case View.Table(_, rows) => rows.map(_.map(text).mkString(" ")).mkString(" / ")
    case View.Items(items) => items.map(text).mkString(", ")
    case View.Group(t, children) => (t.toList ++ children.map(text)).mkString(" ")
    case View.Ref(key, l) => s"$l [$key]"
    case View.Link(l, url) => s"$l <$url>"
    case View.Datum(json, _) => Json.print(json)
    case View.Masked(hint) => hint
    case View.Image(_, alt) => alt
    case View.Picture(_, _, _, text) => text
    case View.Marked(_, x) => text(x)
  }
  private def wrapped(s: String): JTextArea = {
    val a = new JTextArea(s); a.setEditable(false); a.setLineWrap(true); a.setWrapStyleWord(true); a.setOpaque(false); a
  }
  private def named[C <: JComponent](c: C, name: String): C = { c.setName(name); c }
}

/** an action as a button, or as a form drawn from its parameters' erased schema; what is typed becomes JSON the
 * action's shape decodes — the face does not validate, the shape does */
object Forms {
  def action[A](a: Action[A], choose: Chosen => Unit, words: Words = Words.english): JComponent =
    a.params.erased match {
      case Schema.Record(fields) if fields.isEmpty =>
        val b = new JButton(a.label); b.setName("action:" + a.name)
        b.addActionListener(_ => choose(Chosen.plain(a.name))); b
      case schema =>
        val inputs = new Inputs(words)
        val p = new JPanel; p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setName("form:" + a.name)
        p.setBorder(BorderFactory.createTitledBorder(a.label))
        a.doc.foreach(d => { val l = new JLabel(d); l.setName(Role.Doc.cls); p.add(l) })
        p.add(inputs.of(Nil, schema))
        val submit = new JButton(a.label); submit.setName("submit:" + a.name)
        submit.addActionListener(_ => choose(Chosen(a.name, inputs.collect(Nil, schema))))
        p.add(submit); p
    }

  final class Inputs(words: Words = Words.english) {
    private val texts = mutable.Map.empty[List[String], JTextField]
    private val checks = mutable.Map.empty[List[String], JCheckBox]
    private val combos = mutable.Map.empty[List[String], JComboBox[String]]
    private val areas = mutable.Map.empty[List[String], JTextArea]
    private val items = mutable.Map.empty[List[String], () => Vector[Int]]
    private def name(path: List[String]) = "f" + path.map("-" + _).mkString

    def of(path: List[String], schema: Schema, field: Option[Schema.Field] = None): JComponent = schema match {
      case Schema.Record(fs) =>
        val p = new JPanel(new GridLayout(0, 2, 8, 4))
        field.flatMap(_.label).orElse(field.map(_.name)).foreach(l => p.setBorder(BorderFactory.createTitledBorder(l)))
        fs.foreach { f =>
          val l = new JLabel(f.label.getOrElse(f.name) + ":"); l.setName(Role.Label.cls); p.add(l);
          p.add(of(path :+ f.name, f.schema, Some(f)))
        }
        p
      case Schema.Described(of0, label0, doc) =>
        of(path, of0,
          Some(field.getOrElse(Schema.Field(path.lastOption.getOrElse(""), of0, None, None, false, None)).copy(
              label = Some(label0), doc = doc)))
      case Schema.Optional(of0) => of(path, of0, field)
      case Schema.Choice(options) =>
        val c = new JComboBox[String](options.toArray); c.setName(name(path)); combos(path) = c; c
      case Schema.OneOf(cases) =>
        val c = new JComboBox[String](cases.map(_._1).toArray); c.setName(name(path)); combos(path) = c
        val cards = new JPanel(new java.awt.CardLayout)
        cases.foreach { case (n, cs) => cards.add(of(path :+ n, cs), n) }
        c.addActionListener(_ =>
          cards.getLayout.asInstanceOf[java.awt.CardLayout].show(cards, c.getSelectedItem.asInstanceOf[String]))
        val p = new JPanel; p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.add(c); p.add(cards); p
      case Schema.Many(of0, min, max) if !okay.faces.FormJson.leafish(of0) =>
        // a list of records: an item added by a button, removed by one; each item's fields by index
        val list = new JPanel; list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS)); list.setName(name(path))
        var next = 0; var ids = Vector.empty[Int]
        items(path) = () => ids
        def add(): Unit = if (!max.exists(ids.size >= _)) {
          val i = next; next += 1; ids = ids :+ i
          val item = new JPanel; item.setLayout(new BoxLayout(item, BoxLayout.Y_AXIS));
          item.setName(name(path :+ i.toString))
          item.add(of(path :+ i.toString, of0))
          val remove = new JButton(words.remove); remove.setName("remove:" + name(path :+ i.toString))
          remove.addActionListener(_ => {
            ids = ids.filterNot(_ == i); list.remove(item); list.revalidate(); list.repaint()
          })
          item.add(remove); list.add(item); list.revalidate(); list.repaint()
        }
        (0 until (min max 0)).foreach(_ => add())
        val addButton = new JButton(words.add); addButton.setName("add:" + name(path));
        addButton.addActionListener(_ => add())
        val p = new JPanel; p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS))
        field.flatMap(_.label).orElse(field.map(_.name)).foreach(l => p.setBorder(BorderFactory.createTitledBorder(l)))
        p.add(list); p.add(addButton); p
      case Schema.Many(_, _, _) =>
        val a = new JTextArea(3, 20); a.setName(name(path)); a.setToolTipText(words.onePerLine); areas(path) = a;
        new JScrollPane(a)
      case Schema.Bool =>
        val c = new JCheckBox(); c.setName(name(path)); checks(path) = c
        field.flatMap(_.default).foreach { case Json.Bool(b) => c.setSelected(b); case _ => () }; c
      case leaf =>
        val t = if (field.exists(_.secret)) new JPasswordField(16) else new JTextField(16)
        t.setName(name(path)); texts(path) = t
        field.flatMap(_.default).foreach(d =>
          t.setText(d match { case Json.Str(s) => s; case other => Json.print(other) }))
        field.flatMap(_.doc).foreach(t.setToolTipText); t
    }

    def collect(path: List[String], schema: Schema): Json = schema match {
      case Schema.Record(fs) => Json.Obj(fs.flatMap(f =>
            collect(path :+ f.name, f.schema) match { case Json.Null => None; case v => Some(f.name -> v) }))
      case Schema.Described(of0, _, _) => collect(path, of0)
      case Schema.Optional(of0) => collect(path, of0)
      case Schema.Choice(_) =>
        combos.get(path).map(c => Json.Str(c.getSelectedItem.asInstanceOf[String])).getOrElse(Json.Null)
      case Schema.OneOf(cases) =>
        combos.get(path).map(_.getSelectedItem.asInstanceOf[String]).flatMap(n => cases.find(_._1 == n)) match {
          case Some((n, cs)) => collect(path :+ n, cs) match {
              case o: Json.Obj if Schema.isRecord(cs) => ("type" -> Json.Str(n)) +: o
              case v => Json.Obj.of("type" -> Json.Str(n), "value" -> v)
            }
          case None => Json.Null
        }
      case Schema.Many(of0, _, _) if items.contains(path) =>
        Json.Arr(items(path)().map(i => collect(path :+ i.toString, of0)))
      case Schema.Many(of0, _, _) => areas.get(path).map(a =>
          Json.Arr(a.getText.split("\n").toVector.map(_.trim).filter(_.nonEmpty).map(typed(of0, _)))).getOrElse(
          Json.Null)
      case Schema.Bool => checks.get(path).map(c => Json.Bool(c.isSelected)).getOrElse(Json.Null)
      case leaf => texts.get(path).map(_.getText).filter(_.nonEmpty).map(typed(leaf, _)).getOrElse(Json.Null)
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
