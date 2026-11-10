import test from 'node:test';
import assert from 'node:assert/strict';
import { project, unproject, worldSize } from '../public/js/map.js';

test('projekce: známé dlaždice a zpětná transformace', () => {
  // Ústí nad Labem (50.66, 14.04) leží na z=10 v dlaždici 551/344 (nezávisle spočteno OSM vzorcem)
  const p = project(50.66, 14.04, 10);
  assert.equal(Math.floor(p.x / 256), 551);
  assert.equal(Math.floor(p.y / 256), 344);
  // roh světa
  assert.deepEqual(project(0, 0, 0), { x: 128, y: 128 });
  assert.equal(worldSize(3), 2048);
});

test('projekce: roundtrip na všech zoomech', () => {
  for (const z of [0, 5, 10, 14, 18]) for (const [la, lo] of [[50.66, 14.04], [-33.9, 151.2], [64.1, -21.9], [0, 179.9]]) {
    const q = unproject(...Object.values(project(la, lo, z)), z);
    assert.ok(Math.abs(q.lat - la) < 1e-9 && Math.abs(q.lng - lo) < 1e-9, `${la},${lo}@${z}`);
  }
});
