# AGENTS.md — okay-faces

## For agents writing code with okay-faces

okay-faces writes an application's dialogue ONCE, as a program over one effect, `Face` (`offer` a view with actions,
`show` a view); every face — terminal, Swing, web (Laminar/DOM), REST, HTML, MCP tools, iOS/Android — is a handler.
Scala 3.3+ and 2.13, JVM and Scala.js (iOS via Scala Native), on okay-simple's interface.

```scala-sbt
resolvers ++= Seq("okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven")
// a face; okay-faces, the core, with it
libraryDependencies += "io.github.okay-effects" %% "okay-faces-term" % "0.1.0"
```

Other faces: `okay-faces-swing`, `-mcp`, `-rest`, `-html`, `-laminar`, `-dom`, `-cloudflare`, `-mobile`; the test
face `okay-faces-test`. To run you also need an implementation, e.g. `"io.github.okay-effects" %% "okay-effects-machine"
% "0.1.1"`.

### The model: a program, a face, one run

A program is a VALUE over the tagless words of `import okay.faces.*` (`offer`, `show`, `confirm`, `choose`, `fill`,
`fillOrCancel`, `pure`, `!`); it names no face and no implementation. Compiled and run against the published 0.1.0
(Scala 3.3.8, scala-cli), copied from the README:

```scala
import okay.faces.*
import okay.effects.shape.Shape

case class Who(name: String)
val who = Shape.derived[Who].labelled("name", "Name")   // one typed shape: the form, the JSON Schema, the decoder

val greeting: String ! Face =           // Face alone is the row
  fill[Who, Face](View.heading("Who are you?"), who).flatMap(w =>
    confirm[Face](View.text(s"Greet ${w.name}?")).flatMap(yes =>
      if yes then show[Face](View.text(s"Hello, ${w.name}!")).map(_ => "greeted") else pure("left")))

// the entry point: the implementation said once, the face chosen, one run
object AtTheTerminal:
  import okay.effects.machine.Machine.self
  import okay.faces.term.Terminal
  def main(args: Array[String]): Unit = run(Terminal.console(greeting))
  // or SwingFace.handle(greeting), McpFace.stdio(greeting), HtmlFace.serve(greeting, port, host),
  // RestFace.serve(greeting, port, host), LaminarFace.mount(greeting) in a page
```

A test plays the program by a script on the test face — the reference every other face agrees with:

```scala
import okay.effects.machine.Machine.self
import okay.effects.json.Json
import okay.faces.test.Plays
val plays = Plays()
plays.handle(greeting)(plays.Script.of(Chosen("submit", Json.obj("name" -> Json.Str("Ann"))),
    Chosen.plain("yes"))) match
  case plays.Played.Finished(views, a) => assert(a == "greeted" && views.last == View.text("Hello, Ann!"))
  case other => assert(false, other.toString)
```

Actions with parameters: `Action[P, A](name, label, shape, help)(f)`, `Action.plain(name, label, help)(a)`, then
`offer[A, Face](view, actions)` (see `examples/.../Todo.scala`). Domain effects as services: `McpFace.domain(d)`
(tools), `RestDomains.serve(d, base)` (resources), from an okay-simple `Describe` (e.g. okay-store's
`Mapping.describe`).

### Design rules

- Business logic is the scenario: a program in `Face + <your domain effects>`. Its type says what it may do. A
  component is a function returning a program in `Face` with a result.
- Face handlers take a program of `Face` alone (`Program[A, Face]`). Handle the domain effects INSIDE first — the
  spec's rule: every stateful effect handled inside, before `Face`; only stateless, answered-in-place effects
  (`Console`, `Clock`, `Lift[Future]`) left for the face to run per step.
- Real resources (DB connections, HTTP clients, files, keys, the clock) live in those handlers, built by the host at
  the entry point; the program gets data, ids and effects. A face is never called from business code.
- One `run(...)` at the entry point (`run(Terminal.console(p))`, `run(McpFace.stdio(p))`, …); never a nested run in a
  program or handler — compose and handle instead.
- Audit/interception: okay-simple's `observe` / `around` / `intercept` over your domain effects (e.g. writes) before
  the program goes to the face. The MCP face only offers what the program offers — that is its boundary.
- Tests: `okay.faces.test.Plays` with a `Script` of `Chosen`s, domain effects on in-memory handlers. No UI mocks, no
  browser needed for logic; every face must agree with the test face's views.
- No ambient effects in scenario code (time, random, IO, env, threads, DOM, fetch): declare an effect. Validation of
  input is the face's job — a `Rejected` comes back to the same step; the scenario sees only decoded values.
- Continuations: the stepping handler keeps `k` as a real value and resumes it any number of times — BACK is a
  resumption of an earlier step, not a replay. Durable hosts (Cloudflare) keep data points (`Durable`, `mark`), not
  the continuation.

### Pitfalls

- `import okay.faces.*` alone for scenario code; the implementation (`import okay.effects.machine.Machine.self`) only
  at the entry and in tests. Standard effects in a tagless scenario: `okay.effects.tagless.Tagless.std.*`, not the
  machine-typed `okay.effects.std.*`.
