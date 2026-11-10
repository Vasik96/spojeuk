// Hledání spojení (algoritmus RAPTOR). Port z Net.kt; konstanty jsou stejné jako v appce.
import { addDays, dayDiff, prettyName, stopLabel, distanceM } from './util.js';

export const MIN_TRANSFER = 120;        // min. čas na přestup v s
export const NEXT_DAY_CUTOFF = 6 * 3600; // spoje po půlnoci, které se ještě berou jako "dnešní noc"
const INF = 1_000_000_000;

export const MODES = { ALL: 'all', BUS: 'bus', TRAIN: 'train' };
const accepts = (mode, isTrain) => mode === MODES.ALL || (mode === MODES.TRAIN ? isTrain : !isTrain);

function lowerBound(arr, target) {
  let lo = 0, hi = arr.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (arr[mid] < target) lo = mid + 1; else hi = mid;
  }
  return lo;
}

/** Jízdní řád jednoho dne (včetně přesahu z včerejška a rána zítřka), časy v s od půlnoci dne. */
export class Net {
  constructor(tt, day) {
    this.tt = tt;
    this.day = day;
    const yest = tt.activeSvcs(addDays(day, -1));
    const today = tt.activeSvcs(day);
    const tmrw = tt.activeSvcs(addDays(day, 1));

    const groups = new Map();
    const add = (t, off) => {
      const key = t.stops.join(',');
      let g = groups.get(key);
      if (!g) { g = { stops: t.stops, inst: [] }; groups.set(key, g); }
      const arr = off ? t.arr.map((x) => x + off) : t.arr;
      const dep = off ? t.dep.map((x) => x + off) : t.dep;
      g.inst.push({ de0: dep[0], tid: t.id, arr, dep });
    };
    for (let tid = 1; tid < tt.trips.length; tid++) {
      const t = tt.trips[tid];
      if (yest.has(t.svc) && t.t1 >= 86400) add(t, -86400);
      if (today.has(t.svc)) add(t, 0);
      if (tmrw.has(t.svc) && t.t0 <= NEXT_DAY_CUTOFF) add(t, 86400);
    }

    /** @type {{stops:Int32Array,tids:number[],arr:Int32Array[],dep:Int32Array[],cols:Int32Array[]}[]} */
    this.patterns = [];
    this.stopPats = new Array(tt.stops.length).fill(null);
    for (const g of groups.values()) {
      g.inst.sort((a, b) => a.de0 - b.de0);
      const pi = this.patterns.length;
      const stops = g.stops;
      const cols = Array.from(stops, (_, i) => Int32Array.from(g.inst, (x) => x.dep[i]));
      this.patterns.push({ stops, tids: g.inst.map((x) => x.tid), arr: g.inst.map((x) => x.arr), dep: g.inst.map((x) => x.dep), cols });
      stops.forEach((s, pos) => { (this.stopPats[s] ||= []).push([pi, pos]); });
    }
  }

