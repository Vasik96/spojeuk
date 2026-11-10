// Jízdní řád v paměti. Port Timetable.kt: zastávky, místa, spoje, kalendář, pěší přesuny.
import { addDays, distanceM, foldText, normLine, pragueNow, weekday } from './util.js';

export const FOOT_MAX_M = 250;   // max. pěší přesun mezi zastávkami (Config.kt)
export const WALK_MS = 1.1;      // rychlost chůze v m/s

export class Timetable {
  /** @param {object} data obsah timetable.json */
  constructor(data) {
    this.meta = data.meta;
    this.agencies = data.agencies;

    /** @type {{id:number,name:string,place:string,lat:number,lng:number,inreg:boolean}[]} index = id (0 je prázdné) */
    this.stops = [null];
    /** place → id zastávek */
    this.places = new Map();
    data.stops.forEach(([name, place, lat, lng, inreg], i) => {
      const id = i + 1;
      this.stops.push({ id, name, place, lat, lng, inreg: !!inreg, hasCoords: lat !== 0 || lng !== 0 });
      if (!this.places.has(place)) this.places.set(place, []);
      this.places.get(place).push(id);
    });

    /** @type {object[]} index = id spoje (0 je prázdné) */
    this.trips = [null];
    this.tripsByKey = new Map();    // "cis:num" → [tid]
    this.tripsByTrain = new Map();  // číslo vlaku → [tid]
    data.trips.forEach((t, i) => {
      const [line, ai, cis, num, head, svc, wheel, isTrain, arr0, stops, deltas, t0, t1] = t;
      const tid = i + 1;
      const n = stops.length;
      const arr = new Int32Array(n), dep = new Int32Array(n);
      let a = arr0, k = 0;
      for (let j = 0; j < n; j++) {
        if (j > 0) a = dep[j - 1] + deltas[k++];
        arr[j] = a;
        dep[j] = a + deltas[k++];
      }
      const trip = { id: tid, line, agency: data.agencies[ai] || '', cis, num, head, svc, wheel, isTrain: !!isTrain, t0, t1, stops: Int32Array.from(stops), arr, dep };
      this.trips.push(trip);
      if (cis) { const key = `${cis}:${num}`; (this.tripsByKey.get(key) || this.tripsByKey.set(key, []).get(key)).push(tid); }
      if (isTrain && num) (this.tripsByTrain.get(num) || this.tripsByTrain.set(num, []).get(num)).push(tid);
    });

    this.svcs = data.svcs;          // [dow, d0, d1]
    this.svcDates = data.svcDates;  // "YYYYMMDD" → [[svc, type]]
    this._active = new Map();
    this._foot = null;
    this._lineIndex = null;
  }

  /** Množina aktivních služeb v daný den ("YYYYMMDD"). */
  activeSvcs(day) {
    let set = this._active.get(day);
    if (set) return set;
    set = new Set();
    const wd = weekday(day);
    this.svcs.forEach(([dow, d0, d1], si) => {
      if (d0 <= day && day <= d1 && dow[wd] === '1') set.add(si);
    });
    for (const [si, type] of this.svcDates[day] || []) {
      if (type === 1) set.add(si); else if (type === 2) set.delete(si);
    }
    this._active.set(day, set);
    return set;
  }

