// Konfigurace webu. Při hostování frontendu odděleně od API nastav API_BASE (např. 'https://api.example.cz').
export const CONFIG = {
  API_BASE: '',
  TIMETABLE_URL: '/data/timetable.json',
  POLL_MS: 8000,
  // Podkladová mapa: OpenStreetMap (bez klíče). Pro vlastní dlaždice změň URL.
  TILES: {
    // CARTO už vyžaduje API klíč, proto OpenStreetMap (bez klíče); tmavý režim řeší DARK_FILTER
    light: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    dark: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
  },
  // záloha, kdyby CARTO dlaždice nešly načíst (třeba blokované blokátorem); klíč není potřeba
  FALLBACK_TILE: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
  DARK_FILTER: 'invert(1) hue-rotate(180deg) brightness(.82) contrast(.9) saturate(.7)',
  ATTRIBUTION: '© OpenStreetMap',
  MAP_CENTER: [50.66, 14.04], // Ústí nad Labem
  LATE_MIN: 3,                // od kolika minut se zpoždění zobrazuje červeně
};
