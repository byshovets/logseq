# Self-hosted single-user Logseq web app - single-origin image.
#
# Builds THIS checkout (not upstream master) with SELF_HOST=true, plus the
# db-sync Node adapter, and runs both behind one origin via
# scripts/self-host/single-origin-server.mjs. See docs/self-host/DEPLOY.md.
#
# Build (repo root):  docker build -f scripts/self-host/Dockerfile -t logseq-selfhost .
# Run:                docker run -p 8080:8080 -v logseq-data:/data logseq-selfhost

# --- builder -----------------------------------------------------------------
# clojure + Node 24 + pnpm 10.33.0. Java 21+: the pinned closure-compiler
# (v20250820) is built for class-file 65.0, so the root Dockerfile's temurin-11
# base no longer works.
FROM clojure:temurin-21-tools-deps-bookworm-slim AS builder

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    apt-transport-https \
    gpg \
    git \
    build-essential libcairo2-dev libpango1.0-dev libjpeg-dev libgif-dev librsvg2-dev

RUN curl -fsSL https://deb.nodesource.com/setup_24.x | bash - && \
    apt-get update && apt-get install -y nodejs && \
    corepack enable && corepack prepare pnpm@10.33.0 --activate

WORKDIR /data

COPY . .

RUN pnpm install --frozen-lockfile --config.network-timeout=240000

# Release web build in self-host mode (no login, e2ee off, sync = page origin).
RUN SELF_HOST=true pnpm run release-app

# db-sync Node adapter (the server-side database). --ignore-workspace keeps the
# install local to deps/db-sync (the repo root is a pnpm workspace, which would
# otherwise hoist everything to the root node_modules); the final --prod
# re-install leaves a production-only node_modules for the runner stage.
RUN cd deps/db-sync && \
    pnpm install --frozen-lockfile --ignore-workspace --config.network-timeout=240000 && \
    pnpm run build:node-adapter && \
    rm -rf node_modules && \
    pnpm install --frozen-lockfile --ignore-workspace --prod --config.network-timeout=240000

# --- runner --------------------------------------------------------------------
FROM node:24-bookworm-slim

WORKDIR /app

COPY --from=builder /data/static ./static
COPY --from=builder /data/deps/db-sync/worker/dist/node-adapter.js ./deps/db-sync/worker/dist/node-adapter.js
COPY --from=builder /data/deps/db-sync/package.json ./deps/db-sync/package.json
COPY --from=builder /data/deps/db-sync/node_modules ./deps/db-sync/node_modules
COPY --from=builder /data/scripts/self-host/single-origin-server.mjs ./scripts/self-host/single-origin-server.mjs

# /data is the notebook: index.sqlite + graphs/ + assets/. Mount and back it up.
ENV PORT=8080 \
    DB_SYNC_DATA_DIR=/data \
    NODE_OPTIONS=--max-old-space-size=384
VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"

CMD ["node", "scripts/self-host/single-origin-server.mjs"]
