// THE LIST ON CLOUDFLARE, end to end: `wrangler dev` (deploy.sh), a session by its pages, one by its API, one by an
// agent (MCP); the thin page in a headless Chrome, its session changed from elsewhere and the page told by its socket;
// then the Worker started again — every object's memory gone, its storage kept — and the sessions where they were,
// their journals played; then on from there. `node examples/cloudflare/e2e.js` after `deploy.sh --version`
const { spawn } = require("child_process"); const path = require("path");
const sleep = ms => new Promise(r => setTimeout(r, ms));
const dir = path.join(__dirname, "worker"); const base = "http://127.0.0.1:8798";
let dev, out = "";
const start = async () => {
  out = ""; dev = spawn("npx", ["-y", "wrangler@4", "dev", "--port", "8798", "--ip", "127.0.0.1"], { cwd: dir, detached: true });
  dev.unref();
  dev.stdout.on("data", d => out += d); dev.stderr.on("data", d => out += d);
  for (let i = 0; i < 120 && !/Ready on/.test(out); i++) await sleep(500);
  await sleep(500);
};
const stop = async () => { try { process.kill(-dev.pid, "SIGTERM"); } catch (e) {} await sleep(1500); };
const form = (url, fields) => fetch(base + url, { method: "POST", redirect: "manual",
  headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(fields).toString() });
const json = (url, body) => fetch(base + url, { method: "POST", headers: { "content-type": "application/json" },
  body: body === undefined ? "" : JSON.stringify(body) }).then(r => r.json());
