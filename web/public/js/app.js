// Spoje UK: webová aplikace (vyhledávání spojení + živá mapa vozidel).
import { CONFIG } from './config.js';
import { loadTimetable } from './timetable.js';
import { Planner, describeJourney, MODES } from './planner.js';
import { suggest, nearestPlaces } from './suggest.js';
import { LiveTracker, fetchVehicles, vehicleUid } from './live.js';
import { MapView } from './map.js';
import { store } from './store.js';
import {
  prettyName, splitStopName, stopLabel, hhmm, pragueNow, addDays, dayDiff, dayToIso, isoToDay, fmtMinutes, fmtDist,
  pluralStops, pluralTransfers, ageLabel, fmtDayShort, DAY_NAMES, weekday, NBSP,
} from './util.js';

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const icon = (n, cls = '') => `<svg class="icon ${cls}" aria-hidden="true"><use href="/assets/icons.svg#${n}"/></svg>`;

/** Stav aplikace. */
const S = {
  tt: null, planner: null, live: null, index: [], map: null,
  from: null, to: null, mode: MODES.ALL, when: 'now',
  journeys: [], desc: new Map(), expanded: new Set(), query: null, loadingMore: false, noMoreEarlier: false,
  loc: null, vehicles: [], vehMarkers: new Map(), selected: null, showRoute: false,
  overlay: { lines: [], markers: [] }, meMarker: null, outage: false,
};

// ───────────── motiv ─────────────
const THEME_ICON = { auto: 'auto', light: 'sun', dark: 'moon' };
const THEME_LABEL = { auto: 'automaticky', light: 'světlý', dark: 'tmavý' };
const darkMq = matchMedia('(prefers-color-scheme: dark)');
function resolvedTheme() {
  const t = store.theme();
  return t === 'auto' ? (darkMq.matches ? 'dark' : 'light') : t;
}
function applyTheme() {
  const t = store.theme();
  if (t === 'auto') delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = t;
  const b = $('#theme-btn');
  b.innerHTML = icon(THEME_ICON[t]);
  b.setAttribute('aria-label', `Vzhled: ${THEME_LABEL[t]}. Klepnutím změníš.`);
  S.map?.setTheme(resolvedTheme());
  const m = $('meta[name="theme-color"]');
  if (m) m.content = resolvedTheme() === 'dark' ? '#05111f' : '#3d9de8';
}
$('#theme-btn').addEventListener('click', () => {
  const order = ['auto', 'light', 'dark'];
  store.setTheme(order[(order.indexOf(store.theme()) + 1) % 3]);
  applyTheme();
});
darkMq.addEventListener('change', applyTheme);

// ───────────── záložky ─────────────
function setTab(tab) {
  $('#shell').dataset.tab = tab;
  $$('.tabbar button').forEach((b) => (b.dataset.tab === tab ? b.setAttribute('aria-current', 'page') : b.removeAttribute('aria-current')));
  if (tab === 'map') requestAnimationFrame(() => S.map?.invalidate());
  try { history.replaceState(null, '', tab === 'map' ? '#map' : location.pathname + location.search); } catch { /* ignore */ }
}
$$('.tabbar button').forEach((b) => b.addEventListener('click', () => setTab(b.dataset.tab)));

// ───────────── mapa ─────────────
function initMap() {
  S.map = new MapView($('#map'), { center: CONFIG.MAP_CENTER, zoom: 12, tiles: CONFIG.TILES, attribution: CONFIG.ATTRIBUTION, fallbackTiles: CONFIG.FALLBACK_TILE, darkFilter: CONFIG.DARK_FILTER, theme: resolvedTheme() });
  S.map.on('tilesfailed', () => { const o = $('#outage'); o.querySelector('span').textContent = 'Podkladovou mapu se nepodařilo načíst (blokátor reklam nebo síť?). Vozidla se zobrazují i tak.'; o.hidden = false; });
  S.map.on('click', () => { if (S.selected) closeSheet(); });
}

