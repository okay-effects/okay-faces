package okay.faces

import okay.effects.shape.Problem

/**
 * THE STEP MACHINE: a program of `Face` turned into steps by its standard handler, which draws nothing. Every face is a
 * loop over `Step`: show the view with its actions, read a `Chosen`, `resume` — and RUN the rest, `M[Step]`, a program
 * of the row that remains (`[X] => X ! Rest`): by `run` when nothing remains, to a `Future` for a browser, by the
 * world's handlers for a console. What a face adds is those things and no more. `resume` holds the program's
 * continuation, deep and resumable any number of times: a face that keeps earlier `Waiting`s has BACK for free —
 * whatever handlers were applied INSIDE `Face` have their state in the continuation and come back with it; the world's
 * handlers outside do not.
 */
sealed trait Step[A, M[_]]
object Step {
  final case class Done[A, M[_]](a: A) extends Step[A, M]
  /** shown, and waiting: `resume` decodes the choice against `actions` — `Left(Rejected)`, and the face shows the
   * problems
   * and waits on THIS step again — or the rest, a program of the remaining row */
  final case class Waiting[A, M[_]](view: View, actions: List[Action[_]],
    resume: Chosen => Either[Rejected, M[Step[A, M]]],
    meanwhile: Option[Meanwhile[M[Step[A, M]]]] = None, options: Options = Options())
    extends Step[A, M] {
    /** whether this step may be returned to: a face keeps it in its history then, and offers a way back */
    def back: Boolean = options.back
    /** a face that listens: the rest of the program when the event fires, to run as `resume`'s */
    def listen(k: M[Step[A, M]] => Unit): () => Unit = meanwhile.fold(() => ())(_.register(k))
  }
  /** WHAT A STEP SAYS OF ITSELF, beside its view and actions — one value, so a new option is a new field and no pattern
   * on `Offer` or `Waiting` breaks. `back`: whether the step may be returned to once left; not by default, most steps
   * are not to be returned to. `at`: the point the program passed on its way here (`Face.Mark`), if it passed one
   * since the step before — where it can be taken up again */
  final case class Options(back: Boolean = false, at: Option[Point] = None)
  /** a point a program can be taken up from: its name, its state as data */
  final case class Point(name: String, state: okay.effects.json.Json)
  object Options {
    /** a step one may return to */
    val returnable: Options = Options(back = true)
  }
  /** the point a program passed on its way to a step, carried by the step it came to */
  def at[A, M[_]](p: Point, s: Step[A, M], later: (M[Step[A, M]], Step[A, M] => Step[A, M]) => M[Step[A, M]])
    : Step[A, M] = s match {
    case w: Waiting[A, M] @unchecked => w.copy(options = w.options.copy(at = Some(p)))
    case Told(v, next) => Told[A, M](v, later(next, at(p, _, later)))
    case other => other
  }
  /** told, and going on at once: a face shows it and runs the rest */
  final case class Told[A, M[_]](view: View, next: M[Step[A, M]]) extends Step[A, M]

  /** THE OPENING of a program: what it tells before its first offer, and that first step — settled once by a face that
   * wants to know before it shows (a window its title, an agent's server its name and instructions); `run` the face's
   */
  final case class Opening[A, M[_]](intro: Vector[View], first: Step[A, M]) {
    /** the first level-1 heading shown: a window's title, a page's, a server's name */
    def title: Option[String] =
      View.title(intro ++ (first match { case w: Waiting[A, M] @unchecked => Vector(w.view); case _ => Vector.empty }))
  }
  def opening[A, M[_]](first: M[Step[A, M]], run: M[Step[A, M]] => Step[A, M]): Opening[A, M] = {
    def settle(s: Step[A, M], told: Vector[View]): Opening[A, M] = s match {
      case Told(v, next) => settle(run(next), told :+ v)
      case other => Opening(told, other)
    }
    settle(run(first), Vector.empty)
  }
  /** the action named, taken with the arguments, or why not */
  def take[A](chosen: Chosen, actions: List[Action[A]]): Either[Rejected, A] =
    actions.find(_.name == chosen.action) match {
      case None => Left(Rejected(List(Problem(List("action"), s"one of ${actions.map(_.name).mkString(", ")}"))))
      case Some(a) => a.taken(chosen.args).left.map(Rejected(_))
    }
}
