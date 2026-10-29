package okay.faces.ios.example

import okay.effects.machine.Machine.self
import okay.faces.examples.{Noughts, Todo}
import okay.faces.ios.{IosApp, IosFace}

/** THE EXAMPLES ON AN iPHONE, each one line, as on every other face: the shell opens one by its class name */
class TodoApp extends IosApp { def dialogue = IosFace(Todo.keeping, Todo.look) }
class NoughtsApp extends IosApp { def dialogue = IosFace(Noughts.games, Noughts.look) }
