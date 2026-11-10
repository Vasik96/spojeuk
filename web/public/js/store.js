// Úložiště v zařízení (localStorage). Vše je v try/catch: v anonymním okně nebo se zakázanými daty appka dál funguje.
const NS = 'spojeuk:';

function read(key, fallback) {
  try { const v = localStorage.getItem(NS + key); return v == null ? fallback : JSON.parse(v); } catch { return fallback; }
}
function write(key, value) {
  try { localStorage.setItem(NS + key, JSON.stringify(value)); } catch { /* ignore */ }
}

export const store = {
  get: read,
  set: write,

  /** Počty použití zastávek (kolikrát se podle nich hledalo). */
  usage() { return read('usage', {}); },
  recordUsage(place) {
    const u = read('usage', {});
    u[place] = (u[place] || 0) + 1;
    write('usage', u);
  },
  /** Odebere zastávku z často používaných. */
  forgetUsage(place) {
    const u = read('usage', {});
    delete u[place];
    write('usage', u);
  },

  lastTrip() { return read('last', null); },
  saveTrip(from, to) { write('last', { from, to, at: Date.now() }); },

  theme() { const t = read('theme', 'auto'); return ['auto', 'light', 'dark'].includes(t) ? t : 'auto'; },
  setTheme(t) { write('theme', t); },
};
