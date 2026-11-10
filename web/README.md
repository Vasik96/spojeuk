# Spoje UK web

Webová verze Spoje UK pro všechna zařízení mimo Android: **landing page** (`/`) a **aplikace** (`/app/`) s hledáním spojení a živou mapou vozidel DÚK. Funguje i jako PWA (přidání na plochu, hledání offline).

Bez závislostí: stačí **Node 20+**, žádné `npm install`, žádný build krok.

```bash
cd web
npm run data     # stáhne jízdní řády z release Vasik96/spojeuk-data → public/data/timetable.json
npm start        # http://localhost:8080
npm run dev      # totéž, ale polohy vozidel se počítají z jízdního řádu (když není dostupný živý zdroj)
npm test
```

Pro otevření na telefonu spusť server na počítači a v mobilním prohlížeči otevři LAN adresu, kterou server vypíše (např. `http://10.234.129.1:8080/app/`). Telefon i počítač musí být ve stejné Wi-Fi síti. Pokud se stránka nenačte, povol příchozí spojení na portu 8080 ve firewallu počítače. `localhost` v mobilu označuje samotný telefon, nikoli počítač.

Při prvním startu bez dat se jízdní řády stáhnou samy a pak se každých 12 h kontrolují (`DATA_REFRESH_H=0` vypne).

## Jak je to postavené

| Část | Soubor | Co dělá |
|---|---|---|
| Data | `lib/build-timetable.mjs` | GTFS (autobusy + vlaky) → kompaktní JSON (~4 MB, ~0,4 MB po kompresi). Stejná logika jako `GtfsImporter` v appce, včetně párování nádraží |
| Hledání | `public/js/planner.js` | RAPTOR, přestupy ≥ 120 s, pěšky do 250 m. Běží v prohlížeči, tedy offline |
| Napovídání | `public/js/suggest.js` | používání → vzdálenost → zbytek |
| Živá vozidla | `lib/feed.mjs`, `public/js/live.js` | server stahuje feed, drží zmizelá vozidla (MHD 5 min, ostatní 3 min), klient je páruje se spoji |
| Mapa | `public/js/map.js` | vlastní engine (dlaždice, značky, čáry, pinch/drag), bez knihoven |
| Server | `server.mjs` | statické soubory (brotli/gzip, CSP) + `GET /api/vehicles`, `GET /api/health` |

## Konfigurace (proměnné prostředí)

| Proměnná | Výchozí | Význam |
|---|---|---|
| `PORT`, `HOST` | `8080`, `0.0.0.0` | kde poslouchat |
| `FEED_URL` | – | vlastní `spoje-feed` server (`…/duk/traffic`), zkouší se jako první; jinak veřejný zdroj |
| `MOCK` | – | `1` = polohy z jízdního řádu (vývoj) |
| `POLL_S` | `10` | jak často stahovat zdroj |
| `HOLD_MHD_S`, `HOLD_OTHER_S` | `300`, `180` | jak dlouho držet zmizelé vozidlo |
| `DATA_REFRESH_H` | `12` | kontrola nových jízdních řádů |
| `GTFS_BASE_URL` | release `gtfs` | odkud brát GTFS |

Podkladová mapa a API adresa se mění v `public/js/config.js` (`TILES`, `API_BASE`). Výchozí jsou dlaždice OpenStreetMap (bez klíče; CARTO už klíč vyžaduje), pro větší provoz si nastav vlastní dlaždice.

## Nasazení

- **Node server** (VPS, Fly, Railway…): `node server.mjs`, před něj HTTPS proxy (např. Caddy).
- **Docker**: `docker build -t spojeuk-web . && docker run -p 8080:8080 spojeuk-web`.
- **Statický hosting** (Netlify, Cloudflare Pages…) jen pro frontend: nahraj `public/` včetně `data/` a v `config.js` nastav `API_BASE` na adresu serveru s `/api/vehicles` (server posílá CORS hlavičku).

## Poznámky

- Hledání se od Android verze liší v jednom: pěší přesun se po jízdě dělá nejvýš jedním krokem (≤ 250 m) a nikdy řetězem zastávek. Starší logika mohla po jízdě spojit několik pěších úseků za sebou, což přesáhlo limit 250 m.
- Trasy spoje na mapě vedou po zastávkách (přímé spojnice). Android appka kreslí trasy po silnicích přes OSRM; to by šlo doplnit stejným způsobem.
- Živý zdroj Portabo je veřejný, ale neoficiální. Klíč k API DPMÚL z `server.py` jsem do webu záměrně nepřenášel (patří do prostředí serveru, ne do repozitáře).
