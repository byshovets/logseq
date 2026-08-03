// Single-origin server for the self-hosted Logseq web app.
//
// One origin, one process tree:
//   - spawns the db-sync Node adapter (the server-side database) on a loopback
//     port with auth disabled,
//   - serves the release web build (static/) at /,
//   - reverse-proxies the adapter's routes (/health, /graphs*, /e2ee*,
//     /assets/*, /sync/* incl. the WebSocket upgrade) to it.
//
// Same-origin means no CORS and a single URL to deploy behind a reverse proxy.
// Node stdlib only - no dependencies.
//
// Env:
//   PORT              listen port                      (default 8080)
//   HOST              listen host                      (default 0.0.0.0)
//   STATIC_DIR        release web build                (default <repo>/static)
//   ADAPTER_PATH      built adapter script             (default <repo>/deps/db-sync/worker/dist/node-adapter.js)
//   ADAPTER_PORT      loopback port for the adapter    (default 8787)
//   DB_SYNC_DATA_DIR  adapter data volume              (default <repo>/.selfhost-data)
//
// This server speaks plain HTTP by design (decided; docs/self-host/DEPLOY.md):
// TLS always terminates at the reverse proxy in front, never here. OPFS (the
// app's local storage) only exists in secure contexts, so browsers can use
// this server directly via http://localhost only - every other path must go
// through an HTTPS proxy.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const STATIC_DIR = path.resolve(process.env.STATIC_DIR || path.join(ROOT, 'static'));
const ADAPTER_PATH = path.resolve(process.env.ADAPTER_PATH || path.join(ROOT, 'deps', 'db-sync', 'worker', 'dist', 'node-adapter.js'));
const ADAPTER_PORT = Number(process.env.ADAPTER_PORT || 8787);
const DATA_DIR = path.resolve(process.env.DB_SYNC_DATA_DIR || path.join(ROOT, '.selfhost-data'));
if (process.env.TLS_CERT || process.env.TLS_KEY) {
  // Fail fast instead of silently ignoring a TLS expectation: this server
  // never terminates TLS - that is the reverse proxy's job.
  console.error('TLS_CERT/TLS_KEY are not supported: TLS terminates at the reverse proxy (see docs/self-host/DEPLOY.md).');
  process.exit(1);
}

// The adapter's whole HTTP surface (deps/db-sync .../node/dispatch.cljs).
const ADAPTER_ROUTES = /^\/(e2ee|(health|graphs|assets|sync)([/?]|$))/;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.map': 'application/json',
  '.json': 'application/json',
  '.wasm': 'application/wasm',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.webp': 'image/webp',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.eot': 'application/vnd.ms-fontobject',
  '.mp3': 'audio/mpeg',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/plain; charset=utf-8',
  '.edn': 'text/plain; charset=utf-8',
  '.xml': 'application/xml',
  '.webmanifest': 'application/manifest+json',
};

// --- adapter child process ---------------------------------------------------

let shuttingDown = false;

function startAdapter() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const child = spawn(process.execPath, [ADAPTER_PATH], {
    env: {
      ...process.env,
      DB_SYNC_PORT: String(ADAPTER_PORT),
      DB_SYNC_DATA_DIR: DATA_DIR,
      DB_SYNC_DISABLE_AUTH: 'true',
      DB_SYNC_STORAGE_DRIVER: 'sqlite',
    },
    stdio: 'inherit',
  });
  child.on('exit', (code, signal) => {
    if (shuttingDown) return; // we killed it; the signal handler owns the exit
    console.error(`db-sync adapter exited (code=${code} signal=${signal}); shutting down`);
    process.exit(code === null ? 1 : code || 1);
  });
  return child;
}

function waitForAdapter(retries = 50) {
  return new Promise((resolve, reject) => {
    const attempt = (left) => {
      const req = http.get({ host: '127.0.0.1', port: ADAPTER_PORT, path: '/health' }, (res) => {
        res.resume();
        resolve();
      });
      req.on('error', () => {
        if (left <= 0) return reject(new Error('db-sync adapter did not become healthy'));
        setTimeout(() => attempt(left - 1), 200);
      });
    };
    attempt(retries);
  });
}

