package okay.faces.examples

import okay.effects.json.Json.{Str, obj}
import okay.effects.machine.Machine._
import okay.effects.shape.Problem
import okay.faces.examples.Todo._
import okay.faces.test.Plays
import okay.faces.{Chosen, Rejected, View}

/** THE LIST BY SCRIPT on the test face: a form filled, an item done by its number, one removed after a choice and a
 * yes, the done ones cleared, back as undo; what the program shows and how it ends — the reference every face agrees
 * with */
class TodoSuite extends munit.FunSuite {
  val plays = Plays()
  import plays.{Back, Pick, Played, Script}
  def add(title: String, due: Option[String] = None): Chosen =
    Chosen("add", obj(("title" -> Str(title)) :: due.toList.map(d => "due" -> Str(d)): _*))
  def item(n: Int): Chosen = Chosen("ref", obj("key" -> Str(n.toString)))

  /** the list as the last view shows it: the script runs out at the list, so the play is stuck there */
  def ending(script: Script): (List[View], Vector[(String, String)]) = plays.handle(Todo.keeping)(script) match {
    case Played.Stuck(views, _, None) => (views, shown(views.last))
    case other => fail(s"$other")
  }

  test("two added, one done: the list kept, what was told and shown in order; the program has no end") {
    val (views, list) = ending(Script.of(add("milk"), add("bread", Some("2026-10-25")), item(1)))
    assertEquals(list, Vector("done" -> "milk", "pending" -> "bread"))
    assertEquals(views.head, about)
    assertEquals(views(2), View.info("added: milk"))
    assert(views.last.toString.contains("1 of 2 done") && views.last.toString.contains("due 2026-10-25"),
      views.last.toString)
  }
  test("the form checks: no title, the step unchanged, the problem at the field; a due day that is not a day") {
    plays.handle(Todo.keeping)(Script.of(Chosen("add", obj()))) match {
      case Played.Stuck(_, at, Some(Rejected(problems))) =>
        assertEquals(problems.map(_.path), List(List("title")));
        assertEquals(at.actions.map(_.name), List("add"))
      case other => fail(s"$other")
    }
    plays.handle(Todo.keeping)(Script.of(add("x", Some("soon")))) match {
      case Played.Stuck(_, _, Some(Rejected(problems))) => assertEquals(problems.map(_.path), List(List("due")))
      case other => fail(s"$other")
    }
  }
  test("remove one: a choice among the items by title, a yes; no undoes nothing") {
    val (views, list) = ending(Script.of(add("milk"), add("bread"), Chosen.plain("remove"), Chosen.plain("bread"),
      Chosen.plain("yes"), Chosen.plain("remove"), Chosen.plain("milk"), Chosen.plain("no")))
    assertEquals(list, Vector("pending" -> "milk"))
    assert(views.exists(_ == View.text("Remove which?")) && views.exists(_ == View.text("Remove 'bread'?")),
      views.toString)
  }
  test("clear the done: offered only once something is done; a yes removes them all") {
    val (_, list) = ending(Script.of(add("a"), add("b"), item(1), Chosen.plain("clear"), Chosen.plain("yes")))
    assertEquals(list, Vector("pending" -> "b"))
    plays.handle(Todo.keeping)(Script.of(add("a"), Chosen.plain("clear"))) match {
      case Played.Stuck(_, at, Some(Rejected(List(Problem(List("action"), _))))) =>
        assertEquals(at.actions.map(_.name), List("add", "ref", "remove"))
      case other => fail(s"$other")
    }
  }
  test("no step is returned to: Back leaves the list as it is") {
    plays.handle(Todo.keeping)(Script(Pick(add("a")), Pick(add("b")), Back)) match {
      case Played.Stuck(views, _, None) => assertEquals(shown(views.last), Vector("pending" -> "a", "pending" -> "b"))
      case other => fail(s"$other")
    }
  }

  test("the list as data, what a host keeps of it, reads back the same; a turn started at its point goes on there") {
    import okay.effects.shape.Day
    val t = Todo.Todos.empty.added(Todo.Item("Milk", Day.parse("2026-10-26").toOption, Some("2 l")))
      .added(Todo.Item("Bread")).toggled(2)
    assertEquals(Todo.Todos.read(Todo.Todos.write(t)), Some(t))
    assert(Todo.durable.from(okay.faces.Step.Point("todo", Todo.Todos.write(t))).nonEmpty)
    assert(Todo.durable.from(okay.faces.Step.Point("elsewhere", Todo.Todos.write(t))).isEmpty)
  }
}
