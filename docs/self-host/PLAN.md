---
date: 2026-08-01
---

# Logseq -> Self-Hosted Web App: Transformation Plan

> **Status (2026-08-03): MVP + Phase 4 built, verified, and committed.** A
> single-user self-host deployment works end-to-end: create/edit notes in one
> browser -> they persist server-side -> a fresh browser auto-opens the graph and
> shows the same notes, with no login and **no manual sync step**. Branch
> `byshovets/self-host-web-mvp`, seven atomic commits (see START_HERE.md).
> §1b records the MVP as-built requirements, seams, and gotchas; §10 records the
> productionization as-built: origin-default sync URLs, the single-origin server,
> the Docker image (container-verified), auto-upload of the first graph, the OPFS
> gate, and the DEPLOY.md runbook. What remains is **not code**: deploying it
> (DEPLOY.md), and the §10.4/§10.7 measurement/robustness runs (real graph size,
> adapter-restart behavior, checksum repro) plus the §10.5 CI decision.

> **Currentness (FPF G.11).** Built and validated against tree edition
> `9a11243d50` (upstream master, pnpm era). This tracks a moving upstream master
> - C1 already shipped ahead of the plan and the e2ee gating changed under it, so
> re-run the currentness check if HEAD advances before you resume. Claims are
> tagged **[E]** evidence I ran, **[C]** code-read/as-built on this edition,
> **[D]** a decision you made, **[G]** an unbased guess.

## BLUF

The heavy lifting is **already done upstream**, and the integration is now **built
and proven in an MVP**. [C] The browser build persists DB graphs entirely
client-side (sqlite-wasm on OPFS, no Electron), and the tree ships a self-hostable
`db-sync` Node adapter (`deps/db-sync`) that is the server-side database. [E] The
MVP wires them into a single-user no-auth web app: I confirmed the full loop -
create a graph in browser A, edit it, and a fresh browser B **auto-opens it and
shows the note** (server `tx_log` grew; plaintext, e2ee off). Remaining work is
**productionization** (§10), not building persistence, a protocol, or the
integration itself. [G] rough remaining effort to a shippable image: ~1 week;
treat as a guess.

**[D] Auth: removed entirely** (your call, "keep it simple") - no tokens, no
JWKS, no issuer, no SSO. This decision is now propagated through the whole plan;
earlier draft sections that still described a token/Cognito seam were the main
coherence defect this review fixed.

---

## 0. Problem frame, alternatives, and what "better" means

The earlier draft opened with the solution ("db-sync is the answer"). FPF
problem-shaping says state the problem first, without smuggling the solution in.

**Problem (EntityOfConcern + constraints).** Make one user's Logseq notes
persist in a **server-side database** and be usable from **any web browser**, as
usable as the desktop app. Constraints you set: single user; access control
handled by a layer in front (out of scope); "keep it simple." Not stated by you,
so flagged as open: whether "any browser" includes **two devices editing at the
same time** (see the decision below).

**"As usable as desktop" decomposed into evaluation characteristics** (so "better"
is checkable, not vibes):

| Characteristic | Status under this plan |
|---|---|
| Create/edit/search notes (DB graph) | **Met** - works in plain browser today [E] |
| Data persists server-side, multi-device | **Met** via db-sync [E, sequential] |
| Offline editing | **Met** - full local sqlite, syncs on reconnect [C] |
| Assets (images/attachments) | **Met** - synced via db-sync assets endpoint [C], scale untested |
| Plugins | **Partial** - subset runs on web already; native-fs plugins don't |
| PDF viewer/annotation | **Met** on web [C] |
| Local on-disk *file* graphs | **Dropped** - web uses DB graphs only (deliberate) |
| Performance on large graphs | **Unknown** - not measured (open) |
| Crash durability vs Google Docs | **Met once committed; active-block gap** (§7b) |

**Alternatives considered (option retention - not premature convergence).** db-sync
is the *selected* method, not the only one:

- **(A) db-sync Node adapter [selected].** Reuse the fork's real-time
  replication-log server. Pro: already built, maintained, proven; real-time;
  offline-capable; reuse >> rewrite. Con: it is **multi-tenant machinery**
  (members, per-user RSA keys, AES-key grants, presence) run for **one user** -
  carried complexity we never use. Accepted because building a single-user store
  would re-solve sync, conflict, and bootstrap that db-sync already handles.
- **(B) Server-authoritative thin client** (app talks to a REST/SQL backend, no
  local sqlite). Rejected: throws away the offline-first local DB that is the
  app's core, and is a far larger rewrite.
- **(C) Periodic full-graph snapshot upload/download** (no live tx sync). Simpler
  server, but loses incremental/offline-merge and risks clobbering on multi-device.
  Viable fallback if db-sync's concurrency story proves too fragile.
- **(D) Bespoke minimal single-user sync server.** Most "right-sized" to the
  problem, but months of work to reach db-sync's maturity. Not worth it.

**[D] RESOLVED (2026-08-01): single device at a time.** You confirmed the graph
owner edits from **one device at a time** (sequential multi-device: finish on the
phone, then open the laptop). This is the decision that most de-risks the plan:
db-sync's least-tested path - *simultaneous* editing of the same graph - is now
**out of scope**, so the concurrent-merge and checksum-divergence concerns (§8,
§1) drop from open risk to "not exercised by your usage." Option (C), the
snapshot-only retreat, is therefore **no longer needed as a concurrency hedge**;
keep it in mind only if db-sync maturity issues surface for a different reason.

One soft caveat that survives the decision: "single device at a time" still
requires **letting sync settle before switching devices**. If you edit on the
laptop and immediately sleep it before its tx batch reaches the server, then edit
the same blocks on the phone, both devices hold divergent unsynced tails - the
pending-op queue + rebase (§7b) will reconcile on reconnect, but that is the one
residual merge path. The app's sync indicator tells you when it is safe to switch;
practically, this is a non-issue for deliberate single-device use.

---

## 1. What was validated (not theory - I ran it)

**[E]** All results below were produced on this machine. Scope caveat: the
end-to-end sync runs (tests 3-5) were executed on the pre-upgrade fork edition
via a temporary local token issuer; the **sync behavior is independent of the
auth mechanism**, so those results carry over. The **no-auth** result (test 2)
was validated directly against the adapter with the `DB_SYNC_DISABLE_AUTH` patch
and is the mechanism this plan actually adopts.

Local stack: Node adapter built (`cd deps/db-sync && npm run build:node-adapter`
-> `worker/dist/node-adapter.js`), run on `:8787` with `DB_SYNC_DATA_DIR=<dir>`,
`DB_SYNC_STORAGE_DRIVER=sqlite`; creates `index.sqlite` + `graphs/<id>/db.sqlite`
+ `assets/`. Client: web build served at `:3001`, driven headless via Playwright
+ system Chrome.

| # | Test | Result |
|---|------|--------|
| 1 | Health + auth wall (default) | `/health` ok; `/graphs` 401 without token |
| 2 | **No-auth mode (adopted design)** | with `DB_SYNC_DISABLE_AUTH=true` + the ~15-line `auth-claims` patch, **zero tokens**: `/graphs` list, `/e2ee/user-keys`, and graph-create all return 200, graph owned by a constant `local` user. **No Cognito, no issuer, no JWKS.** |
| 3 | Create remote graph + upload | web created graph, snapshot uploaded; server showed `kvs=393` for the graph |
| 4 | **Live incremental sync** | created graph `WebTest`, edited a block -> server `tx_log` grew 3 -> 4 over the live WebSocket; HTTP `pull?since=0` returned all 4 txs |
| 5 | **"Available everywhere"** | downloaded the uploaded graph into a **fresh browser profile** (`kvs=393` restored) - a second device pulls the full graph |

