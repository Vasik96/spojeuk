#!/usr/bin/env python3

# THIS FILE IS DEPRECATED AND UNUSED, refer to https://github.com/Vasik96/spojeuk-server for the rust version

"""spoje-feed: drží poslední známé polohy vozidel z Portabo GetTraffic (DÚK) a z API DPMÚL.

DÚK (Portabo): každých POLL_S sekund stáhne feed, zapamatuje si každé použitelné vozidlo (podle ID) a když
vozidlo z feedu na chvíli zmizí (nebo přijde se State 255 / nulovou polohou), dál ho vrací
s poslední známou polohou, maximálně HOLD_*_S sekund.

Výstup je ve stejném formátu jako originál ({"VehicleList": [...]}) + u každého vozidla
pole "_ageSec" (stáří polohy v s). Appka tak jen změní URL.

DPMÚL (Globdata): API odpovídá pomalu (několik sekund), tak ho server dotahuje na pozadí přes
jedno trvalé spojení a appce vrací poslední hotovou odpověď hned. Výstup je původní JSON pole
beze změny, appka jen změní URL.

Endpointy:  GET /duk/traffic     sloučený feed DÚK (Portabo)
            GET /dpmul/traffic   poslední odpověď DPMÚL (JSON pole vozidel)
            GET /health          stav serveru: souhrnné "ok", "uptimeMs", pod klíči "duk" a "dpmul" stav zdrojů
Jen stdlib, žádné pip balíčky.
"""
from __future__ import annotations

import http.client, json, logging, os, signal, threading, time, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