// ───────────── napovídání zastávek ─────────────
function setupField(key) {
  const field = $(`[data-field="${key}"]`);
  const input = $('input', field), list = $('.suggest', field), clearBtn = $('[data-action="clear"]', field);
  let items = [], active = -1, typed = false;

  const setValue = (place) => {
    S[key] = place;
    input.value = place ? prettyName(place) : '';
    clearBtn.hidden = !place;
  };
  field.setValue = setValue;

  const close = () => { list.hidden = true; input.setAttribute('aria-expanded', 'false'); active = -1; input.removeAttribute('aria-activedescendant'); };
  const choose = (place) => { setValue(place); close(); typed = false; if (key === 'from' && !S.to) $('#to-input').focus(); };

  const render = () => {
    const q = typed ? input.value : '';
    items = suggest(S.index, q, { usage: store.usage(), loc: S.loc, limit: 8 });
    list.innerHTML = '';
    if (!items.length) {
      list.innerHTML = `<li class="hint" role="option" aria-disabled="true">${q ? 'Nic jsme nenašli. Zkus jiný název.' : 'Začni psát název zastávky.'}</li>`;
    }
    items.forEach((it, i) => {
      const { stop, city } = splitStopName(prettyName(it.place));
      const li = document.createElement('li');
      li.id = `${key}-opt-${i}`; li.setAttribute('role', 'option'); li.dataset.i = i;
      const meta = it.used && !q ? '' : Number.isFinite(it.dist) ? fmtDist(it.dist) : '';
      li.innerHTML = `<span class="ico">${icon(it.used ? 'clock' : 'pin')}</span>
        <span class="txt"><span class="name">${esc(stop || it.place)}</span><span class="sub">${esc(city)}</span></span>
        ${meta ? `<span class="meta">${esc(meta)}</span>` : ''}
        ${it.used && !q ? `<button type="button" class="forget" aria-label="Odebrat z často používaných" data-forget="${i}">${icon('x')}</button>` : ''}`;
      list.append(li);
    });
    list.hidden = false;
    input.setAttribute('aria-expanded', 'true');
    highlight(-1);
  };
  const highlight = (i) => {
    active = i;
    $$('li[role="option"]', list).forEach((li, k) => li.setAttribute('aria-selected', String(k === i)));
    if (i >= 0) { input.setAttribute('aria-activedescendant', `${key}-opt-${i}`); $$('li', list)[i]?.scrollIntoView({ block: 'nearest' }); } else input.removeAttribute('aria-activedescendant');
  };

  input.addEventListener('focus', () => { typed = false; input.select(); render(); });
  input.addEventListener('input', () => { typed = true; S[key] = null; clearBtn.hidden = !input.value; render(); });
  input.addEventListener('blur', () => setTimeout(close, 150));
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); if (list.hidden) render(); highlight(Math.min(items.length - 1, active + 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); highlight(Math.max(0, active - 1)); }
    else if (e.key === 'Escape') close();
    else if (e.key === 'Enter' && !list.hidden && items.length) { e.preventDefault(); choose(items[active >= 0 ? active : 0].place); if (key === 'to') $('#search-form').requestSubmit(); }
  });
  // mousedown místo click, aby se výběr stihl před blur
  list.addEventListener('mousedown', (e) => {
    e.preventDefault();
    const f = e.target.closest('[data-forget]');
    if (f) { store.forgetUsage(items[+f.dataset.forget].place); render(); return; }
    const li = e.target.closest('li[data-i]');
    if (li) choose(items[+li.dataset.i].place);
  });
  clearBtn.addEventListener('click', () => { setValue(null); input.focus(); });
  $('[data-action="locate"]', field)?.addEventListener('click', useMyLocation);
}

async function getLocation() {
  if (!navigator.geolocation) throw new Error('Prohlížeč nepodporuje určení polohy.');
  return new Promise((res, rej) => navigator.geolocation.getCurrentPosition(
    (p) => res({ lat: p.coords.latitude, lng: p.coords.longitude }),
    (e) => rej(new Error(e.code === 1 ? 'Přístup k poloze je zakázaný. Povol ho v nastavení prohlížeče.' : 'Polohu se nepodařilo zjistit.')),
    { enableHighAccuracy: false, timeout: 8000, maximumAge: 60000 }));
}

