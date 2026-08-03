# Start here - self-host implementation handoff

One page for whoever picks this up. Depth is in [PLAN.md](./PLAN.md); this is the map.

## What this is
Turn this Logseq fork into a **single-user, self-hosted web app**: the browser
holds the graph locally (OPFS sqlite), the `db-sync` Node adapter is the
server-side database, no in-app auth, reachable from any browser. All design
decisions are **closed** - see PLAN.md 0 and the `[D]` tags throughout.

## Current state (DONE)
Branch **`byshovets/self-host-web-mvp`**, built against upstream edition
`9a11243d50` (pnpm era). MVP + Phase 4, committed as atomic commits:
1. `self-host: add DB_SYNC_DISABLE_AUTH mode to db-sync adapter` (server)
2. `self-host: client single-user no-auth mode (SELF_HOST flag)` (client MVP)
3. `self-host: add plan, run script, and smoke test to the repo` (docs/scripts)
4. `self-host: extract fork-owned namespace, minimize upstream edits` (refactor)
5. `self-host: origin-default sync URLs, auto-upload first graph, OPFS gate`
6. `self-host: single-origin server and production Docker image`
7. `self-host: deploy runbook and phase-4 status docs`

**Phase 4 (productionization, PLAN.md 10) - implemented and verified:**
- **10.1** single-origin release build: sync URLs default to the page origin in
  self-host release builds (`config.cljs`), a stdlib-only Node server
  (`scripts/self-host/single-origin-server.mjs`) serves `static/` + proxies the
  adapter (incl. the `/sync/` WS upgrade), and a fork-owned Docker image
  (`scripts/self-host/Dockerfile`) packages both. The stale root Dockerfile is
  left untouched (upstream-owned; ours is separate by the PLAN.md 11 principle).
- **10.2** deploy runbook with Pocket ID forward-auth at the proxy:
  [DEPLOY.md](./DEPLOY.md).
- **10.3** first-run gap closed: a graph without a ready remote counterpart
  **auto-uploads** on create/open (with bounded retry over a backend outage;
  failures surface as an error notification). Interrupted uploads recover: a
  not-ready server row **older than 30 minutes** is deleted and the upload
  retried - the age gate exists because the ready bit alone cannot distinguish
  an interrupted upload from one another tab/browser is running right now.
  Auto-open picks the newest **ready** remote graph (by server metadata = most
  recently added, not most recently edited; polls while all candidates are
  still uploading) and only fires on a fresh browser (current graph nil/Demo).
  Demo graphs stay local on purpose - every fresh browser makes its own local
  Demo, so syncing it would collide.
- **10.4** storage capability gate: unsupported browsers and insecure origins
  (plain HTTP on non-localhost - OPFS needs a secure context) each get an
  explicit error page (verified by stripping `getDirectory` in a real browser).
  Non-localhost access therefore requires HTTPS: `TLS_CERT`/`TLS_KEY` on the
  single-origin server, or TLS at a proxy in front (DEPLOY.md).

All of it is verified: the A->B smoke passes on the dev stack, on the release
single-origin server, and against the built Docker container (fresh browser
auto-opens the seeded graph and shows the synced note). No manual sync step
anywhere.

## Development environment
- **JDK 21+** - the pinned closure-compiler (v20250820) is built for class-file
  65.0; shadow-cljs fails to load under Java 11/17 with
  `UnsupportedClassVersionError`. (This also silently broke upstream's root
  Dockerfile, which still uses temurin-11 - our fork-owned Dockerfile uses
  temurin-21.)
- **Node >= 22.20** (root `engines`; the Docker image pins Node 24) and
  **pnpm 10.33.0** (`packageManager` pin; `npx pnpm@10.33.0` works without a
  global install).
- Gotcha: `pnpm install` inside `deps/db-sync` walks up to the root pnpm
  workspace and hoists everything there; use `--ignore-workspace` when you need
  a local `deps/db-sync/node_modules` (the Dockerfile does).

## Run it + verify

