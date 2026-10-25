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

  test("a journal kept as JSON reads back the same") {
    val r = Request("POST", "/s/s1/act/add", Map("x" -> List("1")), Map("content-type" -> List("text/plain"),
      "cookie" -> List("no")), "title=Milk")
    assertEquals(Hosting.fromJson(Hosting.json(Hosting.kept(r))), Some(Hosting.kept(r)))
    assertEquals(Hosting.kept(r).headers.keySet, Set("content-type"))
  }
}
