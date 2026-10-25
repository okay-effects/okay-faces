# Hosting on Cloudflare Workers (spec, 2026-10-25)

A way of DEPLOYING a program, not a part of it: the program and its other mains are unchanged; one more main, one
line, serves it on Cloudflare Workers, its sessions Durable Objects. Module `web/cloudflare`
(`okay-faces-cloudflare`): `Hosting` and `Door` everywhere (tested on the JVM), the Worker's glue Scala.js's alone.

## The shape

- **A door** (`Door`): where a face's sessions are, which request opens one, which session a request is of (its
  path's segment after `base` — `Door.byPath` — or a header), the service of a session by its id —
  `Door.html(program, settings, atRoot)` (the HTML face; `base`, and `/` unless `atRoot` is false, open),
  `Door.rest(program, settings)` (the REST face, the thin page's; `POST base` opens), `Door.mcp(program, settings,
  path)` (the MCP face over streamable HTTP, `McpFace.served`; an `initialize` with no `Mcp-Session-Id` opens, the
  header names the session), `Door.shell(title, script, look)` (the thin page at `/`, its script at `/index.js`, no
  session). A session's service is the face's own handler, run on the
  implementation given, its ids fixed to the one id. The program is taken BY NAME, made again for each session: a
  handler it holds (a ledger in memory) is the session's own — never shared, never doubled by a journal played.
- **The Worker** (`Cloudflare.serve(doors*)`, its `fetch` exported as `okayFacesFetch`): a request that opens a
  session gets a new random id and goes to the Durable Object of `base/id`; a request of a session (`base/{id}/…`)
  to its object; anything else (the look's `/okay.css`) answered in the Worker by a session never opened. The door,
  the id, whether it opens: headers to the object. A redirect is the browser's to follow, never the stub's.
- **A session, a Durable Object** (`OkayFacesSession`, binding `OKAY_FACES_SESSIONS`): `Hosting`'s program.
- **Pushed** (2026-10-25): a page's socket to its session, `base/{id}/socket`, accepted by the object's state
  (hibernation: held by Cloudflare while the object sleeps); each socket told `moved` when the session changes — a
  request that changed it (journaled), from anywhere, or an event while it waited (core's `Session.moved`, the REST
  face's `Settings.moved`). The thin pages — the Laminar one (`RemoteFace`, `RemotePage`) and the DOM one
  (`DomRemoteFace`, `DomRemotePage`, its `Server.Listen` handled by `World`) — open the socket (`socket`, by default
  in `mount`), ask again when told, and do not poll while it is up; where the host has none (the JVM's server) the
  socket fails and they poll as before: one page for every host.
- **The thin page's script** is given by the Worker's entry as text (`okayFacesAsset("/index.js", …)`, wrangler's
  Text rule for `*.txt`), read by `Cloudflare.asset`.

## Where memory is lost: the state kept, not the way there (2026-10-25)

Cloudflare drops an idle object; a session's continuation is code (`Frames.Bind(f: X => Prog)` — a function) and is
never stored. What is stored is DATA the program says it is at: a POINT (`Face.Mark(point, state)`, `mark`), its name
and its state as JSON. `Durable` makes a program of points: `Durable.loop(name, write, read)(start)(turn)` — a loop of
a state, the point passed at the start of each turn, `from(point)` the program started at it. The step machine
carries a point to the step it comes to (`Step.Options.at`), a session tells it (`Session.marked`, the faces'
`Settings.marked`, `McpFace.served(…, marked)`), a door takes `from` (`Door.html/rest/mcp(…, from = d.from)`).

A host keeps of a session (`Saved`): the request that OPENED it; the last POINT its program passed — and when one is
passed, the requests before it are forgotten; the requests that changed it SINCE (each but a read, unless refused). An
object new starts the program at the point (or from the start, with no point), plays the opening request and the
few since, and is where it was: the middle of a turn — a question asked — too. A program with no points is kept by
all its requests, as before (deterministic, then). An event's move, the clock: their results are in the state at the
next point.

`Hosting` is a program over the effect `Kept` (`Read`, `Put` of a `Write`: `Opened`, `Pointed`, `Appended`).
Handlers: the object's storage — `open`, `at`, `j:<n>` (the requests since), read once when the object is new, written
after (a point deletes the `j:` keys); a value (`Hosting.kept`, `after`: the tests').

## Deploying

The application's Cloudflare main, linked as an ES module (`fullLinkJS`), copied as `main.js` beside
`template/entry.js` and `template/wrangler.toml` (the binding, the SQLite class, the Text rule); the thin page's
script (linked on its own) as `page.js.txt`; `wrangler dev`, `wrangler deploy`. The example: `examples/cloudflare` —
`TodoCloudflare` (the thin page at `/`, the pages at `/s`, the API at `/sessions`, the tools at `/mcp`), `deploy.sh`
(`PAGE=dom` for the thin page with no library), `e2e.js`: a session by its pages, one by its API, one by an agent;
the thin page in a headless Chrome told by its socket when its session changed elsewhere; the Worker started again:
all where they were.

## Not yet

A timer that must outlast the object is an alarm (later). A point's state that cannot be read back (a program
changed) starts the program from the start: a migration is the program's (`read` may take older states). MCP's
server-sent requests outside a POST (none: GET is 405, as on the JVM).
