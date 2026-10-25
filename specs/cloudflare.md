# Hosting on Cloudflare Workers (spec, 2026-10-25)

A way of DEPLOYING a program, not a part of it: the program and its other mains are unchanged; one more main, one
line, serves it on Cloudflare Workers, its sessions Durable Objects. Module `web/cloudflare`
(`okay-faces-cloudflare`): `Hosting` and `Door` everywhere (tested on the JVM), the Worker's glue Scala.js's alone.

## The shape

- **A door** (`Door`): where a face's sessions are (`base/{id}`), which request opens one, the service of a session
  by its id — `Door.html(program, settings)` (the HTML face; `/` and `base` open), `Door.rest(program, settings)`
  (the REST face, the thin page's; `POST base` opens). A session's service is the face's own handler, run on the
  implementation given, its ids fixed to the one id.
- **The Worker** (`Cloudflare.serve(doors*)`, its `fetch` exported as `okayFacesFetch`): a request that opens a
  session gets a new random id and goes to the Durable Object of `base/id`; a request of a session (`base/{id}/…`)
  to its object; anything else (the look's `/okay.css`) answered in the Worker by a session never opened. The door,
  the id, whether it opens: headers to the object. A redirect is the browser's to follow, never the stub's.
- **A session, a Durable Object** (`OkayFacesSession`, binding `OKAY_FACES_SESSIONS`): `Hosting`'s program.

## Where memory is lost

Cloudflare drops an idle object; a session's continuation (closures) cannot be stored. So a session is its
JOURNAL: the requests that changed it — the one that opened it, each one but a read, unless refused (a status of 400
or more) — kept as they reach the face (method, path, query, body and its type; no other header). An object new
plays its journal through a new service of the same id and is where it was; then answers. The program must be
deterministic for that: what is not (the clock, the outside world) is to be journaled as effects' results (later).

`Hosting` is a program over the effect `Kept` (`Journal`, `Append`): the session held, or woken by its journal; the
request answered; what changed it appended. Handlers: the object's storage (`j:<n>`, read once when the object is
new, appended to after — Cloudflare holds the response until it is stored); a list (`Hosting.kept`, the tests').

## Deploying

The application's Cloudflare main, linked as an ES module (`fullLinkJS`), copied as `main.js` beside
`template/entry.js` and `template/wrangler.toml` (the binding, the SQLite class); `wrangler dev`, `wrangler deploy`.
The example: `examples/cloudflare` — `TodoCloudflare`, `deploy.sh`, `e2e.js` (a session by its pages, one by its API,
the Worker started again: both where they were).

## Not yet

The thin page served from the Worker (its script linked into the Worker); `Meanwhile` pushed over a WebSocket held by
the object (hibernation) instead of polled; the MCP face's door; a journal's effects' results (the clock); a journal
grown long, cut by a snapshot when the program can give one.