(async () => {
  let ok = true; const check = (what, c) => { console.log(`${c ? "✓" : "✗"} ${what}`); ok = ok && !!c; };
  await start();
  // the pages: a session opened by `/s` (the thin page is at `/`), an item added by the form, the page after the redirect
  const opened = await fetch(base + "/s", { redirect: "manual" });
  const page = opened.headers.get("location");
  check("GET /s opens a session: " + opened.status + " " + page, opened.status === 303 && /^\/s\/[0-9a-f]{32}$/.test(page));
  const added = await form(page + "/act/add", { title: "Milk" });
  check("the form: " + added.status, added.status === 303);
  check("the page shows it", /Milk/.test(await (await fetch(base + page)).text()));
  // the API: a session opened, an item added
  const created = await fetch(base + "/sessions", { method: "POST" });
  const api = created.headers.get("location");
  check("POST /sessions: " + created.status + " " + api, created.status === 201);
  const r1 = await json(api + "/act/add", { title: "Bread" });
  check("the API: Bread added", /Bread/.test(JSON.stringify(r1)));
  check("the look, outside the sessions", /--okay-fg/.test(await (await fetch(base + "/okay.css")).text()));
  // the thin page: its session on the API; an item added from elsewhere, the page told by its socket (no polling:
  // the list waits on nothing)
  const fs = require("fs"); fs.rmSync("/tmp/ofc-chrome", { recursive: true, force: true });
  const chrome = spawn("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", ["--headless=new",
    "--remote-debugging-port=9371", "--user-data-dir=/tmp/ofc-chrome", "about:blank"], { stdio: "ignore" });
  let target; for (let i = 0; i < 40 && !target; i++) { await sleep(250);
    try { target = (await (await fetch("http://127.0.0.1:9371/json")).json()).find(t => t.type === "page"); } catch (e) {} }
  const ws = new WebSocket(target.webSocketDebuggerUrl); let n = 0; const pending = {}; const events = [];
  ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pending[m.id]) { pending[m.id](m.result); delete pending[m.id]; }
    else if (m.method) events.push(m); };
  await new Promise(r => ws.onopen = r);
  const cdp = (method, params = {}) => new Promise(r => { const id = ++n; pending[id] = r; ws.send(JSON.stringify({ id, method, params })); });
  const js = async ex => (await cdp("Runtime.evaluate", { expression: ex, returnByValue: true })).result.value;
  await cdp("Network.enable"); await cdp("Page.enable");
  await cdp("Page.navigate", { url: base + "/" });
  for (let i = 0; i < 40 && !(await js("/To do/.test(document.body.innerText)")); i++) await sleep(250);
  check("the thin page drawn", await js("/To do/.test(document.body.innerText)"));
  const thin = events.find(e => e.method === "Network.responseReceived" && /\/sessions$/.test(e.params.response.url));
  const self = thin && (thin.params.response.headers.Location || thin.params.response.headers.location);
  check("its session: " + self, /^\/sessions\/[0-9a-f]{32}$/.test(self || ""));
  for (let i = 0; i < 20 && !events.some(e => e.method === "Network.webSocketHandshakeResponseReceived"); i++) await sleep(250);
  check("its socket up", events.some(e => e.method === "Network.webSocketHandshakeResponseReceived" &&
    e.params.response.status === 101));
  const gets = () => events.filter(e => e.method === "Network.requestWillBeSent" && e.params.request.method === "GET" &&
    e.params.request.url.endsWith(self)).length;
  const before = gets();
  await json(self + "/act/add", { title: "Pushed" });
  for (let i = 0; i < 20 && !(await js("/Pushed/.test(document.body.innerText)")); i++) await sleep(250);
  check("added from elsewhere, the page shows it (told by its socket)", await js("/Pushed/.test(document.body.innerText)"));
  check("asked again once it was told: " + (gets() - before) + " GET", gets() - before >= 1);
  chrome.kill();
  // an agent: MCP over HTTP, its session by its header
  const mcp = async (body, sid) => {
    const r = await fetch(base + "/mcp", { method: "POST", headers: Object.assign({ "content-type": "application/json",
      accept: "application/json" }, sid ? { "mcp-session-id": sid } : {}), body: JSON.stringify(body) });
    return { sid: r.headers.get("mcp-session-id"), body: r.status === 202 ? null : await r.json() };
  };
  const init = await mcp({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18",
    capabilities: {}, clientInfo: { name: "e2e" } } });
  check("MCP: initialize, its session " + init.sid, /^[0-9a-f]{32}$/.test(init.sid || "") && init.body.result);
  await mcp({ jsonrpc: "2.0", method: "notifications/initialized" }, init.sid);
  const listed = await mcp({ jsonrpc: "2.0", id: 2, method: "tools/list" }, init.sid);
  check("MCP: tools " + listed.body.result.tools.map(t => t.name).join(","), listed.body.result.tools.some(t => t.name === "add"));
  const called = await mcp({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "add",
    arguments: { title: "Agent's" } } }, init.sid);
  check("MCP: added", /Agent's/.test(JSON.stringify(called.body)));
  // a turn begun: Milk to be removed, the question asked — then the Worker started again in the middle of it
  await form(page + "/act/remove", {});
  await form(page + "/act/milk", {});
  check("the question asked", /Remove &#39;Milk&#39;\?|Remove 'Milk'\?/.test(await (await fetch(base + page)).text()));
  // every object forgotten: the Worker again, the storage kept
  await stop(); await start();
  check("after a restart, the page: Milk", /Milk/.test(await (await fetch(base + page)).text()));
  const again = await (await fetch(base + api)).json();
  check("after a restart, the API: Bread", /Bread/.test(JSON.stringify(again)));
  const looked = await mcp({ jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "look", arguments: {} } },
    init.sid);
  check("after a restart, the agent's session: Agent's", /Agent's/.test(JSON.stringify(looked.body)));
  check("after a restart, the question still asked (the list's point, and the two requests since)",
    /Remove &#39;Milk&#39;\?|Remove 'Milk'\?/.test(await (await fetch(base + page)).text()));
  await form(page + "/act/yes", {});
  check("answered: Milk removed", !/Milk/.test(await (await fetch(base + page)).text()));
  await form(page + "/act/add", { title: "Eggs" });
  const both = await (await fetch(base + page)).text();
  check("on from there: Eggs", /Eggs/.test(both) && !/Milk/.test(both));
  check("another session, not this one's", !/Bread/.test(both));
  check("no session: 404", (await fetch(base + "/s/" + "0".repeat(32))).status === 404);
  await stop();
  console.log(ok ? "\nCLOUDFLARE OK" : "\nCLOUDFLARE FAILED\n" + out.slice(-2000));
  process.exit(ok ? 0 : 1);
})().catch(async e => { console.log("error:", e, out.slice(-2000)); await stop(); process.exit(1); });