function formMessage(text, kind = 'warn') {
  const m = $('#form-msg');
  m.hidden = !text; m.textContent = text || '';
  m.style.background = kind === 'info' ? 'var(--brand-100)' : ''; m.style.color = kind === 'info' ? 'var(--brand-700)' : '';
}

async function useMyLocation() {
  formMessage('');
  try {
    S.loc = await getLocation();
    const [n] = nearestPlaces(S.index, S.loc.lat, S.loc.lng, 1);
    if (!n || n.dist > 5000) return formMessage('V okolí jsme nenašli žádnou zastávku.');
    $('[data-field="from"]').setValue(n.place);
    formMessage(`Nejbližší zastávka: ${stopLabel(prettyName(n.place))} (${fmtDist(n.dist)} vzdušnou čarou).`, 'info');
  } catch (e) { formMessage(e.message); }
}

// ───────────── formulář ─────────────
function setupForm() {
  setupField('from'); setupField('to');
  $('#swap').addEventListener('click', () => {
    const [a, b] = [S.from, S.to];
    $('[data-field="from"]').setValue(b); $('[data-field="to"]').setValue(a);
  });
  $$('[data-when]').forEach((b) => b.addEventListener('click', () => {
    S.when = b.dataset.when;
    $$('[data-when]').forEach((x) => x.setAttribute('aria-checked', String(x === b)));
    $('#when').hidden = S.when === 'now';
    if (S.when === 'custom' && !$('#date-input').value) {
      const n = pragueNow();
      $('#date-input').value = dayToIso(n.day);
      $('#time-input').value = hhmm(n.sec);
    }
  }));
  $$('[data-mode]').forEach((b) => b.addEventListener('click', () => {
    S.mode = b.dataset.mode;
    $$('[data-mode]').forEach((x) => x.setAttribute('aria-checked', String(x === b)));
  }));
  $('#search-form').addEventListener('submit', (e) => { e.preventDefault(); runSearch(); });
  $('#earlier').addEventListener('click', loadEarlier);
  $('#later').addEventListener('click', loadLater);
  new IntersectionObserver((es) => { if (es.some((x) => x.isIntersecting) && S.journeys.length) loadLater(true); }, { root: $('#scroll'), rootMargin: '0px 0px 200px 0px' }).observe($('#more'));

  // předvyplnění z minulé cesty
  const last = store.lastTrip();
  if (last && S.tt.places.has(last.from) && S.tt.places.has(last.to)) {
    $('[data-field="from"]').setValue(last.from); $('[data-field="to"]').setValue(last.to);
  }
}

function startTime() {
  const now = pragueNow();
  if (S.when === 'now') return now;
  const d = $('#date-input').value, t = $('#time-input').value;
  if (!d) return now;
  const [h, m] = (t || '00:00').split(':').map(Number);
  return { day: isoToDay(d), sec: h * 3600 + m * 60 };
}

const tick = () => new Promise((r) => setTimeout(r, 30));

async function runSearch() {
  formMessage('');
  // nedokončený zápis: zkus první návrh
  for (const key of ['from', 'to']) {
    const v = $(`#${key}-input`).value.trim();
    if (!S[key] && v) { const [first] = suggest(S.index, v, { usage: store.usage(), loc: S.loc, limit: 1 }); if (first) $(`[data-field="${key}"]`).setValue(first.place); }
  }
  if (!S.from || !S.to) return formMessage('Vyber zastávku odkud i kam.');
  if (S.from === S.to) return formMessage('Odkud a kam jsou stejné. Vyber dvě různé zastávky.');

  const btn = $('.go');
  btn.disabled = true; btn.lastChild.textContent = 'Hledám…';
  await tick();
  try {
    store.recordUsage(S.from); store.recordUsage(S.to); store.saveTrip(S.from, S.to);
    const start = startTime();
    const t0 = performance.now();
    const js = S.planner.find(S.from, S.to, start, { count: 8, mode: S.mode });
    S.query = { from: S.from, to: S.to, mode: S.mode, start };
    S.journeys = js; S.desc.clear(); S.expanded.clear(); S.noMoreEarlier = false;
    console.debug(`[hledání] ${js.length} spojení za ${Math.round(performance.now() - t0)} ms`);
    renderResults(true);
    $('#scroll').scrollTo({ top: $('#results').offsetTop - 80, behavior: 'smooth' });
  } catch (e) {
    console.error(e);
    formMessage('Hledání selhalo. Zkus to prosím znovu.');
  } finally {
    btn.disabled = false; btn.lastChild.textContent = 'Najít spojení';
  }
}