Screenshots + scripts are in the scratchpad (`proto/e2e-sync-clean.js` is the
sync proof; the no-auth server patch is validated but reverted, described in §6).

**Assurance scope of this evidence (FPF B.3 - what it does NOT cover).** These
runs prove the mechanism *works at all*, not that it works *at scale or over
time*. Explicitly **not** exercised: large graphs (tested ~393 kv rows, a demo);
high tx volume or long-running stability; **concurrent** multi-device editing;
asset sync beyond a trivial case; flaky/slow networks and reconnect storms;
crash/restart of the *server*. Basis = a single happy-path session on one machine.
Decay: re-run against the target graph size and usage before trusting it in
production. Treat §1 as "de-risked the unknowns that would have killed the
approach," not "production-validated."

Non-blocking observation: a dev-only `checksum-mismatch` **warning** fired during
sync (`when worker-util/dev?`, `handle_message.cljs:144`). Data still synced
correctly (tx_log grew, pull returned everything). This is a pre-existing RTC
diagnostic the fork actively works on (`docs/agent-guide/db-sync/checksum-mismatch-repro.md`),
**not introduced by self-hosting**. Track it, don't block on it.

---

## 1b. MVP as built - requirements, change set, and gotchas discovered

[E/C] Everything here is what the working MVP actually needed. Branch
`byshovets/self-host-web-mvp`. This supersedes the speculative C1-C6 in §5; where
they differ, this is authoritative.

### The change set (9 files + a run script)

**Server - `deps/db-sync` (no auth):**
- `worker/auth.cljs`: `DB_SYNC_DISABLE_AUTH` flag -> `auth-claims` returns a fixed
  local user instead of verifying a JWT. **The `sub` MUST be a UUID**
  (`00000000-0000-0000-0000-000000000001`) - see gotcha 1.
- `node/server.cljs`: pass `DB_SYNC_DISABLE_AUTH` from `process.env` into the
  adapter `env` (mirrors the existing `DB_SYNC_ALLOW_UNVERIFIED_JWT_CLAIMS` wiring).

**Client - `src/main/frontend` (behind a `SELF_HOST` build define):**
- `config.cljs` + `shadow-cljs.edn`: add `SELF-HOST` goog-define wired to the
  `SELF_HOST` env var (mirrors `ENABLE-DB-SYNC-LOCAL`).
- `handler/events/ui.cljs`: a `:self-host/init` event (fired from `handler.cljs`
  after graph restore). It synthesizes a local session - an **unsigned far-future
  JWT** (client only base64-decodes the payload; the server ignores it in
  disable-auth mode), sets `user-groups ["team"]`, pushes `set-db-sync-config`
  with `:self-host? true`, calls `<get-remote-graphs`, and **auto-opens** the
  single remote graph on a fresh browser (via `persist-db/<list-db` to tell truly-
  local from merely-listed-remote graphs, then the `:rtc/download-remote-graph`
  event which downloads *and* switches).
- `handler/db_based/sync.cljs` + `worker/sync/upload.cljs`: force `graph-e2ee?
  = false` in self-host - **in both** the main-thread and the worker
  `normalize-graph-e2ee?` (the worker decides e2ee on create/upload, so patching
  only the main thread was not enough - gotcha 2).
- `handler/events/rtc.cljs`: auto-answer the e2ee password prompt with a constant
  in self-host, so no residual key path can block headless/first-run.
- `handler.cljs`: fire `:self-host/init` on boot when `config/self-host?`.

**Run:** `scripts/dev-selfhost.sh` starts the adapter (`:8787`,
`DB_SYNC_DISABLE_AUTH=true`) + the web app (`:3001`, `SELF_HOST=true
ENABLE_DB_SYNC_LOCAL=true`). Open `http://localhost:3001/index.html`.

### Gotchas discovered (these are the load-bearing lessons)

1. **The self-host user-id must be a UUID.** The sync layer turns the owner's
   user-id into a graph-member *page* whose `:block/uuid` is that id, and the
   search indexer (`worker/search.cljs valid-upsert-block?`) rejects non-UUID
   block ids. Using `"local"` broke search index build **and** graph switching
   (a rejected promise cascaded). A UUID `sub` fixes both.
2. **e2ee decision lives in the worker, not the main thread.** Create/upload read
   `sync-crypt/graph-e2ee?` and the worker's own `normalize-graph-e2ee?`. With
   e2ee left on, sync "worked" but a second browser rendered **encrypted transit
   blobs** instead of text. e2ee must be forced off on the worker side; the seam
   is a `:self-host?` key carried in the worker's `*db-sync-config`.
3. **`create-and-start-sync` races on client-op readiness** (`start-skipped
   :reason :client-op-not-ready`). The reliable path is the UI's own
   `<rtc-upload-graph!` (snapshot upload initializes client-ops, then starts sync).
4. **A fresh browser only pulls the delta after the snapshot if RTC starts.** The
   snapshot is as-of-upload; the marker edit was a later tx. The
   `:rtc/download-remote-graph` event downloads + switches + the graph then syncs
   the delta. (Its schema-version assert needs the remote's real version, which
   upload does set - here `65`.)
5. **CORS already works** (adapter sends `Access-Control-Allow-Origin: *`), so the
   two-port dev setup runs cross-origin. Production should still be single-origin
   (§10) and tighten that wildcard.

### What the MVP does NOT include (deferred to §10)
Production packaging (release build + single-origin Docker), first-run
"sync your first graph" UX (auto-open only triggers once a remote graph exists),
the OPFS capability gate, and the reverse proxy that is the actual security
boundary. It is the dev/watch build, uncommitted.

---

## 2. How persistence works today (the key realization)

There are **two independent storage systems**, and the app already uses both on
plain web:

### 2a. Local store (per browser) - already complete, no Electron needed
> **[C] Ref note:** on edition 9a11243d50 the persist layer was refactored -
> the sqlite/OPFS storage code moved `frontend.worker.db-worker ->
> frontend.worker.db-core`, and boot goes through `persist-db/<start-runtime!`
> (`persist_db.cljs:302`) -> `browser/start-db-worker!`. The architectural claims
> below are unchanged; treat file names as `db_core.cljs` where the old draft said
> `db_worker.cljs`.

- Every DB graph lives in a dedicated Web Worker running `@sqlite.org/sqlite-wasm`
  over an **OPFS SAHPool VFS** (`db_core.cljs`, `installOpfsSAHPool*`). Three
  sqlite files per graph: `db.sqlite`, `search/db.sqlite` (FTS5),
  `client-ops-/db.sqlite`.
- `frontend.persist-db` selects the single browser impl for **all** platforms.
  Electron adds *only* a periodic mirror of the bytes to disk
  (`(when (util/electron?) ...)`). `better-sqlite3` is **not** used by the
  Electron app runtime.
- Requirement: OPFS `createSyncAccessHandle` (modern Chrome/FF/Safari). No
  `SharedArrayBuffer`/COOP-COEP needed. No File System Access picker for DB
  graphs. **No graceful fallback if OPFS is absent** (§8 risk).
