import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Timetable } from '../public/js/timetable.js';
import { Planner, Net, MIN_TRANSFER, describeJourney, MODES } from '../public/js/planner.js';
import { foldText, addDays, weekday, hhmm } from '../public/js/util.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dataFile = path.join(root, 'public/data/timetable.json');
if (!fs.existsSync(dataFile)) throw new Error('Chybí public/data/timetable.json (spusť npm run data)');
const tt = new Timetable(JSON.parse(fs.readFileSync(dataFile, 'utf8')));
const planner = new Planner(tt);

// pondělí, den s běžným provozem
const DAY = (() => { let d = '20261109'; while (weekday(d) !== 0) d = addDays(d, 1); return d; })();

const findPlace = (city, stop) => [...tt.places.keys()].find((p) => foldText(p).startsWith(foldText(`${city},${stop}`)));

test('kalendář a načtení dat', () => {
  assert.ok(tt.trips.length > 10000, 'málo spojů');
  assert.ok(tt.activeSvcs(DAY).size > 20, 'málo aktivních služeb v pracovní den');
  // dekódování časů: monotónní příjezdy/odjezdy
  for (let tid = 1; tid < tt.trips.length; tid += 7) {
    const t = tt.trips[tid];
    for (let i = 0; i < t.stops.length; i++) {
      assert.ok(t.dep[i] >= t.arr[i], `spoj ${tid}: odjezd před příjezdem`);
      if (i) assert.ok(t.arr[i] >= t.dep[i - 1], `spoj ${tid}: příjezd před odjezdem z předchozí`);
    }
    assert.equal(t.t0, t.dep[0]);
    assert.equal(t.t1, t.arr[t.arr.length - 1]);
  }
});

test('Divadlo → Třebízského (příklad z appky)', () => {
  const from = findPlace('Ústí nad Labem', 'Divadlo');
  const to = findPlace('Ústí nad Labem', 'Třebízského');
  assert.ok(from && to, `nenalezeno: ${from} / ${to}`);
  const js = planner.find(from, to, { day: DAY, sec: 12 * 3600 });
  assert.ok(js.length >= 4, `čekáno aspoň 4 spojení, je ${js.length}`);
  const d = js.map((j) => describeJourney(tt, j));
  console.log('  ', d.slice(0, 4).map((j) => `${hhmm(j.dep)}→${hhmm(j.arr)} [${j.lines.join('+')}] ${j.transfers} př.`).join(' | '));
  for (let i = 1; i < js.length; i++) assert.ok(js[i].dep >= js[i - 1].dep, 'výsledky nejsou seřazené');
  assert.ok(d.some((j) => j.transfers === 0), 'v rámci města má být přímý spoj');
});

/** Ověří, že spojení je skutečně proveditelné podle jízdního řádu. */
function validate(j, day) {
  const rides = j.legs.filter((l) => l.kind === 'ride');
  assert.ok(rides.length >= 1);
  let prev = null;
  for (const l of j.legs) {
    assert.ok(l.arr >= l.dep, 'záporná délka úseku');
    if (prev) assert.ok(l.dep >= prev.arr - 1, `úseky se překrývají (${prev.arr} → ${l.dep})`);
    if (l.kind === 'ride') {
      const t = tt.trips[l.tid];
      // úsek musí přesně odpovídat souvislé části spoje v jízdním řádu (včetně časů, kromě posunu o den)
      let bi = -1;
      for (let i = 0; i + l.stops.length <= t.stops.length && bi < 0; i++) {
        const shift = l.stops[0].dep - t.dep[i];
        if (t.stops[i] !== l.from || ![-86400, 0, 86400].includes(shift)) continue;
        if (l.stops.every((x, k) => t.stops[i + k] === x.stop && t.arr[i + k] + shift === x.arr && t.dep[i + k] + shift === x.dep)) bi = i;
      }
      assert.ok(bi >= 0, `úsek spoje ${l.tid} neodpovídá jízdnímu řádu`);
      const shift = l.stops[0].dep - t.dep[bi];
      // offset −86400 = spoj z včerejška přesahující půlnoc, +86400 = spoj zítřka brzy ráno
      assert.ok(tt.activeSvcs(addDays(day, shift / 86400)).has(t.svc), 'spoj v ten den nejede');
    }
    prev = l;
  }
  // přestupy: mezi dvěma jízdami aspoň MIN_TRANSFER
  rides.slice(1).forEach((r, i) => assert.ok(r.dep - rides[i].arr >= MIN_TRANSFER, `krátký přestup ${r.dep - rides[i].arr}s`));
  assert.equal(j.arr, Math.max(...j.legs.map((l) => l.arr)));
}

// ───────── nezávislá kontrola: Connection Scan Algorithm ─────────