async function loadMore(fn) {
  if (S.loadingMore || !S.query) return;
  S.loadingMore = true;
  await tick();
  try {
    const before = S.journeys.length;
    const next = fn();
    S.journeys = next;
    return next.length - before;
  } finally { S.loadingMore = false; }
}

async function loadLater(auto = false) {
  if (!S.query || S.loadingMore) return;
  const { from, to, mode } = S.query;
  const n = await loadMore(() => S.planner.moreLater(from, to, S.journeys, { mode }));
  if (n === 0 && !auto) $('#later').textContent = 'Další spoje už nejedou';
  if (n === 0) $('#more').hidden = true;
  renderResults();
}

async function loadEarlier() {
  if (!S.query) return;
  const { from, to, mode } = S.query;
  const n = await loadMore(() => S.planner.moreEarlier(from, to, S.journeys, { mode }));
  if (!n) { S.noMoreEarlier = true; $('#earlier').disabled = true; }
  renderResults();
}

// ───────────── výsledky ─────────────
function descOf(j) {
  let d = S.desc.get(j.key);
  if (!d) { d = describeJourney(S.tt, j); S.desc.set(j.key, d); }
  return d;
}

function relative(j) {
  const now = pragueNow();
  const secs = j.dep + dayDiff(now.day, j.day) * 86400 - now.sec;
  if (secs > 0) return `za ${fmtMinutes(Math.ceil(secs / 60))}`;
  if (secs > -60) return 'právě odjíždí';
  return `odjel před ${fmtMinutes(Math.floor(-secs / 60))}`;
}

function dayLabel(j) {
  const now = pragueNow();
  const diff = dayDiff(now.day, j.day);
  if (diff === 0) return '';
  const name = diff === 1 ? 'zítra' : DAY_NAMES[weekday(j.day)];
  return `${name} ${fmtDayShort(j.day)}`;
}

function badgeHtml(line, isTrain) {
  return `<span class="badge${isTrain ? ' train' : ''}">${isTrain ? icon('train') : ''}${esc(line)}</span>`;
}

function delayTag(d) {
  const v = S.live?.forTrip(d.rides[0].tid);
  if (!v || dayDiff(pragueNow().day, d.day) !== 0) return '';
  if (v.delay >= 1) return `<span class="tag${v.delay > CONFIG.LATE_MIN ? ' late' : ''}">Zpoždění ${v.delay}${NBSP}min</span>`;
  return '<span class="tag ok">Včas</span>';
}

function timelineHtml(d) {
  const tt = S.tt;
  const pts = [];
  const push = (p) => {
    const last = pts[pts.length - 1];
    if (last && last.stop === p.stop && !last.walk && !p.walk && last.dep == null) { last.dep = p.dep; last.info = p.info ?? last.info; last.mid = p.mid; }
    else pts.push(p);
  };
  for (const l of d.legs) {
    if (l.kind === 'walk') {
      const m = Math.max(1, Math.ceil((l.arr - l.dep) / 60));
      pts.push({ walk: true, text: `Pěšky ${m}${NBSP}min` });
      continue;
    }
    const t = tt.trips[l.tid];
    const info = `${badgeHtml(t.line, t.isTrain)} směr ${esc(prettyName(t.head))}`;
    const first = l.stops[0], lastS = l.stops[l.stops.length - 1];
    push({ stop: first.stop, arr: null, dep: first.dep, info, boarding: true });
    const mids = l.stops.slice(1, -1);
    if (mids.length) pts.push({ mids, stop: -1 });
    push({ stop: lastS.stop, arr: lastS.arr, dep: null });
  }
  return `<ol class="timeline">${pts.map((p) => {
    if (p.walk) return `<li class="leg">${icon('walk')} ${p.text}</li>`;
    if (p.mids) {
      return `<li class="mid"><details><summary>${pluralStops(p.mids.length)} mezi</summary><ul>${p.mids.map((m) => `<li><span class="t">${hhmm(m.dep)}</span>${esc(stopLabel(prettyName(tt.stops[m.stop].name)))}</li>`).join('')}</ul></details></li>`;
    }
    const name = prettyName(tt.stops[p.stop].name);
    const time = p.arr != null && p.dep != null ? `${hhmm(p.arr)} → ${hhmm(p.dep)}` : hhmm(p.arr ?? p.dep);
    return `<li><span class="t">${time}</span><strong>${esc(stopLabel(name))}</strong> <span class="sj-city" style="color:var(--muted);font-size:.85rem">${esc(splitStopName(name).city)}</span>${p.info ? `<div class="leg">${p.info}</div>` : ''}</li>`;
  }).join('')}</ol>`;
}

