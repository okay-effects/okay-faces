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
