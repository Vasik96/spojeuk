// Spouští se před vykreslením, ať stránka při načtení neblikne špatným motivem.
try {
  const t = JSON.parse(localStorage.getItem('spojeuk:theme') || '"auto"');
  if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
} catch { /* ignore */ }