UPSTREAM = os.environ.get("UPSTREAM_URL", "https://tabule.portabo.cz/api/v1-tabule/cis/GetTraffic/0")
POLL_S = float(os.environ.get("POLL_S", "10"))
HOLD_MHD_S = int(os.environ.get("HOLD_MHD_S", "300"))      # MHD (ID 30xxxx) držet max 5 min
HOLD_OTHER_S = int(os.environ.get("HOLD_OTHER_S", "180"))  # ostatní max 3 min
HOLD_IDLE_S = 30                                           # stavy 2/3 (nejede spoj) jen krátce
BIND = os.environ.get("BIND", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8787"))
STATE_FILE = Path(os.environ.get("STATE_FILE", "/var/lib/spoje-feed/state.json"))
UA = "spoje-feed/0.1"

DPMUL_URL = os.environ.get("DPMUL_URL", "https://dpmxmobappbe.globdata.cz/api/v1/public/Common/skeleton/actual-vehicles")
# klíč z oficiální aplikace DPMÚL (stejný jako v DpmulSource.kt); když se změní, přepsat tady nebo v prostředí
DPMUL_API_KEY = os.environ.get("DPMUL_API_KEY", "924486f2-3dd8-464f-9dd7-1f25aa43f0e9")
DPMUL_POLL_S = float(os.environ.get("DPMUL_POLL_S", "5"))          # odstup mezi starty dotazů
DPMUL_TIMEOUT_S = float(os.environ.get("DPMUL_TIMEOUT_S", "30"))
DPMUL_HOLD_S = int(os.environ.get("DPMUL_HOLD_S", "120"))          # starší odpověď už nevracet (vrátí se [])

START_MONO = time.monotonic()      # pro uptime (nezávislé na změnách systémového času)

log = logging.getLogger("spoje-feed")
lock = threading.Lock()
store: dict[int, dict] = {}        # id -> {"e": surový záznam, "ts": kdy naposledy živý, "state": int}
payload = b'{"VehicleList":[]}'
status = {"lastOk": 0.0, "failures": 0, "lastError": "", "vehicles": 0, "held": 0, "heldMhd": 0}

dpmul_lock = threading.Lock()
dpmul_payload = b"[]"
dpmul_status = {"lastOk": 0.0, "failures": 0, "lastError": "", "vehicles": 0, "lastFetchMs": 0}


def is_mhd(vid: int) -> bool:
    return 300000 <= vid <= 309999


def usable(e: dict):
    """(id, state) pokud je záznam použitelný, jinak None."""
    try:
        vid, state = int(e["ID"]), int(e.get("State", 255))
        lat, lon = float(e["Latitude"]), float(e["Longitude"])
    except (KeyError, TypeError, ValueError):
        return None
    if vid == 0 or state == 255 or not lat or not lon:
        return None
    return vid, state


def ttl(vid: int, state: int) -> float:
    t = HOLD_MHD_S if is_mhd(vid) else HOLD_OTHER_S
    return t if state in (0, 1) else min(t, HOLD_IDLE_S)


def rebuild(now: float):
    """Přepočítá výstup a vyhodí prošlé záznamy. Volat pod zámkem."""
    global payload
    out, held, held_mhd = [], 0, 0
    for vid, it in list(store.items()):
        age = now - it["ts"]
        if age > ttl(vid, it["state"]):
            del store[vid]
            continue
        if age > POLL_S * 1.5:
            held += 1
            held_mhd += is_mhd(vid)
        out.append({**it["e"], "_ageSec": int(age)})
    payload = json.dumps({"VehicleList": out, "_server": {"time": int(now), "held": held}},
                         ensure_ascii=False, separators=(",", ":")).encode()
    if (held, held_mhd) != (status["held"], status["heldMhd"]):
        log.info("vozidel=%d, drženo=%d (z toho MHD %d)", len(out), held, held_mhd)
    status.update(vehicles=len(out), held=held, heldMhd=held_mhd)


def fetch() -> dict:
    req = urllib.request.Request(UPSTREAM, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


def save():
    try:
        STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
        tmp = STATE_FILE.with_suffix(".tmp")
        with lock:
            tmp.write_text(json.dumps({str(k): v for k, v in store.items()}))
        os.replace(tmp, STATE_FILE)
    except Exception as e:  # noqa: BLE001
        log.warning("uložení stavu selhalo: %s", e)


def load():
    try:
        raw = json.loads(STATE_FILE.read_text())
        with lock:
            for k, v in raw.items():
                store[int(k)] = v
            rebuild(time.time())
        log.info("načteno %d vozidel ze stavu", len(store))
    except FileNotFoundError:
        pass
    except Exception as e:  # noqa: BLE001
        log.warning("stav nelze načíst: %s", e)


def poller(stop: threading.Event):
    last_save = 0.0
    while not stop.is_set():
        t0 = time.time()
        try:
            data = fetch()
            now = time.time()
            with lock:
                for e in data.get("VehicleList", []):
                    u = usable(e)
                    if u:
                        store[u[0]] = {"e": e, "ts": now, "state": u[1]}
                rebuild(now)
                status.update(lastOk=now, failures=0, lastError="")
        except Exception as e:  # noqa: BLE001
            status["failures"] += 1
            status["lastError"] = str(e)
            if status["failures"] in (1, 6) or status["failures"] % 60 == 0:
                log.warning("upstream selhal (%dx): %s", status["failures"], e)
            with lock:
                rebuild(time.time())
        if time.time() - last_save > 30:
            save()
            last_save = time.time()
        stop.wait(max(0.0, POLL_S - (time.time() - t0)))


class DpmulClient:
    """Trvalé spojení na DPMÚL API (keep-alive), ať se DNS a TLS neplatí při každém dotazu."""

    def __init__(self, url: str):
        u = urllib.parse.urlsplit(url)
        self.https = u.scheme == "https"
        self.host = u.netloc
        self.path = u.path + (f"?{u.query}" if u.query else "")
        self.conn: http.client.HTTPConnection | None = None

    def _connect(self) -> http.client.HTTPConnection:
        cls = http.client.HTTPSConnection if self.https else http.client.HTTPConnection
        return cls(self.host, timeout=DPMUL_TIMEOUT_S)

    def close(self):
        if self.conn is not None:
            try:
                self.conn.close()
            finally:
                self.conn = None

    def fetch(self) -> bytes:
        """POST s prázdným tělem (API GET nebere). Vrací surové tělo odpovědi."""
        headers = {
            "User-Agent": "Dart/3.12 (dart:io)",
            "Accept": "application/json",
            "Content-Type": "application/json; charset=utf-8",
            "Content-Length": "0",
            "x-app-env": "prod",
            "x-app-version": "2.0.14",
            "dpmxbe-api-key": DPMUL_API_KEY,
        }
        # první pokus může narazit na spojení, které server mezitím zavřel; pak se zkusí nové
        for attempt in (1, 2):
            fresh = self.conn is None
            if fresh:
                self.conn = self._connect()
            try:
                self.conn.request("POST", self.path, body=b"", headers=headers)
                resp = self.conn.getresponse()
                body = resp.read()
                if resp.status != 200:
                    raise RuntimeError(f"HTTP {resp.status}")
                if resp.will_close:
                    self.close()
                return body
            except Exception:
                self.close()
                if fresh or attempt == 2:
                    raise
        raise RuntimeError("nedosažitelné")


def dpmul_poller(stop: threading.Event):
    global dpmul_payload
    client = DpmulClient(DPMUL_URL)
    while not stop.is_set():
        t0 = time.time()
        try:
            body = client.fetch()
            arr = json.loads(body)
            if not isinstance(arr, list):
                raise ValueError("odpověď není JSON pole")
            now = time.time()
            with dpmul_lock:
                dpmul_payload = body
                dpmul_status.update(lastOk=now, failures=0, lastError="", vehicles=len(arr),
                                    lastFetchMs=int((now - t0) * 1000))
        except Exception as e:  # noqa: BLE001
            with dpmul_lock:
                dpmul_status["failures"] += 1
                dpmul_status["lastError"] = str(e)
                n = dpmul_status["failures"]
            if n in (1, 6) or n % 60 == 0:
                log.warning("DPMÚL upstream selhal (%dx): %s", n, e)
        # další dotaz hned, jakmile uplyne DPMUL_POLL_S od startu toho předchozího
        stop.wait(max(0.0, DPMUL_POLL_S - (time.time() - t0)))
    client.close()


def dpmul_body() -> tuple[bytes, float | None]:
    """Poslední odpověď DPMÚL a její stáří; starší než DPMUL_HOLD_S se nevrací."""
    with dpmul_lock:
        last = dpmul_status["lastOk"]
        body = dpmul_payload
    if not last:
        return b"[]", None
    age = time.time() - last
    return (body if age <= DPMUL_HOLD_S else b"[]"), age


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?")[0]
        extra = {}
        if path == "/duk/traffic":
            body = payload
        elif path == "/dpmul/traffic":
            body, age = dpmul_body()
            extra["X-Age-Sec"] = "-1" if age is None else str(int(age))
        elif path == "/health":
            now = time.time()
            with lock:
                d_duk = dict(status)
            with dpmul_lock:
                d_dpmul = dict(dpmul_status)
            for d in (d_duk, d_dpmul):
                age = now - d["lastOk"] if d["lastOk"] else None
                d.update(ok=age is not None and age < 60,
                         upstreamAgeSec=None if age is None else int(age))
            body = json.dumps({"ok": d_duk["ok"] and d_dpmul["ok"],
                               "uptimeMs": int((time.monotonic() - START_MONO) * 1000),
                               "duk": d_duk, "dpmul": d_dpmul}).encode()
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "max-age=3")
        for k, v in extra.items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    load()
    stop = threading.Event()
    threading.Thread(target=poller, args=(stop,), daemon=True).start()
    threading.Thread(target=dpmul_poller, args=(stop,), daemon=True).start()
    srv = ThreadingHTTPServer((BIND, PORT), Handler)
    srv.daemon_threads = True

    def bye(*_):
        stop.set()
        threading.Thread(target=srv.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, bye)
    signal.signal(signal.SIGINT, bye)
    log.info("poslouchám na %s:%d, upstream DÚK %s, DPMÚL %s", BIND, PORT, UPSTREAM, DPMUL_URL)
    srv.serve_forever()
    save()


if __name__ == "__main__":
    main()
