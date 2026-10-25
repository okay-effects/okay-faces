package okay.faces.examples

import okay.effects.shape.{Day, Shape}
import okay.faces.{Look, Marks, Role, Rule, Style, Tone, View}

/** TO DO: the second example, with nothing of a game in it — a list kept from step to step, an item added by a FORM
 * (an action with parameters), an item done or to do again by a click on it (`ref`), one removed by a CHOICE and a
 * CONFIRMATION, the done ones cleared after a yes. The same faces handle it as handle the game; the program knows
 * none of them */
object Todo {
  /** an item: what, by when if ever, a note if any */
  final case class Item(title: String, due: Option[Day] = None, note: Option[String] = None)
  object Item {
    /** THE FORM, from the type: a face asks the fields — a dialog, prompts, a tool's arguments — and checks them */
    val shape: Shape[Item] = Shape.derived[Item]
      .labelled("title", "Title", Some("what to do"))
      .labelled("due", "Due", Some("a day, yyyy-mm-dd"))
      .labelled("note", "Note", Some("anything more"))
  }

  /** THE LIST, no effect in it: items by number, which are done, the next number */
  final case class Todos(items: Vector[(Int, Item)], done: Set[Int], next: Int = 1) {
    def added(i: Item): Todos = copy(items = items :+ (next -> i), next = next + 1)
    def toggled(id: Int): Todos = copy(done = if (done(id)) done - id else done + id)
    def removed(id: Int): Todos = copy(items = items.filterNot(_._1 == id), done = done - id)
    def cleared: Todos = copy(items = items.filterNot(i => done(i._1)), done = Set.empty)
    def titled(id: Int): String = items.collectFirst { case (`id`, i) => i.title }.getOrElse("?")
  }
  object Todos {
    val empty: Todos = Todos(Vector.empty, Set.empty)
    import okay.effects.json.Json
    /** THE LIST AS DATA: what a host keeps of it (`Durable`) — each item by the form's own shape */
    def write(t: Todos): Json = Json.obj(
      "items" -> Json.Arr(t.items.map { case (id, i) =>
        Json.obj("id" -> Json.num(id), "item" -> Item.shape.encode(i)) }),
      "done" -> Json.Arr(t.done.toVector.sorted.map(Json.num(_))), "next" -> Json.num(t.next))
    def read(j: Json): Option[Todos] = {
      def int(x: Json): Option[Int] = x match { case Json.Num(n) if n.isValidInt => Some(n.toInt); case _ => None }
      def field(x: Json, k: String): Option[Json] =
        x match { case o: Json.Obj => o.fields.toMap.get(k); case _ => None }
      def list(x: Option[Json]): Vector[Json] = x match { case Some(Json.Arr(xs)) => xs; case _ => Vector.empty }
      for (next <- field(j, "next").flatMap(int)) yield Todos(
        list(field(j, "items")).flatMap(x => for (id <- field(x, "id").flatMap(int);
          i <- field(x, "item").flatMap(v => Item.shape.decode(v).toOption)) yield id -> i),
        list(field(j, "done")).flatMap(int).toSet, next)
    }
  }

  /** what one does at the list */
  sealed trait Move
  final case class Add(item: Item) extends Move
  final case class Toggle(id: Int) extends Move
  case object RemoveOne extends Move
  case object Clear extends Move

  /** THE LIST'S OWN MARKS, typed: an item still to do, one done, when one is due */
  sealed trait Mark
  case object Pending extends Mark
  case object Done extends Mark
  case object Due extends Mark
  val marks: Marks[Mark] = Marks.of(Shape.derived[Mark]).explained(
    Pending -> "an item still to do", Done -> "an item done", Due -> "when an item is due")
  /** the look: a done item muted, a due day small and muted, the list on a surface */
  val style: Style = Style.default
    .set(marks.role(Done), Rule(color = Some(Tone.Muted)))
    .set(marks.role(Due), Rule(color = Some(Tone.Muted), size = 0.9))
    .adjust(Role.Items)(_.copy(background = Some(Tone.Surface), pad = 1, gap = 1))
  val look: Look = Look.default.copy(style = style)

