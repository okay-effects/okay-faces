# AGENTS.md — okay-faces

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
  JSON is a change to `Model.kt` too.
- The DOM faces (`web/dom`: `DomFace`, `DomRemoteFace`) keep the Laminar faces' markup exactly: a change to the
  Laminar faces' markup is a change to theirs too (`Drawing`) — `SameMarkupSuite`, `SameRemoteMarkupSuite` fail
  otherwise. Inside, the world is effects (`Page`, `History`, `Focus`, `Keys`, `Server`, `Timer`, `World.scala`)
  handled by `World`; no DOM call, no fetch, no timer outside those handlers. Build with effects and handlers (the
  user's rule): a face's own effects, handled inside it.
