// Malý mapový engine bez závislostí: slippy-map dlaždice (Web Mercator) na canvasu,
// značky jako DOM prvky, čáry v SVG. Tažení, pinch, kolečko, dvojklik, klávesy, setrvačnost.

const TILE = 256;
const MAX_LAT = 85.05112878;

// ───────────── projekce (čisté funkce, testované v Node) ─────────────

export const worldSize = (zoom) => TILE * 2 ** zoom;

/** lat/lng → pixely světa na dané úrovni přiblížení. */
export function project(lat, lng, zoom) {
  const s = worldSize(zoom);
  const l = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
  const sin = Math.sin((l * Math.PI) / 180);
  return { x: ((lng + 180) / 360) * s, y: (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * s };
}

export function unproject(x, y, zoom) {
  const s = worldSize(zoom);
  const n = Math.PI - (2 * Math.PI * y) / s;
  return { lat: (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))), lng: (x / s) * 360 - 180 };
}

const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const SVGNS = 'http://www.w3.org/2000/svg';

export class MapView {
  /**
   * @param {HTMLElement} el
   * @param {{center?:[number,number], zoom?:number, minZoom?:number, maxZoom?:number,
   *          tiles:{light:string, dark:string}, attribution?:string, theme?:'light'|'dark'}} opts
   */
  constructor(el, opts) {
    this.el = el;
    this.opts = { minZoom: 7, maxZoom: 18, center: [50.66, 14.04], zoom: 12, theme: 'light', ...opts };
    this.center = { lat: this.opts.center[0], lng: this.opts.center[1] };
    this.zoom = this.opts.zoom;
    this.theme = this.opts.theme;
    this.w = 1; this.h = 1;
    this.markers = new Set();
    this.lines = new Set();
    this.tiles = new Map();
    this.listeners = {};
    this._dirty = true;
    this._anim = null;
    this._inertia = null;

    el.classList.add('map');
    el.tabIndex = 0;
    el.setAttribute('role', 'application');
    el.setAttribute('aria-label', 'Mapa vozidel');
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'map-tiles';
    this._applyThemeFilter();
    this.svg = document.createElementNS(SVGNS, 'svg');
    this.svg.setAttribute('class', 'map-lines');
    this.markerLayer = document.createElement('div');
    this.markerLayer.className = 'map-markers';
    el.append(this.canvas, this.svg, this.markerLayer);

    const attr = document.createElement('div');
    attr.className = 'map-attribution';
    attr.textContent = this.opts.attribution || '';
    el.append(attr);

    this._buildControls();
    this._bind();
    this._ro = new ResizeObserver(() => this._resize());
    this._ro.observe(el);
    this._resize();
    this._loop = this._loop.bind(this);
    requestAnimationFrame(this._loop);
  }

  // ───────────── veřejné API ─────────────

  on(type, fn) { (this.listeners[type] ||= []).push(fn); return this; }
  _emit(type, e) { for (const fn of this.listeners[type] || []) fn(e); }

  setTheme(theme) {
    if (theme === this.theme) return;
    this.theme = theme;
    this._applyThemeFilter();
    this.invalidate();
  }

  /** Tmavý režim: dlaždice jsou jen jedny (bez klíče), ztmaví se filtrem. */
  _applyThemeFilter() {
    this.canvas.style.filter = this.theme === 'dark' && this.opts.darkFilter ? this.opts.darkFilter : '';
  }

  invalidate() { this._dirty = true; }

  getBounds() {
    const c = project(this.center.lat, this.center.lng, this.zoom);
    const nw = unproject(c.x - this.w / 2, c.y - this.h / 2, this.zoom);
    const se = unproject(c.x + this.w / 2, c.y + this.h / 2, this.zoom);
    return { north: nw.lat, west: nw.lng, south: se.lat, east: se.lng };
  }

  /** lat/lng → pixely uvnitř prvku mapy. */
  toScreen(lat, lng) {
    const c = project(this.center.lat, this.center.lng, this.zoom);
    const p = project(lat, lng, this.zoom);
    return { x: p.x - c.x + this.w / 2, y: p.y - c.y + this.h / 2 };
  }

  fromScreen(x, y) {
    const c = project(this.center.lat, this.center.lng, this.zoom);
    return unproject(c.x + x - this.w / 2, c.y + y - this.h / 2, this.zoom);
  }

