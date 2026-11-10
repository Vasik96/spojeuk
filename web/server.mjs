#!/usr/bin/env node
// Spoje UK web: statické soubory + API živých poloh. Žádné závislosti, stačí Node 20+.
//
//   PORT=8080 node server.mjs
//   MOCK=1 node server.mjs                    polohy z jízdního řádu (vývoj, když není dostupný zdroj)
//   FEED_URL=https://tvuj-server/duk/traffic  vlastní spoje-feed místo veřejného zdroje
//   DATA_REFRESH_H=12                         jak často kontrolovat nové jízdní řády (0 = nikdy)
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { Feed, httpFetcher } from './lib/feed.mjs';
import { mockFetcher } from './lib/mock.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC = path.join(root, 'public');
const DATA_FILE = path.join(PUBLIC, 'data', 'timetable.json');
const env = process.env;
const PORT = Number(env.PORT || 8080);
const HOST = env.HOST || '0.0.0.0';
const PORTABO = 'http://37.46.211.70:8787/duk/traffic';

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.webp': 'image/webp', '.jpg': 'image/jpeg', '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json; charset=utf-8', '.txt': 'text/plain; charset=utf-8', '.xml': 'application/xml; charset=utf-8',
};
const COMPRESSIBLE = new Set(['.html', '.js', '.mjs', '.css', '.json', '.svg', '.webmanifest', '.txt', '.xml']);

const SECURITY = {
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'strict-origin-when-cross-origin',
  'permissions-policy': 'geolocation=(self), camera=(), microphone=()',
  'content-security-policy': "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
};

// ───────────── živé polohy ─────────────
const useMock = env.MOCK === '1';
const urls = env.FEED_URL ? [env.FEED_URL, PORTABO] : [PORTABO];
const feed = new Feed({
  fetchRaw: useMock ? mockFetcher(DATA_FILE) : httpFetcher(urls),
  pollMs: Number(env.POLL_S || 10) * 1000,
  holdMhdS: Number(env.HOLD_MHD_S || 300),
  holdOtherS: Number(env.HOLD_OTHER_S || 180),
});
feed.start();

// ───────────── statické soubory ─────────────
const cache = new Map(); // cesta → { mtime, raw, br, gz, etag }

function load(file) {
  const st = fs.statSync(file);
  let c = cache.get(file);
  if (c && c.mtime === st.mtimeMs) return c;
  const raw = fs.readFileSync(file);
  const ext = path.extname(file);
  c = { mtime: st.mtimeMs, raw, etag: `"${st.size.toString(16)}-${Math.floor(st.mtimeMs).toString(16)}"`, br: null, gz: null };
  if (COMPRESSIBLE.has(ext) && raw.length > 512) {
    c.br = zlib.brotliCompressSync(raw, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: raw.length > 1e6 ? 9 : 11 } });
    c.gz = zlib.gzipSync(raw, { level: 9 });
  }
  cache.set(file, c);
  return c;
}

function send(req, res, status, body, headers = {}) {
  res.writeHead(status, { ...SECURITY, ...headers, 'content-length': Buffer.byteLength(body) });
  res.end(req.method === 'HEAD' ? undefined : body);
}

function serveFile(req, res, file) {
  const ext = path.extname(file);
  const c = load(file);
  const accept = String(req.headers['accept-encoding'] || '');
  const enc = c.br && /\bbr\b/.test(accept) ? 'br' : c.gz && /\bgzip\b/.test(accept) ? 'gzip' : null;
  const body = enc === 'br' ? c.br : enc === 'gzip' ? c.gz : c.raw;
  // data a service worker se kontrolují vždy, ostatní se krátce cachují
  const cc = file === DATA_FILE || ext === '.html' || file.endsWith('sw.js') ? 'no-cache' : 'public, max-age=300';
  const headers = { ...SECURITY, 'content-type': MIME[ext] || 'application/octet-stream', 'cache-control': cc, etag: c.etag, vary: 'accept-encoding' };
  if (enc) headers['content-encoding'] = enc;
  if (req.headers['if-none-match'] === c.etag) { res.writeHead(304, headers); return res.end(); }
  res.writeHead(200, { ...headers, 'content-length': body.length });
  res.end(req.method === 'HEAD' ? undefined : body);
}

