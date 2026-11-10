import test from 'node:test';
import assert from 'node:assert/strict';
import { Feed, normalize } from '../lib/feed.mjs';

const raw = (o) => ({ ID: 300451, Latitude: 50.66, Longitude: 14.04, State: 0, Delay: 2, LineID: '80', CISLineID: 80, RouteID: '2359', ...o });

test('normalizace: autobus, vlak, nepoužitelné záznamy', () => {
  const bus = normalize(raw({}));
  assert.equal(bus.cis, '000080'); assert.equal(bus.trip, '2359'); assert.equal(bus.city, true); assert.equal(bus.train, false);
  const train = normalize(raw({ ID: 21234, RouteID: ' 1193 ' }));
  assert.equal(train.train, true); assert.equal(train.cis, '1193'); assert.equal(train.trip, '');
  assert.equal(normalize(raw({ State: 255 })), null);
  assert.equal(normalize(raw({ Latitude: 0 })), null);
  assert.equal(normalize(raw({ LineID: '200', qride_linename: undefined })).line, 'X');
  const old = new Date(Date.now() - 20 * 60e3).toISOString();
  assert.equal(normalize(raw({ ID: 100001, LastActivityDT: old })), null, 'regionální vůz starší než 10 min');
  assert.ok(normalize(raw({ LastActivityDT: old })), 'MHD smí být starší (limit 45 min)');
});

test('držení: MHD 5 min, ostatní 3 min, stáří roste', async () => {
  let t = 1_000_000, list = [raw({}), raw({ ID: 100002, LineID: '452' })];
  const f = new Feed({ fetchRaw: async () => ({ VehicleList: list }), now: () => t, pollMs: 10_000 });
  await f.poll();
  assert.equal(f.snapshot().vehicles.length, 2);
  list = []; t += 120_000; await f.poll();
  let s = f.snapshot();
  assert.equal(s.vehicles.length, 2, 'po 2 min se ještě drží');
  assert.equal(s.held, 2);
  assert.ok(s.vehicles.every((v) => v.ageSec >= 120));
  t += 90_000; // 3,5 min
  s = f.snapshot();
  assert.deepEqual(s.vehicles.map((v) => v.id), [300451], 'regionální zmizel, MHD zůstal');
  t += 120_000; // 5,5 min
  assert.equal(f.snapshot().vehicles.length, 0);
});

test('výpadek zdroje se hlásí, chyba nerozbije stav', async () => {
  let fail = false, t = 5_000_000;
  const f = new Feed({ fetchRaw: async () => { if (fail) throw new Error('boom'); return { VehicleList: [raw({})] }; }, now: () => t });
  await f.poll(); assert.equal(f.snapshot().outage, false);
  fail = true; t += 90_000; await f.poll();
  const s = f.snapshot();
  assert.equal(s.outage, true); assert.equal(f.status.failures, 1); assert.equal(s.vehicles.length, 1);
});