  setView(center, zoom, { animate = false } = {}) {
    const z = clamp(zoom ?? this.zoom, this.opts.minZoom, this.opts.maxZoom);
    const target = { lat: center[0], lng: center[1] };
    this._inertia = null;
    if (!animate || this.w < 2) { this.center = target; this.zoom = z; this._viewChanged(true); return; }
    this._anim = { from: { ...this.center, zoom: this.zoom }, to: { ...target, zoom: z }, t0: performance.now(), dur: 450 };
    this._dirty = true;
  }

  /** Přiblíží tak, aby se vešly všechny body. padding = {top,right,bottom,left} v px. */
  fitBounds(points, { padding = {}, maxZoom = 16, animate = true } = {}) {
    if (!points.length) return;
    const pad = { top: 40, right: 40, bottom: 40, left: 40, ...padding };
    let s = 90, n = -90, w = 180, e = -180;
    for (const [la, lo] of points) { s = Math.min(s, la); n = Math.max(n, la); w = Math.min(w, lo); e = Math.max(e, lo); }
    const availW = Math.max(50, this.w - pad.left - pad.right), availH = Math.max(50, this.h - pad.top - pad.bottom);
    let z = maxZoom;
    for (; z > this.opts.minZoom; z--) {
      const a = project(n, w, z), b = project(s, e, z);
      if (b.x - a.x <= availW && b.y - a.y <= availH) break;
    }
    // střed tak, aby obsah ležel uprostřed volné plochy (padding může být nesymetrický)
    const cb = project((n + s) / 2, (w + e) / 2, z);
    const shiftX = (pad.left - pad.right) / 2, shiftY = (pad.top - pad.bottom) / 2;
    const c = unproject(cb.x - shiftX, cb.y - shiftY, z);
    this.setView([c.lat, c.lng], z, { animate });
  }

  zoomBy(delta, px = this.w / 2, py = this.h / 2, { animate = true } = {}) {
    const z = clamp(Math.round(this.zoom + delta), this.opts.minZoom, this.opts.maxZoom);
    const anchor = this.fromScreen(px, py);
    const cp = project(anchor.lat, anchor.lng, z);
    const c = unproject(cp.x - (px - this.w / 2), cp.y - (py - this.h / 2), z);
    this.setView([c.lat, c.lng], z, { animate });
  }

  addMarker({ lat, lng, el, onClick, z = 0 }) {
    const marker = new Marker(this, lat, lng, el, onClick, z);
    this.markers.add(marker);
    this.markerLayer.append(marker.root);
    this._dirty = true;
    return marker;
  }

  addPolyline(points, { color = '#2b85d6', width = 5, dash = null, opacity = 0.9, casing = '#fff' } = {}) {
    const line = new Polyline(this, points, { color, width, dash, opacity, casing });
    this.lines.add(line);
    this._dirty = true;
    return line;
  }

  destroy() { this._ro.disconnect(); this._dead = true; }

  // ───────────── ovládací prvky ─────────────

  _buildControls() {
    const box = document.createElement('div');
    box.className = 'map-controls';
    const mk = (label, aria, fn) => {
      const b = document.createElement('button');
      b.type = 'button'; b.textContent = label; b.setAttribute('aria-label', aria);
      b.addEventListener('click', fn);
      b.addEventListener('pointerdown', (e) => e.stopPropagation());
      return b;
    };
    box.append(mk('+', 'Přiblížit', () => this.zoomBy(1)), mk('−', 'Oddálit', () => this.zoomBy(-1)));
    this.el.append(box);
  }

  // ───────────── vstup ─────────────

