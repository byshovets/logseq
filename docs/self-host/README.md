# Self-hosted single-user Logseq web app

This directory holds the design and operational docs for running this fork as a
self-hosted, single-user web app (server-side database via the `db-sync` Node
adapter, no in-app auth, reached from any browser).

- **[PLAN.md](./PLAN.md)** - the full design: MVP as-built (change set + gotchas),
  path to production, measured resource footprint, and the locked decisions
  (single backend, data at home, Pocket ID forward-auth at the proxy, e2ee off,
  fork-maintenance strategy). Start here.
- **[START_HERE.md](./START_HERE.md)** - one-page handoff for the implementation
  agent: what's done, what's next, and how to run/verify.
- **[DEPLOY.md](./DEPLOY.md)** - the production runbook: Docker image, data
  volume/backups, Pocket ID forward-auth at the proxy, browser requirements.

Related tooling lives in [`../../scripts/self-host/`](../../scripts/self-host/):
`dev-selfhost.sh` (run the local dev stack), `single-origin-server.mjs` (the
production single-origin server), `Dockerfile` (the production image),
`smoke-test.js` (the A->B end-to-end check), and
`editor-durability-hardening.patch` (the separate editor-durability change from
PLAN.md 7b/Phase 5).
