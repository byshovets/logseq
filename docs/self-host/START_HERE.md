# Start here - self-host implementation handoff

One page for whoever picks this up. Depth is in [PLAN.md](./PLAN.md); this is the map.

## What this is
Turn this Logseq fork into a **single-user, self-hosted web app**: the browser
holds the graph locally (OPFS sqlite), the `db-sync` Node adapter is the
server-side database, no in-app auth, reachable from any browser. All design
decisions are **closed** - see PLAN.md 0 and the `[D]` tags throughout.

## Current state (DONE)
Branch **`byshovets/self-host-web-mvp`**, built against upstream edition
`9a11243d50` (pnpm era). Working MVP, committed as atomic commits:
1. `self-host: add DB_SYNC_DISABLE_AUTH mode to db-sync adapter` (server)
2. `self-host: client single-user no-auth mode (SELF_HOST flag)` (client MVP)
3. `self-host: add plan, run script, and smoke test to the repo` (docs/scripts)
4. `self-host: extract fork-owned namespace, minimize upstream edits` (refactor)

**Phase 4 (productionization, PLAN.md 10) is implemented on top:**
- **10.1** single-origin release build: sync URLs default to the page origin in
  self-host release builds (`config.cljs`), a stdlib-only Node server
  (`scripts/self-host/single-origin-server.mjs`) serves `static/` + proxies the
  adapter (incl. the `/sync/` WS upgrade), and a fork-owned Docker image
  (`scripts/self-host/Dockerfile`) packages both. The stale root Dockerfile is
  left untouched (upstream-owned; ours is separate by the PLAN.md 11 principle).
- **10.2** deploy runbook with Pocket ID forward-auth at the proxy:
  [DEPLOY.md](./DEPLOY.md).
- **10.3** first-run gap closed: a never-synced (non-Demo) graph **auto-uploads**
  on create/open; auto-open now picks the most recently updated remote graph and
  only fires on a fresh browser (current graph nil/Demo). Demo graphs stay local
  on purpose - every fresh browser makes its own local Demo, so syncing it would
  collide.
- **10.4** OPFS capability gate: unsupported browsers get an explicit error page.

The A->B end-to-end flow passes: create a graph in one browser (no manual sync
step) -> a fresh browser auto-opens the graph and shows the edit.

## Run it + verify (2 minutes)
```
pnpm install
(cd deps/db-sync && pnpm install && pnpm run build:node-adapter)
scripts/self-host/dev-selfhost.sh        # adapter :8787 + app :3001
# open http://localhost:3001/index.html
node scripts/self-host/smoke-test.js     # A->B check; prints SMOKE PASS
```

## The change footprint (what you're maintaining)
- **Fork-owned (never conflicts):** `src/main/frontend/handler/self_host.cljs` -
  the whole self-host session/init/auto-open lives here.
- **Upstream files touched, all flag-gated (`SELF_HOST` / `DB_SYNC_DISABLE_AUTH`),
  inert by default:** `config.cljs` + `shadow-cljs.edn` (the flag), `handler.cljs`
  (a require + one-line boot hook), tiny e2ee guards in `handler/db_based/sync.cljs`
  + `worker/sync/upload.cljs` + `handler/events/rtc.cljs`, and the server seam in
  `deps/db-sync/.../worker/auth.cljs` + `node/server.cljs`. **`events/ui.cljs` is
  untouched.** Full rationale: PLAN.md 11.

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

## Fork maintenance (PLAN.md 11)
Track `upstream` (`github.com/logseq/logseq`); `git fetch upstream && git rebase
upstream/master` (not merge). Re-run the currentness check in PLAN.md if HEAD has
moved. Consider proposing the flag-gated mode upstream - if merged, the fork
disappears.

## Separate track (not on the critical path)
Editor crash-durability: `scripts/self-host/editor-durability-hardening.patch`
(PLAN.md 7b / Phase 5). Independent of self-hosting.
