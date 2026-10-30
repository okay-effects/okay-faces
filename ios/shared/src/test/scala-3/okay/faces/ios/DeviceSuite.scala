package okay.faces.ios

import okay.effects.json.{Descent, Json}
import okay.effects.machine.Machine
import okay.faces._
import okay.faces.ios.phone._

class DeviceSuite extends munit.FunSuite {
  type P[X] = Device.Phone[X]
  def read(s: String): Json = Descent.parse(s).fold(e => fail(e.message), j => j)
  def at(j: Json, k: String): Json = j match { case o: Json.Obj => o.get(k).getOrElse(Json.Null); case _ => Json.Null }

  /** a counter kept on the phone, a page fetched, a reminder: every device operation in one small program */
  def counter(n: Int): Unit ! P = offer[String, P](View.text(s"count $n"), List(
      Action.plain[String]("more", "More")("more"), Action.plain[String]("fetch", "Fetch")("fetch"),
      Action.plain[String]("remind", "Remind")("remind"))).flatMap {
    case "more" => save[P]("count", (n + 1).toString).flatMap(_ => counter(n + 1))
    case "fetch" => fetch[P]("https://example.org/").flatMap(r =>
        show[P](View.text(s"${r.status}: ${r.body}")).flatMap(_ => counter(n)))
    case _ => remind[P]("Counter", s"at $n", 60).flatMap(_ => counter(n))
  }
  val program: Unit ! P = load[P]("count").flatMap(c => counter(c.flatMap(_.toIntOption).getOrElse(0)))

  test("a program on a phone: its device answered in place by the port — kept, fetched, reminded") {
    val phone = new Device.Memory(network = (m, u, _) => Device.Reply(200, Map.empty, s"$m $u"))
    phone.kept("count") = "41"
    val d = IosFace.phone[Machine.type, Unit](program, phone)(Machine)
    assertEquals(at(read(d.open()), "view"), ViewJson.encode(View.text("count 41")))
    assertEquals(at(read(d.act("""{"action": "more"}""")), "view"), ViewJson.encode(View.text("count 42")))
    assertEquals(phone.kept.get("count"), Some("42"))
    val fetched = read(d.act("""{"action": "fetch"}"""))
    assertEquals(at(fetched, "told"), Json.Arr(Vector(ViewJson.encode(View.text("200: GET https://example.org/")))))
    d.act("""{"action": "remind"}""")
    assertEquals(phone.notified.toList, List("Counter" -> "at 42"))
  }

  test("kept across a restart: a new dialogue on the same phone starts where the last one left") {
    val phone = Device.memory()
    val first = IosFace.phone[Machine.type, Unit](program, phone)(Machine)
    first.open(); first.act("""{"action": "more"}"""); first.act("""{"action": "more"}""")
    val again = IosFace.phone[Machine.type, Unit](program, phone)(Machine)
    assertEquals(at(read(again.open()), "view"), ViewJson.encode(View.text("count 2")))
  }

  test("the operations as JSON both ways, as the shell reads and answers them") {
    val ops = List[Device[_]](Device.Fetch("POST", "u", List("a" -> "b"), "x", identity), Device.Load("k", identity),
      Device.Save("k", Some("v"), identity), Device.Secret("s", identity), Device.Hide("s", None, identity),
      Device.Now(identity), Device.Notify("t", "b", 5, identity), Device.Open("tel:1", identity))
    assertEquals(ops.map(o => at(Device.request(o), "op")).collect { case Json.Str(s) => s },
      List("fetch", "load", "save", "secret", "hide", "now", "notify", "open"))
    val reply = Device.answer(Device.Fetch("GET", "u", Nil, "", identity), read(
      """{"status": 201, "headers": {"Content-Type": "text/plain"}, "body": "hi"}"""))
    assertEquals(reply, Device.Reply(201, Map("content-type" -> "text/plain"), "hi"))
    assertEquals(Device.answer(Device.Fetch("GET", "u", Nil, "", identity), read("""{"error": "offline"}""")),
      Device.Reply(0, Map.empty, "offline"))
  }
}