  /** Pěší přesuny mezi blízkými zastávkami: pole (indexované id) dvojic [id, sekundy]. */
  get foot() {
    if (this._foot) return this._foot;
    const foot = new Array(this.stops.length).fill(null);
    const cells = new Map();
    const key = (a, b) => a * 100000 + b;
    for (let id = 1; id < this.stops.length; id++) {
      const s = this.stops[id];
      if (!s.hasCoords) continue;
      const k = key(Math.floor(s.lat / 0.003), Math.floor(s.lng / 0.0045));
      if (!cells.has(k)) cells.set(k, []);
      cells.get(k).push(id);
    }
    for (let id = 1; id < this.stops.length; id++) {
      const sa = this.stops[id];
      if (!sa.hasCoords) continue;
      const cy = Math.floor(sa.lat / 0.003), cx = Math.floor(sa.lng / 0.0045);
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const list = cells.get(key(cy + dy, cx + dx));
        if (!list) continue;
        for (const b of list) {
          if (b === id) continue;
          const sb = this.stops[b];
          const d = distanceM(sa.lat, sa.lng, sb.lat, sb.lng);
          if (d <= FOOT_MAX_M) (foot[id] ||= []).push([b, Math.max(60, Math.floor(d / WALK_MS))]);
        }
      }
    }
    return (this._foot = foot);
  }

  // ───────── dohledání spoje podle živých dat ─────────

  /** Spoj podle čísla z ČIS (cis + číslo spoje), preferuje instanci nejbližší v čase (dnes / včera). */
  findTripId(cis, num) {
    const c = String(cis ?? '').trim().padStart(6, '0');
    if (c === '000000' || !num) return null;
    const list = this.tripsByKey.get(`${c}:${num}`);
    return list ? this._nearestInTime(list) : null;
  }

  findTrainTripId(trainNumber) {
    const n = String(trainNumber ?? '').trim().replace(/^0+/, '');
    if (!n) return null;
    const list = this.tripsByTrain.get(n);
    return list ? this._nearestInTime(list) : null;
  }

  _nearestInTime(candidates, now = pragueNow()) {
    let best = null, bestGap = Infinity;
    for (let back = 0; back <= 1; back++) {
      const day = addDays(now.day, -back);
      const rel = now.sec + back * 86400;
      const active = this.activeSvcs(day);
      for (const tid of candidates) {
        const t = this.trips[tid];
        if (!active.has(t.svc)) continue;
        const gap = rel < t.t0 ? t.t0 - rel : rel > t.t1 ? rel - t.t1 : 0;
        if (gap < bestGap) { bestGap = gap; best = tid; }
      }
    }
    return bestGap <= 2 * 3600 ? best : null;
  }

  /** Dnešní spoj stejné linky vedoucí nejblíž dané poloze (záloha pro vozy bez použitelného čísla spoje). */
  nearestOnLine(line, lat, lng, now = pragueNow()) {
    if (!this._lineIndex) {
      this._lineIndex = new Map();
      for (let tid = 1; tid < this.trips.length; tid++) {
        const k = normLine(this.trips[tid].line);
        (this._lineIndex.get(k) || this._lineIndex.set(k, []).get(k)).push(tid);
      }
    }
    const active = this.activeSvcs(now.day);
    let best = null, bestDist = Infinity;
    for (const tid of this._lineIndex.get(normLine(line)) || []) {
      const t = this.trips[tid];
      if (!active.has(t.svc) || now.sec < t.t0 - 600 || now.sec > t.t1 + 600) continue;
      for (const sid of t.stops) {
        const s = this.stops[sid];
        if (!s.hasCoords) continue;
        const d = distanceM(lat, lng, s.lat, s.lng);
        if (d < bestDist) { bestDist = d; best = tid; }
      }
    }
    return bestDist <= 400 ? best : null;
  }

  /** Souřadnice zastávek spoje (jen ty s polohou) a kumulativní vzdálenost v metrech. */
  tripPolyline(tid) {
    const t = this.trips[tid];
    const pts = [], idx = [];
    t.stops.forEach((sid, i) => { const s = this.stops[sid]; if (s.hasCoords) { pts.push([s.lat, s.lng]); idx.push(i); } });
    const cum = [0];
    for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + distanceM(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]));
    return { pts, idx, cum };
  }

  // ───────── hledání zastávek (pro napovídání) ─────────

  /** Index míst pro rychlé napovídání: [{ place, ids, norm, words, lat, lng }]. */
  placeIndex() {
    if (this._placeIndex) return this._placeIndex;
    const out = [];
    for (const [place, ids] of this.places) {
      let la = 0, lo = 0, n = 0, inreg = false;
      for (const id of ids) { const s = this.stops[id]; if (s.hasCoords) { la += s.lat; lo += s.lng; n++; } inreg ||= s.inreg; }
      const norm = foldText(place);
      out.push({ place, ids, norm, words: norm.split(/[\s,.\-/]+/).filter(Boolean), lat: n ? la / n : 0, lng: n ? lo / n : 0, hasCoords: n > 0, inreg });
    }
    return (this._placeIndex = out);
  }
}

/** Načte timetable.json (s ohledem na gzip od serveru) a vytvoří Timetable. */
export async function loadTimetable(url = '/data/timetable.json', { onProgress } = {}) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Jízdní řády se nepodařilo načíst (HTTP ${res.status})`);
  onProgress?.(0.5);
  const data = await res.json();
  onProgress?.(0.9);
  return new Timetable(data);
}
