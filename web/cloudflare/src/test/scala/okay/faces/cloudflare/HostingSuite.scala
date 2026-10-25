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

  /** a host: what it holds, its journal */
  final class Host(var journal: List[Request]) {
    var held = new Held
    def apply(req: Request, opens: Boolean = false): Response = {
      val (appended, r) = Machine.run(hosting.kept[L, Machine.Pure, Response](journal)(
        hosting.answer[L](door, "s1", held, req, opens)(Machine.splitLast[Machine.Pure, Kept]))(
        Machine.splitLast[Machine.Pure, Kept]))
      journal = journal ++ appended
      r
    }
    def forget(): Unit = held = new Held
  }

  test("a session's changes journaled; the host forgets it, the journal brings it back; reads and refusals not kept") {
    val host = new Host(Nil)
    assertEquals(host(Request("POST", "/sessions"), opens = true).status, 201)
    assertEquals(host(Request("POST", "/sessions/s1/act/add")).status, 200)
    assertEquals(host(Request("POST", "/sessions/s1/act/add")).status, 200)
    assertEquals(host(Request.get("/sessions/s1")).status, 200)
    assertEquals(host(Request("POST", "/sessions/s1/act/nothing")).status, 400)
    assertEquals(host.journal.map(r => r.method + " " + r.path),
      List("POST /sessions", "POST /sessions/s1/act/add", "POST /sessions/s1/act/add"))
    host.forget()
    assert(host(Request.get("/sessions/s1")).body.contains("count 2"))
    assert(host(Request("POST", "/sessions/s1/act/add")).body.contains("count 3"))
  }

  test("the program made for each session: what it holds is the session's own") {
    var made = 0
    def counted: Unit ! Face = { made += 1; count(0) }
    val d = Door.rest(counted)
    val (a, b) = (d.session("a", () => ()), d.session("b", () => ()))
    a(Request("POST", "/sessions")); a(Request("POST", "/sessions/a/act/add"))
    b(Request("POST", "/sessions"))
    assertEquals(made, 2)
    assert(a(Request.get("/sessions/a")).body.contains("count 1"))
    assert(b(Request.get("/sessions/b")).body.contains("count 0"))
  }

  test("an agent's session (MCP) journaled by its header: the host forgets it, the journal brings it back") {
    val mcp = Door.mcp(count(0))
    var journal = List.empty[Request]
    var held = new Held
    def ask(body: String, opens: Boolean = false): Response = {
      val req = Request.post("/mcp", body).copy(headers = Map("content-type" -> List("application/json")) ++
        (if (opens) Map.empty else Map("mcp-session-id" -> List("m1"))))
      val (appended, r) = Machine.run(hosting.kept[L, Machine.Pure, Response](journal)(
        hosting.answer[L](mcp, "m1", held, req, opens)(Machine.splitLast[Machine.Pure, Kept]))(
        Machine.splitLast[Machine.Pure, Kept]))
      journal = journal ++ appended
      r
    }
    val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
      """"capabilities":{},"clientInfo":{"name":"t"}}}"""
    val opened = ask(init, opens = true)
    assertEquals(opened.headers.get("Mcp-Session-Id"), Some("m1"))
    assert(mcp.opens(Request.post("/mcp", init)) && mcp.idOf(Request.post("/mcp", "{}")).isEmpty)
    def call(n: Int, tool: String) =
      ask(s"""{"jsonrpc":"2.0","id":$n,"method":"tools/call","params":{"name":"$tool","arguments":{}}}""")
    assert(call(2, "add").body.contains("count 1"))
    assert(call(3, "add").body.contains("count 2"))
    held = new Held
    assert(call(4, "look").body.contains("count 2"), journal.map(_.body).mkString("\n"))
  }

  test("a session that moves by itself (an event while it waits) tells its host") {
    val bus = okay.faces.Meanwhile.manual[Int]
    def waiting(n: Int): Unit ! Face =
      offer[Int, Face](View.text(s"count $n"), List(Action.plain[Int]("add", "Add", None)(1)), bus.meanwhile)
        .flatMap(d => waiting(n + d))
    var told = 0
    val s = Door.rest(waiting(0)).session("e", () => told += 1)
    s(Request("POST", "/sessions"))
    assertEquals(told, 0)
    bus.fire(5)
    assertEquals(told, 1)
    assert(s(Request.get("/sessions/e")).body.contains("count 5"))
  }

  test("a journal kept as JSON reads back the same") {
    val r = Request("POST", "/s/s1/act/add", Map("x" -> List("1")), Map("content-type" -> List("text/plain"),
      "cookie" -> List("no")), "title=Milk")
    assertEquals(Hosting.fromJson(Hosting.json(Hosting.kept(r))), Some(Hosting.kept(r)))
    assertEquals(Hosting.kept(r).headers.keySet, Set("content-type"))
    val m = Request("POST", "/mcp").copy(headers = Map("mcp-session-id" -> List("s1"), "accept" -> List("x"),
      "authorization" -> List("no")))
    assertEquals(Hosting.kept(m).headers.keySet, Set("mcp-session-id", "accept"))
  }
}