function resolvePath(urlPath) {
  let p;
  try { p = decodeURIComponent(urlPath); } catch { return null; }
  if (p.includes('\0')) return null;
  if (p.endsWith('/')) p += 'index.html';
  const file = path.normalize(path.join(PUBLIC, p));
  if (file !== PUBLIC && !file.startsWith(PUBLIC + path.sep)) return null; // ochrana proti ../
  return file;
}

const server = http.createServer((req, res) => {
  try {
    if (req.method !== 'GET' && req.method !== 'HEAD') return send(req, res, 405, 'Method Not Allowed', { allow: 'GET, HEAD' });
    const url = new URL(req.url, 'http://x');

    if (url.pathname === '/api/vehicles') {
      return send(req, res, 200, JSON.stringify(feed.snapshot()), { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'public, max-age=3', 'access-control-allow-origin': '*' });
    }
    if (url.pathname === '/api/health') {
      const s = feed.snapshot();
      return send(req, res, 200, JSON.stringify({ ok: !s.outage, mock: useMock, vehicles: s.vehicles.length, held: s.held, upstreamAgeSec: s.upstreamAgeSec, failures: feed.status.failures, lastError: feed.status.lastError, timetable: fs.existsSync(DATA_FILE) }), { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    }

    let file = resolvePath(url.pathname);
    if (!file) return send(req, res, 400, 'Bad Request', { 'content-type': 'text/plain' });
    if (url.pathname === '/app') { res.writeHead(301, { location: '/app/' }); return res.end(); }
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      const nf = path.join(PUBLIC, '404.html');
      if (fs.existsSync(nf)) { const c = load(nf); return send(req, res, 404, c.raw, { 'content-type': MIME['.html'] }); }
      return send(req, res, 404, 'Not Found', { 'content-type': 'text/plain' });
    }
    serveFile(req, res, file);
  } catch (e) {
    console.error(e);
    send(req, res, 500, 'Internal Server Error', { 'content-type': 'text/plain' });
  }
});

// ───────────── jízdní řády: první sestavení a pravidelná obnova ─────────────
async function refreshData() {
  const { build } = await import('./scripts/build-data.mjs');
  const old = fs.existsSync(DATA_FILE) ? JSON.parse(fs.readFileSync(path.join(PUBLIC, 'data', 'meta.json'), 'utf8')).tag : null;
  const base = env.GTFS_BASE_URL || 'https://github.com/Vasik96/spojeuk-data/releases/download/gtfs';
  let remote = null;
  try { remote = (await (await fetch(`${base}/gtfs.json`)).json()).tag; } catch { /* offline */ }
  if (old && remote && old === remote) return console.log('[data] jízdní řády jsou aktuální');
  console.log('[data] stahuji jízdní řády…');
  await build({ tag: remote ?? '' });
}

if (!fs.existsSync(DATA_FILE)) {
  console.log('[data] chybí public/data/timetable.json, sestavuji…');
  refreshData().catch((e) => console.error('[data] sestavení selhalo:', e.message, '\n       spusť ručně: npm run data'));
}
const hours = Number(env.DATA_REFRESH_H ?? 12);
if (hours > 0) setInterval(() => refreshData().catch((e) => console.error('[data]', e.message)), hours * 3600e3).unref();

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  server.listen(PORT, HOST, () => {
    console.log(`Spoje UK web: http://localhost:${PORT}  (${useMock ? 'MOCK polohy' : 'živé polohy'})`);
    if (HOST === '0.0.0.0' || HOST === '::') {
      for (const addresses of Object.values(os.networkInterfaces())) {
        for (const address of addresses ?? []) {
          if (!address.internal && address.family === 'IPv4') {
            console.log(`Pro mobil ve stejné síti: http://${address.address}:${PORT}/app/`);
          }
        }
      }
    }
  });
  for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { feed.stop(); server.close(() => process.exit(0)); });
}
export { server, feed };
