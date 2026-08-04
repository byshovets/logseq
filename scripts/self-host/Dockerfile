# Self-hosted single-user Logseq web app - single-origin image.
#
# Builds THIS checkout (not upstream master) with SELF_HOST=true, plus the
# db-sync Node adapter, and runs both behind one origin via
# scripts/self-host/single-origin-server.mjs. See docs/self-host/DEPLOY.md.
#
# Build (repo root):  docker build --platform linux/amd64 -f scripts/self-host/Dockerfile -t logseq-selfhost .
# Run:                docker run --read-only --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m \
#                       -p 8080:8080 -v logseq-data:/data logseq-selfhost

# Keep both the human-readable version and immutable index digest. This image is
# intentionally amd64-only; CI and the LEXX deployment are both x86_64.
FROM node:24.18.0-bookworm-slim@sha256:6f7b03f7c2c8e2e784dcf9295400527b9b1270fd37b7e9a7285cf83b6951452d AS node-base

# --- build base ---------------------------------------------------------------
# Clojure + Node 24 + pnpm 10.33.0. Java 21+: the pinned closure-compiler
# (v20250820) is built for class-file 65.0, so the stale root Dockerfile's
# temurin-11 base no longer works.
FROM clojure:temurin-21-tools-deps-1.12.5.1654-bookworm-slim@sha256:29b6b3e99e954aef27c0377f041505ac1bd494f8e72d9148c2b73c241f7e0dd5 AS build-base

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    git \
    build-essential libcairo2-dev libpango1.0-dev libjpeg-dev libgif-dev librsvg2-dev \
    && rm -rf /var/lib/apt/lists/*

# Take Node from the separately pinned official image instead of executing the
# NodeSource repository bootstrap script during the build.
COPY --from=node-base /usr/local/ /usr/local/
RUN corepack enable && corepack prepare pnpm@10.33.0 --activate

WORKDIR /build

COPY . .

RUN pnpm install --frozen-lockfile --config.network-timeout=240000

# --- release builder ----------------------------------------------------------
FROM build-base AS release-builder

ARG SELF_HOST=true
RUN test "$SELF_HOST" = true

# Release web build in self-host mode (no login, e2ee off, sync = page origin).
RUN SELF_HOST="$SELF_HOST" pnpm run release-app

# db-sync Node adapter (the server-side database). --ignore-workspace keeps the
# install local to deps/db-sync (the repo root is a pnpm workspace, which would
# otherwise hoist everything to the root node_modules); the final --prod
# re-install leaves a production-only node_modules for the runner stage.
RUN cd deps/db-sync && \
    pnpm install --frozen-lockfile --ignore-workspace --config.network-timeout=240000 && \
    pnpm run build:node-adapter && \
    rm -rf node_modules && \
    pnpm install --frozen-lockfile --ignore-workspace --prod --config.network-timeout=240000

# CI-only target: serves an unoptimized build on :3001 for browser A in the
# existing smoke-test.js. The default/final target remains the runtime below.
FROM build-base AS smoke-dev
ARG SELF_HOST=true
RUN test "$SELF_HOST" = true
ENV SELF_HOST="$SELF_HOST" \
    ENABLE_DB_SYNC_LOCAL=true
EXPOSE 3001
CMD ["pnpm", "run", "app-watch"]

# --- runner -------------------------------------------------------------------
FROM node-base AS runner

ARG VCS_REF=unknown
ARG VERSION=unknown
ARG SOURCE_URL=https://github.com/byshovets/logseq
LABEL org.opencontainers.image.title="Logseq self-host" \
      org.opencontainers.image.description="Single-user self-hosted Logseq web app and db-sync adapter" \
      org.opencontainers.image.source="$SOURCE_URL" \
      org.opencontainers.image.revision="$VCS_REF" \
      org.opencontainers.image.version="$VERSION"

WORKDIR /app

COPY --from=release-builder /build/static ./static
COPY --from=release-builder /build/deps/db-sync/worker/dist/node-adapter.js ./deps/db-sync/worker/dist/node-adapter.js
COPY --from=release-builder /build/deps/db-sync/package.json ./deps/db-sync/package.json
COPY --from=release-builder /build/deps/db-sync/node_modules ./deps/db-sync/node_modules
COPY --from=release-builder /build/scripts/self-host/single-origin-server.mjs ./scripts/self-host/single-origin-server.mjs

# Named/anonymous volumes inherit this ownership. /app stays root-owned and
# non-writable; the process runs as the existing unprivileged node user.
RUN install -d -o node -g node -m 0750 /data && chmod -R a-w /app

# /data is the notebook: index.sqlite + graphs/ + assets/. Mount and back it up.
ENV PORT=8080 \
    DB_SYNC_DATA_DIR=/data \
    NODE_ENV=production \
    NODE_OPTIONS=--max-old-space-size=384 \
    HOME=/tmp \
    TMPDIR=/tmp \
    XDG_CACHE_HOME=/tmp/.cache
VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"

USER node
STOPSIGNAL SIGTERM
CMD ["node", "scripts/self-host/single-origin-server.mjs"]
