// Landing page: zobrazení prvků při rolování, stín navigace, čísla z reálných dat.
const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
const els = document.querySelectorAll('.reveal');
if (reduce || !('IntersectionObserver' in window)) els.forEach((e) => e.classList.add('in'));
else {
  const io = new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } }), { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
  els.forEach((e, i) => { e.style.transitionDelay = `${(i % 3) * 70}ms`; io.observe(e); });
}

const nav = document.getElementById('nav');
const onScroll = () => nav.classList.toggle('scrolled', scrollY > 8);
addEventListener('scroll', onScroll, { passive: true }); onScroll();

// skutečné počty z aktuálních dat (když se nepodaří, zůstane statický text)
fetch('/data/meta.json').then((r) => (r.ok ? r.json() : null)).then((m) => {
  if (!m) return;
  const fmt = (n, step) => `${(Math.floor(n / step) * step).toLocaleString('cs-CZ').replace(/ /g, ' ')}+`;
  const t = document.querySelector('[data-stat="trips"]'), s = document.querySelector('[data-stat="stops"]');
  if (t && m.trips) t.textContent = fmt(m.trips, 1000);
  if (s && m.stops) s.textContent = fmt(m.stops, 100);
}).catch(() => {});