  _bind() {
    const el = this.el;
    const pts = new Map();
    let drag = null;       // { moved, lastX, lastY, vx, vy, lastT }
    let pinch = null;      // { dist, zoom }

    const pos = (e) => { const r = el.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };

    el.addEventListener('pointerdown', (e) => {
      if (e.target.closest('.map-controls')) return;
      pts.set(e.pointerId, pos(e));
      this._inertia = null;
      this._anim = null;
      if (pts.size === 1) { const p = pos(e); drag = { moved: false, sx: p.x, sy: p.y, lastX: p.x, lastY: p.y, vx: 0, vy: 0, lastT: performance.now() }; }
      if (pts.size === 2) {
        const [a, b] = [...pts.values()];
        pinch = { dist: Math.hypot(a.x - b.x, a.y - b.y), zoom: this.zoom };
        if (drag) drag.moved = true;
        try { el.setPointerCapture(e.pointerId); } catch { /* ignore */ }
      }
    });

    el.addEventListener('pointermove', (e) => {
      if (!pts.has(e.pointerId)) return;
      const p = pos(e);
      pts.set(e.pointerId, p);
      if (pts.size === 2 && pinch) {
        const [a, b] = [...pts.values()];
        const dist = Math.hypot(a.x - b.x, a.y - b.y);
        const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        const z = clamp(pinch.zoom + Math.log2(dist / pinch.dist), this.opts.minZoom, this.opts.maxZoom);
        this._zoomAt(mid.x, mid.y, z);
        return;
      }
      if (!drag) return;
      if (!drag.moved && Math.hypot(p.x - drag.sx, p.y - drag.sy) > 5) {
        drag.moved = true;
        try { el.setPointerCapture(e.pointerId); } catch { /* ignore */ }
        el.classList.add('dragging');
      }
      if (drag.moved) {
        const dx = p.x - drag.lastX, dy = p.y - drag.lastY;
        const now = performance.now(), dt = Math.max(1, now - drag.lastT);
        drag.vx = 0.8 * drag.vx + 0.2 * (dx / dt); drag.vy = 0.8 * drag.vy + 0.2 * (dy / dt);
        drag.lastX = p.x; drag.lastY = p.y; drag.lastT = now;
        this._panBy(-dx, -dy);
      }
    });

    const end = (e) => {
      if (!pts.has(e.pointerId)) return;
      pts.delete(e.pointerId);
      if (pts.size < 2) pinch = null;
      if (pts.size === 0 && drag) {
        el.classList.remove('dragging');
        if (drag.moved) {
          const speed = Math.hypot(drag.vx, drag.vy);
          if (speed > 0.15 && performance.now() - drag.lastT < 80) this._inertia = { vx: drag.vx, vy: drag.vy, t: performance.now() };
          this._viewChanged(false);
        } else if (e.type === 'pointerup' && !e.target.closest('.map-marker, .map-controls')) {
          this._emit('click', { ...this.fromScreen(drag.sx, drag.sy), x: drag.sx, y: drag.sy });
        }
        drag = null;
      }
    };
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', end);

    el.addEventListener('wheel', (e) => {
      e.preventDefault();
      const p = pos(e);
      const unit = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 100 : 1;
      this._zoomAt(p.x, p.y, clamp(this.zoom - (e.deltaY * unit) * 0.0022, this.opts.minZoom, this.opts.maxZoom));
      clearTimeout(this._wheelT);
      this._wheelT = setTimeout(() => this._viewChanged(false), 120);
    }, { passive: false });

    el.addEventListener('dblclick', (e) => { if (e.target.closest('.map-marker, .map-controls')) return; const p = pos(e); this.zoomBy(1, p.x, p.y); });

    el.addEventListener('keydown', (e) => {
      const step = 80;
      const k = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[e.key];
      if (k) { e.preventDefault(); this._panBy(k[0], k[1]); this._viewChanged(false); }
      else if (e.key === '+' || e.key === '=') this.zoomBy(1);
      else if (e.key === '-' || e.key === '_') this.zoomBy(-1);
    });
  }

  _panBy(dx, dy) {
    const c = project(this.center.lat, this.center.lng, this.zoom);
    const n = unproject(c.x + dx, c.y + dy, this.zoom);
    this.center = { lat: clamp(n.lat, -MAX_LAT, MAX_LAT), lng: n.lng };
    this._dirty = true;
  }

  /** Změní zoom tak, aby bod pod (px,py) zůstal na místě. */
  _zoomAt(px, py, z) {
    const anchor = this.fromScreen(px, py);
    const cp = project(anchor.lat, anchor.lng, z);
    const c = unproject(cp.x - (px - this.w / 2), cp.y - (py - this.h / 2), z);
    this.center = c; this.zoom = z; this._dirty = true;
  }

  _viewChanged(immediate) {
    this._dirty = true;
    this._emit('move', { center: this.center, zoom: this.zoom });
    if (!immediate) this._emit('moveend', { center: this.center, zoom: this.zoom });
  }

  _resize() {
    const r = this.el.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    this.w = Math.max(1, r.width); this.h = Math.max(1, r.height); this.dpr = dpr;
    this.canvas.width = Math.round(this.w * dpr); this.canvas.height = Math.round(this.h * dpr);
    this.svg.setAttribute('width', this.w); this.svg.setAttribute('height', this.h);
    this._dirty = true;
  }

  // ───────────── dlaždice ─────────────