  /**
   * Nejrychlejší spojení po kolech (k = počet jízd). Vrací nejlepší spojení pro každý počet jízd,
   * které se zlepší oproti předchozím.
   */
  raptor(srcs, dsts, t0, { kmax = 4, mode = MODES.ALL } = {}) {
    const tt = this.tt;
    const N = tt.stops.length;
    const foot = tt.foot;
    const arrs = Array.from({ length: kmax + 1 }, () => new Int32Array(N).fill(INF));
    const par = Array.from({ length: kmax + 1 }, () => new Array(N).fill(null));      // jak jsme se na zastávku dostali (jízdou nebo pěšky)
    const ridePar = Array.from({ length: kmax + 1 }, () => new Array(N).fill(null));  // jen jízda, ze které se smí jít pěšky
    const rideArr = Array.from({ length: kmax + 1 }, () => new Int32Array(N).fill(INF));
    const best = new Int32Array(N).fill(INF);      // nejdřívější příjezd jakkoli (jízdou i pěšky)
    const bestRide = new Int32Array(N).fill(INF);  // nejdřívější příjezd jízdou (z něj se smí jít pěšky dál)
    let marked = new Set();

    for (const s of srcs) { arrs[0][s] = t0; best[s] = t0; marked.add(s); }
    for (const s of srcs) {
      for (const [s2, d] of foot[s] || []) {
        if (t0 + d < best[s2]) {
          arrs[0][s2] = t0 + d; best[s2] = t0 + d;
          par[0][s2] = { walk: true, from: s, dur: d };
          marked.add(s2);
        }
      }
    }

    let target = INF;
    const found = [];
    for (let k = 1; k <= kmax; k++) {
      const queue = new Map();
      for (const s of marked) {
        for (const [pi, pos] of this.stopPats[s] || []) {
          const cur = queue.get(pi);
          if (cur === undefined || pos < cur) queue.set(pi, pos);
        }
      }
      const newMarked = new Set();
      const rideStops = new Set();   // zastávky, kam v tomto kole dojela jízda (z nich se smí jít pěšky)
      const prevArrs = arrs[k - 1], curArrs = arrs[k], curPar = par[k], curRidePar = ridePar[k], curRideArr = rideArr[k];
      const mt = k === 1 ? 0 : MIN_TRANSFER;

      for (const [pi, start] of queue) {
        const pat = this.patterns[pi];
        const stops = pat.stops;
        let trip = -1, board = -1, boardPrev = INF;
        for (let i = start; i < stops.length; i++) {
          const s = stops[i];
          if (trip >= 0) {
            const a = pat.arr[trip][i];
            if (a < bestRide[s] && a < target) {
              const ref = { pi, trip, board, alight: i };
              bestRide[s] = a; curRideArr[s] = a; curRidePar[s] = ref;
              rideStops.add(s);
              if (a < best[s]) { best[s] = a; curArrs[s] = a; curPar[s] = ref; newMarked.add(s); }
            }
          }
          const p = prevArrs[s];
          if (p !== INF) {
            const col = pat.cols[i];
            let j = lowerBound(col, p + mt);
            if (mode !== MODES.ALL) {
              while (j < col.length && !accepts(mode, tt.trips[pat.tids[j]].isTrain)) j++;
            }
            if (j < col.length && (trip < 0 || j < trip || (j === trip && p < boardPrev))) {
              trip = j; board = i; boardPrev = p;
            }
          }
        }
      }

      // pěší přesuny po jízdě: jen jeden krok a jen z místa, kam dojela jízda (nikdy řetězem pěších úseků)
      for (const s of rideStops) {
        const curS = curRideArr[s];
        for (const [s2, d] of foot[s] || []) {
          const a = curS + d;
          if (a < best[s2] && a < target) {
            best[s2] = a; curArrs[s2] = a;
            curPar[s2] = { walk: true, from: s, dur: d };
            newMarked.add(s2);
          }
        }
      }

      marked = newMarked;
      let hit = null;
      for (const d of dsts) if (curArrs[d] !== INF && (!hit || curArrs[d] < hit[0])) hit = [curArrs[d], d];
      if (hit) { target = hit[0]; found.push(this._build(par, ridePar, k, hit[1])); }
      if (marked.size === 0) break;
    }
    return found;
  }