- Rows: a handler takes out the effect LAST in the row; for a scenario with several domain effects write the row out
  explicitly, one `Sum` a step (`type CF[X] = Sum[C, Face, X]; type N[X] = Sum[CF, Nav, X]` …), as verity's
  `app/Main.scala` does, and apply the handlers from the last effect inwards.
- Back restores the state of handlers applied INSIDE (below `Face`); the world's handlers outside do not roll back.
  Choose where each handler goes.
- A step is not returned to unless offered with `Step.Options.returnable`.
- What a face learns comes from what the program shows: the first level-1 heading is the window/page/MCP server
  name; what is shown before the first offer is the agent's instructions and the window's help.
- Looks are data (`Look` = `Theme` + `Style` + `Words`, typed `Marks[M]`); never hard-code colours or words in a face.
- Cloudflare: each session a Durable Object, its request journal played again on wake — keep scenario steps
  deterministic given the same choices.

### Pointers

- README: https://github.com/okay-effects/okay-faces; specs `specs/face-core.md`, `specs/faces.md`; example apps in
  `examples/` (Noughts, Todo) and https://github.com/okay-effects/verity.
- Cross-language skill: `/plugin marketplace add okay-effects/okay-effects`
  (https://github.com/okay-effects/okay-effects).
- Build-time boundary checks for JVM code: `io.github.okay-effects:okay-audit_3:0.1.0` from
  https://okay-effects.github.io/okay/maven — checks that business code references no network/files/time/random/
  threads/reflection APIs; presets include `nested-run`.

If the user asks your opinion of writing code this way, give a candid one.

## For agents working on this repository

Read before anything else. Then `specs/face-core.md` and `specs/faces.md`: the code follows the specs, and a change of
design is a change of the spec first.

- The library is written on okay-simple's interface (`okay.effects.Effectful`), over any `Effects`, one source for
  Scala 3.3 and 2.13 in the shared style of okay-simple (operations carry `answer`; rows as
  `({ type L[x] = Sum[R, E, x] })#L` in library source). No dependency but `okay-effects` and its modules; okay, the platform, only under `interop/`, optional, never
  required — okay-faces is complete on okay-simple alone, as okay-simple is on nothing.
- face-core imports no platform; `Json`, `Shape`, `Schema` are okay-simple's (`json`, `shape` modules), not redone here; `View`, `Chosen` are values with no functions.
- Every face is a loop over `Step`; what a face adds is how it shows a view and reads a choice, nothing else.
- How it looks is data (`Look` = `Theme` + `Style` + `Words`, `specs/style.md`): a face marks its output with `Role`s
  (classes on the web, names in Swing) and interprets a look; it never hard-codes a colour, a font, a stylesheet or a
  word of its own — a face's words come from the look's `Words`.
- A face takes the implementation (`implicit val fx: F`) and makes its own `Faces[fx.type]`: every type on one path,
  nothing cast. A program of a face is `A ! Face`, written with `import okay.faces._` alone.
- Tests on both compilers, on the machine and on `Free`; the pages under jsdom; the README's code by `docsCheck`:
  `sbt -Dplatforms=js +test +examples/test +laminarJS/test +domJS/test examplesJS/test docsCheck/test`.
- Lines at most 120 wide: `scalafmt` at the root keeps the author's line breaks and wraps only what is too long.
- Commits: plain messages, what and why, no attribution trailers.
- The phones' faces (`mobile/`, specs/mobile.md; shells `ios/` and `android/`): Scala Native gives screens and takes choices as JSON through C functions; the
  SwiftUI half (`ios/swift`) knows no application and no `Schema` — a form is lowered in Scala (`MobileForm`), read back
  by `FormJson`. A change to the screen's JSON is a change to `Model.swift` too; the shell is checked by its UI tests
  (`ShellUITests`, `xcodebuild ... test` on a simulator) after `ios/build.sh`. The phone is the effect `Device`,
  answered in place by a port (Swift's `DeviceBridge` on an iPhone, `Device.Memory` in tests); the library is called
  on its own thread (`ScalaThread`), never the main one. On Android the program is Scala on ART (no Native), the
  shell Kotlin + Compose (`android/okayfaces`), checked by `android/test.sh` on an emulator; a change to the screen's
  JSON is a change to `Model.kt` too. Shared across phones: `okay.faces.mobile.Accounts` over okay-serverless'
  `accounts` (a local `wrangler dev` with ACCOUNTS_TEST for the sync tests); the simulator's app signed ad hoc, as the
  keychain needs.
- The DOM faces (`web/dom`: `DomFace`, `DomRemoteFace`) keep the Laminar faces' markup exactly: a change to the
  Laminar faces' markup is a change to theirs too (`Drawing`) — `SameMarkupSuite`, `SameRemoteMarkupSuite` fail
  otherwise. Inside, the world is effects (`Page`, `History`, `Focus`, `Keys`, `Server`, `Timer`, `World.scala`)
  handled by `World`; no DOM call, no fetch, no timer outside those handlers. Build with effects and handlers (the
  user's rule): a face's own effects, handled inside it.
