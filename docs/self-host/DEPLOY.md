# Deploying self-hosted Logseq

Operational runbook for the single-origin production build. Design rationale
lives in [PLAN.md](./PLAN.md) 10; this is the how-to.

## The shape (PLAN.md 10.8, decided)

One backend, reached two ways:

```
home browser  ----------------- LAN -----------------.
                                                      v
office browser -> VPS (Pocket ID gate + reverse proxy) --WireGuard--> home server
                                                                      [container]
                                                                      /data volume
```

- The **home server** runs the container and holds all data (`/data`). The VPS
  holds no notebook data - it is purely the authenticated front door.
- Each browser sets its own path: the home browser uses the LAN address
  directly; the office browser uses the VPS address. Same backend, same graphs.
- **Every non-localhost path must be HTTPS.** The app stores graphs in OPFS,
  which browsers only expose in secure contexts - plain `http://` works for
  `localhost` only; on a LAN IP/hostname the app shows its storage error page
  instead of booting. The VPS path gets TLS at the proxy anyway; for the direct
  LAN path either set `TLS_CERT`/`TLS_KEY` on the container (see below) or put
  a TLS-terminating proxy in front (e.g. home Caddy with `tls internal`).
- **The app itself has no auth and e2ee is off** - anyone who can reach the
  origin has full read/write, and data is plaintext at rest. The reverse proxy
  IS the security model. Never expose the container port to the internet
  directly.

## Build and run the image

```bash
# repo root
docker build -f scripts/self-host/Dockerfile -t logseq-selfhost .
docker run -d --name logseq \
  -p 8080:8080 \
  -v logseq-data:/data \
  --restart unless-stopped \
  logseq-selfhost
```

Or compose:

```yaml
services:
  logseq:
    build:
      context: .
      dockerfile: scripts/self-host/Dockerfile
    ports:
      - "8080:8080"
    volumes:
      - logseq-data:/data
    restart: unless-stopped
volumes:
  logseq-data:
```

Open `http://localhost:8080/` (from the host itself), or serve HTTPS for any
other machine - either at a proxy in front, or directly by mounting a cert and
setting `TLS_CERT`/`TLS_KEY` (PEM paths inside the container):

```bash
docker run -d --name logseq \
  -p 8443:8080 \
  -v logseq-data:/data \
  -v /path/to/certs:/certs:ro \
  -e TLS_CERT=/certs/fullchain.pem -e TLS_KEY=/certs/privkey.pem \
  --restart unless-stopped \
  logseq-selfhost
```

First run: the app boots into a local Demo graph; create your own graph and it
is **synced to the server automatically** (Demo stays browser-local by design).
Any other browser pointed at the origin then auto-opens the newest remote graph
(newest by server metadata, i.e. most recently added - not most recently
edited).

Sizing (measured, PLAN.md 10.7): 256 MB RAM steady / 512 MB limit, 0.5 vCPU,
1-2 GB volume (more for large asset libraries). The image defaults
`NODE_OPTIONS=--max-old-space-size=384`; raise it together with the memory
limit for very large graphs.

### Without Docker

Build environment: **JDK 21+** (the pinned closure-compiler is built for
class-file 65.0 - shadow-cljs fails to load under Java 11/17), **Node >= 22.20**,
**pnpm 10.33.0**.

```bash
pnpm install
SELF_HOST=true pnpm run release-app
(cd deps/db-sync && pnpm install && pnpm run build:node-adapter)
PORT=8080 DB_SYNC_DATA_DIR=/path/to/data node scripts/self-host/single-origin-server.mjs
```

## The data volume and backups

`/data` (env `DB_SYNC_DATA_DIR`) holds everything: `index.sqlite` (graph/user
metadata), `graphs/<id>/db.sqlite` (one per graph: current state + edit log),
`assets/` (raw attachment files). Back it up by snapshotting the volume, or
consistently while running:

```bash
docker exec -e NODE_PATH=/app/deps/db-sync/node_modules logseq node -e '
  const D = require("better-sqlite3");
  const files = require("fs").globSync(["/data/index.sqlite", "/data/graphs/*/db.sqlite"]);
  (async () => { for (const f of files) await D(f, {readonly: true}).backup(f + ".bak"); })();
'
# then rsync /data (including the .bak files and assets/) off-host
```

A plain filesystem copy is also fine when the container is stopped.

## VPS front door: Pocket ID forward-auth (PLAN.md 10.2, decided)

The VPS reverse-proxies to the home backend over WireGuard and gates every
request with Pocket ID (passkeys) via forward-auth. The app is never wired to
Pocket ID - the gate is pure HTTP. Caddy example:

```caddyfile
logseq.example.com {
    forward_auth pocket-id:1411 {
        uri /api/oidc/forward-auth
        copy_headers Remote-User Remote-Email
    }
    reverse_proxy 10.0.0.2:8080   # home backend over WireGuard
}
```

(oauth2-proxy or Traefik's forwardAuth middleware work the same way; consult
Pocket ID's docs for the exact forward-auth endpoint of your version.)

**The one detail that commonly breaks: the WebSocket upgrade.** Live sync runs
over `wss://<origin>/sync/<graph-id>`. The proxy must:

1. pass `Upgrade`/`Connection` headers through to the backend (Caddy's
   `reverse_proxy` does by default; nginx needs the explicit
   `proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";`),
2. run forward-auth on the upgrade request too - the session cookie rides the
   same-origin WS handshake, so cookie-based forward-auth passes it; header
   -injection schemes that rely on redirects will break it.

Verify after setup: log in, open the app, and check the sync indicator
connects (or `wscat -H "Cookie: <session>" wss://logseq.example.com/sync/test`
returns an HTTP error from the app rather than the auth gate's redirect).

The home-LAN path deliberately bypasses the gate (trusted network, decided in
PLAN.md 10.2). To gate it too, run the same forward-auth on a home proxy.

## Browser requirements

Modern Chrome/Edge/Firefox/Safari, not in private browsing, over **HTTPS or
localhost**: graphs are stored locally in OPFS sqlite, which browsers expose
only in secure contexts. Unsupported browsers and insecure (plain-HTTP,
non-localhost) origins each get an explicit error page instead of a silent
failure. The server remains the source of truth - losing a browser's local
copy only costs a re-download.

## Smoke test

With a dev watch app on :3001 and the single-origin release server on :8080
sharing one adapter (see the header of `scripts/self-host/smoke-test.js`):

```bash
APP_URL_B=http://localhost:8080/ DB_SYNC_DATA_DIR=<data dir> \
  node scripts/self-host/smoke-test.js   # prints SMOKE PASS
```