  _build(par, ridePar, startK, targetStop) {
    const legs = [];
    let s = targetStop, k = startK, afterWalk = false;
    for (;;) {
      // za pěším úsekem (při zpětném průchodu) hledáme jízdu, která na výchozí zastávku dovezla
      const p = afterWalk ? ridePar[k][s] : par[k][s];
      afterWalk = false;
      if (!p) break;
      if (p.walk) {
        legs.push({ kind: 'walk', from: p.from, to: s, dep: 0, arr: p.dur });
        s = p.from;
        afterWalk = k > 0;
      } else {
        const pat = this.patterns[p.pi];
        const arr = pat.arr[p.trip], dep = pat.dep[p.trip];
        const stops = [];
        for (let i = p.board; i <= p.alight; i++) stops.push({ stop: pat.stops[i], arr: arr[i], dep: dep[i] });
        legs.push({ kind: 'ride', from: pat.stops[p.board], to: pat.stops[p.alight], dep: dep[p.board], arr: arr[p.alight], tid: pat.tids[p.trip], stops });
        s = pat.stops[p.board];
        k -= 1;
      }
    }
    legs.reverse();

    // časy pěších úseků se dopočítají podle sousedních jízd
    // (pěší úsek za jízdou začíná příjezdem; pěší úsek před první jízdou končí jejím odjezdem;
    //  může jich jít víc za sebou, proto se časy skládají postupně)
    const firstRide = legs.findIndex((l) => l.kind === 'ride');
    for (let i = firstRide - 1; i >= 0; i--) {
      const dur = legs[i].arr;
      legs[i].arr = legs[i + 1].dep;
      legs[i].dep = legs[i].arr - dur;
    }
    for (let i = firstRide + 1; i < legs.length; i++) {
      if (legs[i].kind !== 'walk') continue;
      const dur = legs[i].arr;
      legs[i].dep = legs[i - 1].arr;
      legs[i].arr = legs[i].dep + dur;
    }

    const rides = legs.filter((l) => l.kind === 'ride');
    const dep = legs[0].kind === 'walk' ? legs[0].dep : rides[0].dep;
    const arr = Math.max(...legs.map((l) => l.arr));
    return { day: this.day, dep, arr, legs, transfers: rides.length - 1, key: `${this.day}|${dep}|${arr}|${rides.map((r) => r.tid).join('-')}` };
  }
}

/** Odstraní spojení, která jsou horší než jiné (později nebo stejně odjíždí, dřív nebo stejně dojede, ≤ přestupů). */
export function prune(js) {
  const abs = (j, v) => v + dayDiff(js[0].day, j.day) * 86400;
  const keep = js.filter((j) => !js.some((k) => k !== j
    && abs(k, k.dep) >= abs(j, j.dep) && abs(k, k.arr) <= abs(j, j.arr) && k.transfers <= j.transfers
    && !(abs(k, k.dep) === abs(j, j.dep) && abs(k, k.arr) === abs(j, j.arr) && k.transfers === j.transfers)));
  return keep.sort((a, b) => abs(a, a.dep) - abs(b, b.dep) || abs(a, a.arr) - abs(b, b.arr));
}

export class Planner {
  constructor(tt) {
    this.tt = tt;
    this.nets = new Map();
  }

  net(day) {
    let n = this.nets.get(day);
    if (!n) {
      if (this.nets.size >= 4) this.nets.delete(this.nets.keys().next().value);
      n = new Net(this.tt, day);
      this.nets.set(day, n);
    }
    return n;
  }

  /**
   * @param {string} fromPlace místo odkud (klíč tt.places)
   * @param {string} toPlace   místo kam
   * @param {{day:string, sec:number}} start
   */
  find(fromPlace, toPlace, start, { count = 8, endSec, horizonHours = 14, mode = MODES.ALL } = {}) {
    const tt = this.tt;
    const net = this.net(start.day);
    let t = Math.max(0, start.sec);
    const tEnd = endSec ?? t + horizonHours * 3600;
    const srcs = tt.places.get(fromPlace) || [];
    const dsts = new Set(tt.places.get(toPlace) || []);
    if (!srcs.length || !dsts.size) return [];

    const out = new Map();
    for (let runs = 0; out.size < count && t <= tEnd && runs < 40; runs++) {
      const js = net.raptor(srcs, dsts, t, { mode });
      if (!js.length) break;
      for (const j of js) if (j.dep <= tEnd) out.set(j.key, j);
      t = Math.min(...js.map((j) => j.dep)) + 1;
    }
    const pruned = prune([...out.values()]);
    return mode === MODES.ALL ? pruned
      : pruned.filter((j) => j.legs.every((l) => l.kind !== 'ride' || accepts(mode, tt.trips[l.tid].isTrain)));
  }

