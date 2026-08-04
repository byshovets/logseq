// Self-host smoke test (the CI-worthy end-to-end check).
//
// Verifies, with NO manual login and NO manual sync step, that: browser A
// auto-logs-in, creates a graph, the graph auto-uploads, and an edit syncs to
// the server; then a fresh browser B auto-opens that graph and shows the edit.
// This is the A->B proof the whole design rests on.
//
// Prereqs: the dev stack is running (scripts/self-host/dev-selfhost.sh), i.e. the
// web app on :3001 and the db-sync adapter on :8787 with DB_SYNC_DISABLE_AUTH=true.
//
// Env:
//   APP_URL           default http://localhost:3001/index.html
//   APP_URL_B         browser B's URL, default APP_URL. Point it at the
//                     single-origin release server (http://localhost:8080/) to
//                     validate the release build end-to-end: run the dev app
//                     WITHOUT its own adapter, and let the single-origin server
//                     own :8787 so both origins share one database.
//   DB_SYNC_DATA_DIR  default <repo>/.selfhost-data  (must match the adapter's)
//   SMOKE_MODULES_DIR directory containing the Playwright and better-sqlite3
//                     dependencies; default <repo> (CI uses an isolated install)
//
// Exit code 0 = pass, 1 = fail. Uses the repo's own playwright + system Chrome.
// Browser A drives internal APIs, so APP_URL must be a dev (watch) build;
// browser B only reads the DOM, so APP_URL_B may be a release build.
const path = require('path');
const os = require('os');
const fs = require('fs');

const ROOT = path.join(__dirname, '..', '..');
const MODULES_DIR = process.env.SMOKE_MODULES_DIR || ROOT;
const { chromium } = require(require.resolve('playwright', { paths: [MODULES_DIR] }));
const Database = require(require.resolve('better-sqlite3', { paths: [MODULES_DIR] }));

const APP = process.env.APP_URL || 'http://localhost:3001/index.html';
const APP_B = process.env.APP_URL_B || APP;
const DATA_DIR = process.env.DB_SYNC_DATA_DIR || path.join(ROOT, '.selfhost-data');
const GRAPH = 'SmokeTest' + Date.now().toString().slice(-6);
const MARKER = 'smoke-note-' + Date.now();
const profileDir = (n) => path.join(os.tmpdir(), 'logseq-selfhost-smoke', n + '-' + process.pid);

function graphStats() {
  const dir = path.join(DATA_DIR, 'graphs');
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).map((g) => {
    const p = path.join(dir, g, 'db.sqlite');
    if (!fs.existsSync(p)) return null;
    const db = new Database(p, { readonly: true });
    const txs = db.prepare('select count(*) c from tx_log').get().c;
    db.close();
    return { g, txs };
  }).filter(Boolean);
}

async function graphSyncState(page) {
  return page.evaluate((graphName) => {
    const graphs = cljs.core.clj__GT_js(frontend.state.get_rtc_graphs());
    const rtc = cljs.core.clj__GT_js(
      frontend.state.get_state(cljs.core.keyword('rtc', 'state')),
    );
    const remote = graphs.find((graph) => graph.GraphName === graphName);
    return {
      remoteReady: remote?.['graph-ready-for-use?'] === true,
      wsState: rtc?.['rtc-state']?.['ws-state'] || null,
    };
  }, GRAPH);
}

async function boot(ctx, url) {
  const page = ctx.pages()[0] || (await ctx.newPage());
  await page.goto(url, { waitUntil: 'load', timeout: 90000 });
  await page.waitForSelector('#main-content-container', { timeout: 120000 });
  await page.waitForTimeout(9000); // boot + :self-host/init
  return page;
}

(async () => {
  console.log(`smoke: APP=${APP} APP_B=${APP_B} DATA_DIR=${DATA_DIR}`);
  // Browser A: log in (auto), create + upload a graph, edit it.
  const ctxA = await chromium.launchPersistentContext(profileDir('A'), { headless: true, channel: 'chrome' });
  const A = await boot(ctxA, APP);
  const st = await A.evaluate(() => ({
    loggedIn: frontend.handler.user.logged_in_QMARK_(),
    rtcGroup: frontend.handler.user.rtc_group_QMARK_(),
  }));
  if (!st.loggedIn || !st.rtcGroup) throw new Error('self-host session did not initialize: ' + JSON.stringify(st));

  await A.evaluate((g) => frontend.handler.repo.new_db_BANG_(g), GRAPH);
  for (let i = 0; i < 20; i++) { await A.waitForTimeout(1000); const r = await A.evaluate(() => frontend.state.get_current_repo()); if (r && r.includes(GRAPH)) break; }
  // No manual sync: creating/opening a never-synced graph auto-uploads it.
  // The server creates db.sqlite before the snapshot finishes. Wait for the
  // remote ready flag and RTC connection so an edit cannot race ahead of its
  // parent entities in the bootstrap snapshot.
  let syncState;
  for (let i = 0; i < 90; i++) {
    await A.waitForTimeout(1000);
    syncState = await graphSyncState(A);
    if (graphStats().length && syncState.remoteReady && syncState.wsState === 'open') break;
  }
  if (!graphStats().length || !syncState?.remoteReady || syncState.wsState !== 'open') {
    throw new Error('graph did not finish auto-upload and RTC startup: ' + JSON.stringify(syncState));
  }

  await A.evaluate((marker) => {
    const today = frontend.date.today();
    const opts = cljs.core.PersistentHashMap.fromArrays([cljs.core.keyword('page')], [today]);
    frontend.handler.editor.api_insert_new_block_BANG_(marker, opts);
  }, MARKER);
  let synced = false;
  for (let i = 0; i < 30; i++) { await A.waitForTimeout(2000); if (graphStats().some((x) => x.txs > 0)) { synced = true; break; } }
  if (!synced) throw new Error('edit did not sync to server tx_log');
  console.log('A: graph created, auto-uploaded, edit synced');

  // Browser B: fresh profile should auto-open the graph and show the edit.
  const ctxB = await chromium.launchPersistentContext(profileDir('B'), { headless: true, channel: 'chrome' });
  const B = await boot(ctxB, APP_B);
  await B.waitForTimeout(10000); // auto-open download + delta pull
  const hasMarker = await B.evaluate((m) => document.body.innerText.includes(m), MARKER);
  if (!hasMarker) throw new Error('browser B did not show the edit from A (auto-open/sync failed)');
  console.log('B: auto-opened the graph and shows A\'s edit');

  // Live sync: a second edit in A must reach the still-open B over B's own
  // WebSocket - this fails if the synthetic session doesn't feed the login-user
  // flow that gates RTC start in the first session.
  const MARKER2 = MARKER + '-live';
  await A.evaluate((marker) => {
    const today = frontend.date.today();
    const opts = cljs.core.PersistentHashMap.fromArrays([cljs.core.keyword('page')], [today]);
    frontend.handler.editor.api_insert_new_block_BANG_(marker, opts);
  }, MARKER2);
  let live = false;
  for (let i = 0; i < 30; i++) {
    await B.waitForTimeout(2000);
    if (await B.evaluate((m) => document.body.innerText.includes(m), MARKER2)) { live = true; break; }
  }
  await ctxA.close();
  await ctxB.close();
  if (!live) throw new Error('browser B did not receive A\'s live edit (RTC not running in first session)');
  console.log('B: received A\'s live edit over RTC');
  console.log('SMOKE PASS');
})().catch((e) => { console.error('SMOKE FAIL:', e.message); process.exit(1); });
