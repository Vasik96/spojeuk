// Minimální čtečka ZIP + CSV bez závislostí (stačí na GTFS z releasu spojeuk-data).
import zlib from 'node:zlib';

/** Přečte ZIP z bufferu a vrátí Map<název souboru, Buffer>. Podporuje stored a deflate. */
export function readZip(buf) {
  // End Of Central Directory
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('Neplatný ZIP (chybí EOCD)');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const out = new Map();
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('Poškozený ZIP (central directory)');
    const method = buf.readUInt16LE(p + 10);
    const csize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    p += 46 + nameLen + extraLen + commentLen;
    if (name.endsWith('/')) continue;
    const lNameLen = buf.readUInt16LE(local + 26);
    const lExtraLen = buf.readUInt16LE(local + 28);
    const start = local + 30 + lNameLen + lExtraLen;
    const raw = buf.subarray(start, start + csize);
    const data = method === 0 ? raw : method === 8 ? zlib.inflateRawSync(raw) : null;
    if (!data) throw new Error(`Nepodporovaná komprese ${method} u ${name}`);
    out.set(name.split('/').pop(), data);
  }
  return out;
}

/** Streamovací CSV parser (RFC 4180: uvozovky, "" jako escapovaná uvozovka). Vrací řádky jako objekty. */
export function* parseCsv(buf) {
  const text = buf.toString('utf8').replace(/^﻿/, '');
  let header = null;
  let i = 0;
  const n = text.length;
  while (i < n) {
    const row = [];
    let eol = false;
    while (!eol) {
      let field;
      if (text.charCodeAt(i) === 34) { // "
        i++;
        let s = '';
        for (;;) {
          const q = text.indexOf('"', i);
          if (q < 0) { s += text.slice(i); i = n; break; }
          s += text.slice(i, q);
          if (text.charCodeAt(q + 1) === 34) { s += '"'; i = q + 2; } else { i = q + 1; break; }
        }
        field = s;
      } else {
        let j = i;
        while (j < n) {
          const c = text.charCodeAt(j);
          if (c === 44 || c === 10 || c === 13) break;
          j++;
        }
        field = text.slice(i, j);
        i = j;
      }
      row.push(field);
      const c = text.charCodeAt(i);
      if (c === 44) { i++; continue; }
      if (c === 13) i++;
      if (text.charCodeAt(i) === 10) i++;
      eol = true;
    }
    if (row.length === 1 && row[0] === '') continue;
    if (!header) { header = row; continue; }
    const o = {};
    for (let k = 0; k < header.length; k++) o[header[k]] = row[k] ?? '';
    yield o;
  }
}

/** "HH:MM:SS" (i >24 h) → sekundy, jinak -1. */
export function parseTime(s) {
  if (!s) return -1;
  const p = s.trim().split(':');
  if (p.length < 2) return -1;
  const h = +p[0], m = +p[1], x = p.length > 2 ? +p[2] : 0;
  return Number.isFinite(h + m + x) ? h * 3600 + m * 60 + x : -1;
}