function journeyHtml(j, i) {
  const d = descOf(j);
  const open = S.expanded.has(j.key);
  const dl = dayLabel(d);
  const trans = d.transferList.map((t) => `<div class="transfer">Přestup: <strong>${esc(t.at)}</strong> · čekání ${t.waitMin}${NBSP}min${t.walkMin ? ` · pěšky ${t.walkMin}${NBSP}min` : ''}</div>`).join('');
  const lines = d.rides.map((r) => badgeHtml(S.tt.trips[r.tid].line, S.tt.trips[r.tid].isTrain)).join('<span class="sep">+</span>');
  return `<li class="journey" data-i="${i}" data-key="${esc(j.key)}">
    <div class="j-top">
      <div>
        <div class="j-times">${hhmm(d.dep)} ${icon('arrow-right')} ${hhmm(d.arr)}</div>
        <div class="j-rel">${esc(dl ? `${dl} · ` : '')}${esc(relative(d))}</div>
        <div class="j-dur">${fmtMinutes(d.durationMin)} ${d.transfers ? 'celkem' : 'jízdy'}</div>
      </div>
      <div class="j-actions"><button type="button" class="pill-btn" data-act="map">${icon('map')}Mapa</button></div>
    </div>
    <div class="j-lines">${d.boardWalkMin ? `<span class="tag">${icon('walk')} pěšky ${d.boardWalkMin}${NBSP}min</span>` : ''}${lines}${delayTag(d)}</div>
    <div class="j-meta">${pluralStops(d.stopsCount)} · ${pluralTransfers(d.transfers)}</div>
    ${trans}
    <div class="j-sub">Směr ${esc(d.destination)}</div>
    <div class="j-sub">${esc(d.agency)}</div>
    <button type="button" class="toggle" data-act="toggle" aria-expanded="${open}">Podrobnosti ${icon('chevron')}</button>
    ${open ? timelineHtml(d) : ''}
  </li>`;
}

function renderResults(fresh = false) {
  const res = $('#results'), empty = $('#empty');
  if (!S.query) { res.hidden = true; empty.hidden = true; return; }
  if (!S.journeys.length) {
    res.hidden = true; empty.hidden = false;
    const q = S.query;
    empty.innerHTML = `<strong>Spojení jsme nenašli</strong>${q.mode !== MODES.ALL ? 'Zkus zvolit „Vše“ místo omezení na jeden druh dopravy, nebo ' : 'Zkus '}posunout čas odjezdu${q.start.sec > 3600 * 20 ? ' (nebo vybrat zítřejší den)' : ''}.`;
    return;
  }
  empty.hidden = true; res.hidden = false;
  const list = $('#journeys');
  const html = S.journeys.map(journeyHtml).join('');
  if (html !== S.lastHtml) {   // při obnovení poloh se DOM nepřekresluje, pokud se nic nezměnilo
    S.lastHtml = html;
    list.classList.toggle('fresh', fresh);
    list.innerHTML = html;
  }
  $('#count').textContent = `${S.journeys.length} nalezeno`;
  $('#earlier').disabled = S.noMoreEarlier;
  if (fresh) { $('#more').hidden = false; $('#later').textContent = 'Zobrazit další spoje'; }
}

