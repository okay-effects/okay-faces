package okay.faces.mobile

import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.effects.shape.{Day, Shape}
import okay.faces._

class MobileFaceSuite extends munit.FunSuite {
  final case class Item(title: String, due: Option[Day], note: String)
  val item: Shape[Item] = Shape.record(Shape.field("title", Shape.text), Shape.field("due", Shape.date.optional),
    Shape.field("note", Shape.text))(Item(_, _, _))(i => (i.title, i.due, i.note))

  import okay.faces._
  /** a list asked for one item, then said back */
  val program: String ! Face = offer[Item, Face](View.heading("Add"),
    List(Action[Item, Item]("add", "Add", item)(i => i).withLong("note"))).flatMap(i =>
    show[Face](View.text(s"added ${i.title}")).flatMap(_ =>
      confirm[Face](View.text("again?")).map(yes => if (yes) "again" else i.title)))

  def read(s: String): Json = okay.effects.json.Descent.parse(s).fold(e => fail(e.message), j => j)
  def obj(s: String): Json.Obj = read(s) match { case o: Json.Obj => o; case other => fail(s"$other") }
  def at(j: Json, k: String): Json = j match { case o: Json.Obj => o.get(k).getOrElse(Json.Null); case _ => Json.Null }

  test("the first screen: the view, the title, the action's fields lowered by dotted name") {
    val d = MobileFace[Machine.type, String](program)(Machine)
    val s = obj(d.open())
    assertEquals(at(s, "title"), Json.Str("Add"))
    val add = at(s, "actions") match { case Json.Arr(Vector(a)) => a; case x => fail(s"$x") }
    val fields = at(add, "fields") match { case Json.Arr(fs) => fs; case x => fail(s"$x") }
    assertEquals(fields.map(f => (at(f, "name"), at(f, "kind"), at(f, "required"))), Vector(
      (Json.Str("title"), Json.Str("text"), Json.Bool(true)),
      (Json.Str("due"), Json.Str("date"), Json.Bool(false)),
      (Json.Str("note"), Json.Str("long"), Json.Bool(true))))
  }

  test("a choice typed: read back by FormJson, decoded; problems kept on the same step; the next step, the told view") {
    val d = MobileFace[Machine.type, String](program)(Machine)
    d.open()
    val rejected = obj(d.act("""{"action": "add", "form": {"title": ["milk"], "due": ["not a day"]}}"""))
    assert(at(rejected, "problems") != Json.Arr(Vector.empty), rejected)
    assertEquals(at(rejected, "title"), Json.Str("Add"))
    val next = obj(d.act("""{"action": "add", "form": {"title": "milk", "due": "2026-11-01", "note": "2 l"}}"""))
    assertEquals(at(next, "problems"), Json.Arr(Vector.empty))
    assertEquals(at(next, "told"), Json.Arr(Vector(ViewJson.encode(View.text("added milk")))))
    val done = obj(d.act("""{"action": "no"}"""))
    assertEquals(at(done, "done"), Json.Bool(true))
  }