Dev (2 minutes):
```
pnpm install
(cd deps/db-sync && pnpm install && pnpm run build:node-adapter)
scripts/self-host/dev-selfhost.sh        # adapter :8787 + app :3001
# open http://localhost:3001/index.html
node scripts/self-host/smoke-test.js     # A->B check; prints SMOKE PASS
```

Production build, without Docker:
```
SELF_HOST=true pnpm run release-app
PORT=8080 DB_SYNC_DATA_DIR=/path/to/data node scripts/self-host/single-origin-server.mjs
```

Docker image (repo root):
```
docker build -f scripts/self-host/Dockerfile -t logseq-selfhost .
docker run -p 8080:8080 -v logseq-data:/data logseq-selfhost
```

Release smoke (dev app as browser A, release server as browser B, one adapter -
see the header of `smoke-test.js`):
```
APP_URL_B=http://localhost:8080/ DB_SYNC_DATA_DIR=<data dir> node scripts/self-host/smoke-test.js
```

## The change footprint (what you're maintaining)
- **Fork-owned (never conflicts):** `src/main/frontend/handler/self_host.cljs`
  (session/init/auto-open/auto-upload/OPFS gate), `scripts/self-host/*`
  (dev script, single-origin server, Dockerfile, smoke test), `docs/self-host/*`.
- **Upstream files touched, all flag-gated (`SELF_HOST` / `DB_SYNC_DISABLE_AUTH`),
  inert by default:** `config.cljs` (the flag + origin-default sync URLs) +
  `shadow-cljs.edn` (the flag), `handler.cljs` (a require + one-line boot hook),
  tiny e2ee guards in `handler/db_based/sync.cljs` + `worker/sync/upload.cljs` +
  `handler/events/rtc.cljs`, and the server seam in
  `deps/db-sync/.../worker/auth.cljs` + `node/server.cljs`. **`events/ui.cljs`
  and the root `Dockerfile` are untouched.** Full rationale: PLAN.md 11.

## What's next
1. **Deploy it** (ops-side, [DEPLOY.md](./DEPLOY.md)): home server container +
   VPS Pocket ID forward-auth; verify the WS upgrade passes the auth gate on
   the real proxy.
2. **Robustness runs the code can't prove** (PLAN.md 10.4/10.7): re-measure at
   real graph size, adapter-restart/reconnect behavior, the fork's
   checksum-mismatch repro harness.
3. **Decide CI**: the smoke test is CI-worthy but heavy (full build); wire it
   as a manual/nightly job if wanted.

## Don't re-break these (PLAN.md 1b gotchas)
- The self-host user-id **must be a UUID** (else search index + graph-switching
  break).
- e2ee is forced off **in the worker** (`worker/sync/upload.cljs`), not just the
  main thread - create/upload decide e2ee worker-side.
- Sync the first graph via **`<rtc-upload-graph!`**, not `create-and-start-sync`
  (readiness race).
- Graph creation/switch sets the current repo **before** the main-thread db conn
  registers - anything reacting to `current-repo-flow` must wait for the conn
  (`<wait-for-db-conn` in `self_host.cljs`), or it silently skips and `dedupe`
  means no retry.
- Consume the continuous `current-repo-flow` with a direct `m/reduce` (the
  `frontend.background-tasks` shape); the `m/ap` + `m/?>` shape is for discrete
  flows (mix/debounce outputs) and silently does nothing here.
- Demo graphs must stay excluded from auto-upload - each fresh browser creates
  its own local Demo before init, so syncing it collides with a remote Demo.

## Fork maintenance (PLAN.md 11)
Track `upstream` (`github.com/logseq/logseq`); `git fetch upstream && git rebase
upstream/master` (not merge). Re-run the currentness check in PLAN.md if HEAD has
moved. Consider proposing the flag-gated mode upstream - if merged, the fork
disappears.

## Separate track (not on the critical path)
Editor crash-durability: `scripts/self-host/editor-durability-hardening.patch`
(PLAN.md 7b / Phase 5). Independent of self-hosting.