$('#journeys').addEventListener('click', (e) => {
  const btn = e.target.closest('[data-act]');
  if (!btn) return;
  const li = btn.closest('.journey');
  const j = S.journeys[+li.dataset.i];
  if (btn.dataset.act === 'toggle') {
    const k = j.key;
    if (S.expanded.has(k)) S.expanded.delete(k); else S.expanded.add(k);
    li.outerHTML = journeyHtml(j, +li.dataset.i);
    S.lastHtml = null;
  } else if (btn.dataset.act === 'map') showJourneyOnMap(descOf(j));
});

// ───────────── trasy na mapě ─────────────
function clearOverlay() {
  S.overlay.lines.forEach((l) => l.remove()); S.overlay.markers.forEach((m) => m.remove());
  S.overlay = { lines: [], markers: [] };
  $('#clear-route').hidden = true;
}

function dot(lat, lng, cls, label) {
  const el = document.createElement('div');
  el.className = cls;
  const m = S.map.addMarker({ lat, lng, el, z: 5 });
  S.overlay.markers.push(m);
  if (label) {
    const l = document.createElement('div'); l.className = 'stop-label'; l.textContent = label;
    S.overlay.markers.push(S.map.addMarker({ lat, lng, el: l, z: 6 }));
  }
}

function pin(lat, lng, to, label) {
  const el = document.createElement('div');
  el.className = `endpoint${to ? ' to' : ''}`;
  el.innerHTML = icon(to ? 'map' : 'pin');
  S.overlay.markers.push(S.map.addMarker({ lat, lng, el, z: 8 }));
  const l = document.createElement('div'); l.className = 'stop-label'; l.textContent = label;
  S.overlay.markers.push(S.map.addMarker({ lat, lng, el: l, z: 7 }));
}

function showJourneyOnMap(d) {
  clearOverlay(); closeSheet(true);
  const tt = S.tt, pts = [];
  const sp = (id) => tt.stops[id];
  d.legs.forEach((l) => {
    if (l.kind === 'walk') {
      const a = sp(l.from), b = sp(l.to);
      if (a.hasCoords && b.hasCoords) { S.overlay.lines.push(S.map.addPolyline([[a.lat, a.lng], [b.lat, b.lng]], { color: '#6a829b', width: 4, dash: '2 8', casing: null })); pts.push([a.lat, a.lng], [b.lat, b.lng]); }
      return;
    }
    const t = tt.trips[l.tid];
    const line = l.stops.map((x) => sp(x.stop)).filter((s) => s.hasCoords).map((s) => [s.lat, s.lng]);
    S.overlay.lines.push(S.map.addPolyline(line, { color: t.isTrain ? '#6a5bd8' : '#2786d6', width: 6 }));
    pts.push(...line);
    l.stops.slice(1, -1).forEach((x) => { const s = sp(x.stop); if (s.hasCoords) dot(s.lat, s.lng, 'stopdot mid'); });
  });
  // přestupy
  d.rides.slice(0, -1).forEach((r) => { const s = sp(r.to); if (s.hasCoords) dot(s.lat, s.lng, 'stopdot', stopLabel(prettyName(s.name))); });
  const first = sp(d.rides[0].from), last = sp(d.rides[d.rides.length - 1].to);
  if (first.hasCoords) pin(first.lat, first.lng, false, stopLabel(prettyName(first.name)));
  if (last.hasCoords) pin(last.lat, last.lng, true, stopLabel(prettyName(last.name)));
  $('#clear-route').hidden = false;
  setTab('map');
  setTimeout(() => S.map.fitBounds(pts, { padding: { top: 110, right: 50, bottom: 90, left: 50 }, maxZoom: 16 }), 60);
}
$('#clear-route').addEventListener('click', clearOverlay);

// ───────────── vozidla ─────────────
function vehClass(v) {
  return `veh${v.train ? ' train' : ''}${v.delay > CONFIG.LATE_MIN ? ' late' : ''}${v.ageSec > 90 ? ' stale' : ''}${vehicleUid(v) === S.selected ? ' sel' : ''}`;
}