  _tileUrl(z, x, y) {
    const n = 2 ** z;
    const tpl = this.opts.tiles[this.theme] || this.opts.tiles.light;
    return tpl.replace('{s}', 'abcd'[(x + y) % 4]).replace('{z}', z).replace('{x}', ((x % n) + n) % n).replace('{y}', y).replace('{r}', this.dpr > 1 ? '@2x' : '');
  }

  /** Když se nenačte žádná dlaždice, přepne se na záložní zdroj (a dá o tom vědět). */
  _tileFailed() {
    this.tileFail = (this.tileFail || 0) + 1;
    if (this.tileFail < 4 || this.tileOk) return;
    const fb = this.opts.fallbackTiles;
    if (fb && !this._usingFallback) {
      this._usingFallback = true;
      this.opts.tiles = { light: fb, dark: fb };
      this.tileFail = 0;
      this.tiles.clear();
      this._dirty = true;
    } else if (!this._warned) {
      this._warned = true;
      this._emit('tilesfailed', {});
    }
  }

  _tile(z, x, y, load = true) {
    const key = `${this.theme}/${z}/${x}/${y}`;
    let t = this.tiles.get(key);
    if (!t && load) {
      const img = new Image();
      t = { img, ok: false, fail: false, used: 0 };
      img.onload = () => { t.ok = true; this.tileOk = (this.tileOk || 0) + 1; this._dirty = true; };
      img.onerror = () => { t.fail = true; this._tileFailed(); };
      img.src = this._tileUrl(z, x, y);
      this.tiles.set(key, t);
      if (this.tiles.size > 500) {
        const old = [...this.tiles.entries()].sort((a, b) => a[1].used - b[1].used).slice(0, 150);
        for (const [k, v] of old) { v.img.src = ''; this.tiles.delete(k); }
      }
    }
    if (t) t.used = performance.now();
    return t;
  }

  _drawTiles() {
    const ctx = this.canvas.getContext('2d');
    const dpr = this.dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = this.theme === 'dark' ? '#0f1b2b' : '#e8f1fa';
    ctx.fillRect(0, 0, this.w, this.h);
    const z = clamp(Math.round(this.zoom), 0, 19);
    const scale = 2 ** (this.zoom - z);
    const ts = TILE * scale;
    const c = project(this.center.lat, this.center.lng, z);
    const left = c.x - this.w / 2 / scale, top = c.y - this.h / 2 / scale;
    const n = 2 ** z;
    const x0 = Math.floor(left / TILE), x1 = Math.floor((left + this.w / scale) / TILE);
    const y0 = Math.max(0, Math.floor(top / TILE)), y1 = Math.min(n - 1, Math.floor((top + this.h / scale) / TILE));
    for (let ty = y0; ty <= y1; ty++) {
      for (let tx = x0; tx <= x1; tx++) {
        const sx = (tx * TILE - c.x) * scale + this.w / 2, sy = (ty * TILE - c.y) * scale + this.h / 2;
        const t = this._tile(z, tx, ty);
        if (t.ok) { ctx.drawImage(t.img, Math.floor(sx), Math.floor(sy), Math.ceil(ts) + 1, Math.ceil(ts) + 1); continue; }
        // zatím nenačteno: ukaž zvětšenou dlaždici o úroveň výš
        if (z > 0) {
          const p = this._tile(z - 1, Math.floor(tx / 2), Math.floor(ty / 2), false);
          if (p?.ok) {
            ctx.drawImage(p.img, (tx % 2 + 2) % 2 * (p.img.width / 2), (ty % 2 + 2) % 2 * (p.img.height / 2), p.img.width / 2, p.img.height / 2, Math.floor(sx), Math.floor(sy), Math.ceil(ts) + 1, Math.ceil(ts) + 1);
          }
        }
      }
    }
  }

  // ───────────── smyčka ─────────────

