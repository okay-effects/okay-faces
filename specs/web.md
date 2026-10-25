# web — Airstream, Laminar, Waypoint, and the Laminar face

Status: specification, moved from okay-simple's backlog 2026-10-20. `web/laminar` — the FACE, `LaminarFace`, a Step
machine as a Laminar element — is in (2026-10-20), for a row done (`element`, `Runner.run`) and for `Async` remaining (`elementAsync`, to a Future);
sums in forms; the browser's back as the face's. The bridges are in too (2026-10-20): `web/airstream` — `fromEventStream`,
`fromSignal`, `toEventStream`, `fromProgram`, `into`, `varState`; `runOnMount` in `web/laminar` (`Lifecycle`);
`web/waypoint` — `Navigation` with `router(r)`, `recording`, `pages`. Tested under jsdom on both compilers.

## The modules: Airstream, Laminar, Waypoint

Asked 2026-10-18 for okay-simple (moved there from okay's backlog); moved HERE 2026-10-20: they are the web's side of
an application's face, okay-faces owns its whole web story and releases on its own, and okay-simple stays the narrow,
released interface. okay-ui's own host and its typed DOM vocabulary stay in okay, `backlog.d/okay-ui/okay-laminar.md`.

Three modules in the directory `web/`, Scala.js only, both compilers, each a class over any `Runner` with its default
on the machine, as okay-simple's modules are. None depends on `face-core`: a Laminar team may take `web/airstream`
alone. The face itself, `web/face-laminar` (a `Step` loop as a Laminar component: `View` and actions rendered, a click
is `resume`), comes after them and depends on them and on `face-core`.

| Module | Artifact | Package | Depends on |
|---|---|---|---|
| `web/airstream` | `okay-faces-airstream` | `okay.faces.airstream` | okay-effects-stream, okay-effects-async; `com.raquo::airstream` |
| `web/laminar` | `okay-faces-laminar` | `okay.faces.laminar` | web/airstream; `com.raquo::laminar` |
| `web/waypoint` | `okay-faces-waypoint` | `okay.faces.waypoint` | web/laminar; `com.raquo::waypoint` |

Versions (Maven Central, 2026-10-18): Laminar and Airstream 17.2.1, published for Scala 3 (on 3.3.1) and 2.13;
Waypoint 9.0.0, on Laminar 17.2.0 and url-dsl 0.6.2. 18.0.0-M5 / 10.0.0-M7 are milestones: take the stable ones.

**web/airstream — Airstream's streams and okay's, both ways.**
- `fromEventStream(es)(owner)` and `fromSignal(s)(owner)`: a `Stream[O, Async]`, subscribed on its first pull, the
  events between pulls buffered, released with the `Owner` (a Laminar element's, or a `ManualOwner`); a `Signal`'s
  current value its first element.
- `toEventStream(s)`: an okay `Stream[O, Async]` as an `EventStream[O]` by `EventStream.fromCustomSource`: started
  (`asynchrony.start`) when Airstream starts it, its `Running` cancelled when Airstream stops it — so the stream runs
  exactly while something observes it. `fromProgram(p)`: one program the same way, its value or failure one event.
- `varState(v)(p)`: a `Var[S]` as the handler of `State % S`, in place: `get` is `v.now()`, `put` is `v.set`, so a page
  sees a program's state as it changes.
- `s.into(observer)`: a stream drained into an Airstream `Observer`.

**web/laminar — programs in a page, tied to its elements.**
- `runOnMount(p)(done)`: a `Modifier` that starts the program when the element mounts and cancels it when it unmounts
  (okay-async's cancellation: what the program waits on is cancelled too); its result, a value or a failure, to `done`.
- `child.text <-- fromProgram(p)` and the like come from web/airstream for free; this module adds the element-level
  words and a test of the lifecycle.

**web/waypoint — routes as an effect.**
- `Navigation[P, X]`: `Push(page)`, `Replace(page)`, `Current` — operations a program performs, with Scala 2's
  `answer` functions (shared source).
- `router(r)(p)`: Waypoint's `Router[P]` as their handler: `pushState`, `replaceState`, `currentPageSignal.now()`.
- `pages(r)(owner)`: the current page as a `Stream[P, Async]` (web/airstream's `fromSignal`).
- A test handler that records the pages asked for: a program that navigates, tested with no browser history.

**Build.** JS-only cross projects (`CrossProject(id, file(dir))(JSPlatform)`, defined only when `-Dplatforms` asks for
js), in `testJS` and `publishPages`, the build copied from okay-simple's. Tests under jsdom: `addSbtPlugin("org.scala-js" % "sbt-scalajs-env-jsdom-nodejs"
% "1.1.0")`, `jsEnv := new JSDOMNodeJSEnv(...)`; jsdom 29.1.1 is installed globally here (`/opt/homebrew/lib`), so the
env needs `NODE_PATH` (or a local `npm install jsdom`).

**Tests.**
- airstream: a stream crossed twice is the same elements; an `EventStream`'s events buffered between pulls; a `Signal`'s
  current value first; `toEventStream` starts once on the first observer and its program is cancelled when the last
  stops; `varState` sees every `put`.
- laminar (jsdom): mount starts, unmount cancels exactly once; a failure reaches `done`, not the console.
- waypoint (jsdom): `Push` changes `currentPageSignal` and the URL; `Current` answers it; the recording handler.
- Both compilers, as every module.

**Docs.** `docs/web.md`: the three modules, their versions, an example each. The blocks cannot run in docsCheck
(the JVM): mark them as excerpts of the tests, as the site does.

**Check first.** `EventStream.fromCustomSource`'s exact signature in 17.2.1; Waypoint 9.0.0's `Router` constructor
(routes, `getPageTitle`, `serializePage`, `deserializePage`, `popStateEvents`, `owner`) and whether it can run under
jsdom; how `AsyncStreams` holds its `ExecutionContext` (the browser's is the macrotask one), to do the same.

## The remote face — the dialogue on the server, the page in the browser

`RemoteFace(transport).element(base)` is a Laminar element over a dialogue that lives behind the REST face. The client
knows no application: it draws whatever `View` comes (`ViewJson.decode`), draws a form from whatever `Schema` comes
(`Affordance.action`, structural over the schema, so a choice is checked where it is made before it travels), and
sends a `Chosen` back. The wire is the REST face's resource — `data` with `view`, `actions` as affordances (the exact
`Schema`, for us; the links keep JSON Schema for everyone else), `back`, `done`; `links` with `self` — and its three
calls: `POST base` opens, `POST self/act/{name}` chooses (`400` with problems leaves the step), `POST self/back`.

The transport is a function `Request => Future[Response]`: okay-http's `Browser.fetch()` in a page, the REST face's
own service run on the machine in a test — so the whole loop, server and page, is tested under jsdom with no network
(`RemoteFaceSuite`). DECIDED: the remote face keeps its own small state (`Shown`) rather than posing as a `Step`
machine: a rejection comes back asynchronously as a `400`, and a `Step` rejects synchronously; forcing one onto the
other would show problems as a new step and push a history entry for nothing.

This is the case "write the application once, pick Laminar and REST, get a server with a rich page": verity's `app`
mode is a REST server plus a shell page and the linked script, and the page is `RemoteFace(Browser.fetch()).element("/sessions")`
and nothing else. The look comes as `/okay.css` from the same server. What is not in the browser: the program —
every step is a round trip, as pages from the server are, with the page kept and the forms live. The other cut, the
dialogue in the browser and the domain behind REST, is `Describe` + `RestDomains.remote` under `Browser.fetches`
(okay-http, `Fetch` awaited through `Async`): verity's `browser` mode runs its whole scenario in the page with `Store`
and `Files` on the server (verity `web/Local`, `LocalSuite` under jsdom).

## The DOM face — the Laminar face's page with no library, its world as effects

`web/dom` (`okay-faces-dom`, `okay.faces.dom`, Scala.js, both compilers): `DomFace` is the Laminar face with the same
API (`element`, `elementAsync`, `handle`, `mount`, `Settings`) and the SAME MARKUP — tag for tag, class for class,
attribute for attribute — so a look's stylesheet dresses it the same and an application changes its handler, nothing
else. `SameMarkupSuite` plays every scenario on both faces and compares the page after each act (Laminar's comment
nodes and the DOM face's accessibility attributes, which no selector of a look uses, left out).

THE REMOTE FACE BY THE DOM: `DomRemoteFace(transport).element(base)` is the Laminar `RemoteFace`'s page — the same
wire, the same markup (`SameRemoteMarkupSuite`: one dialogue on an in-process REST face, both pages, the markup after
each settled act), what was typed kept when the server refuses — with no library; `DomRemotePage` its script, on
okay-simple's machine. The REST resource's reading is core's `Resource` (`decode`, `problems`), both remote faces'.
What both DOM faces draw is `Drawing`'s.

Inside, the faces are programs over effects of their own, handled inside them — the world reached in one place:
- `Page` — the DOM: an element made (tag, classes, attributes, children), text, a place (a comment), a node swapped,
  put before another, removed, a listener added, a value read or set, buttons disabled, a case shown or hidden;
- `History` — the browser's history: a step pushed, the browser's back listened to;
- `Focus` — where the keyboard is: the first field of a step drawn, the first field with a problem;
- `Keys` — keys listened to: Escape is back where the step offers it;
- `Server` — a request through the face's transport, its reply to a callback (the remote face);
- `Timer` — something later: a listening step asked again (the remote face).
Each has one handler, the browser's (`World`), applied where the face runs its programs (`world`, which a face may
override to intercept any of them). The machine's state (the step shown, its history) stays the face's.

KEEPING WHAT IS ON THE PAGE (2026-10-25). A step that follows another often shows nearly the same: an event
(`Meanwhile`) brought a line more to a timeline, a card changed its mark. Drawn again whole, the page would lose what
was typed, the focus, the scroll. So when the new step offers THE SAME ACTIONS as the one shown (names, labels,
docs, parameters' schemas, the fields held back or long) with the same history and told views, only its view is
changed, IN PLACE (`Page.Morph`): a node equal to the new one is kept; an element of the same tag with no listener
of its own keeps its node, its attributes and children made the new ones', position by position; anything else
replaced. A node with a listener (a ref, a form, a button) is kept whole or replaced whole, never half. The forms
of the actions are not touched at all: what is typed stays. And a choice is the CURRENT step's: a kept button or
form chooses on the step shown now, not on the one that drew it (the same actions, so the same meaning).
One machine may show program after program (`Session.show(first, done)`): an application that runs its own loop
(okay-ui's runner) keeps one face, its page kept between screens the same way.

LONG TEXT: an action's fields `long` (`Action.withLong(names*)`, like `withMore`, on the wire too) are drawn as a
`<textarea>` — on the DOM and Laminar faces, on the HTML face.

Accessibility, by `Page`: the problems a live region (`role="alert"`), a field with a problem `aria-invalid`, a
field's doc its `aria-describedby`, the face `aria-busy` while a rest runs. Focus by default (`Settings.focus`).
