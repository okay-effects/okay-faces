package okay.faces
package cloudflare

import okay.effects.machine.Machine
import okay.effects.machine.Machine.self
import okay.http.{Request, Response}

/** A SESSION WHERE MEMORY IS LOST: `Hosting`'s program with the journal a list — the host forgets its session, the
 * journal played through a new one brings it back where it was */
class HostingSuite extends munit.FunSuite {
  /** a counter: one action, its count shown */
  def count(n: Int): Unit ! Face =
    offer[Int, Face](View.text(s"count $n"), List(Action.plain[Int]("add", "Add", None)(1)))
      .flatMap(d => count(n + d))

  val door: Door = Door.rest(count(0))
  val hosting = new Hosting[Machine.type]
  type L[X] = okay.effects.Sum[Machine.Pure, Kept, X]

  /** a host: what it holds, what it keeps */
  final class Host(d: Door, id: String) {
    var saved = Saved()
    var held = new Held
    def apply(req: Request, opens: Boolean = false): Response = {
      val (writes, r) = Machine.run(hosting.kept[L, Machine.Pure, Response](saved)(
        hosting.answer[L](d, id, held, req, opens)(Machine.splitLast[Machine.Pure, Kept]))(
        Machine.splitLast[Machine.Pure, Kept]))
      saved = hosting.after(saved, writes)
      r
    }
    def forget(): Unit = held = new Held
  }

  test("a program with no point: kept by its requests; the host forgets it, they bring it back") {
    val host = new Host(door, "s1")
    assertEquals(host(Request("POST", "/sessions"), opens = true).status, 201)
    assertEquals(host(Request("POST", "/sessions/s1/act/add")).status, 200)
    assertEquals(host(Request("POST", "/sessions/s1/act/add")).status, 200)
    assertEquals(host(Request.get("/sessions/s1")).status, 200)
    assertEquals(host(Request("POST", "/sessions/s1/act/nothing")).status, 400)
    assertEquals(host.saved.tail.map(r => r.method + " " + r.path),
      List("POST /sessions/s1/act/add", "POST /sessions/s1/act/add"))
    host.forget()
    assert(host(Request.get("/sessions/s1")).body.contains("count 2"))
    assert(host(Request("POST", "/sessions/s1/act/add")).body.contains("count 3"))
  }

  /** a counter that says where it is: its count, at the start of each turn; a turn asks again before a big step */
  val counting: Durable[Unit] = Durable.loop[Int, Unit]("count", n => okay.effects.json.Json.num(n),
    j => j match { case okay.effects.json.Json.Num(n) => Some(n.toInt); case _ => None })(0) { n =>
    offer[Int, Face](View.text(s"count $n"), List(Action.plain[Int]("add", "Add", None)(1),
      Action.plain[Int]("ten", "Add ten", None)(10))).flatMap { d =>
      if (d < 10) pure[Either[Unit, Int], Face](Right(n + d))
      else confirm[Face](View.text(s"Add $d to $n?")).map(yes => Right(if (yes) n + d else n))
    }
  }
  val durableDoor: Door = Door.rest(counting.program, from = counting.from)

  test("a durable program: its state kept at each point, the requests before forgotten; woken, it starts there") {
    val host = new Host(durableDoor, "s2")
    host(Request("POST", "/sessions"), opens = true)
    host(Request("POST", "/sessions/s2/act/add")); host(Request("POST", "/sessions/s2/act/add"))
    assertEquals(host.saved.at.map(p => p.name -> okay.effects.json.Json.print(p.state)), Some("count" -> "2"))
    assertEquals(host.saved.tail, Nil)
    host.forget()
    assert(host(Request.get("/sessions/s2")).body.contains("count 2"))
    assert(host(Request("POST", "/sessions/s2/act/add")).body.contains("count 3"))
  }