// --- static files ------------------------------------------------------------

function resolveStatic(urlPath) {
  let p;
  try {
    p = decodeURIComponent(urlPath.split('?')[0]);
  } catch {
    return null;
  }
  if (p === '/') p = '/index.html';
  const candidates = [p];
  // The app's compiled asset-path is /static/js while the build itself is the
  // web root, so retry /static/* against the root.
  if (p.startsWith('/static/')) candidates.push(p.slice('/static'.length));
  for (const candidate of candidates) {
    const full = path.resolve(path.join(STATIC_DIR, candidate));
    if (!full.startsWith(STATIC_DIR + path.sep)) continue;
    if (fs.existsSync(full) && fs.statSync(full).isFile()) return full;
  }
  return null;
}

function serveStatic(req, res) {
  const file = resolveStatic(req.url);
  if (!file) {
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not found');
    return;
  }
  const stat = fs.statSync(file);
  const lastModified = stat.mtime.toUTCString();
  if (req.headers['if-modified-since'] === lastModified) {
    res.writeHead(304);
    res.end();
    return;
  }
  res.writeHead(200, {
    'content-type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
    'content-length': stat.size,
    'last-modified': lastModified,
    'cache-control': 'no-cache',
  });
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  fs.createReadStream(file).pipe(res);
}

// --- reverse proxy -----------------------------------------------------------

function proxyRequest(req, res) {
  const upstream = http.request(
    { host: '127.0.0.1', port: ADAPTER_PORT, path: req.url, method: req.method, headers: req.headers },
    (upRes) => {
      res.writeHead(upRes.statusCode, upRes.headers);
      upRes.pipe(res);
    }
  );
  upstream.on('error', (e) => {
    console.error('proxy error:', e.message);
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'application/json' });
    res.end('{"error":"upstream unavailable"}');
  });
  req.pipe(upstream);
}

function proxyUpgrade(req, socket, head) {
  const upstream = net.connect(ADAPTER_PORT, '127.0.0.1', () => {
    let raw = `${req.method} ${req.url} HTTP/1.1\r\n`;
    for (let i = 0; i < req.rawHeaders.length; i += 2) {
      raw += `${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}\r\n`;
    }
    raw += '\r\n';
    upstream.write(raw);
    if (head && head.length) upstream.write(head);
    upstream.pipe(socket);
    socket.pipe(upstream);
  });
  const drop = () => {
    socket.destroy();
    upstream.destroy();
  };
  upstream.on('error', drop);
  socket.on('error', drop);
}

// --- main ---------------------------------------------------------------------

if (!fs.existsSync(path.join(STATIC_DIR, 'index.html'))) {
  console.error(`No web build at ${STATIC_DIR} (missing index.html). Run: SELF_HOST=true pnpm run release-app`);
  process.exit(1);
}
if (!fs.existsSync(ADAPTER_PATH)) {
  console.error(`No adapter at ${ADAPTER_PATH}. Run: (cd deps/db-sync && pnpm install && pnpm run build:node-adapter)`);
  process.exit(1);
}

const adapter = startAdapter();
await waitForAdapter();

const server = http.createServer((req, res) => {
  if (ADAPTER_ROUTES.test(req.url)) {
    proxyRequest(req, res);
  } else {
    serveStatic(req, res);
  }
});
server.on('upgrade', (req, socket, head) => {
  if (req.url.startsWith('/sync/')) {
    proxyUpgrade(req, socket, head);
  } else {
    socket.destroy();
  }
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    shuttingDown = true;
    adapter.kill(sig);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 2000).unref();
  });
}

server.listen(PORT, HOST, () => {
  console.log(`logseq self-host -> http://${HOST}:${PORT}  (static: ${STATIC_DIR}, data: ${DATA_DIR}, adapter: 127.0.0.1:${ADAPTER_PORT})`);
  console.warn(
    'Plain HTTP by design: browsers only expose OPFS (the app\'s local storage) in secure contexts, ' +
      'so this server is directly usable via http://localhost only. Every other path must go through ' +
      'the HTTPS reverse proxy in front (docs/self-host/DEPLOY.md).'
  );
});
