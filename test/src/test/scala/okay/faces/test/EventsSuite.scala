package okay.faces.test

import okay.effects.{Sum, Void}
import okay.effects.machine.Machine
import okay.faces.{Action, Face, Faces, Meanwhile, View}

/** AN EVENT WHILE A STEP WAITS: offered beside the actions, it resumes the step when it fires; the person's choice
 * resumes it before; a step with no actions waits on the event alone */
class EventsSuite extends munit.FunSuite {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  val plays: Plays[Machine.type] = new Plays[Machine.type]()(Machine)
  import Machine._
  import faces._
  import plays._
  type F[X] = Sum[Void, Face, X]
  sealed trait What; case object Skipped extends What; final case class Arrived(n: Int) extends What

  def waiting(bus: Meanwhile.Manual[What]): String ! F =
    offer[What, F](View.text("waiting for the mail"), List(Action.plain[What]("skip", "Skip")(Skipped)),
      bus.meanwhile).flatMap {
      case Skipped => show[F](View.text("skipped")).map(_ => "skipped")
      case Arrived(n) => show[F](View.text(s"$n letters")).map(_ => s"got $n")
    }

  test("the event fires: the step resumed with its value, the rest told; the listener gone after") {
    val bus = Meanwhile.manual[What]
    val played = play(steps[String, F, Void](waiting(bus)), Script(Fire(() => bus.fire(Arrived(3)))))
    assertEquals(played, Played.Finished(List(View.text("waiting for the mail"), View.text("3 letters")), "got 3"))
    assertEquals(bus.listening, 0)
  }
  test("an event at once, as the step listens: the step after it shown, its actions with it") {
    // a phone's events come between steps: one there already is heard as the next step listens
    val eager = new Meanwhile[What](k => { k(Arrived(2)); () => () })
    val program: String ! F =
      offer[Unit, F](View.text("first"), List(Action.plain[Unit]("go", "Go")(()))).flatMap(_ =>
        offer[What, F](View.text("waiting for the mail"), List(Action.plain[What]("skip", "Skip")(Skipped)), eager))
        .flatMap(w => offer[String, F](View.text(s"after $w"), List(Action.plain[String]("done", "Done")("done"))))
    val session = faces.session[String, Void](steps[String, F, Void](program))
    assert(run(session.choose(okay.faces.Chosen.plain("go"))).isRight)
    assertEquals(run(session.shown)._2, View.text("after Arrived(2)"))
    assertEquals(run(session.actions).map(_.name), List("done"))
  }
  test("the person first: the choice resumes the step, the event is no longer listened to") {
    val bus = Meanwhile.manual[What]
    val played = play(steps[String, F, Void](waiting(bus)), Script.of(okay.faces.Chosen.plain("skip")))
    assertEquals(played, Played.Finished(List(View.text("waiting for the mail"), View.text("skipped")), "skipped"))
    assertEquals(bus.listening, 0)
  }
  test("await: no actions, the event alone; a fire with nothing listening leaves the step") {
    val bus = Meanwhile.manual[Int]
    val program: Int ! F = await[Int, F](View.text("loading"), bus.meanwhile)
    assertEquals(play(steps[Int, F, Void](program), Script(Fire(() => bus.fire(7)))),
      Played.Finished(List(View.text("loading")), 7))
    val none = Meanwhile.manual[Int]
    play(steps[Int, F, Void](program), Script(Fire(() => none.fire(1)))) match {
      case Played.Stuck(views, at, None) =>
        assertEquals(views, List(View.text("loading"))); assertEquals(at.actions, Nil)
      case other => fail(other.toString)
    }
  }
  test("from a future: the value when it comes, not after a cancel") {
    import scala.concurrent.Promise
    import scala.concurrent.ExecutionContext.Implicits.global
    val p = Promise[Int]()
    var got = List.empty[Int]
    val cancel = Meanwhile.fromFuture(p.future).register(n => got ::= n)
    cancel(); p.success(1)
    // the second's value awaited, not slept for: on Scala.js there is no sleeping; the first, cancelled, came before
    val q = Promise[Int](); val seen = Promise[Unit]()
    Meanwhile.fromFuture(q.future).register { n => got ::= n; seen.trySuccess(()); () }
    q.success(2)
    seen.future.map(_ => assertEquals(got, List(2)))
  }
  test("a jump from any screen: the screens unwound, the one named begun; done when a screen ends without one") {
    val jumps = new okay.faces.Jumps[Machine.type]()(Machine)
    import jumps._
    sealed trait Place; case object Home extends Place; case object Deep extends Place
    type J[X] = Sum[F, ({ type L[x] = Jump[Place, x] })#L, X]
    def screen(p: Place): Unit ! J = p match {
      case Home => offer[Boolean, J](View.text("home"),
          List(Action.plain[Boolean]("deeper", "Deeper")(true), Action.plain[Boolean]("quit", "Quit")(false))).flatMap(
          deeper =>
            if (deeper) screen(Deep) else show[J](View.text("bye")))
      case Deep => offer[Unit, J](View.text("deep"), List(Action.plain[Unit]("home", "Home")(()))).flatMap(_ =>
          jump[Place, J, Unit](Home))
    }
    val played = play(steps[Unit, F, Void](jumping[Place, J, F](Home)(screen)),
      Script.of(okay.faces.Chosen.plain("deeper"), okay.faces.Chosen.plain("home"), okay.faces.Chosen.plain("quit")))
    assertEquals(played,
      Played.Finished(List(View.text("home"), View.text("deep"), View.text("home"), View.text("bye")), ()))
  }
}
