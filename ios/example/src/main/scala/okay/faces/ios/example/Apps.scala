package okay.faces.ios.example

import okay.effects.machine.Machine.self
import okay.faces.examples.{Noughts, Todo}
import okay.faces.ios.{IosApp, IosFace}

/** THE EXAMPLES ON AN iPHONE, each one line, as on every other face: the shell opens one by its class name */
/** the list taken up where it was: iOS may end the app, the list is kept on the phone */
class TodoApp extends IosApp { def dialogue = IosFace.durable(Todo.durable, device, Todo.look) }
class NoughtsApp extends IosApp { def dialogue = IosFace(Noughts.games, Noughts.look) }
