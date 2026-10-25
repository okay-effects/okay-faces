package okay.faces

import okay.effects.json.Json
import okay.effects.shape.Problems

/**
 * THE EFFECT: a representation offered with the actions available, answered by the action taken. A console program's
 * `println` and `readLine`, Delphi's `ShowModal`, an MCP server's elicitation: the program leads, the counterpart
 * answers, and the answer is the way up — a click, a tool call, a line typed, each the handler's business. A long-lived
 * screen is a loop of offers.
 */
sealed trait Face[X]
object Face {
  /** show, and wait for one of the actions, taken with its parameters — or for the event `meanwhile`, if one is given;
   * `options`: what the step says of itself (`Step.Options`: whether it may be returned to) */
  final case class Offer[A, X](view: View, actions: List[Action[A]], answer: A => X,
    meanwhile: Option[Meanwhile[A]] = None, options: Step.Options = Step.Options())
    extends Face[X]
  /** show, and go on */
  final case class Show[X](view: View, answer: Unit => X) extends Face[X]
  /** THE PROGRAM IS AT A POINT IT CAN BE TAKEN UP FROM: the point's name, its state as data — a host that loses its
   * memory keeps this instead of what led here, and starts the program there again (`Durable`); a face goes on */
  final case class Mark[X](point: String, state: Json, answer: Unit => X) extends Face[X]
}

/** what a face sends back: an action's name and its arguments, a JSON object */
final case class Chosen(action: String, args: Json = Json.Obj.empty)
object Chosen {
  def plain(action: String): Chosen = Chosen(action, Json.Obj.empty)
}

/** a choice no step's action fits, or arguments that did not decode: the face shows the problems and waits on the same
 * step */
final case class Rejected(problems: Problems)