- Assets (images) on web -> LightningFS -> IndexedDB (`fs.cljs:37-38`).

### 2b. Server store (the "cloud") - the db-sync Node adapter
- Per-graph sqlite: `kvs(addr,content,addresses)` + `tx_log(t,tx,...)` +
  `sync_meta`. Metadata in `index.sqlite`: `graphs/users/graph_members/
  user_rsa_keys/graph_aes_keys` (schema self-creates at runtime,
  `index.cljs:99`; the `.sql` files are Cloudflare-only).
- Protocol: WebSocket `hello/pull/tx-batch/changed` + HTTP for bootstrap
  snapshot, assets, e2ee keys (`docs/agent-guide/db-sync/protocol.md`).
- This is a **real-time replication log**, not a dumb blob store. Edits stream as
  txs; a new device bootstraps from a snapshot then tails the log.

**Consequence:** "server-side database that saves user data, usable everywhere"
is exactly what db-sync already does. We are wiring it up for single-user
self-host, not writing it.

---

## 3. What "user settings" actually need (smaller than it looks)

Settings live in three tiers:

- **In-graph `config.edn`** (synced by db-sync): keyboard shortcuts (`:shortcuts`),
  macros, `:default-home`, publishing prefs, Zotero settings, custom.css/js,
  favorites, and the fork's recycle-bin. **These already travel with the graph.**
- **App-level localStorage** (per-browser, NOT synced): theme, sidebar widths,
  recent pages, current-graph pointer, PDF prefs, `:ls-shortcuts` override,
  auth tokens, Zotero API key. **Desktop also keeps these per-machine** - so a
  fresh browser losing them is *not a regression vs the desktop app*.
- **Electron `userData` files** (desktop-only): proxy, window state, spell-check.
  Irrelevant to web.

**Implication - [D] scope decision ACCEPTED (2026-08-01).** The plan does **not**
relocate app-level localStorage (theme, UI prefs, custom `:ls-shortcuts`) to the
server. `config.edn` (synced) already carries the cross-device-important settings
(shortcuts, macros, Zotero); theme and per-browser UI state stay local, exactly as
desktop keeps them per-machine. You accepted that **"available everywhere" means
your graph data everywhere, not your theme/UI state everywhere** - a fresh browser
reproduces your notes, and you re-set look-and-feel once (or not at all). Persisting
a localStorage slice server-side remains an *optional later* enhancement, not part
of the MVP.

---

## 4. Target architecture (single origin)

```
                          one origin (e.g. https://logseq.example.com)
  +-----------------------------------------------------------------+
  |  reverse proxy / self-host server                               |
  |                                                                 |
  |  GET  /                -> static Logseq web build (nginx-style) |
  |  ANY  /graphs, /sync/*, /assets/*, /e2ee/*  -> db-sync adapter  |
  |       (adapter runs with DB_SYNC_DISABLE_AUTH=true, one user)   |
  +-----------------------------------------------------------------+
                 |                              |
        browser (OPFS sqlite)          server sqlite (index + per-graph + assets)
```

Everything is same-origin, so no CORS, no cross-site cookie friction. The
"auth handled separately" layer sits in front of this origin (§6).

---

## 5. Concrete code changes (minimal, file-by-file)

> **Superseded by §1b (as-built).** This section was the pre-build plan. The MVP
> confirmed most of it but corrected two things: C5 (e2ee) had to be forced off in
> the **worker** too, not just the main thread; and the self-host session is
> synthesized client-side (a fake JWT + a `:self-host/init` event), not via a
> server bootstrap. Read §1b for what actually shipped; the C-items below remain
> useful as rationale.

All small. None touch the sync engine's protocol
(`deps/db-sync/src/logseq/db_sync/worker/*` unchanged except the auth seam).

### C1. Point the client at the self-hosted sync server - MOSTLY ALREADY SHIPPED UPSTREAM
**[C] Correction from the currentness review:** the earlier draft proposed
building runtime sync-server config. Upstream already did it. On edition
`9a11243d50`, `config.cljs:51-100` provides `get-custom-sync-server-url` /
`set-custom-sync-server-url!` (localStorage key `sync-server-url`), and
`db-sync-ws-url` / `db-sync-http-base` are now **functions** that return the
custom server when set, else the default. There is even a **Settings UI**
(`components/settings.cljs:626-685`, `sync-server-url-row` at ~905) to enter it.
All callers already invoke them as functions
(`persist_db.cljs:183`, `handler/db_based/sync.cljs:32`, `events/ui.cljs:438`,
`persist_db/browser.cljs:226`, `components/repo.cljs:607`).

So the self-host client work here shrinks to: (a) optionally seed
`sync-server-url` (or default it to the deploy origin) so the user doesn't type
it; (b) the WebSocket base + HTTP base are covered, but **`API-DOMAIN` (used by
`<user-info`) is still a hardcoded `"api.logseq.com"`** (`config.cljs:28`) - that
one still needs a self-host override, handled in C3. Net: ~1 small change, plus
C3, not a subsystem to build.

### C2. No-auth client gates - `handler/user.cljs` + `worker/sync.cljs` [C, edition 9a11243d50]
Behind a `self-host?` build define: `logged-in?` (`user.cljs:90`) -> true,
`rtc-group?` (`user.cljs:359`) -> true, `task--ensure-id&access-token`
(`user.cljs:341`) -> resolved no-op. Default `user-groups` to `["team"]` locally.
On boot, trigger `:user/fetch-info-and-graphs` directly (no token/exp gate) so
remote graphs list without a login step. NOTE: `worker/sync.cljs :: connect!`
(`sync.cljs:318-319`) still refuses to open the WebSocket unless `(or token
(auth-token))` is truthy - so the self-host client must supply a constant
placeholder token (e.g. `"local"`); the server ignores it in disable-auth mode.
Replaces the whole token/Cognito flow.

### C3. `user_info` must not hard-fail - `handler/user.cljs:515` [C, edition 9a11243d50]
`<user-info` (`user.cljs:515`) POSTs to `https://<API-DOMAIN>/file-sync/user_info`
(`user.cljs:428`; `API-DOMAIN` = `"api.logseq.com"` at `config.cljs:28`) and
currently 401s (harmless - the handler catches it). In self-host mode,
short-circuit `<user-info` to return a synthetic `{UserGroups:["team"],
UserGUID:"local", ...}` so the boot flow's `fetch-graphs?` branch fires and a
fresh browser **auto-lists remote graphs**. No server endpoint needed. (This also
covers the one URL C1 doesn't: `API-DOMAIN` stays hardcoded, but self-host never
calls it.)

### C4. Auto-open the synced graph - `handler/events/ui.cljs` (`:user/fetch-info-and-graphs`)
`:user/fetch-info-and-graphs` already calls `<get-remote-graphs` +
`refresh-repos!`. Add: if the current repo isn't a downloaded remote graph and
exactly one remote graph exists, auto-`<rtc-download-graph!` + select it, so a
fresh device lands directly in the user's data. (For a brand-new user, first run
creates the local Demo graph and offers "upload to sync".)

