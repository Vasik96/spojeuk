// Service worker: aplikace a jízdní řády fungují offline (živé polohy a dlaždice mapy vždy ze sítě).
const VERSION = 'v2';
const CACHE = `spojeuk-${VERSION}`;
const SHELL = [
  '/app/', '/css/base.css', '/css/app.css', '/assets/icons.svg', '/assets/logo.svg', '/assets/favicon.svg', '/manifest.webmanifest',
  '/js/app.js', '/js/config.js', '/js/util.js', '/js/timetable.js', '/js/planner.js', '/js/suggest.js', '/js/live.js', '/js/map.js', '/js/store.js', '/js/theme-init.js',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin || url.pathname.startsWith('/api/')) return;
  // stale-while-revalidate: rychle z cache, na pozadí aktualizace
  e.respondWith(caches.open(CACHE).then(async (cache) => {
    const hit = await cache.match(req, { ignoreSearch: true });
    const net = fetch(req).then((res) => { if (res.ok) cache.put(req, res.clone()); return res; }).catch(() => hit);
    return hit || net;
  }));
});