function csaEarliest(net, srcs, dsts, t0) {
  const N = tt.stops.length;
  const INF = 1e9;
  const conns = [];
  net.patterns.forEach((p, pi) => {
    for (let k = 0; k < p.tids.length; k++) {
      const id = pi * 1e5 + k;
      for (let i = 0; i + 1 < p.stops.length; i++) conns.push([p.dep[k][i], p.arr[k][i + 1], p.stops[i], p.stops[i + 1], id]);
    }
  });
  conns.sort((a, b) => a[0] - b[0]);
  const ready = new Float64Array(N).fill(INF);  // kdy se dá nastoupit
  const arrive = new Float64Array(N).fill(INF); // nejdřívější příjezd
  const foot = tt.foot;
  const origin = new Set(srcs);
  const first = new Float64Array(N).fill(INF);  // nástup bez povinného přestupu (jen z počátku)
  for (const s of srcs) { first[s] = t0; arrive[s] = t0; for (const [n, d] of foot[s] || []) { first[n] = Math.min(first[n], t0 + d); arrive[n] = Math.min(arrive[n], t0 + d); } }
  const on = new Set();
  for (const [dep, arr, from, to, trip] of conns) {
    if (!on.has(trip) && (first[from] <= dep || ready[from] <= dep)) on.add(trip);
    if (!on.has(trip)) continue;
    if (arr < arrive[to]) arrive[to] = arr;
    ready[to] = Math.min(ready[to], arr + MIN_TRANSFER);
    for (const [n, d] of foot[to] || []) {
      if (arr + d < arrive[n]) arrive[n] = arr + d;
      ready[n] = Math.min(ready[n], arr + d + MIN_TRANSFER);
    }
  }
  let b = INF;
  for (const d of dsts) b = Math.min(b, arrive[d]);
  return b;
}

test('RAPTOR vs. Connection Scan: stejný nejdřívější příjezd, platná spojení', () => {
  let seed = 12345;
  const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32);
  const places = [...tt.places.entries()].filter(([, ids]) => ids.some((id) => tt.stops[id].inreg));
  const net = planner.net(DAY);
  let compared = 0, equal = 0, limited = 0, noRoute = 0;
  for (let n = 0; n < 250; n++) {
    const [fp, fi] = places[Math.floor(rnd() * places.length)];
    const [tp, ti] = places[Math.floor(rnd() * places.length)];
    if (fp === tp) continue;
    const t0 = Math.floor((5 + rnd() * 16) * 3600);
    const js = net.raptor(fi, new Set(ti), t0);
    const csa = csaEarliest(net, fi, ti, t0);
    js.forEach((j) => validate(j, DAY));
    const r = js.length ? Math.min(...js.map((j) => j.arr)) : Infinity;
    if (csa >= 1e9) { assert.equal(r, Infinity, 'RAPTOR našel spojení, které CSA nezná'); noRoute++; continue; }
    assert.ok(r >= csa - 0.5, `RAPTOR (${hhmm(r)}) je rychlejší než CSA (${hhmm(csa)}) pro ${fp} → ${tp}: nemožné`);
    compared++;
    if (Math.abs(r - csa) < 0.5) { equal++; continue; }
    // s limitem 4 jízd (jako v appce) se nemusí dojet nejrychleji; bez limitu musí RAPTOR sedět přesně
    const unl = net.raptor(fi, new Set(ti), t0, { kmax: 14 });
    unl.forEach((j) => validate(j, DAY));
    const ru = Math.min(...unl.map((j) => j.arr));
    assert.ok(Math.abs(ru - csa) < 0.5, `ani bez limitu jízd nesedí: ${fp} → ${tp} @${hhmm(t0)}: RAPTOR ${hhmm(ru)}, CSA ${hhmm(csa)}`);
    limited++;
  }
  console.log(`   porovnáno ${compared}, shodných ${equal}, shodných až bez limitu 4 jízd ${limited}, bez spojení ${noRoute}`);
  assert.ok(compared >= 100, 'málo srovnatelných dvojic');
  assert.ok(equal / compared >= 0.85, `s limitem 4 jízd shoda jen ${(100 * equal / compared).toFixed(1)} %`);
});

test('filtr Vlaky / Autobusy a stránkování', () => {
  const from = findPlace('Ústí nad Labem', 'hl.n.') || [...tt.places.keys()].find((p) => /Ústí nad Labem.*hl/i.test(p));
  const to = [...tt.places.keys()].find((p) => /^Praha[ ,]/.test(p) && /hl/i.test(p));
  if (!from || !to) return;
  const trains = planner.find(from, to, { day: DAY, sec: 8 * 3600 }, { mode: MODES.TRAIN });
  for (const j of trains) j.legs.filter((l) => l.kind === 'ride').forEach((l) => assert.ok(tt.trips[l.tid].isTrain));
  const base = planner.find(from, to, { day: DAY, sec: 8 * 3600 }, { count: 3 });
  const more = planner.moreLater(from, to, base);
  assert.ok(more.length >= base.length);
  const earlier = planner.moreEarlier(from, to, more);
  for (let i = 1; i < earlier.length; i++) assert.ok(earlier[i].dep >= earlier[i - 1].dep);
});
