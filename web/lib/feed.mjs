// Živé polohy vozidel DÚK: stažení, normalizace a držení zmizelých vozidel.
// Logika odpovídá PortaboSource.kt a serveru spoje-feed (server/server.py).

const MHD = (id) => id >= 300000 && id <= 309999;
const TRAIN = (id) => id >= 20000 && id <= 29999;

const clean = (v, fb = '') => (v == null || v === 'null' || String(v).trim() === '' ? fb : String(v).trim());

/** Surový záznam Portabo → vozidlo pro web, nebo null když je nepoužitelný. */
export function normalize(e, nowMs = Date.now()) {
  const lat = Number(e.Latitude), lng = Number(e.Longitude);
  const state = e.State == null ? 255 : Number(e.State);
  const id = Number(e.ID) || 0;
  if (state === 255 || !lat || !lng) return null;

  let ageSec = 0;
  if (Number(e._ageSec) > 0) ageSec = Number(e._ageSec);
  else if (e.LastActivityDT) {
    const t = Date.parse(e.LastActivityDT);
    if (Number.isFinite(t)) ageSec = Math.max(0, Math.floor((nowMs - t) / 1000));
  }
  if (ageSec > (MHD(id) ? 45 : 10) * 60) return null;

  const train = TRAIN(id);
  const routeId = clean(e.RouteID);
  let line = clean(e.qride_linename, clean(e.LineID, '?'));
  if (line === '200') line = 'X';
  const bool = (k) => (e[k] == null ? null : !!e[k]);
  return {
    id, lat, lng, line,
    delay: Number(e.Delay) || 0,
    ageSec, state, train,
    cis: train ? routeId : String(parseInt(e.CISLineID, 10) || 0).padStart(6, '0'),
    trip: train ? '' : routeId,
    agency: clean(e.agency, clean(e.agency_name)),
    dest: clean(e.headsign, clean(e.destination, clean(e.last_stop))),
    low: bool('HasLowfloor'), ac: bool('isAirConditioned'),
    city: MHD(id),
  };
}

export class Feed {
  /**
   * @param {() => Promise<object>} fetchRaw vrací surovou odpověď ({VehicleList: [...]})
   */
  constructor({ fetchRaw, pollMs = 10_000, holdMhdS = 300, holdOtherS = 180, holdIdleS = 30, outageMin = 18, now = () => Date.now() }) {
    Object.assign(this, { fetchRaw, pollMs, holdMhdS, holdOtherS, holdIdleS, outageMin, now });
    this.store = new Map();   // klíč → { v, ts }
    this.status = { lastOk: 0, failures: 0, lastError: '' };
    this.timer = null;
  }

  async poll() {
    try {
      const raw = await this.fetchRaw();
      const t = this.now();
      for (const e of raw.VehicleList || []) {
        const v = normalize(e, t);
        if (v) this.store.set(v.id || `${v.lat},${v.lng}`, { v, ts: t });
      }
      this.status = { lastOk: t, failures: 0, lastError: '' };
    } catch (err) {
      this.status.failures++;
      this.status.lastError = String(err?.message || err);
    }
    this._prune();
  }

  _ttl(v) {
    const t = v.city ? this.holdMhdS : this.holdOtherS;
    return v.state === 0 || v.state === 1 ? t : Math.min(t, this.holdIdleS);
  }

  _prune() {
    const t = this.now();
    for (const [k, it] of this.store) if ((t - it.ts) / 1000 > this._ttl(it.v)) this.store.delete(k);
  }

  /** Aktuální stav pro API. Stáří polohy = stáří při pozorování + jak dlouho ji držíme. */
  snapshot() {
    this._prune();
    const t = this.now();
    const vehicles = [];
    let held = 0;
    for (const { v, ts } of this.store.values()) {
      const heldS = Math.floor((t - ts) / 1000);
      if (heldS > this.pollMs / 1000 * 1.5) held++;
      vehicles.push({ ...v, ageSec: v.ageSec + heldS });
    }
    const ageOk = this.status.lastOk ? Math.floor((t - this.status.lastOk) / 1000) : null;
    return {
      time: t, vehicles, held,
      outage: held > this.outageMin || ageOk == null || ageOk > 60,
      upstreamAgeSec: ageOk,
      pollSec: this.pollMs / 1000,
    };
  }

  start() {
    const loop = async () => { await this.poll(); this.timer = setTimeout(loop, this.pollMs); this.timer.unref?.(); };
    loop();
  }

  stop() { clearTimeout(this.timer); }
}

/** Stažení z veřejného zdroje (nebo vlastního spoje-feed serveru přes FEED_URL). */
export function httpFetcher(urls, { timeoutMs = 10_000 } = {}) {
  return async () => {
    let last;
    for (const url of urls) {
      try {
        const res = await fetch(url, { headers: { 'user-agent': 'spojeuk-web/1.0', accept: 'application/json' }, signal: AbortSignal.timeout(timeoutMs) });
        if (!res.ok) throw new Error(`HTTP ${res.status} (${url})`);
        return await res.json();
      } catch (e) { last = e; }
    }
    throw last || new Error('Žádná URL zdroje');
  };
}