  /** Další spojení po posledním z již nalezených. */
  moreLater(fromPlace, toPlace, journeys, { count = 6, mode = MODES.ALL } = {}) {
    if (!journeys.length) return journeys;
    const last = journeys.reduce((a, j) => (j.dep + dayDiff(journeys[0].day, j.day) * 86400 > a.dep + dayDiff(journeys[0].day, a.day) * 86400 ? j : a));
    let day = last.day, sec = last.dep + 60;
    while (sec >= 86400) { sec -= 86400; day = addDays(day, 1); }
    const fresh = this.find(fromPlace, toPlace, { day, sec }, { count, mode });
    const have = new Set(journeys.map((j) => j.key));
    return prune([...journeys, ...fresh.filter((j) => !have.has(j.key))]);
  }

  /** Dřívější spojení před prvním z již nalezených (ve stejném dni). */
  moreEarlier(fromPlace, toPlace, journeys, { count = 6, hours = 2, mode = MODES.ALL } = {}) {
    if (!journeys.length) return journeys;
    const first = journeys.reduce((a, j) => (j.day < a.day || (j.day === a.day && j.dep < a.dep) ? j : a));
    const lo = Math.max(first.dep - hours * 3600, 0);
    const fresh = this.find(fromPlace, toPlace, { day: first.day, sec: lo }, { count: 60, endSec: first.dep - 1, mode })
      .sort((a, b) => a.dep - b.dep).slice(-count);
    const have = new Set(journeys.map((j) => j.key));
    return prune([...journeys, ...fresh.filter((j) => !have.has(j.key))]);
  }
}

// ───────────── popis spojení pro UI ─────────────

/** Doplní spojení údaje pro zobrazení (linky, přestupy, cíl, dopravce, počet zastávek …). */
export function describeJourney(tt, j) {
  const rides = j.legs.filter((l) => l.kind === 'ride');
  const trip = (l) => tt.trips[l.tid];
  const name = (id) => prettyName(tt.stops[id].name);
  const firstWalk = j.legs[0].kind === 'walk' ? j.legs[0] : null;
  const walkMin = (l) => Math.max(1, Math.ceil((l.arr - l.dep) / 60));

  const transferList = rides.slice(0, -1).map((r0, i) => {
    const r1 = rides[i + 1];
    const a = name(r0.to), b = name(r1.from);
    const at = r0.to !== r1.from && stopLabel(a) !== stopLabel(b) ? `${stopLabel(a)} → ${stopLabel(b)}` : stopLabel(a);
    const walkLeg = j.legs.slice(j.legs.indexOf(r0) + 1, j.legs.indexOf(r1)).find((l) => l.kind === 'walk');
    let walk = walkLeg ? walkMin(walkLeg) : null;
    if (walk == null && r0.to !== r1.from) {
      const sa = tt.stops[r0.to], sb = tt.stops[r1.from];
      if (sa.hasCoords && sb.hasCoords) walk = Math.max(1, Math.floor(distanceM(sa.lat, sa.lng, sb.lat, sb.lng) / 80));
    }
    return { at, waitMin: Math.max(0, Math.floor((r1.dep - r0.arr) / 60)), walkMin: walk };
  });

  const first = trip(rides[0]);
  return {
    ...j,
    rides,
    durationMin: Math.max(1, Math.round((j.arr - j.dep) / 60)),
    lines: rides.map((r) => trip(r).line),
    destination: prettyName(trip(rides[rides.length - 1]).head),
    agency: first.agency,
    lowFloor: first.wheel > 0,
    stopsCount: rides.reduce((n, r) => n + Math.max(0, r.stops.length - 1), 0),
    transferList,
    boardWalkMin: firstWalk ? walkMin(firstWalk) : null,
    boardStopName: firstWalk ? name(firstWalk.to) : null,
    trainOnly: rides.every((r) => trip(r).isTrain),
  };
}
