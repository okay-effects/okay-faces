package okay.faces.ios.example

import okay.effects.json.Json

class AppsSuite extends munit.FunSuite {
  def read(s: String): Json = okay.effects.json.Descent.parse(s).fold(e => fail(e.message), j => j)
  def at(j: Json, k: String): Json = j match { case o: Json.Obj => o.get(k).getOrElse(Json.Null); case _ => Json.Null }

  test("the list on the shell's wire: an item added by the form, the list shown with it") {
    val d = new TodoApp().dialogue
    val first = read(d.open())
    assertEquals(at(first, "title"), Json.Str("To do"))
    val after = read(d.act("""{"action": "add", "form": {"title": "milk"}}"""))
    assert(Json.print(after).contains("added: milk"), Json.print(after))
    assert(Json.print(after).contains("\"ref\""), Json.print(after))
  }
}

class NotesSuite extends munit.FunSuite {
  import okay.faces.ios.{Device, IosApp}
  def read(s: String): Json = okay.effects.json.Descent.parse(s).fold(e => fail(e.message), j => j)

  test("notes on a phone in memory: kept between runs, GitHub's answer shown, a reminder posted") {
    val phone = new Device.Memory(network = (_, u, _) =>
      Device.Reply(200, Map.empty, """{"description": "faces", "stargazers_count": 3, "pushed_at": "today"}"""))
    IosApp.phone = phone
    val d = new NotesApp().dialogue
    assert(d.open().contains("Nothing kept yet."))
    d.act("""{"action": "add", "form": {"text": "Buy milk"}}""")
    assertEquals(phone.kept.get("notes"), Some("Buy milk"))
    assert(new NotesApp().dialogue.open().contains("Buy milk"), "kept for the next run")
    assert(d.act("""{"action": "github"}""").contains("\"Stars\""))
    assert(d.act("""{"action": "remind"}""").contains("A reminder in 5 seconds."))
    assertEquals(phone.notified.toList, List("Notes" -> "Buy milk"))
  }
}