  test("a sum and a list of records lowered: the case by `.type`, the items by `#`") {
    sealed trait Pay
    final case class Card(number: String) extends Pay
    final case class Cash(change: Boolean) extends Pay
    val pay: Shape[Pay] = Shape.sum[Pay](
      Shape.sub[Pay, Card]("card", Shape.record(Shape.field("number", Shape.text))(Card(_))(_.number)) {
        case c: Card => Some(c); case _ => None },
      Shape.sub[Pay, Cash]("cash", Shape.record(Shape.field("change", Shape.bool))(Cash(_))(_.change)) {
        case c: Cash => Some(c); case _ => None })
    val lines = Shape.record(Shape.field("pay", pay), Shape.field("items", item.many(1)))((p, is) => (p, is))(x => x)
    val fs = MobileForm.fields(Action[(Pay, List[Item]), Unit]("buy", "Buy", lines)(_ => ()))
    assertEquals(fs.map(f => at(f, "kind")), Vector(Json.Str("oneof"), Json.Str("many")))
    val cases = at(fs(0), "cases") match { case Json.Arr(cs) => cs; case x => fail(s"$x") }
    assertEquals(cases.map(c => at(c, "name")), Vector(Json.Str("card"), Json.Str("cash")))
    val card = at(cases(0), "fields") match { case Json.Arr(Vector(f)) => f; case x => fail(s"$x") }
    assertEquals(at(card, "name"), Json.Str("pay.card.number"))
    val itemFields = at(fs(1), "item") match { case Json.Arr(xs) => xs; case x => fail(s"$x") }
    assertEquals(itemFields.map(at(_, "name")).head, Json.Str("items.#.title"))
    // and back: what the shell sends, by those names
    val args = FormJson.collect(lines.erased, Map("pay.type" -> List("cash"), "pay.cash.change" -> List("true"),
      "items.0.title" -> List("milk"), "items.0.note" -> List("x")))
    assertEquals(lines.decode(args).map(_._1), Right(Cash(true)))
  }

  test("the look: tones as colours, rules by the role's name, the words") {
    val l = MobileLook.json(Look.default.copy(words = Words(back = "Назад")))
    assertEquals(at(at(at(l, "theme"), "palette"), "accent"), Json.Str("#0969da"))
    assertEquals(at(at(at(l, "rules"), "heading-1"), "bold"), Json.Bool(true))
    assertEquals(at(at(l, "words"), "back"), Json.Str("Назад"))
  }

  test("back: the step before, when it was kept") {
    val p: Int ! Face = offer[Int, Face](View.text("one"), List(Action.plain[Int]("next", "Next")(1)),
      Step.Options.returnable).flatMap(_ => offer[Int, Face](View.text("two"), List(Action.plain[Int]("ok", "OK")(2))))
    val d = MobileFace[Machine.type, Int](p)(Machine)
    d.open()
    assertEquals(at(obj(d.act("""{"action": "next"}""")), "back"), Json.Bool(true))
    assertEquals(at(obj(d.back()), "view"), ViewJson.encode(View.text("one")))
  }
}

class MobileDurableSuite extends munit.FunSuite {
  import okay.effects.json.Descent
  def read(s: String): Json = Descent.parse(s).fold(e => fail(e.message), j => j)

  import okay.faces._
  /** a count, a point each turn */
  val counting: Durable[Unit] = Durable.loop[Int, Unit]("count", n => Json.num(n), {
    case Json.Num(n) => Some(n.toInt); case _ => None })(0)(n =>
    offer[Int, Face](View.text(s"count $n"), List(Action.plain[Int]("more", "More")(n + 1))).map(Right(_)))

  test("taken up where it was: the point kept on the phone, the next run started there") {
    val phone = Device.memory()
    val first = MobileFace.durable[Machine.type, Unit](counting, phone)(Machine)
    assert(first.open().contains("count 0"))
    first.act("""{"action": "more"}"""); first.act("""{"action": "more"}""")
    assert(phone.kept.get("okay-faces.point").exists(_.contains("\"count\"")), phone.kept.toString)
    assert(MobileFace.durable[Machine.type, Unit](counting, phone)(Machine).open().contains("count 2"))
    phone.kept("okay-faces.point") = "not a point"
    assert(MobileFace.durable[Machine.type, Unit](counting, phone)(Machine).open().contains("count 0"), "afresh")
  }

  test("the look at night: the dark palette beside the light one, when the look is on the light tokens") {
    val l = read(MobileFace[Machine.type, Unit](pure[Unit, Face](()))(Machine).look)
    val dark = l match { case o: Json.Obj => o.get("dark"); case _ => None }
    assert(dark.exists(Json.print(_).contains(Theme.dark.palette.bg.hex)), Json.print(l))
  }
}
