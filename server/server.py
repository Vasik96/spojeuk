#!/usr/bin/env python3
"""spoje-feed: drží poslední známé polohy vozidel z Portabo GetTraffic.

Každých POLL_S sekund stáhne feed, zapamatuje si každé použitelné vozidlo (podle ID) a když
vozidlo z feedu na chvíli zmizí (nebo přijde se State 255 / nulovou polohou), dál ho vrací
s poslední známou polohou, maximálně HOLD_*_S sekund.

Výstup je ve stejném formátu jako originál ({"VehicleList": [...]}) + u každého vozidla
pole "_ageSec" (stáří polohy v s). Appka tak jen změní URL.

Endpointy:  GET /traffic   sloučený feed
            GET /health    stav serveru
Jen stdlib, žádné pip balíčky.
"""
from __future__ import annotations

import json, logging, os, signal, threading, time, urllib.request
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

log = logging.getLogger("spoje-feed")
lock = threading.Lock()
store: dict[int, dict] = {}        # id -> {"e": surový záznam, "ts": kdy naposledy živý, "state": int}
payload = b'{"VehicleList":[]}'
status = {"lastOk": 0.0, "failures": 0, "lastError": "", "vehicles": 0, "held": 0, "heldMhd": 0}


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


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/traffic"):
            body = payload
        elif path == "/health":
            age = time.time() - status["lastOk"] if status["lastOk"] else None
            body = json.dumps({**status, "ok": age is not None and age < 60,
                               "upstreamAgeSec": None if age is None else int(age)}).encode()
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "max-age=3")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    load()
    stop = threading.Event()
    threading.Thread(target=poller, args=(stop,), daemon=True).start()
    srv = ThreadingHTTPServer((BIND, PORT), Handler)
    srv.daemon_threads = True

    def bye(*_):
        stop.set()
        threading.Thread(target=srv.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, bye)
    signal.signal(signal.SIGINT, bye)
    log.info("poslouchám na %s:%d, upstream %s", BIND, PORT, UPSTREAM)
    srv.serve_forever()
    save()


if __name__ == "__main__":
    main()