### C5. E2EE with e2ee OFF costs nothing now - **corrected by the deeper currentness check**
**[C] Material change vs the earlier draft.** The earlier draft (from old-edition
behavior) said graph creation is refused without a user RSA key pair *regardless*
of e2ee, so "the RSA key upload + password prompt still occur even with e2ee off."
**That is no longer true.** On edition 9a11243d50 the server gates the RSA
requirement on e2ee: `deps/db-sync/…/handler/index.cljs:131` is
`(if (and graph-e2ee? (not has-user-rsa-key-pair?)) (bad-request "missing user rsa
key pair") …)`. So **with `graph-e2ee? = false`, no RSA key pair and no E2EE
password are needed at all** - the whole keychain-no-op problem simply doesn't
arise. Note `graph-e2ee?` defaults to `true` if the client omits it
(`index.cljs:122`), so the self-host client must **explicitly send
`graph-e2ee? = false`** at create time.

- **[preferred] e2ee off:** send `graph-e2ee? = false`; nothing to do about the
  keychain no-ops. Simplest, and server data is at rest unencrypted (acceptable
  because the reverse proxy is the security boundary - §8).
- **[optional] keep full E2EE:** then you must solve web password persistence
  (localStorage, since the no-auth design dropped any server endpoint). More
  moving parts. Defer unless at-rest encryption on the server matters to you.

### C6. Prune/guard desktop-only UI on web (cosmetic, low priority)
Most desktop features are already `(util/electron?)`-gated and simply don't render
on web (shell, git, auto-update, HTTP-API server, global config.edn, native
titlebar/spell-check, reveal-in-folder, the AI settings tab). Plugins and PDF
already work partially on web. Nothing here blocks core note-taking; clean up
dangling menu items as polish.

### Explicitly NOT changing
- The sync engine and protocol (client `worker/sync/*`, server `worker/*`).
- The OPFS/sqlite-wasm local persistence.
- The db-worker <-> main-thread transport.

---

## 6. Auth: removed entirely (your call - "keep it simple")

No tokens, no JWKS, no issuer, no SSO. The app runs as a single implicit user.
This is a two-sided, small change:

- **Server (`deps/db-sync`):** add a `DB_SYNC_DISABLE_AUTH=true` mode. The single
  seam is `worker/auth.cljs :: auth-claims` -> when the flag is set, return a
  fixed claims map (constant `sub`/`email`, e.g. `{"sub":"local","email":
  "local@localhost"}`) instead of verifying a JWT. Every downstream ownership
  check (`index/<user-has-access-to-graph?`, graph `user_id`, members) then keys
  off that one constant user. The `COGNITO_*` env vars become unnecessary.
  ~1 function + a config flag.
- **Client (self-host build):** short-circuit the client-side gates so the sync
  UI is always on: `handler/user.cljs :: logged-in?` -> true, `rtc-group?` ->
  true, and `task--ensure-id&access-token` -> no-op. Drop the `Authorization`
  header entirely (or send a constant; the server ignores it). No `user_info`
  stub needed - just default `user-groups` locally. These collapse to a single
  `self-host?` build define that a handful of predicates read.

Net effect: open the origin, and it works. Access control, if ever wanted, is a
reverse proxy in front of the origin - out of scope here.

---

## 7. Deployment shape

> The **MVP-informed, authoritative** production steps are in §10. This section is
> the earlier high-level shape; §10 supersedes it where they differ.

Single container (or compose) serving one origin:

1. **Build stage:** `pnpm install --frozen-lockfile` + `pnpm run release-app` ->
   `static/`. **[C] Corrected for the tree upgrade:** the project migrated
   yarn->pnpm (`packageManager: pnpm@10.33.0`, root `pnpm-lock.yaml`,
   `pnpm-workspace.yaml` with `shamefullyHoist` + an `allowBuilds` allowlist); the
   old `yarn install` instruction no longer applies. The existing root
   `Dockerfile` still references the old flow and would need this bump. (`server/`
   is stray `node_modules`, not a real project - verified.)
2. **Server stage:** Node runtime running the db-sync adapter
   (`deps/db-sync/worker/dist/node-adapter.js`) with `DB_SYNC_DISABLE_AUTH=true`,
   fronted by nginx (or the adapter itself) serving `static/` and reverse-proxying
   `/graphs|/sync|/assets|/e2ee`. **No `/jwks.json`, `/auth/session`, or
   `user_info` server endpoint** - the no-auth decision (§6) removed the whole
   token bootstrap; `<user-info` is short-circuited client-side (C3).
3. **Volume:** `DB_SYNC_DATA_DIR` -> a persistent volume holding `index.sqlite`,
   `graphs/`, `assets/`. This volume **holds** the user's data (the volume is the
   carrier, not the data itself - back it up accordingly).
4. **Env:** `DB_SYNC_PORT`, `DB_SYNC_DATA_DIR`, `DB_SYNC_STORAGE_DRIVER=sqlite`,
   `DB_SYNC_ASSETS_DRIVER=filesystem`, `DB_SYNC_DISABLE_AUTH=true`. The `COGNITO_*`
   vars are **no longer needed**.

Backups = snapshot the volume (or `sqlite3 .backup`). db-sync also exposes a
snapshot download endpoint for portable graph exports.

**[E] Toolchain note (corrected):** `better-sqlite3@12` declares a Node 20-25
engine range, but I verified it **builds and runs on Node 26** under pnpm's
`allowBuilds` (`node -e "require('better-sqlite3')(':memory:')"` succeeds). The
project's `engines` requires `node >=22.20.0`. Pin the container to Node 22 or 24
for the cleanest match; Node 26 also works.

**[E] Toolchain note (2026-08-03, found by the Docker build): building the app
requires JDK 21+.** The pinned closure-compiler (v20250820) ships class-file
65.0 bytecode, so shadow-cljs fails to load under Java 11/17
(`UnsupportedClassVersionError`). This silently broke upstream's root
Dockerfile (temurin-11 base); the fork-owned `scripts/self-host/Dockerfile`
uses `clojure:temurin-21-tools-deps-bookworm-slim`. Local development needs the
same JDK 21+. Second build gotcha: `pnpm install` inside `deps/db-sync` walks
up to the root pnpm workspace and hoists everything there - pass
`--ignore-workspace` when a local `deps/db-sync/node_modules` is needed.

---

## 7b. Durability / crash resilience (vs Google Docs)

**Scope note (FPF - distinct EntityOfConcern).** This section is about the
*local editor's* crash durability, which is **orthogonal to self-hosting** - it
also improves the desktop app and does not touch the server. It rides along
because you asked the Google-Docs-reliability question, but it is not a
prerequisite for the self-host MVP (Phases 1-4). Keep it as its own workstream.

Verified from code. The model is local-first with a durable outbound queue -
functionally the **same class of guarantee as Google Docs**, with one coarser
window.

- **Every committed transaction is persisted synchronously to local sqlite**, not
  batched. DataScript uses a write-ahead tail: `store-after-transact!` appends the
  tx's datoms to a persisted tail on every transact (`datascript/conn.cljc`,
  `storage store-tail` synchronous `-store`, called from `deps/db/…/db.cljs:169`).
  **[C] Re-verified on edition 9a11243d50:** the persist layer moved
  `db_worker.cljs -> worker/db_core.cljs`, and the registered
  `debounce d/store` is now **1000ms** (`db_core.cljs:541`, was 100ms) - but it is
  **still dead code**: its only consumer (`debounced-store-db`) sits inside a
  `(comment ...)` at `db.cljs:103-110`, so the synchronous-per-transact claim
  holds unchanged. WAL is enabled (`db_core.cljs:386`, `journal_mode=WAL`) over
  the OPFS SAHPool, so a committed write survives a tab/renderer/process crash and
  is replayed on reopen.
