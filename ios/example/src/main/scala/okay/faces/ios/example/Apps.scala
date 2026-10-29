package okay.faces.ios.example

import okay.effects.machine.Machine
import okay.faces.examples.{Noughts, Todo}
import okay.faces.ios.IosFace

/** THE EXAMPLES ON AN iPHONE, each one line, as on every other face: the shell opens one by its class name */
class TodoApp extends okay.faces.ios.IosApp {
  def dialogue = IosFace[Machine.type, Unit](Todo.keeping, Todo.look)(Machine)
}
class NoughtsApp extends okay.faces.ios.IosApp {
  def dialogue = IosFace[Machine.type, Noughts.Outcome](Noughts.games, Noughts.look)(Machine)
}
