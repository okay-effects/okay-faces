# okay-faces — an application's capabilities as effects, its faces as handlers

An application has several faces: a window for a person, a page in a browser, a line dialogue at a terminal, tools
for an agent, resources for a program. Each shows WHAT IS THERE and WHAT CAN BE DONE. okay-faces writes that once,
as a program over one algebraic effect, `Face`, and each face is a handler of it.

The idea underneath is older than user interfaces: algebraic effects are algebraic data, so a problem is modelled at
whatever level of abstraction suits it, and the model is interpreted level by level — an effect into another effect,
and at the bottom into what actually runs. Here the levels are: a program, the effect `Face`, a step machine every
face shares, each face's own effects (`Console`, `Http`, `Link`), the world.

![one step of the game on every face](docs/okay-faces-how.png)

Built on [okay-simple](https://github.com/okay-effects/okay-simple)'s interface, for Scala 3.3 and 2.13, the JVM and
Scala.js, over any of its implementations; the data an application speaks — `Json`, `Shape`, `Schema` — is
okay-simple's. One dependency, no platform.

```scala-sbt
resolvers ++= Seq("okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven")
// a face; okay-faces, the core, with it
libraryDependencies += "io.github.okay-effects" %% "okay-faces-term" % "0.1.0"
```

The modules, one per face, are in [specs/face-core.md](specs/face-core.md); releases in [RELEASING.md](RELEASING.md).

## A program, and every face

A program is a value. It names no face and no implementation: `import okay.faces._` brings the words, and the words
are tagless.

```scala
import okay.faces.*
import okay.effects.shape.Shape

case class Who(name: String)
val who = Shape.derived[Who].labelled("name", "Name")   // one typed shape: the form, the JSON Schema, the decoder

val greeting: String ! Face =           // Face alone is the row
  fill[Who, Face](View.heading("Who are you?"), who).flatMap(w =>
    confirm[Face](View.text(s"Greet ${w.name}?")).flatMap(yes =>
      if yes then show[Face](View.text(s"Hello, ${w.name}!")).map(_ => "greeted") else pure("left")))

// every face is a handler of the one effect; the implementation is said once, where the program is run:
object AtTheTerminal:
  import okay.effects.machine.Machine.self
  import okay.faces.term.Terminal
  def main(args: Array[String]): Unit = run(Terminal.console(greeting))
  // or SwingFace.handle(greeting), McpFace.stdio(greeting), HtmlFace.serve(greeting, port, host),
  // RestFace.serve(greeting, port, host), LaminarFace.mount(greeting) in a page

// the test face plays it by a script, and tells what was shown — the reference every other face agrees with:
import okay.effects.machine.Machine.self
import okay.faces.test.Plays
val plays = Plays()
plays.handle(greeting)(plays.Script.of(Chosen("submit", okay.effects.json.Json.obj("name" -> okay.effects.json.Json.Str("Ann"))),
    Chosen.plain("yes"))) match
  case plays.Played.Finished(views, a) => assert(a == "greeted" && views.last == View.text("Hello, Ann!"))
  case other => assert(false, other.toString)
```

The block above is compiled and run by `sbt docsCheck/test`.

| face | module | what it is |
|---|---|---|
| test | `test` | a script of choices drives the step machine; what was shown, asserted |
| terminal | `term` | a line dialogue in okay-effects-io's `Console`; the person a handler of `Fields`, asked field by field; a typed `[key]` is a click; ANSI when asked |
| Swing | `swing` | a panel or a window on the JVM; forms from the shape; dressed by a `Look`; what the program tells first is the window's help |
| Laminar | `web/laminar` | Scala.js: the view drawn, forms from the shape, a click is `resume`, the browser's back; `Async` in the row to a `Future`; `web/airstream` and `web/waypoint` the bridges |
| DOM | `web/dom` | Scala.js, no library: the Laminar face's page, markup for markup — a look dresses it the same, only the handler changes; inside, a program over its own effects (the page, history, focus, keys), with focus, Escape as back and accessibility attributes |
| remote | `web/laminar`, `web/dom` | the same page over a dialogue on a server behind the REST face, the browser knowing no application — by Laminar (`RemoteFace`) or with no library (`DomRemoteFace`, the same markup) |
| MCP | `mcp` | the dialogue as tools for an agent, on [okay-mcp](https://github.com/okay-effects/okay-mcp): stdio, or HTTP with sessions; a domain effect's operations as tools too |
| REST | `rest` | the dialogue as a JSON API with links and affordances (HATEOAS); a domain effect as resources, and performed from afar over `Fetch` — on [okay-http](https://github.com/okay-effects/okay-http) |
| HTML | `html` | pages from the server, structural HTML5, no script; forms posted; the look as `/okay.css` |
| Android | `mobile`, `android/` | the program as Scala on Android's runtime, the screen in Jetpack Compose (`android/okayfaces`), knowing no application; the same screens, forms, look and `Device` as the iPhone's — the network, storage, the Keystore, notifications ([specs/mobile.md](specs/mobile.md)) |
| iOS | `mobile`, `ios/` | the program in Scala Native, a static library of eight C functions (screens and choices as JSON); the screen in SwiftUI (`ios/swift`, the package `OkayFaces`), knowing no application; forms lowered by dotted name, the look interpreted; the phone as an effect, `Device` — the network, storage, the keychain, notifications, links — answered by Swift ([specs/mobile.md](specs/mobile.md)) |

Where it runs is a deploying, not a face: the page alone (a static host), a server on the JVM (`HtmlFace.serve`,
`RestFace.serve`), or **Cloudflare Workers** — `web/cloudflare`, one more main, one line,
`Cloudflare.serve(Door.shell(…), Door.html(program), Door.rest(program), Door.mcp(program))`: each session a Durable
Object, its journal of requests played again when the object wakes; the thin page told by its session's socket when
it moved, polling where a host has none ([specs/cloudflare.md](specs/cloudflare.md), `examples/cloudflare`).

## How it works

`Face` has two operations: `Offer(view, actions)`, answered by the action taken with its parameters decoded, and
`Show(view)`. A general handler, `steps`, takes `Face` out of the row and gives the program as a `Step` machine —
`Waiting(view, actions, resume)`, `Told(view, next)`, `Done(a)` — with the continuation kept, so a step is resumed, and
may be resumed again. Every face is a loop over `Step`: what a face adds is how it shows a view and how it reads a
choice, nothing else. A choice comes back as JSON, `Chosen(action, args)`; the action's `Shape` decodes it, and a
`Rejected` carries the problems back to the same step.

![the levels](docs/okay-faces-levels.png)

What a face learns of a program it learns from what the program shows: the first level-1 heading is a window's title,
a page's, an agent's server's name; what is told before the first offer is the agent's instructions and the window's
help. A step is not returned to unless the program says so, `Step.Options.returnable`; then every face keeps it in its history
and offers a way back. How it looks is data: a `Look` is a `Theme` of tokens, a `Style` of rules by `Role` and the
face's own `Words`, compiled by each face (CSS, Swing, ANSI); an application's own words on views are typed marks,
`Marks[M]` over a sealed set, one source for the look, the wire and the vocabulary an agent is told. The faces that
serve many counterparts keep `Sessions`: random ids, forgotten when idle. The other role, a domain effect as a
service: okay-simple's `Describe` projected here — `RestDomains`, `McpFace.domain`, `remote`.

Read [specs/face-core.md](specs/face-core.md), [specs/faces.md](specs/faces.md), [specs/style.md](specs/style.md),
[specs/web.md](specs/web.md): the code follows the specs, and a change of design is a change of the spec first.

## The examples

Two programs in `examples/`, each on every face, each main one line: noughts and crosses (`Noughts`) and a to-do
list (`Todo`, with a form from a type, items done by a click, removal after a choice and a confirmation).

```
sbt "examples/runMain okay.faces.examples.NoughtsSwing"            # a window; TodoSwing likewise
sbt "examples/runMain okay.faces.examples.NoughtsTerminal"         # the console
sbt "examples/runMain okay.faces.examples.NoughtsMcp"              # an MCP server on stdio
sbt "examples/runMain okay.faces.examples.NoughtsHtml 8090"        # pages, no script
sbt -Dplatforms=js examplesRemoteJS/fullLinkJS "examples/runMain okay.faces.examples.NoughtsWebRemote 8090"
                                                                   # a JSON API, and the thin page that plays it
sbt -Dplatforms=js examplesJS/fullLinkJS "examples/runMain okay.faces.examples.NoughtsWebLocal 8090"
                                                                   # the whole game in the browser
```

On a phone, the same two programs as `MobileApp`s (`ios/example`), and a third that needs the phone — `NotesApp`:
notes kept between runs, a page fetched, a reminder notified: the library for the simulator, then the shell
(XcodeGen makes its project):

```
ios/build.sh simulator                                             # libokay-faces-mobile-example.a for the shell
cd ios/example/Shell && xcodegen && open Shell.xcodeproj           # run; `-app okay.faces.mobile.example.NoughtsApp`
xcodebuild -project Shell.xcodeproj -scheme Shell -destination 'platform=iOS Simulator,name=iPhone 16 Pro' test
                                                                   # both played by taps (ShellUITests), screens kept
android/test.sh                                                    # Android: built, played on the emulator
```

The first application is [verity](https://github.com/okay-effects/verity), a keeper of one's personal data, on every
face above; [okay-store](https://github.com/okay-effects/okay-store) describes its types for them.

## Checking

Every module is tested on both compilers, on the machine and on `Free`; the page faces under jsdom; the examples on
every face against the test face's views; the README's code by `docsCheck`. Lines are at most 120 wide (`scalafmt`
at the root keeps the author's line breaks and wraps only what is too long).

```
sbt -Dplatforms=js +test +examples/test +laminarJS/test +domJS/test examplesJS/test docsCheck/test
sbt -Dplatforms=native mobileNative/test                           # the iOS face's C functions, on the Mac
```

okay-simple 0.1.1, okay-http 0.1.0 and okay-mcp 0.1.0 come from their release repositories: nothing to publish
locally first.