- **Durable pending-op queue (the Google-Docs analog).** Every local tx is also
  written to a separate persisted `client-ops-<repo>.sqlite` with
  `:db-sync/pending? true` + normalized tx data (`apply_txs.cljs:229-245`), and
  flipped to `false` only on server ack. On (re)start/reconnect, `pending-txs`
  reads all still-pending entries (`apply_txs.cljs:268`) and re-pushes them. So an
  edit made offline, or made just before a crash, **survives restart and syncs
  when the server is reachable**.
- **Editor autosave debounce = 450ms idle** (`editor.cljs:1904-1920`, `#tag` guard
  at `1917`; refs re-anchored for edition 9a11243d50); explicit
  actions (Enter/new block, blur, navigation, indent, collapse) commit
  immediately. Two caveats: (i) there is **no `beforeunload`/`pagehide` flush** -
  closing the tab mid-edit does not force a save; (ii) a block whose current text
  contains a `#tag` is **excluded from the idle autosave** (`(not (re-find
  #"#\S+" value))`) and only persists on an explicit commit - so its unsaved
  window is *unbounded* until you leave the block.

Worst-case loss windows:

| Scenario | Lost? |
|---|---|
| Browser/tab killed after a block was committed (Enter/blur/nav/450ms idle) | Nothing - durable in OPFS WAL + queued to server |
| Killed mid-typing, before the 450ms idle/explicit save | Only the uncommitted text in the *current* block (<=450ms of typing) |
| Killed mid-typing in a block that currently contains a `#tag` | The whole unsaved edit to that block (unbounded window - idle-save suppressed) |
| Killed while offline with unsynced *committed* edits | Nothing - pending queue persists, re-pushes on reconnect |
| OPFS evicted under storage pressure (previously synced) | Local copy gone, but server is source of truth -> re-sync restores it |
| OS power loss (not a browser crash) | At most the last sub-second of committed local writes; server has all acked txs |

**Vs Google Docs - honest read:** *equal* on the thing that matters once a block
is committed - committed content is never lost, and a persistent local op queue
(`client-ops.sqlite`) replays unsynced edits after a crash, with a reverse/apply/
rebase reconcile. *Weaker* in three places: (1) the durable unit is a *committed
block*, not a *keystroke* - GDocs records typing into its local pending store on a
sub-second cadence with unload handling, so a crash mid-sentence loses at most a
keystroke or two; Logseq loses the active block's last edit (450ms, or unbounded
for a `#tag` block) because nothing persists keystrokes and there's no unload
flush; (2) checksum divergence is *detected* (dev-only log) but not auto-repaired
- recovery is a manual snapshot re-download; (3) concurrent multi-device editing
of the *same* graph is younger code - not stressed by single-user/sequential-
device self-host. Arguably *stronger* in one place: the whole graph is a real
local sqlite DB, so it works fully offline, not as a limited cache.

**Hardening - prototyped and measured (patch: `editor-durability-hardening.patch`,
~50 lines across `handler/editor.cljs` + `handler.cljs`; [E] re-applied and
re-measured on edition 9a11243d50 - compiles clean, all three probes PERSISTED,
then reverted).** I built a Playwright
harness that types into a block, tears the page down, reopens from OPFS, and
checks whether the text survived. Three probes, baseline vs hardened:

| Probe | Baseline | Hardened |
|---|---|---|
| #tag block, idle 900ms, reload | LOST | PERSISTED |
| plain block, idle 900ms (control) | PERSISTED | PERSISTED |
| append to an existing block, reload <450ms (crash) | LOST | PERSISTED |

Findings:
1. **Dropping the `#tag` idle-save exclusion** - clean win, one line. Tagged
   blocks now idle-save like any other.
2. **A `pagehide`/`visibilitychange` flush calling `save-current-block!` does NOT
   work** - measured, still LOST. Persistence is an async round-trip to the DB
   worker that doesn't complete before teardown. Important negative result: the
   obvious fix is insufficient for this architecture.
3. **What works is a synchronous localStorage recovery buffer** (Google Docs'
   actual approach): stash the in-flight edit value on every keystroke
   (synchronous, survives even a hard renderer crash), and on boot re-apply it -
   after paging the block in via `db-async/<get-block`, because DB graphs
   lazy-load and the block isn't in the main-thread DB yet at restore time. With
   this, the crash probe flips to PERSISTED. Caveat: it recovers the *currently
   edited* block (the in-flight one); a brand-new block whose creation never
   persisted is out of scope. This is Phase-5 polish, self-contained, no server
   involvement, and brings the active-block case to Google-Docs parity.

## 8. Risks & open items

> Confirmed-during-build items are in §1b (gotchas) and productionization is in
> §10. This list is the residual risk register after the MVP.

- **e2ee must stay OFF; server data is plaintext.** [E, confirmed] e2ee-on
  rendered encrypted blobs on the second browser. Off means readable data at rest
  on the server - acceptable only behind the §10.2 proxy. Locked decision pending
  in §10.5.
- **No-auth = the proxy is the whole security model.** [E] Zero tokens accepted;
  anyone reaching the origin has full read/write. §10.2 is not optional.
- **First-run needs a manual first sync.** [E] Auto-open only fires once a remote
  graph exists; the very first graph must be uploaded once (§10.3).
- **OPFS hard dependency.** No fallback if the browser lacks OPFS SAHPool. Modern
  Chrome/Edge/Firefox/Safari are fine; add a capability check + clear error page.
  Private-window/quota eviction can drop the local copy - but the server is the
  source of truth, so a re-sync restores it. Worth a "storage persisted?" prompt
  (the app already calls `navigator.storage.persist()`).
- **Checksum-mismatch warnings** (§1). Pre-existing, dev-only, non-fatal here.
  Validate with the fork's repro harness before calling sync production-grade.
- **Single-writer assumption -> [D] RESOLVED (§0): single device at a time.**
  Simultaneous same-graph editing is out of scope by your decision, so db-sync's
  least-tested path is not exercised. Multi-tab on one browser is still handled
  (Web Locks master election). Residual: let sync settle before switching devices
  (§0 caveat). This downgrades from a top risk to a usage note.
- **E2EE choice** (C5) affects whether server data is encrypted at rest. With
  `DB_SYNC_DISABLE_AUTH` + e2ee-off, **anyone who can reach the origin has full
  read/write** - the entire access-control story is delegated to the reverse proxy
  in front (your "handled separately" layer). Make that explicit in the deploy
  runbook; an exposed origin with no proxy = open notebook.
- **user_info surface.** Handled by C3 (client-side short-circuit of `<user-info`),
  so no error toasts and no server endpoint. Listed as resolved, not open.
