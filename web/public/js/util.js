// Sdílené pomocné funkce (běží v prohlížeči i v Node). Port z Util.kt / Formatting.kt.

// ───────────── text ─────────────

/** Malá písmena bez diakritiky: "Ústí" → "usti". */
export function foldText(s) {
  return String(s ?? '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
}

/** "Ústí nad Labem,Divadlo" → "Ústí nad Labem, Divadlo" */
export function prettyName(name) {
  return String(name ?? '').replace(/,(?=\S)/g, ', ').replace(/null/g, '').trim();
}

/** "Ústí nad Labem, Divadlo" → { stop: "Divadlo", city: "Ústí nad Labem" } */
export function splitStopName(name) {
  const i = name.indexOf(',');
  return i < 0 ? { stop: name.trim(), city: '' } : { stop: name.slice(i + 1).trim(), city: name.slice(0, i).trim() };
}

export function stopLabel(name) {
  return splitStopName(name).stop || name;
}

/** Linka pro porovnávání: "070" == "70". */
export function normLine(s) {
  const t = String(s ?? '').trim();
  return /^\d+$/.test(t) ? String(parseInt(t, 10)) : foldText(t);
}

export function pluralTransfers(n) {
  if (n === 0) return 'přímý spoj';
  return `${n} ${n === 1 ? 'přestup' : n <= 4 ? 'přestupy' : 'přestupů'}`;
}

export function pluralStops(n) {
  return `${n} ${n === 1 ? 'zastávka' : n >= 2 && n <= 4 ? 'zastávky' : 'zastávek'}`;
}

export const NBSP = ' ';

export function fmtMinutes(m) {
  if (m < 60) return `${m}${NBSP}min`;
  if (m % 60 === 0) return `${m / 60}${NBSP}h`;
  return `${Math.floor(m / 60)}${NBSP}h${NBSP}${m % 60}${NBSP}min`;
}

export function fmtDist(m) {
  return m < 1000 ? `${Math.round(m)}${NBSP}m` : `${(m / 1000).toFixed(1).replace('.', ',')}${NBSP}km`;
}

function fmtAge(s) {
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min ${s % 60} s`;
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
}

export function ageLabel(s) {
  if (s <= 10) return 'Aktuální poloha';
  if (s <= 40) return `Poloha před ${fmtAge(s)}`;
  return `Poloha stará ${fmtAge(s)}. Zdroj teď vozidlo nedodává.`;
}

// ───────────── čas (vždy pražský) ─────────────

const pragueFmt = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/Prague', hourCycle: 'h23',
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
});

/** Aktuální pražský čas: { day: "20261109", sec: sekundy od půlnoci }. */
export function pragueNow(ms = Date.now()) {
  const p = {};
  for (const { type, value } of pragueFmt.formatToParts(new Date(ms))) p[type] = value;
  return { day: `${p.year}${p.month}${p.day}`, sec: (+p.hour) * 3600 + (+p.minute) * 60 + (+p.second) };
}

/** "20261109" → UTC timestamp půlnoci (jen pro počítání dnů, nezávisí na časové zóně uživatele). */
function dayToUtc(day) {
  return Date.UTC(+day.slice(0, 4), +day.slice(4, 6) - 1, +day.slice(6, 8));
}

export function addDays(day, n) {
  const d = new Date(dayToUtc(day) + n * 86400000);
  return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}${String(d.getUTCDate()).padStart(2, '0')}`;
}

/** Den v týdnu, pondělí = 0 … neděle = 6. */
export function weekday(day) {
  return (new Date(dayToUtc(day)).getUTCDay() + 6) % 7;
}

/** Počet dní mezi dvěma dny (b - a). */
export function dayDiff(a, b) {
  return Math.round((dayToUtc(b) - dayToUtc(a)) / 86400000);
}

export function hhmm(sec) {
  const s = ((Math.floor(sec) % 86400) + 86400) % 86400;
  return `${String(Math.floor(s / 3600)).padStart(2, '0')}:${String(Math.floor((s % 3600) / 60)).padStart(2, '0')}`;
}

export const DAY_NAMES = ['pondělí', 'úterý', 'středa', 'čtvrtek', 'pátek', 'sobota', 'neděle'];

/** "20261109" → "9. 11." */
export function fmtDayShort(day) {
  return `${+day.slice(6, 8)}. ${+day.slice(4, 6)}.`;
}

export function dayToIso(day) {
  return `${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6, 8)}`;
}

export function isoToDay(iso) {
  return iso.replaceAll('-', '');
}

// ───────────── geometrie ─────────────

export const M_PER_DEG_LAT = 110540;
export const M_PER_DEG_LNG = 111320;

/** Vzdálenost v metrech (ploché přiblížení, na škále kraje stačí). */
export function distanceM(la1, lo1, la2, lo2) {
  return Math.hypot((lo2 - lo1) * M_PER_DEG_LNG * Math.cos((la1 * Math.PI) / 180), (la2 - la1) * M_PER_DEG_LAT);
}
