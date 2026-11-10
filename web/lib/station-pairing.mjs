// Párování vlakových stanic (CZPTT) s autobusovými zastávkami před nádražím.
// Port z StationPairing.kt, pravidla jsou stejná jako v appce. Nic se neslučuje: vlakovým
// zastávkám se jen nastaví stejný `place`, takže hledání startuje z obou sad stanovišť.
import { foldText, distanceM } from '../public/js/util.js';

const PAIR_MAX_M = 400;
const PAIR_FALLBACK_M = 250;
const PAIR_AMBIGUOUS_M = 40;
const CELL_DEG = 0.01;

/** Ruční výjimky: klíč = normalizovaný název vlakového místa, hodnota = place autobusového místa, nebo null = nespojovat. */
export const STATION_PAIR_OVERRIDES = new Map([
  ['usti nad labem hlavni nadrazi jih', null],
]);

const STATION_WORDS = [
  'hlavni nadrazi', 'vlakove nadrazi', 'zeleznicni stanice', 'zeleznicni zastavka',
  'hl nadrazi', 'hl nadr', 'hl n',
  'zel stanice', 'zel zastavka', 'zel st', 'zel zast',
  'nadrazi', 'nadr', 'zst',
].sort((a, b) => b.length - a.length);

const QUALIFIERS = new Set([
  'jih', 'sever', 'zapad', 'vychod', 'stred',
  'zastavka', 'predmesti', 'mesto', 'dolni', 'horni',
  'odstavne', 'nakladove', 'seradovaci', 'vlecka', 'depo', 'dilny',
]);

const pairTokens = (s) => foldText(s).split(/[^a-z0-9]+/).filter(Boolean);

function hasStationWord(tokens) {
  const s = ` ${tokens.join(' ')} `;
  return STATION_WORDS.some((w) => s.includes(` ${w} `));
}

function coreTokens(tokens) {
  let s = ` ${tokens.join(' ')} `;
  for (const w of STATION_WORDS) s = s.split(` ${w} `).join(' ');
  return s.split(' ').filter(Boolean);
}

const cellKey = (i, j) => `${i}:${j}`;

/**
 * @param {{id:string,name:string,place:string,lat:number|null,lon:number|null,rail:boolean}[]} stops
 * @returns {Map<string,string>} id vlakové zastávky → place autobusového místa
 */
export function pairRailStations(stops, { overrides = STATION_PAIR_OVERRIDES, log = () => {} } = {}) {
  const busIdx = new Map();
  const buses = [];
  const grid = new Map();
  for (const s of stops) {
    if (s.rail || s.lat == null || s.lon == null) continue;
    let i = busIdx.get(s.place);
    if (i === undefined) {
      const t = pairTokens(s.place);
      const core = coreTokens(t);
      i = buses.length;
      buses.push({ place: s.place, core, named: hasStationWord(t), qual: new Set(core.filter((w) => QUALIFIERS.has(w))) });
      busIdx.set(s.place, i);
    }
    const k = cellKey(Math.floor(s.lat / CELL_DEG), Math.floor(s.lon / CELL_DEG));
    if (!grid.has(k)) grid.set(k, []);
    grid.get(k).push({ bus: i, lat: s.lat, lon: s.lon });
  }

  const out = new Map();
  let paired = 0;
  let ambiguous = 0;
  const railByPlace = new Map();
  for (const s of stops) if (s.rail) {
    if (!railByPlace.has(s.place)) railByPlace.set(s.place, []);
    railByPlace.get(s.place).push(s);
  }

  for (const [rPlace, members] of railByPlace) {
    const rt = pairTokens(rPlace);
    const railKey = rt.join(' ');
    if (overrides.has(railKey)) {
      const target = overrides.get(railKey);
      if (target != null) { for (const m of members) out.set(m.id, target); paired++; }
      continue;
    }
    const rCore = coreTokens(rt);
    if (!rCore.length) continue;
    const rQual = new Set(rCore.filter((w) => QUALIFIERS.has(w)));

    const best = new Map();
    for (const m of members) {
      if (m.lat == null || m.lon == null) continue;
      const ci = Math.floor(m.lat / CELL_DEG), cj = Math.floor(m.lon / CELL_DEG);
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const list = grid.get(cellKey(ci + dy, cj + dx));
        if (!list) continue;
        for (const p of list) {
          const d = distanceM(m.lat, m.lon, p.lat, p.lon);
          if (d > PAIR_MAX_M) continue;
          const old = best.get(p.bus);
          if (old === undefined || d < old) best.set(p.bus, d);
        }
      }
    }

    const sameQual = (a, b) => a.size === b.size && [...a].every((x) => b.has(x));
    const cands = [...best.entries()]
      .map(([i, d]) => [buses[i], d])
      .filter(([b]) => b.core[0] === rCore[0] && sameQual(b.qual, rQual));
    const named = cands.filter(([b]) => b.named).sort((a, b) => a[1] - b[1]);
    const pool = named.length ? named
      : rQual.size === 0 ? cands.filter(([, d]) => d <= PAIR_FALLBACK_M).sort((a, b) => a[1] - b[1])
        : [];
    if (!pool.length) continue;
    if (pool.length > 1 && pool[1][1] - pool[0][1] < PAIR_AMBIGUOUS_M) {
      ambiguous++;
      log(`Párování nádraží: nejednoznačné "${rPlace}" ↔ "${pool[0][0].place}" / "${pool[1][0].place}"`);
      continue;
    }
    const target = pool[0][0].place;
    for (const m of members) out.set(m.id, target);
    paired++;
  }
  log(`Párování nádraží: spárováno ${paired}, nejednoznačných ${ambiguous}, vlakových míst ${railByPlace.size}`);
  return out;
}