function updateVehicleMarkers() {
  const seen = new Set();
  for (const v of S.vehicles) {
    const uid = vehicleUid(v);
    seen.add(uid);
    let m = S.vehMarkers.get(uid);
    if (m) { m.setLatLng(v.lat, v.lng, true); m.el.className = vehClass(v); m.el.textContent = v.line; }
    else {
      const el = document.createElement('div');
      el.className = vehClass(v); el.textContent = v.line;
      m = S.map.addMarker({ lat: v.lat, lng: v.lng, el, z: v.delay > CONFIG.LATE_MIN ? 3 : 2, onClick: () => selectVehicle(uid) });
      el.setAttribute('role', 'button'); el.tabIndex = 0;
      el.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectVehicle(uid); } });
      S.vehMarkers.set(uid, m);
    }
    m.el.setAttribute('aria-label', `Linka ${v.line}${v.delay > 0 ? `, zpoždění ${v.delay} minut` : ''}`);
  }
  for (const [uid, m] of S.vehMarkers) if (!seen.has(uid)) { m.remove(); S.vehMarkers.delete(uid); }
}

async function pollVehicles() {
  try {
    const snap = await fetchVehicles();
    S.vehicles = snap.vehicles;
    S.outage = !!snap.outage;
    S.live.update(S.vehicles);
    updateVehicleMarkers();
    $('#status-sub').textContent = `${S.vehicles.length} poloh · aktualizace po ${Math.round(CONFIG.POLL_MS / 1000)}${NBSP}s`;
    $('#outage').hidden = !S.outage;
    if (S.journeys.length) renderResults();
    if (S.selected) renderSheet();
  } catch (e) {
    $('#status-sub').textContent = 'Polohy se nepodařilo načíst';
  } finally {
    setTimeout(pollVehicles, CONFIG.POLL_MS);
  }
}

function selectVehicle(uid) {
  S.selected = uid; S.showRoute = false;
  for (const [id, m] of S.vehMarkers) { const v = S.vehicles.find((x) => vehicleUid(x) === id); if (v) m.el.className = vehClass(v); }
  clearOverlay();
  renderSheet();
  const v = S.vehicles.find((x) => vehicleUid(x) === uid);
  if (v) S.map.setView([v.lat, v.lng], Math.max(S.map.zoom, 14), { animate: true });
}

function closeSheet(keepOverlay = false) {
  S.selected = null; S.showRoute = false;
  $('#sheet').hidden = true;
  for (const [id, m] of S.vehMarkers) { const v = S.vehicles.find((x) => vehicleUid(x) === id); if (v) m.el.className = vehClass(v); }
  if (!keepOverlay) clearOverlay();
}
$('#sheet-close').addEventListener('click', () => closeSheet());
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && S.selected) closeSheet(); });

function renderSheet() {
  const v = S.vehicles.find((x) => vehicleUid(x) === S.selected);
  const sheet = $('#sheet');
  if (!v) { sheet.hidden = false; $('#sheet-body').innerHTML = '<h3>Vozidlo</h3><p>Vozidlo už zdroj nedodává.</p>'; return; }
  const d = S.live.detail(v);
  const late = v.delay > CONFIG.LATE_MIN;
  const delayText = v.delay >= 1 ? `Zpoždění ${v.delay}${NBSP}min` : 'Včas (0 min)';
  $('#sheet-body').innerHTML = `
    <h3>Informace o spoji</h3>
    <span class="big-badge${v.train ? ' train' : ''}${late ? ' late' : ''}">${icon(v.train ? 'train' : 'bus')}${esc(v.line)}</span>
    ${d.trip ? `<p class="age" style="margin-top:.7rem">Číslo spoje: ${esc(d.trip.num || '–')}${d.exact ? '' : ' (odhad podle polohy)'}</p>` : ''}
    <p class="age">${esc(ageLabel(v.ageSec))}</p>
    <dl>
      <div><dt>Zpoždění:</dt><dd><span class="tag${v.delay >= 1 ? (late ? ' late' : '') : ' ok'}">${delayText}</span></dd></div>
      ${d.next ? `<div><dt>Příští zastávka:</dt><dd>${esc(d.next)}</dd></div>` : ''}
      ${d.terminal ? `<div><dt>Konečná zastávka:</dt><dd>${esc(d.terminal)}</dd></div>` : v.dest ? `<div><dt>Směr:</dt><dd>${esc(prettyName(v.dest))}</dd></div>` : ''}
      ${v.agency || d.trip?.agency ? `<div><dt>Dopravce:</dt><dd>${esc(v.agency || d.trip.agency)}</dd></div>` : ''}
    </dl>
    <div class="chips">${v.ac ? `<span class="tag">${icon('snow')}Klimatizace</span>` : ''}${v.low ? `<span class="tag">${icon('access')}Nízkopodlažní</span>` : ''}</div>
    ${d.trip ? `<button class="btn btn-primary" id="route-btn" type="button">${S.showRoute ? 'Skrýt trasu spoje' : 'Zobrazit trasu spoje'}</button>` : ''}`;
  sheet.hidden = false;
  $('#route-btn')?.addEventListener('click', () => { S.showRoute = !S.showRoute; clearOverlay(); if (S.showRoute) drawVehicleRoute(d); renderSheet(); });
  if (S.showRoute && !S.overlay.lines.length) drawVehicleRoute(d, false);
}

