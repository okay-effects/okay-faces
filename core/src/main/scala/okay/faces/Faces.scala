package okay.faces

import okay.effects.{Effects, Sum}
import okay.effects.tagless.{Program, Tagless}
import okay.effects.shape.Shape

/**
 * THE WORDS of `Face`, over any implementation `fx`, and the stepping handler. A scenario is a program in
 * `Face + Domain`; a component is a function returning one, with a result — `ShowModal`.
 */
class Faces[F <: Effects with Singleton](implicit implementation: F) {
  val fx: F = implementation
  import fx._
  /** a session with a step machine, for the faces that serve many counterparts: `Session[fx.type, A, R]`, over this
   * implementation, so any face over it takes one made here; `over` what it shows at the end */
  def session[A, R[_]](first0: Step[A, ({ type L[X] = X ! R })#L] ! R,
    over: View = Session.over): Session[fx.type, A, R] =
    new Session[fx.type, A, R](fx, over) { protected def first: Step[A, M] ! R = first0 }

  def offer[A, R[_]](view: View, actions: List[Action[A]])(implicit in: In[Face, R]): A ! R =
    perform[Face].apply[R, A](Face.Offer[A, A](view, actions, a => a))
  /** show, and wait; the step's `options` (`Step.Options.returnable`: a step one may return to) */
  def offer[A, R[_]](view: View, actions: List[Action[A]], options: Step.Options)(implicit in: In[Face, R]): A ! R =
    perform[Face].apply[R, A](Face.Offer[A, A](view, actions, a => a, None, options))
  /** show, and wait for an action or for the event, whichever comes first */
  def offer[A, R[_]](view: View, actions: List[Action[A]], meanwhile: Meanwhile[A])(implicit in: In[Face, R]): A ! R =
    perform[Face].apply[R, A](Face.Offer[A, A](view, actions, a => a, Some(meanwhile)))
  def offer[A, R[_]](view: View, actions: List[Action[A]], meanwhile: Meanwhile[A], options: Step.Options)(
    implicit in: In[Face, R]): A ! R =
    perform[Face].apply[R, A](Face.Offer[A, A](view, actions, a => a, Some(meanwhile), options))
  /** show until something happens: no action, the event alone — a face that cannot listen is stuck here, so give an
   * action where that matters */
  def await[A, R[_]](view: View, meanwhile: Meanwhile[A])(implicit in: In[Face, R]): A ! R =
    offer[A, R](view, Nil, meanwhile)
  /** show, and go on. (`show`, not `tell`: the standard Writer's word is `tell`, the Reader's `ask`, and a scenario
   * imports both) */
  /** THE PROGRAM AT A POINT IT CAN BE TAKEN UP FROM, its state as data (`Durable` says it for a loop) */
  def mark[R[_]](point: String, state: okay.effects.json.Json)(implicit in: In[Face, R]): Unit ! R =
    perform[Face].apply[R, Unit](Face.Mark[Unit](point, state, u => u))
  def show[R[_]](view: View)(implicit in: In[Face, R]): Unit ! R =
    perform[Face].apply[R, Unit](Face.Show[Unit](view, u => u))

  /** yes or no */
  def confirm[R[_]](question: View, options: Step.Options = Step.Options())(implicit in: In[Face, R]): Boolean ! R =
    offer[Boolean, R](question,
      List(Action.plain[Boolean]("yes", "Yes")(true), Action.plain[Boolean]("no", "No")(false)), options)
  /** one of the options, each a plain action named from its label */
  def choose[A, R[_]](view: View, choices: List[(String, A)], options: Step.Options = Step.Options())(
    implicit in: In[Face, R]): A ! R = {
    val names = choices.map(o => Action.nameOf(o._1))
    val unique = names.zipWithIndex.map { case (n, i) => if (names.indexOf(n) == i) n else s"$n-${i + 1}" }
    offer[A, R](view, choices.zip(unique).map { case ((label, a), name) => Action.plain[A](name, label)(a) }, options)
  }
  /** a form, filled and submitted */
  def fill[P, R[_]](view: View, form: Shape[P], options: Step.Options = Step.Options())(
    implicit in: In[Face, R]): P ! R =
    offer[P, R](view, List(Action[P, P]("submit", "Submit", form)(p => p)), options)
  /** a form, filled and submitted, or cancelled */
  def fillOrCancel[P, R[_]](view: View, form: Shape[P], options: Step.Options = Step.Options())(
    implicit in: In[Face, R]): Option[P] ! R =
    offer[Option[P], R](view,
      List(Action[P, Option[P]]("submit", "Submit", form)(p => Some(p)),
        Action.plain[Option[P]]("cancel", "Cancel")(None)), options)

  /** `Face` alone is the row of a program of a face, `A ! Face`: one effect is a row (`splitOne`), no alias needed */
  /** A PROGRAM OF NO IMPLEMENTATION, on this one: written over `Faces.tagless` as a value, `Program[A, Face]`, it runs
   * here as `A ! Face` — the faces' words are the same, tagless makes them values, a face instantiates them */
  def of[A](p: Program[A, Face]): A ! Face = p.on(fx)

  /** THE STEPPING HANDLER: `Face` taken out of the row, the program a `Step` of what remains. `Offer` becomes a
   * `Waiting` whose `resume` decodes the choice and gives the rest as a program; `Show` a `Told`. General: `k` is kept
   */
  def steps[A, R[_], Rest[_]](p: A ! R)(
    implicit sp: Split[Face, R, Rest]): Step[A, ({ type L[X] = X ! Rest })#L] ! Rest = {
    type M[X] = X ! Rest
    handle[Face, R, Rest, A, Step[A, M]](p,
      new Handler[Face, Rest, A, Step[A, M]] {
        def ret(a: A) = pure(Step.Done[A, M](a))
        def op[X](op: Face[X], k: X => Step[A, M] ! Rest) = op match {
          case Face.Offer(view, actions, answer, meanwhile, options) =>
            pure[Step[A, M], Rest](Step.Waiting[A, M](view, actions,
                chosen => Step.take(chosen, actions).map(a => k(answer(a))), meanwhile.map(_.map(a => k(answer(a)))),
                options))
          case Face.Show(view, answer) => pure[Step[A, M], Rest](Step.Told[A, M](view, k(answer(()))))
          case Face.Mark(point, state, answer) =>
            flatMap[Step[A, M], Step[A, M], Rest](k(answer(())))((s: Step[A, M]) =>
              pure[Step[A, M], Rest](Step.at[A, M](Step.Point(point, state), s,
                (next: M[Step[A, M]], f: Step[A, M] => Step[A, M]) =>
                  flatMap[Step[A, M], Step[A, M], Rest](next)((x: Step[A, M]) => pure[Step[A, M], Rest](f(x))))))
        }
      })
  }
}

/** THE FACES OVER AN IMPLEMENTATION: `Faces()` with one in scope, `Faces.of[Machine.type]`. A fresh value each time,
 * and that is fine: `Faces` holds no state, only words over `fx`, so any two over one implementation are the same
 * faces — what a step machine makes on one runs on another, and a `Dialogue` is typed by the implementation alone.
 * A face (`SwingFace`, `Terminal`, …) takes the implementation, not faces: it makes its own over `fx.type`, so every
 * type in it is on ONE path, `fx`'s */
object Faces {
  /** THE FACES OF NO IMPLEMENTATION: `okay.faces` itself — programs written over `import okay.faces._` are values, and
   * every
   * face handles them, instantiated on its own implementation */
  val tagless: Faces[Tagless.type] = okay.faces.`package`
  /** A FACE'S RUN AS A PROGRAM OF NOTHING: what a face does when it runs a program — a window, a console, a server —
   * as a tagless program, run by `okay.faces.run` on the implementation in scope:
   * `running(face.fx.run(face.handle(p)))`
   */
  def running[A](a: => A): Program[A, Tagless.Pure] = Tagless.delay[A, Tagless.Pure](Tagless.pure[A, Tagless.Pure](a))
  def apply()(implicit fx: Effects): Faces[fx.type] = {
    implicit val exact: fx.type = fx
    new Faces[fx.type]
  }
  def of[F <: Effects with Singleton](implicit fx: F): Faces[F] = new Faces[F]()(fx)
}
