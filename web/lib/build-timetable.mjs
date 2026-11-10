// GTFS (autobusy z CIS JŘ + vlaky z CZPTT) → jeden kompaktní JSON pro prohlížeč.
// Logika odpovídá GtfsImporter v appce (Data.kt): stejné rozpoznání cis/čísla spoje, oblasti, kalendáře a párování nádraží.
import { readZip, parseCsv, parseTime } from './gtfs.mjs';
import { pairRailStations } from './station-pairing.mjs';

export const BBOX = { lat: [50.45, 50.85], lon: [13.75, 14.40] };       // autobusy (Config.kt)
export const RAIL_BBOX = { lat: [50.10, 51.10], lon: [12.90, 14.60] };  // vlaky

const inBox = (lat, lon, b) => lat != null && lon != null && lat >= b.lat[0] && lat <= b.lat[1] && lon >= b.lon[0] && lon <= b.lon[1];
const num = (s) => (s === '' || s == null ? null : Number.isFinite(+s) ? +s : null);

/**
 * @param {{zip: Buffer, rail: boolean}[]} feeds
 * @param {{tag?: string, log?: (s:string)=>void}} opts
 */
export function buildTimetable(feeds, { tag = '', log = () => {} } = {}) {
  const stations = new Map();   // stop_id → {name, lat, lon}
  const posts = new Map();      // stop_id → {parent, name, lat, lon}
  const region = new Set();
  const railIds = new Set();
  const stopCode = new Map();   // stop_id → db id (1-based), v pořadí prvního použití
  const stopOrder = [];
  const svcIndex = new Map();   // service_id → index
  const svcDefs = [];           // [dow, d0, d1]
  const agencies = [];
  const agencyIdx = new Map();
  const trips = [];

  const coords = (g) => {
    const p = posts.get(g);
    if (!p) return [null, null];
    if (p.lat != null && p.lon != null) return [p.lat, p.lon];
    const par = stations.get(p.parent);
    return [par?.lat ?? null, par?.lon ?? null];
  };
  const dbId = (g) => {
    let id = stopCode.get(g);
    if (!id) { stopOrder.push(g); id = stopOrder.length; stopCode.set(g, id); }
    return id;
  };

  const calendars = [];       // odložit, až budou známé použité služby
  const calendarDates = [];

  for (const [fi, feed] of feeds.entries()) {
    const rail = !!feed.rail;
    const box = rail ? RAIL_BBOX : BBOX;
    const files = readZip(feed.zip);
    const csv = (name) => (files.has(name) ? parseCsv(files.get(name)) : []);
    log(`Feed ${fi + 1}/${feeds.length} (${rail ? 'vlaky' : 'autobusy'}): čtu zastávky…`);

    for (const r of csv('stops.txt')) {
      const id = r.stop_id;
      if (!id) continue;
      if (rail) railIds.add(id);
      const lat = num(r.stop_lat), lon = num(r.stop_lon);
      if ((r.location_type || '0') === '1') stations.set(id, { name: r.stop_name, lat, lon });
      else posts.set(id, { parent: r.parent_station, name: r.stop_name, lat, lon });
    }
    for (const g of posts.keys()) {
      if (region.has(g)) continue;
      const [la, lo] = coords(g);
      if (inBox(la, lo, box)) region.add(g);
    }

    const agencyName = new Map();
    for (const r of csv('agency.txt')) agencyName.set(r.agency_id, r.agency_name);
    const routes = new Map();
    for (const r of csv('routes.txt')) {
      if (r.route_id) routes.set(r.route_id, { short: r.route_short_name || '?', agency: agencyName.get(r.agency_id) || '' });
    }
    const tripsMeta = new Map();
    for (const r of csv('trips.txt')) {
      if (r.trip_id) tripsMeta.set(r.trip_id, { route: r.route_id, svc: r.service_id, head: r.trip_headsign, short: r.trip_short_name, wheel: parseInt(r.wheelchair_accessible, 10) || 0 });
    }

    // stop_times seskupené podle spoje (zachová pořadí prvního výskytu)
    log('Zpracovávám jízdní řády…');
    const byTrip = new Map();
    for (const r of csv('stop_times.txt')) {
      const id = r.trip_id;
      if (!id || !r.stop_id) continue;
      let list = byTrip.get(id);
      if (!list) { list = []; byTrip.set(id, list); }
      list.push([parseInt(r.stop_sequence, 10) || 0, r.stop_id, parseTime(r.arrival_time), parseTime(r.departure_time)]);
    }

    for (const [tripId, rows] of byTrip) {
      const meta = tripsMeta.get(tripId);
      if (!meta || rows.length < 2) continue;
      if (!rows.some((r) => region.has(r[1]))) continue;
      let sorted = true;
      for (let i = 1; i < rows.length; i++) if (rows[i][0] < rows[i - 1][0]) { sorted = false; break; }
      if (!sorted) rows.sort((a, b) => a[0] - b[0]);
      const kept = rows.filter((r) => r[2] >= 0 || r[3] >= 0);
      if (kept.length < 2) continue;

      let cis, number;
      if (rail) {
        cis = '';
        number = (meta.short.split(' ').pop() || '').replace(/\D/g, '').replace(/^0+/, '');
      } else {
        const sn = meta.short.trim().split(/\s+/);
        if (sn.length === 2 && /^\d+$/.test(sn[0])) { cis = sn[0].padStart(6, '0'); number = sn[1]; }
        else {
          const p = tripId.split(':');
          if (p.length >= 5) { cis = p[2].padStart(6, '0'); number = p[p.length - 1]; } else { cis = ''; number = ''; }
        }
      }

      const route = routes.get(meta.route) || { short: '?', agency: '' };
      let ai = agencyIdx.get(route.agency);
      if (ai === undefined) { ai = agencies.length; agencies.push(route.agency); agencyIdx.set(route.agency, ai); }
      const svcKey = meta.svc;
      let si = svcIndex.get(svcKey);
      if (si === undefined) { si = svcIndex.size; svcIndex.set(svcKey, si); }

      const stopIds = [], times = [];
      let prevDep = 0;
      const first = kept[0];
      const arr0 = first[2] >= 0 ? first[2] : first[3];
      let t1 = 0;
      kept.forEach((r, i) => {
        const a = r[2] >= 0 ? r[2] : r[3];
        const d = r[3] >= 0 ? r[3] : r[2];
        stopIds.push(dbId(r[1]));
        // [odjezd0-příjezd0, příjezd1-odjezd0, odjezd1-příjezd1, …]
        if (i > 0) times.push(a - prevDep);
        times.push(d - a);
        prevDep = d;
        t1 = a;
      });
      const t0 = first[3] >= 0 ? first[3] : first[2];
      trips.push([route.short, ai, cis, number, meta.head || '', si, meta.wheel, tripId.startsWith('czptt:') ? 1 : 0, arr0, stopIds, times, t0, t1]);
    }

    calendars.push(csv('calendar.txt'));
    calendarDates.push(csv('calendar_dates.txt'));
    log(`Feed ${fi + 1}/${feeds.length} hotový, spojů celkem: ${trips.length}`);
  }

  // kalendář jen pro použité služby
  for (const rows of calendars) for (const r of rows) {
    const si = svcIndex.get(r.service_id);
    if (si === undefined) continue;
    svcDefs[si] = [['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'].map((d) => r[d] || '0').join(''), r.start_date, r.end_date];
  }
  const svcDates = {};
  for (const rows of calendarDates) for (const r of rows) {
    const si = svcIndex.get(r.service_id);
    if (si === undefined) continue;
    (svcDates[r.date] ||= []).push([si, parseInt(r.exception_type, 10) || 1]);
  }
  for (let i = 0; i < svcIndex.size; i++) svcDefs[i] ||= ['0000000', '0', '0']; // služba jen z výjimek

  // párování nádraží
  const pairInput = stopOrder.map((g) => {
    const post = posts.get(g);
    const parentName = post ? stations.get(post.parent)?.name : null;
    const name = post?.name ?? g;
    const [la, lo] = coords(g);
    return { id: g, name, place: parentName || name, lat: la, lon: lo, rail: railIds.has(g) };
  });
  const paired = pairRailStations(pairInput, { log });

  const stops = stopOrder.map((g) => {
    const post = posts.get(g);
    const parentName = post ? stations.get(post.parent)?.name : null;
    const pr = paired.get(g);
    const name = pr ?? post?.name ?? g;
    const place = pr ?? (parentName || name);
    const [la, lo] = coords(g);
    return [name, place, la == null ? 0 : +la.toFixed(5), lo == null ? 0 : +lo.toFixed(5), region.has(g) ? 1 : 0];
  });

  return {
    v: 1,
    meta: { tag, built: new Date().toISOString(), trips: trips.length, stops: stops.length },
    agencies, stops, trips, svcs: svcDefs, svcDates,
  };
}
