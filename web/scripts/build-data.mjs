#!/usr/bin/env node
// Sestaví public/data/timetable.json z regionálního GTFS (stejný zdroj jako Android appka).
//
//   node scripts/build-data.mjs                       stáhne aktuální release z GitHubu
//   node scripts/build-data.mjs --jdf a.zip --rail b.zip   použije lokální soubory
//   --out <složka>   (výchozí public/data)
//   --base <url>     (výchozí release "gtfs" z Vasik96/spojeuk-data)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildTimetable } from '../lib/build-timetable.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const arg = (name, def) => { const i = process.argv.indexOf(`--${name}`); return i > 0 ? process.argv[i + 1] : def; };
const BASE = arg('base', process.env.GTFS_BASE_URL || 'https://github.com/Vasik96/spojeuk-data/releases/download/gtfs');
const outDir = path.resolve(arg('out', path.join(root, 'public', 'data')));

async function download(url) {
  const res = await fetch(url, { redirect: 'follow', headers: { 'user-agent': 'spojeuk-web/1.0' } });
  if (!res.ok) throw new Error(`${url} → HTTP ${res.status}`);
  return Buffer.from(await res.arrayBuffer());
}

export async function build({ jdf, rail, tag } = {}) {
  const read = async (local, name) => (local ? fs.readFileSync(local) : download(`${BASE}/${name}`));
  const [jdfZip, railZip] = await Promise.all([read(jdf, 'jdf-region.zip'), read(rail, 'czptt-region.zip')]);
  if (tag === undefined) {
    try { tag = JSON.parse((await download(`${BASE}/gtfs.json`)).toString()).tag ?? ''; } catch { tag = ''; }
  }
  const data = buildTimetable([{ zip: jdfZip, rail: false }, { zip: railZip, rail: true }], { tag, log: (s) => console.log(s) });
  fs.mkdirSync(outDir, { recursive: true });
  const json = JSON.stringify(data);
  const tmp = path.join(outDir, 'timetable.json.tmp');
  fs.writeFileSync(tmp, json);
  fs.renameSync(tmp, path.join(outDir, 'timetable.json'));
  fs.writeFileSync(path.join(outDir, 'meta.json'), JSON.stringify({ ...data.meta, bytes: json.length }));
  console.log(`Hotovo: ${data.meta.trips} spojů, ${data.meta.stops} zastávek, ${(json.length / 1e6).toFixed(2)} MB → ${outDir}`);
  return data.meta;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  build({ jdf: arg('jdf'), rail: arg('rail'), tag: arg('tag') }).catch((e) => { console.error('Chyba:', e.message); process.exit(1); });
}