function drawVehicleRoute(d, fit = true) {
  clearOverlay();
  S.overlay.lines.push(S.map.addPolyline(d.route, { color: d.trip.isTrain ? '#6a5bd8' : '#2786d6', width: 6 }));
  d.stops.forEach((s, i) => {
    if (!s.has) return;
    const isNext = i === d.nextIdx, isEnd = i === d.stops.length - 1;
    if (i === 0 || isEnd) pin(s.lat, s.lng, isEnd, stopLabel(s.name));
    else dot(s.lat, s.lng, `stopdot${isNext ? ' next' : ' mid'}`, isNext ? stopLabel(s.name) : null);
  });
  $('#clear-route').hidden = false;
  if (fit) S.map.fitBounds(d.route, { padding: { top: 120, right: 50, bottom: matchMedia('(min-width: 960px)').matches ? 60 : 340, left: matchMedia('(min-width: 960px)').matches ? 440 : 50 }, maxZoom: 15 });
}

// moje poloha na mapě
$('#fab-locate').addEventListener('click', async () => {
  try {
    S.loc = await getLocation();
    if (!S.meMarker) { const el = document.createElement('div'); el.className = 'me'; S.meMarker = S.map.addMarker({ lat: S.loc.lat, lng: S.loc.lng, el, z: 1 }); }
    else S.meMarker.setLatLng(S.loc.lat, S.loc.lng);
    S.map.setView([S.loc.lat, S.loc.lng], 15, { animate: true });
  } catch (e) { alert(e.message); }
});

// ───────────── start ─────────────
async function boot() {
  applyTheme();
  initMap();
  if (location.hash === '#map') setTab('map');
  try {
    S.tt = await loadTimetable(CONFIG.TIMETABLE_URL, { onProgress: (p) => { $('#loading-text').textContent = p < 0.6 ? 'Stahuji jízdní řády…' : 'Připravuji vyhledávání…'; } });
  } catch (e) {
    $('#loading').innerHTML = `<p><strong>Jízdní řády se nepodařilo načíst.</strong><br>${esc(e.message)}</p><button class="btn btn-primary" onclick="location.reload()">Zkusit znovu</button>`;
    pollVehiclesSafe();
    return;
  }
  S.planner = new Planner(S.tt);
  S.live = new LiveTracker(S.tt);
  S.index = S.tt.placeIndex();
  $('#loading').hidden = true;
  $('#search-form').hidden = false;
  setupForm();
  const built = S.tt.meta.built ? new Date(S.tt.meta.built).toLocaleDateString('cs-CZ') : '';
  $('#data-info').textContent = built ? `Jízdní řády z ${built} · ${S.tt.meta.trips.toLocaleString('cs-CZ')} spojů` : '';
  pollVehicles();
  setInterval(() => { if (S.journeys.length && !S.loadingMore) renderResults(); }, 30000);
  if ('serviceWorker' in navigator && location.protocol !== 'file:') navigator.serviceWorker.register('/sw.js').catch(() => {});
}
function pollVehiclesSafe() { S.live = { update() {}, forTrip() {}, detail: () => ({ trip: null }) }; pollVehicles(); }

boot();
