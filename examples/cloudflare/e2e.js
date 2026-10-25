// THE LIST ON CLOUDFLARE, end to end: `wrangler dev` (deploy.sh), a session by its pages and one by its API; then the
// Worker started again — every object's memory gone, its storage kept — and both sessions where they were, their
// journals played; then on from there. `node examples/cloudflare/e2e.js` (wrangler from npx)
const { spawn } = require("child_process"); const path = require("path");
const sleep = ms => new Promise(r => setTimeout(r, ms));
const dir = path.join(__dirname, "worker"); const base = "http://127.0.0.1:8798";
let dev, out = "";
const start = async () => {
  out = ""; dev = spawn("npx", ["-y", "wrangler@4", "dev", "--port", "8798", "--ip", "127.0.0.1"], { cwd: dir, detached: true });
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
  // the pages: a session opened by `/`, an item added by the form, the page after the redirect
  const opened = await fetch(base + "/", { redirect: "manual" });
  const page = opened.headers.get("location");
  check("GET / opens a session: " + opened.status + " " + page, opened.status === 303 && /^\/s\/[0-9a-f]{32}$/.test(page));
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
  // every object forgotten: the Worker again, the storage kept
  await stop(); await start();
  check("after a restart, the page: Milk", /Milk/.test(await (await fetch(base + page)).text()));
  const again = await (await fetch(base + api)).json();
  check("after a restart, the API: Bread", /Bread/.test(JSON.stringify(again)));
  await form(page + "/act/add", { title: "Eggs" });
  const both = await (await fetch(base + page)).text();
  check("on from there: Milk and Eggs", /Milk/.test(both) && /Eggs/.test(both));
  check("another session, not this one's", !/Bread/.test(both));
  check("no session: 404", (await fetch(base + "/s/" + "0".repeat(32))).status === 404);
  await stop();
  console.log(ok ? "\nCLOUDFLARE OK" : "\nCLOUDFLARE FAILED\n" + out.slice(-2000));
  process.exit(ok ? 0 : 1);
})().catch(async e => { console.log("error:", e, out.slice(-2000)); await stop(); process.exit(1); });