  /** what is told first: a face has it to tell, an agent gets it as instructions */
  val about: View = View.text("Your list, kept while you are here. An item is a [number]: choose one and it is done, " +
    "or to do again. Add one by the form; remove one, or clear the done ones, after a yes.")
  /** what the listing says: each item's mark and title, in order — a test reads the list off the view */
  def shown(v: View): Vector[(String, String)] = v match {
    case View.Items(items) => items.flatMap(shown)
    case View.Marked(mark, View.Ref(_, title)) => Vector(mark -> title)
    case View.Marked(mark, View.Group(_, children)) => children.collect { case View.Ref(_, title) => mark -> title }
    case View.Group(_, children) => children.flatMap(shown)
    case _ => Vector.empty
  }
  /** the list: each item a `Ref` by its number — live, since `ref` is offered — marked done or pending, its due day
   * beside it; then how many are done */
  def listing(t: Todos): View = View.stack(View.heading("To do"),
    if (t.items.isEmpty) View.text("Nothing to do.")
    else View.Items(t.items.map { case (id, i) =>
      val line: View = View.Ref(id.toString, i.title)
      marks.mark(if (t.done(id)) Done else Pending,
        i.due.fold(line)(d => View.stack(line, marks.mark(Due, View.text(s"due $d")))))
    }),
    View.text(s"${t.done.size} of ${t.items.size} done"))

  /** THE PROGRAM: a value over the faces' words alone, `A ! Face`; the list is kept as long as one is here, so it has
   * no end — the face's own way out (a window closed, a session dropped, a quit at the terminal) is the way out */
  import okay.faces._
  /** THE LIST, DURABLE: a loop of its state — each turn starts at the point `todo`, the list its data, so a host that
   * loses its memory keeps the list and starts a turn there again (`Durable`) */
  val durable: Durable[Unit] = Durable.loop[Todos, Unit]("todo", Todos.write, Todos.read)(Todos.empty)(t =>
    turn(t).map(Right(_)))
  val keeping: Unit ! Face = show[Face](about).flatMap(_ => durable.program)
  /** a turn: the list offered with what one can do to it; the list after */
  def turn(t: Todos): Todos ! Face = {
    val ids = Shape.choice[Int](t.items.map { case (id, i) => id.toString -> id }: _*)
      .described("Item", Some("an item by its number"))
    val toggle = Action[Int, Move]("ref", "Done / to do", Shape.record(Shape.field("key", ids))(c => c)(c => c),
      Some("an item: done when it was to do, to do again when it was done"))(Toggle(_))
    val add = Action[Item, Move]("add", "Add", Item.shape, Some("a new item: its title, a due day and a note if any"))(
      Add(_))
    val remove = Action.plain[Move]("remove", "Remove one", Some("choose an item, then say yes"))(RemoveOne)
    val clear = Action.plain[Move]("clear", "Clear the done", Some("every done item removed, after a yes"))(Clear)
    val actions = List(add) ++ (if (t.items.nonEmpty) List(toggle, remove) else Nil) ++
      (if (t.done.nonEmpty) List(clear) else Nil)
    offer[Move, Face](listing(t), actions).flatMap {
      case Add(item) => show[Face](View.info(s"added: ${item.title}")).map(_ => t.added(item))
      case Toggle(id) => pure[Todos, Face](t.toggled(id))
      case RemoveOne =>
        choose[Int, Face](View.text("Remove which?"), t.items.toList.map { case (id, i) => i.title -> id }).flatMap(
          id => confirm[Face](View.text(s"Remove '${t.titled(id)}'?")).map(yes => if (yes) t.removed(id) else t))
      case Clear =>
        confirm[Face](View.text(s"Clear the ${t.done.size} done?")).map(yes => if (yes) t.cleared else t)
    }
  }
}
