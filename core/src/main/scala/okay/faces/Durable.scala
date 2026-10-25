package okay.faces

import okay.effects.json.Json

/**
 * A PROGRAM THAT SAYS WHERE IT CAN BE TAKEN UP AGAIN: its points, each a name and a state as data, and how to start
 * the program at one. A host that loses its memory (a Durable Object put to sleep) keeps the last point passed instead
 * of what led there, and starts the program there again (`from`): the continuation is code, the point is data. A loop
 * whose state is a value is the usual shape (`Durable.loop`); a program with none is `Durable.plain` — kept, if at all,
 * by what led where it is.
 */
final class Durable[A](val program: A ! Face, points: Map[String, Json => Option[A ! Face]]) {
  /** the program at a point it passed — none if it has no such point, or its state does not read */
  def from(p: Step.Point): Option[A ! Face] = points.get(p.name).flatMap(_(p.state))
  /** this, and another's points too (a program made of loops) */
  def and(other: Durable[A]): Durable[A] = new Durable[A](program, points ++ other.pointsMap)
  private[faces] def pointsMap: Map[String, Json => Option[A ! Face]] = points
}

object Durable {
  /** A LOOP OF A STATE: `step` each turn — `Right` the next state, `Left` the end; the point `name` passed at the
   * start of each turn with the state written (`write`), read back by `read` to start a turn there again */
  def loop[S, A](name: String, write: S => Json, read: Json => Option[S])(start: S)(
    step: S => Either[A, S] ! Face): Durable[A] = {
    def turn(s: S): A ! Face = mark[Face](name, write(s)).flatMap(_ =>
      step(s).flatMap {
        case Left(a) => pure[A, Face](a)
        case Right(next) => turn(next)
      })
    new Durable[A](turn(start), Map(name -> ((j: Json) => read(j).map(turn))))
  }
  /** a program with no point: kept, where it is kept, by what led where it is */
  def plain[A](p: A ! Face): Durable[A] = new Durable[A](p, Map.empty)
}
