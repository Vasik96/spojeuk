// Chytré napovídání zastávek. Řazení: 1. podle používání, 2. podle vzdálenosti od uživatele, 3. zbytek.
import { foldText, distanceM, splitStopName } from './util.js';

/**
 * @param {{place:string,norm:string,words:string[],lat:number,lng:number,hasCoords:boolean}[]} index
 * @param {string} query
 * @param {{usage?:Record<string,number>, loc?:{lat:number,lng:number}|null, limit?:number}} opts
 */
export function suggest(index, query, { usage = {}, loc = null, limit = 8 } = {}) {
  const q = foldText(query).trim();
  const words = q.split(/[\s,.\-/]+/).filter(Boolean);
  const dist = (p) => (loc && p.hasCoords ? distanceM(loc.lat, loc.lng, p.lat, p.lng) : Infinity);

  let pool;
  if (!words.length) {
    // bez dotazu: nejdřív často používané, potom nejbližší
    const used = index.filter((p) => usage[p.place] > 0).sort((a, b) => usage[b.place] - usage[a.place]).slice(0, limit);
    const near = loc ? index.filter((p) => p.hasCoords && p.inreg && !usage[p.place]).sort((a, b) => dist(a) - dist(b)).slice(0, Math.max(0, limit - used.length)) : [];
    return [...used, ...near].map((p) => ({ place: p.place, dist: dist(p), used: usage[p.place] || 0 }));
  }

  pool = [];
  for (const p of index) {
    // každé slovo dotazu musí být začátkem některého slova názvu (nebo podřetězcem, ale s nižší prioritou)
    let prefixAll = true, subAll = true;
    for (const w of words) {
      if (!p.words.some((x) => x.startsWith(w))) prefixAll = false;
      if (!p.norm.includes(w)) subAll = false;
      if (!subAll) break;
    }
    if (!subAll) continue;
    const { stop } = splitStopName(p.place);
    const stopNorm = foldText(stop);
    const exact = stopNorm === q ? 0 : stopNorm.startsWith(q) ? 1 : prefixAll ? 2 : 3;
    pool.push({ p, exact, d: dist(p), u: usage[p.place] || 0 });
  }
  pool.sort((a, b) => (b.u > 0) - (a.u > 0) || b.u - a.u || a.exact - b.exact || a.d - b.d || a.p.place.localeCompare(b.p.place, 'cs'));
  return pool.slice(0, limit).map(({ p, d, u }) => ({ place: p.place, dist: d, used: u }));
}

/** Nejbližší místo (a vzdálenost v m) k dané poloze. */
export function nearestPlaces(index, lat, lng, limit = 5) {
  return index.filter((p) => p.hasCoords)
    .map((p) => ({ place: p.place, dist: distanceM(lat, lng, p.lat, p.lng) }))
    .sort((a, b) => a.dist - b.dist).slice(0, limit);
}