  _loop(now) {
    if (this._dead) return;
    if (this._anim) {
      const a = this._anim;
      const t = Math.min(1, (now - a.t0) / a.dur);
      const e = 1 - (1 - t) ** 3;
      const zoom = a.from.zoom + (a.to.zoom - a.from.zoom) * e;
      // střed se interpoluje ve světových pixelech cílového zoomu, ať pohyb vypadá plynule
      const zz = Math.max(a.from.zoom, a.to.zoom);
      const pa = project(a.from.lat, a.from.lng, zz), pb = project(a.to.lat, a.to.lng, zz);
      this.center = unproject(pa.x + (pb.x - pa.x) * e, pa.y + (pb.y - pa.y) * e, zz);
      this.zoom = zoom;
      this._dirty = true;
      if (t >= 1) { this.center = { lat: a.to.lat, lng: a.to.lng }; this.zoom = a.to.zoom; this._anim = null; this._viewChanged(false); }
    }
    if (this._inertia) {
      const i = this._inertia;
      const dt = Math.min(40, now - i.t); i.t = now;
      this._panBy(-i.vx * dt, -i.vy * dt);
      const f = 0.0035 * dt;
      i.vx *= 1 - Math.min(1, f * 3); i.vy *= 1 - Math.min(1, f * 3);
      if (Math.hypot(i.vx, i.vy) < 0.02) { this._inertia = null; this._viewChanged(false); }
    }
    let animating = false;
    for (const m of this.markers) if (m._step(now)) animating = true;
    if (this._dirty || animating) {
      this._dirty = false;
      this._drawTiles();
      for (const l of this.lines) l._render();
      for (const m of this.markers) m._place();
      this._emit('render', {});
    }
    requestAnimationFrame(this._loop);
  }
}

class Marker {
  constructor(map, lat, lng, el, onClick, z) {
    this.map = map;
    this.lat = lat; this.lng = lng;            // cílová poloha
    this.dLat = lat; this.dLng = lng;          // zobrazená poloha (při animaci se liší)
    this.anim = null;
    this.root = document.createElement('div');
    this.root.className = 'map-marker';
    this.root.style.zIndex = z;
    this.root.append(el);
    this.el = el;
    if (onClick) this.root.addEventListener('click', (e) => { e.stopPropagation(); onClick(e); });
  }

  setLatLng(lat, lng, animate = false) {
    if (lat === this.lat && lng === this.lng) return;
    this.anim = animate ? { fromLat: this.dLat, fromLng: this.dLng, t0: performance.now(), dur: 1200 } : null;
    this.lat = lat; this.lng = lng;
    if (!animate) { this.dLat = lat; this.dLng = lng; }
    this.map._dirty = true;
  }

  setZ(z) { this.root.style.zIndex = z; }

  _step(now) {
    if (!this.anim) return false;
    const t = Math.min(1, (now - this.anim.t0) / this.anim.dur);
    const e = 1 - (1 - t) ** 2;
    this.dLat = this.anim.fromLat + (this.lat - this.anim.fromLat) * e;
    this.dLng = this.anim.fromLng + (this.lng - this.anim.fromLng) * e;
    if (t >= 1) this.anim = null;
    return true;
  }

  _place() {
    const p = this.map.toScreen(this.dLat, this.dLng);
    const off = p.x < -80 || p.y < -80 || p.x > this.map.w + 80 || p.y > this.map.h + 80;
    if (off !== this._off) { this.root.hidden = off; this._off = off; }
    if (!off) this.root.style.transform = `translate(${p.x.toFixed(1)}px, ${p.y.toFixed(1)}px)`;
  }

  remove() { this.root.remove(); this.map.markers.delete(this); }
}

class Polyline {
  constructor(map, points, style) {
    this.map = map;
    this.points = points;
    this.g = document.createElementNS(SVGNS, 'g');
    this.casing = document.createElementNS(SVGNS, 'path');
    this.path = document.createElementNS(SVGNS, 'path');
    for (const [p, w, c, o] of [[this.casing, style.width + 4, style.casing, 0.85], [this.path, style.width, style.color, style.opacity]]) {
      p.setAttribute('fill', 'none'); p.setAttribute('stroke', c); p.setAttribute('stroke-width', w);
      p.setAttribute('stroke-linecap', 'round'); p.setAttribute('stroke-linejoin', 'round'); p.setAttribute('stroke-opacity', o);
    }
    if (style.dash) { this.path.setAttribute('stroke-dasharray', style.dash); this.casing.setAttribute('stroke-opacity', 0); }
    if (!style.casing) this.casing.remove(); else this.g.append(this.casing);
    this.g.append(this.path);
    map.svg.append(this.g);
  }

  setPoints(points) { this.points = points; this.map._dirty = true; }

  _render() {
    const m = this.map;
    const c = project(m.center.lat, m.center.lng, m.zoom);
    const ox = c.x - m.w / 2, oy = c.y - m.h / 2;
    let d = '';
    this.points.forEach(([la, lo], i) => {
      const p = project(la, lo, m.zoom);
      d += `${i ? 'L' : 'M'}${(p.x - ox).toFixed(1)} ${(p.y - oy).toFixed(1)}`;
    });
    this.path.setAttribute('d', d);
    this.casing.setAttribute('d', d);
  }

  remove() { this.g.remove(); this.map.lines.delete(this); }
}