- **[G] Upstream drift.** This plan tracks a moving upstream master. C1 already
  changed under it once (shipped ahead of the plan). Re-run the currentness check
  (the banner's ref set) before implementing if HEAD has advanced again.

---

## 9. Phased roadmap

**Phases 0-3 are DONE in the MVP** (branch `byshovets/self-host-web-mvp`):
- Phase 0 - harness/prototype (done).
- Phase 1 - client points at the self-host sync server (`ENABLE_DB_SYNC_LOCAL` in
  dev; production defaults `sync-server-url` to the origin - see §10).
- Phase 2 - auth removed (server `DB_SYNC_DISABLE_AUTH` + client `SELF_HOST`
  no-auth session). Done and validated.
- Phase 3 - auto-open the synced graph + e2ee off. Done and validated.

**Phase 4 - productionize: DONE (2026-08-03).** Release build with `SELF_HOST`,
single-origin server + Docker image (container-verified), first-run auto-upload,
OPFS gate, deploy runbook (DEPLOY.md), committed. The per-item as-built record
is §10; still open there: actually deploying (ops-side), the measurement runs
(§10.4/§10.7), and the CI decision (§10.5).

**Phase 5 (separate concern) - editor durability.** The measured patch (§7b),
independent of self-hosting. Not on the critical path.

---

## 10. Path to production (from the working MVP)

> **As-built status (2026-08-03):** 10.1, 10.2 (runbook side), 10.3, and the
> OPFS gate of 10.4 are **DONE and verified**; each item below carries a DONE
> banner with what actually shipped. Still open: the 10.4 measurement/robustness
> runs, 10.7 re-measurement at real graph size, and the 10.5 CI decision.

### 10.1 Single-origin release build + Docker image (the core packaging)

> **DONE.** `SELF_HOST=true pnpm run release-app` reaches the release `:app`
> closure-defines (verified: the release db-worker has no CDN references; the
> A->B smoke passes against the release build). Sync URLs default to the page
> origin via `config.cljs` (custom URL > dev adapter > origin > upstream
> default) - no localStorage seeding needed. Single-origin serving = option (a),
> as a stdlib-only Node server (`scripts/self-host/single-origin-server.mjs`):
> serves `static/`, proxies `/health|/graphs*|/e2ee*|/assets/*|/sync/*` incl.
> the WS upgrade, and supervises the adapter as a child process. The root
> Dockerfile was left untouched (upstream-owned, and stale: its temurin-11 base
> can no longer build the app - §7 toolchain note); instead a fork-owned
> `scripts/self-host/Dockerfile` (temurin-21 + Node 24 builder, node:24-slim
> runner, `/data` volume) builds THIS checkout. Image verified: container
> healthy, fresh browser auto-opens a seeded graph and renders its synced note.
- Build the app in `SELF_HOST` mode as a **release** build, not the watch build:
  `SELF_HOST=true pnpm run release-app` (needs the same shadow define wiring the
  MVP added). Verify `SELF_HOST` reaches the release `:app` closure-defines
  (the MVP only added it to the shared block; confirm it applies to the release
  build too).
- Serve **single-origin**: static `static/` + the sync endpoints
  (`/graphs`, `/sync/*`, `/assets/*`, `/e2ee/*`, incl. the WS upgrade) behind one
  origin, so there is no CORS and one URL. Two options: (a) a thin reverse proxy
  (nginx or a ~40-line Node proxy) in front of the adapter; (b) teach the adapter
  to also serve `static/`. Proxy is simpler and keeps the adapter unchanged.
- With single-origin, the client's sync URL should default to the page origin.
  Use the **already-shipped** `set-custom-sync-server-url!` (localStorage
  `sync-server-url`) - have `:self-host/init` default it to `window.location
  .origin` when unset, instead of the dev-only `ENABLE_DB_SYNC_LOCAL` hardcode.
- Rewrite the stale root `Dockerfile` (it clones upstream + uses yarn + nginx-only)
  to: pnpm build the app, build the adapter, and run both single-origin. Pin Node
  22/24 (better-sqlite3 builds on 26 too, but pin for reproducibility) and pnpm
  `10.33.0`.
- **Persistent volume** = `DB_SYNC_DATA_DIR` (`index.sqlite` + `graphs/` +
  `assets/`). Document backup = snapshot the volume or `sqlite3 .backup`.

### 10.2 Security boundary - DECIDED: Pocket ID forward-auth at the proxy

> **Runbook side DONE** (DEPLOY.md: Caddy `forward_auth` example, the
> WS-upgrade caveat, topology). The actual VPS/proxy setup is ops work at
> deploy time; verify sync connects through the auth gate on the real proxy.

With `DB_SYNC_DISABLE_AUTH` + e2ee-off, **anyone who reaches the origin has full
read/write, and server data is plaintext at rest.** The reverse proxy in front IS
the entire security model - so the boundary must be real.

**[D] (2026-08-03) Authenticate at the proxy with Pocket ID (passkeys); the app
stays no-auth behind it.** Pocket ID (self-hosted OIDC, passkey-first) is being
added to the server anyway, so Logseq reuses it via **forward-auth** at the VPS
reverse proxy (oauth2-proxy / Caddy `forward_auth` / Traefik middleware):

```
browser -> VPS reverse proxy --forward_auth--> Pocket ID (passkey login)
              |  admits only authenticated sessions
              v
      Logseq origin (stock DB_SYNC_DISABLE_AUTH app behind it)
```

Rationale: passkey auth belongs at the HTTP gate, not inside Logseq. This keeps
the fork minimal - **the app is never wired to Pocket ID** (its client OAuth is
Cognito-hardwired; wiring it would be a real client fork). The app knowing the
user's identity buys nothing for a single user. So we get real passkey auth with
**zero extra Logseq divergence** on top of the no-auth mode already built.

**Explicitly rejected:** wiring Pocket ID into the app's own login/OAuth flow
(expensive client fork, redundant with the proxy gate), and the static-JWKS
mini-issuer (a fig leaf that saves only ~5 stable server-fork lines while adding a
process; moot once the proxy authenticates).

**Two implementation details that must be right:**
- **Home-LAN path is not gated by the VPS proxy** (§10.8: home browser reaches the
  backend directly). The passkey gate protects the internet-exposed path; the home
  LAN is treated as trusted. To enforce passkeys at home too, run the same
  forward-auth at the home reverse proxy or route home traffic through it. **[D]
  default: home LAN trusted, gate only the VPS path** (revisit if that changes).
- **Forward-auth must pass the WebSocket upgrade.** Sync is
  `wss://origin/sync/:graph-id`; the proxy must let the session cookie ride the
  same-origin WS handshake and not strip `Upgrade` headers. Verify sync connects
  after login - this is the one config detail that commonly breaks.

Also: tighten the adapter's `Access-Control-Allow-Origin: *` to the deploy origin
(single-origin, §10.1, makes CORS a non-issue anyway).

### 10.3 First-run UX (the one gap the MVP left)

> **DONE - the "simplest" option, generalized.** Any never-synced non-Demo DB
> graph **auto-uploads** on create/open (`self_host.cljs`: a background task
> over `current-repo-flow` + an init-time hook; upload via `<rtc-upload-graph!`,
> e2ee off). Demo graphs deliberately stay local: every fresh browser creates
> its own local Demo before `:self-host/init` runs, so syncing it would collide
> with a remote Demo from another browser. Interrupted-upload recovery is
> identity-gated (local RTC uuid must match the not-ready row) and serialized
> per graph across tabs via a Web Lock - see START_HERE for the full rule.
> Multi-graph auto-open defined: pick
> the **newest ready** remote graph by the server's `:updated-at` - note this
> field changes on graph creation and upload completion, NOT on edits, so it
> means "most recently added", not "most recently edited" - and only on a
> **fresh browser** (no non-Demo graph in the local OPFS db list) so a
> returning browser keeps its last-used graph. Two implementation gotchas are
> recorded in
> START_HERE ("Don't re-break these"): wait for the db conn (repo is set before
> the conn registers), and consume the continuous repo-flow with a direct
> `m/reduce`.

The original decision space, kept for the record:
- simplest: on first run, auto-upload the initial graph to the server (so device 2
  auto-opens it); or
- a clear one-click "Sync this graph" affordance (the cloud icon exists; verify it
  triggers `<rtc-upload-graph!` with e2ee-off in self-host).
Also: the MVP hardcodes one implicit user, so `<get-remote-graphs` returns all of
that user's graphs - auto-open only DTRT for exactly one. Define behavior for
multiple graphs (open last-used; let the user pick).

### 10.4 Robustness the MVP did not exercise (§1 assurance scope)
- **OPFS capability gate**: detect missing OPFS SAHPool and show a clear error
  page instead of a silent failure (no fallback exists). **DONE** - main-thread
  probe of `navigator.storage.getDirectory` at namespace load (the sync-handle
  API is worker-only; every browser shipping `getDirectory` ships it too),
  verified by stripping the API in a real browser. The rest of this list is
  still open - it is measurement, not code:
- **Scale**: test a realistically large graph (the MVP used a ~426-row demo) for
  snapshot upload/download time and memory; the adapter streams, but verify.
- **Server restart / reconnect**: confirm the client's pending-op queue re-syncs
  after an adapter restart; confirm WS reconnect/backoff behaves.
- **checksum-mismatch**: still logged (dev-only, pre-existing). Run the fork's
  repro harness once before calling sync production-grade.

### 10.5 Decisions to lock before shipping
- **Keep e2ee off?** [D] **Locked: off** (as recommended). The MVP forces it off
  because on-mode rendered encrypted blobs on device 2, and a hardcoded
  self-host password would be security theater anyway. Off means server-side
  plaintext behind the proxy - lean on the proxy + disk encryption.
- **Commit + CI.** Commit **DONE** (seven atomic commits, START_HERE.md lists
  them); the smoke test ships in-repo (`scripts/self-host/smoke-test.js`, now
  also covering auto-upload and a release-build browser B). Still open: whether
  to wire it into CI (it needs a full build - manual/nightly job material) and
  whether to propose the flag-gated mode upstream (§11).

### 10.6 Explicit non-goals for v1 (unchanged from the MVP)
Multi-user / access control (delegated to the proxy), simultaneous same-graph
editing (single-device-at-a-time, §0), and relocating per-browser UI state
(theme/shortcuts stay local, §3).

### 10.7 Measured backend resource footprint [E]
Measured on the running Node adapter (`node worker/dist/node-adapter.js`,
better-sqlite3 native, Node 26, macOS). The backend is the only server-side
component; the browser does the heavy lifting.

**Memory (RSS):**
| State | RSS |
|---|---|
| Cold boot - fresh, 0 graphs, 0 clients | **~124 MB** |
| Steady state - one ~1000-block graph loaded, 1 client connected | **~165-180 MB** |
| Peak - during active bulk sync of that graph | **~200 MB** |

The ~124 MB floor is Node's V8 heap + the native sqlite module, not data. Above
the floor, RSS scales with the number of **simultaneously open** graphs x their
size (the adapter holds each open graph's DataScript conn in memory; graphs load
lazily on first access, so stored-but-idle graphs cost ~nothing). A 1000-block
graph adds ~50-75 MB while open. For single-user / single-device use, typically
one graph is open -> **budget ~256 MB steady, ~384 MB peak**.

**CPU:** effectively idle at rest (0%). Sync is I/O-bound sqlite writes, not
CPU-bound: bulk editing produced only **brief single-core spikes to ~20%**, avg
~1.4%. **0.25-0.5 vCPU is ample.**

**Storage** (per graph = one sqlite file; plus one shared ~68 KB `index.sqlite`
metadata file):
- **Current-state snapshot** (`kvs` table): ~0.7 KB per row, ~2 rows per block ->
  **~1.4 KB per block**.
- **Edit history** (`tx_log` table, append-only): **~0.9 KB per edit (tx)**.
- Measured: a 1000-block graph where each block was one edit -> **~3.5 KB/block**,
  db file **~3.5 MB**. A 10k-block graph is on the order of **~35 MB**.
- **Growth caveat (the one to watch):** `tx_log` is append-only edit *history* -
  it grows with **total lifetime edits**, not current block count. A block edited
  50 times adds 50 tx rows (~45 KB). Heavy long-term editing grows the file beyond
  the block-count estimate; re-uploading a snapshot (bootstrap reset) compacts it.
  Budget on the order of **~1 KB per lifetime edit**.
- **Assets** (images/attachments) are stored raw on the filesystem under
  `assets/`, capped at ~100 MB per file by the adapter. For image-heavy notebooks
  **assets dominate storage** and are independent of the sqlite sizes above.

**Container sizing recommendation (single-user):** memory request 256 MB / limit
512 MB; CPU request 0.1 / limit 0.5 vCPU; a **1-2 GB persistent volume** covers
the sqlite files with generous headroom, sized up only for large asset libraries.
This fits comfortably on the smallest VPS tiers. Set a Node old-space cap
(`--max-old-space-size`) below the container memory limit so GC pressure surfaces
as slow-down rather than an OOM kill.

**Not exercised (see §1 assurance scope):** very large graphs (>10k blocks),
many graphs open at once, sustained multi-hour edit sessions, and large asset
volumes. Re-measure against the real target notebook before finalizing limits.

### 10.8 Deployment topology - DECIDED (2026-08-03)

**[D] Single backend. No backend-side replication. No external DB. No split-horizon
DNS.** One db-sync adapter instance is the single authoritative server per graph.

- **Reached by two network paths, configured per-browser** via the already-shipped
  custom sync-server URL (localStorage `sync-server-url`): the home browser points
  at the backend's direct LAN address (no tunnel); the office browser points at the
  VPS public address, which reverse-proxies to the backend over WireGuard. Same
  backend, same `graph-uuid`, one `tx_log` - it is one logical server reached two
  ways, not two servers. **[C] Both paths must be HTTPS** (OPFS = secure contexts
  only; plain HTTP works only via localhost). **[D] (2026-08-03) TLS always
  terminates at the reverse proxy, never in the app server**: the single-origin
  server is plain-HTTP-only (it rejects `TLS_CERT`/`TLS_KEY`); the VPS path gets
  TLS at the VPS proxy, the direct LAN path goes through a home TLS proxy (e.g.
  Caddy `tls internal`) - see DEPLOY.md.
- **Why this is enough (latency is a non-issue):** the app is local-first - edits
  hit the browser's local OPFS SQLite synchronously; backend sync is async/
  background. WireGuard latency therefore never touches the editing UX; it affects
  only background delta sync and the one-time bootstrap snapshot. Steady-state
  tunnel traffic is ~1 KB/edit deltas + on-demand assets, because each browser
  bootstraps the graph once and then caches it in OPFS. If the tunnel is down you
  keep working offline and it syncs on reconnect.
- **The client is already the replica.** Each browser holds the full graph and
  syncs deltas; a server-side replica would only duplicate that and create a second
  source of truth.

**Rejected, with reasons (so these don't get reconsidered by accident):**
- **Backend-side replication (e.g., SQLite rsync between two backends):** rejected.
  Two backends = two independent append-only `tx_log`s and per-server checksums,
  but the client tracks its sync position (`t`) and checksum **per server** - swap
  the server's state underneath it and you get stale/`tx-reject`/checksum-mismatch
  and silent divergence. The protocol assumes **one authoritative server per
  graph**. (Copying an open SQLite+WAL mid-write also risks a torn snapshot.)
- **Shared external DB across hosts (Postgres, or SQLite on a network share):**
  rejected. Network-shared SQLite corrupts; a shared Postgres puts a **synchronous
  DB round-trip on the tunnel per query**, which is worse than the current async
  delta sync. A shared DB only helps when co-located with every backend - home and
  VPS are not co-located.
- **Split-horizon DNS:** rejected as unnecessary. The two browsers are separate
  machines with separate per-browser `sync-server-url` config, so each just points
  at its best path - there is no shared hostname that needs to resolve differently.

**[D] (2026-08-03) Data lives at home.** The single backend (adapter + its
`DB_SYNC_DATA_DIR` volume with all sqlite files and assets) runs on the **home
server**. The VPS holds **no notebook data** - it is purely the reverse proxy +
Pocket ID auth gate. Consequences, all already covered by prior decisions:
- **Home:** browser reaches the backend directly on the LAN - no WireGuard, no
  tunnel traffic, lowest latency (§10.8 two-URL setup).
- **Office:** browser -> VPS (Pocket ID passkey gate, §10.2) -> WireGuard -> home
  backend. Local-first means editing is instant regardless; the tunnel carries
  only the one-time bootstrap snapshot + small deltas + on-demand assets.
- **Privacy/ownership:** notes never rest on the rented VPS; backups are a
  home-side volume snapshot (§10.1).
- Reinforces the §10.2 default that the home LAN path is trusted (the data and the
  LAN are both under your physical control); the passkey gate protects the
  internet-exposed VPS path.

With this, the plan has **no open decisions.**

---

## 11. Fork maintenance - DECIDED: minimize the upstream-conflict surface

This is a long-lived fork of a moving upstream; the goal is that `git pull` /
rebase from upstream stays near-effortless. **[D] (2026-08-03) Adopt the
"additive-in-fork-files, minimal-hooks-in-upstream-files" structure (the
option-1 refactor) plus a rebase workflow.**

**Principle.** Git conflicts only where both sides touch the same lines. So:
- **Additive code in fork-owned files never conflicts** - a new namespace, a new
  `defmethod events/handle`, a new `def-thread-api`. Logseq's multimethod
  extension points let us add behavior from a new file with zero edits to existing
  files.
- **Inline edits to upstream-owned functions are the entire risk surface** - they
  conflict whenever upstream edits that function.

**The refactor (shrinks 9 touched files to 2 fork files + ~6 minimal hooks):**
- **Extract all additive self-host logic to a fork-owned namespace**
  `frontend.handler.self-host` (JWT synth, `:self-host/init` defmethod,
  auto-open). This **empties `handler/events/ui.cljs` entirely** - the biggest,
  most-churned upstream file we touched drops to **zero edits** (the ~66 additive
  lines move out; the second `set-db-sync-config` edit is dead code in self-host -
  its only caller `ensure-user-rsa-keys-if-possible!` is on the login path
  self-host bypasses - so it is dropped).
- **Reduce every remaining upstream touch to a minimal, stable, flag-gated hook**,
  not a re-indent: `handler.cljs` (2-line guarded `pub-event` + a require);
  `handler/events/rtc.cljs` (rewrite the `request-e2ee-password` guard as an early
  top-branch that leaves the original body byte-identical -> ~27-line churn
  becomes ~3 added lines); `auth.cljs` (same minimal top-guard on `auth-claims`).
- **Accept the irreducible core:** ~4 tiny flag-gated guards remain (the 2
  `normalize-graph-e2ee?` spots, the rsa-ensure skip, and `auth-claims`), each 1-3
  lines in small, rarely-changed functions. Optionally funnel the e2ee guards
  through one fork-owned predicate so an upstream move re-attaches in one place.
- Everything stays behind `SELF_HOST` / `DB_SYNC_DISABLE_AUTH`, so upstream's
  default behavior and tests are unchanged and the diff is inert-by-default.

**Post-refactor fork footprint:** 2 new fork-owned files + ~6 upstream files
touched by 1-3 stable, flag-gated lines each. No touch to `events/ui.cljs`; no
touch to the churny sync/storage/checksum server files.

**Footprint re-audit (2026-08-03, after the review rounds).** The accepted
irreducible core grew from ~4 to 7 flag-gated touch points, each carrying a
P1 correctness fix; everything stays inert-by-default and the biggest single
inline edit is ~5 lines:
- `config.cljs`: the SELF-HOST define (additive) + a 2-line body edit in each
  of `db-sync-ws-url`/`db-sync-http-base` (origin default). These two fns
  belong to the recently-shipped custom-sync-server feature - the churniest
  region we touch; a conflict re-applies one `(or ...)` wrapper.
- `handler/db_based/sync.cljs`: 3 guards - `normalize-graph-e2ee?` (e2ee off),
  the rsa-ensure skip, and the `should-start-rtc?` graph-identity guard
  (prevents name-based merging of unrelated graphs).
- `worker/sync/upload.cljs`: the worker-side `normalize-graph-e2ee?` guard,
  now backed by a compile-time `SELF-HOST` goog-define (additive block) so
  whole-config replacements can't re-enable e2ee.
- `handler.cljs` (require + 2-line hook), `handler/events/rtc.cljs` (e2ee
  password early-branch), `shadow-cljs.edn` (2 additive define lines),
  `deps/db-sync` `worker/auth.cljs` + `node/server.cljs` (the no-auth seam),
  dicts `en`/`zh-CN` (additive `:self-host/*` entries) - all as designed.
- **Semantic couplings inside the fork-owned namespace** (no git conflicts,
  but rebase checkpoints): the `flows/*current-login-user` reset (the atom has
  a schema validator - guarded by try/catch), the `:rtc/graphs` entry shape,
  and `rtc-flows/logout-flow`. The A->B smoke test is the post-rebase net for
  all of them.

**Git workflow.** Track `upstream` (`github.com/logseq/logseq`) as a remote; keep
the self-host delta as a small set of **atomic, labeled commits** (`server:
DB_SYNC_DISABLE_AUTH`, `client: self-host mode`, `build: SELF_HOST flag`), with the
editor-durability patch (§7b) as its **own** commit. On each update:
`git fetch upstream && git rebase upstream/master` - linear history, small replays,
localized conflicts. Rebase, not merge.

**Strongest lever (worth pursuing separately):** propose the flag-gated self-host
mode as an **upstream PR**. The db-sync adapter is explicitly a self-host feature;
a `DB_SYNC_DISABLE_AUTH` + `SELF_HOST` opt-in is plausibly acceptable. If merged we
carry nothing. Even if not, shaping it as a clean opt-in is what makes it
rebase-proof.

**Status:** decided; not yet executed. The refactor is a behavior-identical
(flag-gated) restructuring of the existing MVP branch, to be re-validated with the
A->B smoke test before/after.
