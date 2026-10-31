package app

import okay.effects.machine.Machine.self
import okay.faces._
import okay.faces.mobile.{Device, MobileApp, MobileFace}
import okay.faces.mobile.phone._

/** YOUR APP: a count kept on the phone — its screens are `offer`s and `show`s, what it asks of the phone `save`,
 * `load`, `fetch`, `remind`, `open`. Change it, and both phones change. */
object Counter {
  type P[X] = Device.Phone[X]

  def turn(n: Int): Unit ! P =
    offer[String, P](View.stack(View.heading("Counter"), View.text(s"$n so far")), List(
      Action.plain[String]("more", "One more")("more"), Action.plain[String]("reset", "Start again")("reset")))
      .flatMap {
        case "more" => save[P]("count", (n + 1).toString).flatMap(_ => turn(n + 1))
        case _ => forget[P]("count").flatMap(_ => turn(0))
      }

  val program: Unit ! P = load[P]("count").flatMap(c => turn(c.flatMap(_.toIntOption).getOrElse(0)))
}

/** what the phone opens, by its name: `app.CounterApp` */
class CounterApp extends MobileApp { def dialogue = MobileFace.phone(Counter.program, device) }
