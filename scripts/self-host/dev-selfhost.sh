#!/usr/bin/env bash
# Run the single-user self-host MVP locally:
#   - db-sync Node adapter (server-side database) on :8787, auth disabled
#   - Logseq web app on :3001, built in self-host mode (no login, e2ee off)
#
# Open http://localhost:3001/index.html in any local browser. A fresh browser
# auto-opens the single synced graph. Edit in one browser, see it in another.
#
# Prereqs (once):
#   pnpm install                                       # repo root
#   (cd deps/db-sync && pnpm install && pnpm run build:node-adapter)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DATA_DIR="${DB_SYNC_DATA_DIR:-$ROOT/.selfhost-data}"
PNPM="${PNPM:-npx --yes pnpm@10.33.0}"
mkdir -p "$DATA_DIR"

ADAPTER="$ROOT/deps/db-sync/worker/dist/node-adapter.js"
if [ ! -f "$ADAPTER" ]; then
  echo "Adapter not built. Run: (cd deps/db-sync && $PNPM run build:node-adapter)" >&2
  exit 1
fi

echo "db-sync adapter  -> http://localhost:8787   (data: $DATA_DIR)"
DB_SYNC_PORT=8787 DB_SYNC_DATA_DIR="$DATA_DIR" DB_SYNC_DISABLE_AUTH=true \
  node "$ADAPTER" &
ADAPTER_PID=$!

echo "web app          -> http://localhost:3001/index.html  (building...)"
( cd "$ROOT" && SELF_HOST=true ENABLE_DB_SYNC_LOCAL=true $PNPM run app-watch ) &
APP_PID=$!

trap 'kill "$ADAPTER_PID" "$APP_PID" 2>/dev/null || true' EXIT INT TERM
echo "Ctrl-C to stop both. Open http://localhost:3001/index.html once the app build finishes."
wait