  test("forgotten in the middle of a turn: the point, and the one request since, bring it back to the question") {
    val host = new Host(durableDoor, "s3")
    host(Request("POST", "/sessions"), opens = true)
    host(Request("POST", "/sessions/s3/act/add"))
    assert(host(Request("POST", "/sessions/s3/act/ten")).body.contains("Add 10 to 1?"))
    assertEquals(host.saved.at.map(p => okay.effects.json.Json.print(p.state)), Some("1"))
    assertEquals(host.saved.tail.map(_.path), List("/sessions/s3/act/ten"))
    host.forget()
    assert(host(Request.get("/sessions/s3")).body.contains("Add 10 to 1?"))
    assert(host(Request("POST", "/sessions/s3/act/yes")).body.contains("count 11"))
    assertEquals(host.saved.tail, Nil)
  }

  test("the program made for each session: what it holds is the session's own") {
    var made = 0
    def counted: Unit ! Face = { made += 1; count(0) }
    val d = Door.rest(counted)
    val (a, b) = (d.session("a", () => (), None), d.session("b", () => (), None))
    a(Request("POST", "/sessions")); a(Request("POST", "/sessions/a/act/add"))
    b(Request("POST", "/sessions"))
    assertEquals(made, 2)
    assert(a(Request.get("/sessions/a")).body.contains("count 1"))
    assert(b(Request.get("/sessions/b")).body.contains("count 0"))
  }

  test("an agent's session (MCP), durable: its point kept; the host forgets it, it starts there") {
    val mcp = Door.mcp(counting.program, from = counting.from)
    val host = new Host(mcp, "m1")
    def ask(body: String, opens: Boolean = false): Response =
      host(Request.post("/mcp", body).copy(headers = Map("content-type" -> List("application/json")) ++
        (if (opens) Map.empty else Map("mcp-session-id" -> List("m1")))), opens)
    val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
      """"capabilities":{},"clientInfo":{"name":"t"}}}"""
    assertEquals(ask(init, opens = true).headers.get("Mcp-Session-Id"), Some("m1"))
    assert(mcp.opens(Request.post("/mcp", init)) && mcp.idOf(Request.post("/mcp", "{}")).isEmpty)
    def call(n: Int, tool: String) =
      ask(s"""{"jsonrpc":"2.0","id":$n,"method":"tools/call","params":{"name":"$tool","arguments":{}}}""")
    assert(call(2, "add").body.contains("count 1"))
    assert(call(3, "add").body.contains("count 2"))
    assertEquals(host.saved.at.map(p => okay.effects.json.Json.print(p.state)), Some("2"))
    host.forget()
    assert(call(4, "look").body.contains("count 2"))
  }

  test("a session that moves by itself (an event while it waits) tells its host") {
    val bus = okay.faces.Meanwhile.manual[Int]
    def waiting(n: Int): Unit ! Face =
      offer[Int, Face](View.text(s"count $n"), List(Action.plain[Int]("add", "Add", None)(1)), bus.meanwhile)
        .flatMap(d => waiting(n + d))
    var told = 0
    val s = Door.rest(waiting(0)).session("e", () => told += 1, None)
    s(Request("POST", "/sessions"))
    assertEquals(told, 0)
    bus.fire(5)
    assertEquals(told, 1)
    assert(s(Request.get("/sessions/e")).body.contains("count 5"))
  }

  test("a request and a point kept as JSON read back the same") {
    val pt = Step.Point("count", okay.effects.json.Json.obj("n" -> okay.effects.json.Json.num(3)))
    assertEquals(Hosting.pointFromJson(Hosting.pointJson(pt)), Some(pt))
    val r = Request("POST", "/s/s1/act/add", Map("x" -> List("1")), Map("content-type" -> List("text/plain"),
      "cookie" -> List("no")), "title=Milk")
    assertEquals(Hosting.fromJson(Hosting.json(Hosting.kept(r))), Some(Hosting.kept(r)))
    assertEquals(Hosting.kept(r).headers.keySet, Set("content-type"))
    val m = Request("POST", "/mcp").copy(headers = Map("mcp-session-id" -> List("s1"), "accept" -> List("x"),
      "authorization" -> List("no")))
    assertEquals(Hosting.kept(m).headers.keySet, Set("mcp-session-id", "accept"))
  }
}
