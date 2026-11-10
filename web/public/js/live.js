// Živá vozidla: stahování z API, přiřazení ke spoji z jízdního řádu, příští a konečná zastávka.
import { CONFIG } from './config.js';
import { distanceM, M_PER_DEG_LAT, M_PER_DEG_LNG, prettyName, pragueNow } from './util.js';

export async function fetchVehicles() {
  const res = await fetch(`${CONFIG.API_BASE}/api/vehicles`, { cache: 'no-store' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export const vehicleUid = (v) => (v.id ? `v${v.id}` : `p${v.lat.toFixed(4)},${v.lng.toFixed(4)}`);

/** Vzdálenost bodu od začátku lomené čáry spoje (m) podél trasy. */
export function progressAlong(poly, lat, lng) {
  if (poly.pts.length < 2) return null;
  const cosLat = Math.cos((lat * Math.PI) / 180);
  let bestD = Infinity, bestPos = 0;
  for (let i = 0; i < poly.pts.length - 1; i++) {
    const ax = (poly.pts[i][1] - lng) * M_PER_DEG_LNG * cosLat, ay = (poly.pts[i][0] - lat) * M_PER_DEG_LAT;
    const dx = (poly.pts[i + 1][1] - lng) * M_PER_DEG_LNG * cosLat - ax, dy = (poly.pts[i + 1][0] - lat) * M_PER_DEG_LAT - ay;
    const len2 = dx * dx + dy * dy;
    const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, (-ax * dx - ay * dy) / len2));
    const d = Math.hypot(ax + t * dx, ay + t * dy);
    if (d < bestD) { bestD = d; bestPos = poly.cum[i] + t * (poly.cum[i + 1] - poly.cum[i]); }
  }
  return { pos: bestPos, off: bestD };
}

export class LiveTracker {
  constructor(tt) {
    this.tt = tt;
    this.polys = new Map();
    this.byTrip = new Map(); // "cis:trip" nebo číslo vlaku → vozidlo (pro zpoždění ve výsledcích)
  }

  poly(tid) {
    let p = this.polys.get(tid);
    if (!p) { p = this.tt.tripPolyline(tid); this.polys.set(tid, p); }
    return p;
  }

  /** Najde spoj vozidla v jízdním řádu (přesně podle čísla, jinak podle polohy na lince). */
  matchTrip(v, now = pragueNow()) {
    const tt = this.tt;
    const exact = v.train ? tt.findTrainTripId(v.cis) : tt.findTripId(v.cis, v.trip);
    if (exact) return { tid: exact, exact: true };
    const near = tt.nearestOnLine(v.line, v.lat, v.lng, now);
    return near ? { tid: near, exact: false } : null;
  }

  /** Přepočítá index zpoždění pro výsledky hledání. */
  update(vehicles) {
    this.byTrip.clear();
    for (const v of vehicles) {
      if (v.train) { if (v.cis) this.byTrip.set(`train:${v.cis.replace(/^0+/, '')}`, v); }
      else if (v.cis !== '000000' && v.trip) this.byTrip.set(`${v.cis}:${v.trip}`, v);
    }
  }

  /** Živé vozidlo, které jede daný spoj (nebo undefined). */
  forTrip(tid) {
    const t = this.tt.trips[tid];
    return t.isTrain ? this.byTrip.get(`train:${t.num}`) : t.cis ? this.byTrip.get(`${t.cis}:${t.num}`) : undefined;
  }

  /** Podrobnosti o vozidle: spoj, příští a konečná zastávka, zbývající zastávky. */
  detail(v) {
    const m = this.matchTrip(v);
    if (!m) return { trip: null };
    const tt = this.tt, t = tt.trips[m.tid];
    const name = (sid) => prettyName(tt.stops[sid].name);
    const poly = this.poly(m.tid);
    const pr = progressAlong(poly, v.lat, v.lng);
    let nextIdx = null;
    if (pr && pr.off < 600) {
      const k = poly.cum.findIndex((c) => c >= pr.pos - 40);
      nextIdx = poly.idx[k < 0 ? poly.idx.length - 1 : k];
    }
    return {
      trip: t, tid: m.tid, exact: m.exact,
      terminal: name(t.stops[t.stops.length - 1]),
      next: nextIdx != null ? name(t.stops[nextIdx]) : null,
      route: poly.pts,
      stops: Array.from(t.stops, (sid, i) => ({ id: sid, name: name(sid), lat: tt.stops[sid].lat, lng: tt.stops[sid].lng, arr: t.arr[i], dep: t.dep[i], has: tt.stops[sid].hasCoords })),
      nextIdx,
    };
  }
}

export { distanceM };
