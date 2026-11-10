// Vývojový zdroj poloh: z jízdního řádu spočítá, kde by právě měla být vozidla (surový formát Portabo).
import fs from 'node:fs';
import { Timetable } from '../public/js/timetable.js';
import { pragueNow, addDays } from '../public/js/util.js';

export function mockFetcher(timetableFile, { limit = 450 } = {}) {
  let tt = null;
  return async () => {
    tt ||= new Timetable(JSON.parse(fs.readFileSync(timetableFile, 'utf8')));
    const now = pragueNow();
    const out = [];
    for (let back = 0; back <= 1; back++) {
      const day = addDays(now.day, -back), rel = now.sec + back * 86400;
      const active = tt.activeSvcs(day);
      for (let tid = 1; tid < tt.trips.length && out.length < limit; tid++) {
        const t = tt.trips[tid];
        if (!active.has(t.svc) || rel < t.t0 || rel > t.t1) continue;
        let i = 0;
        while (i + 1 < t.stops.length && t.arr[i + 1] <= rel) i++;
        const a = tt.stops[t.stops[i]], b = tt.stops[t.stops[Math.min(i + 1, t.stops.length - 1)]];
        if (!a.hasCoords || !b.hasCoords) continue;
        const span = t.arr[Math.min(i + 1, t.stops.length - 1)] - t.dep[i];
        const f = span > 0 ? Math.min(1, Math.max(0, (rel - t.dep[i]) / span)) : 0;
        const city = tid % 3 === 0 && !t.isTrain;
        out.push({
          ID: t.isTrain ? 20000 + (tid % 9000) : (city ? 300000 : 100000) + tid,
          Latitude: a.lat + (b.lat - a.lat) * f, Longitude: a.lng + (b.lng - a.lng) * f,
          State: 0, Delay: tid % 7 === 0 ? 4 + (tid % 5) : tid % 4 === 0 ? 1 : 0,
          LineID: t.line, qride_linename: t.line,
          CISLineID: t.cis ? parseInt(t.cis, 10) : 0, RouteID: t.isTrain ? t.num : t.num,
          agency: t.agency, headsign: tt.stops[t.stops[t.stops.length - 1]].name,
          HasLowfloor: t.wheel > 0 || tid % 2 === 0, isAirConditioned: tid % 3 !== 1,
        });
      }
    }
    return { VehicleList: out };
  };
}
