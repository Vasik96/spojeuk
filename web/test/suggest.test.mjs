import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { Timetable } from '../public/js/timetable.js';
import { suggest, nearestPlaces } from '../public/js/suggest.js';
import { LiveTracker, progressAlong } from '../public/js/live.js';

const tt = new Timetable(JSON.parse(fs.readFileSync(new URL('../public/data/timetable.json', import.meta.url), 'utf8')));
const index = tt.placeIndex();

test('napovídání: bez diakritiky, použití > vzdálenost', () => {
  const r = suggest(index, 'divadlo');
  assert.ok(r.length > 1);
  assert.ok(r.every((x) => /divadlo/i.test(x.place.normalize('NFD').replace(/[̀-ͯ]/g, ''))));
  // uživatel v Teplicích, ale často jezdí z Ústí: použití vyhrává
  const usti = r.find((x) => x.place.startsWith('Ústí nad Labem,Divadlo')).place;
  const teplice = { lat: 50.64, lng: 13.83 };
  assert.equal(suggest(index, 'divadlo', { usage: { [usti]: 3 }, loc: teplice })[0].place, usti);
  // bez použití rozhoduje vzdálenost
  const near = suggest(index, 'divadlo', { loc: teplice })[0];
  assert.ok(near.dist < 2000, `nejbližší divadlo má být v Teplicích: ${near.place} ${near.dist}`);
});

test('napovídání: prázdný dotaz = oblíbené, pak nejbližší', () => {
  const loc = { lat: 50.6607, lng: 14.0322 };
  const r = suggest(index, '', { usage: { 'Ústí nad Labem,Divadlo': 5 }, loc, limit: 5 });
  assert.equal(r[0].place, 'Ústí nad Labem,Divadlo');
  assert.equal(r.length, 5);
  assert.ok(nearestPlaces(index, loc.lat, loc.lng, 1)[0].dist < 400);
});

test('živá vozidla: přiřazení spoje a příští zastávka', () => {
  const live = new LiveTracker(tt);
  // vezmeme libovolný dnešní autobus a postavíme vozidlo doprostřed mezi 2. a 3. zastávku
  const t = tt.trips.find((x, i) => i && !x.isTrain && x.cis && x.stops.length > 4);
  const a = tt.stops[t.stops[1]], b = tt.stops[t.stops[2]];
  const v = { id: 1, lat: (a.lat + b.lat) / 2, lng: (a.lng + b.lng) / 2, line: t.line, cis: t.cis, trip: t.num, train: false };
  const poly = live.poly(t.id);
  const pr = progressAlong(poly, v.lat, v.lng);
  assert.ok(pr.off < 5, 'bod leží na trase');
  const k = poly.cum.findIndex((c) => c >= pr.pos - 40);
  assert.equal(poly.idx[k], 2, 'příští zastávka je 3. v pořadí');
});
