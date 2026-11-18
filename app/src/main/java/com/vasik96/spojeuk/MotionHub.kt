package com.vasik96.spojeuk

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ViewBox(val s: Double, val w: Double, val n: Double, val e: Double) {
    fun contains(lat: Double, lng: Double) = lat in s..n && lng in w..e

    fun expanded(f: Double): ViewBox {
        val dy = (n - s) * f
        val dx = (e - w) * f
        return ViewBox(s - dy, w - dx, n + dy, e + dx)
    }
}

/**
 * Jedno místo, které drží pohyb všech vozidel. Místo N coroutin / N MarkerState animací
 * běží jedna smyčka (v MapScreen, synchronizovaná s vykreslením každého snímku), která volá [tick]
 * a výsledek rozdá mapě.
 */
internal class MotionHub(
    private val routes: VehicleRoutes,
    private val scope: CoroutineScope,   // main scope z rememberCoroutineScope()
) {
    class Entry(var vehicle: Vehicle) {
        val motion = VehicleMotion(vehicle.lat, vehicle.lng, vehicle.observedAtMs, isTrain = vehicle.train)
        var pos = LatLng(vehicle.lat, vehicle.lng)
        var wantKey: String? = null      // jakou trasu vůz chce (null = pohyb po přímce)
        var routeKey: String? = null     // pro jakou trasu už běží / doběhlo načítání
        var lastTick = 0L
        var routeJob: Job? = null
    }

    private val entries = HashMap<String, Entry>()

    /**
     * Vozy, které na chvíli vypadly z dat (jedna dvě odpovědi bez nich: stáří polohy, výpadek zdroje). Když se do
     * [GRACE_MS] vrátí, naváže se na jejich dosavadní pohyb; jinak by se objevily jinde bez animace (skok).
     */
    private val gone = HashMap<String, Pair<Entry, Long>>()

    private fun obtain(k: String, v: Vehicle, now: Long): Entry {
        entries[k]?.let { return it }
        val revived = gone.remove(k)?.first
            ?.takeIf { haversineMeters(it.pos, LatLng(v.lat, v.lng)) <= REVIVE_MAX_M }
        val e = revived ?: Entry(v)
        entries[k] = e
        return e
    }

    fun positionOf(k: String): LatLng? = entries[k]?.pos

    /**
     * Voláno při každé nové dávce vozidel. Každý nový fix se VŽDY odanimuje z aktuální pozice do nové (A -> B).
     * [followRoads] jen určuje, jestli se mezi A a B jede po trase spoje (silnici), nebo po přímce.
     */
    fun update(list: List<Pair<String, Vehicle>>, followRoads: Boolean) {
        val alive = HashSet<String>(list.size * 2)
        val now = android.os.SystemClock.elapsedRealtime()
        for ((k, v) in list) {
            alive += k
            val e = obtain(k, v, now)
            e.vehicle = v
            // azimut z DPMÚL je směr k nejbližší zastávce vzdušnou čarou, ne směr jízdy: tam směr určí realtime pohyb
            val heading = if (v.source == SourceIds.DPMUL) null else v.heading
            e.motion.onFix(v.lat, v.lng, v.observedAtMs, heading = heading, predict = followRoads)

            e.wantKey = if (followRoads) "${v.tid}|${v.cis}|${v.trip}" else null
            if (e.wantKey == null && e.routeKey != null) {
                e.routeKey = null
                e.routeJob?.cancel()
                e.motion.setRoute(null)
            }
        }
        val iter = entries.iterator()
        while (iter.hasNext()) {
            val (k, e) = iter.next()
            if (k !in alive) {
                // trasa i pohyb se pamatují (načítání trasy dobíhá), vůz se může za chvíli vrátit
                gone[k] = e to now
                iter.remove()
            }
        }
        val g = gone.iterator()
        while (g.hasNext()) {
            val (_, pair) = g.next()
            if (now - pair.second > GRACE_MS) { pair.first.routeJob?.cancel(); g.remove() }
        }
    }

    /**
     * Načte trasy (OSRM / tvary) jen vozidlům ve viditelné oblasti a nejvýš [MAX_LAUNCH] za volání.
     * Dřív se trasa hledala pro všechna vozidla najednou (u PID tisíce), což zatěžovalo CPU i síť.
     * Při oddálení ([enabled] = false) se nic nespouští, vozidla se pohybují po přímce.
     */
    fun ensureRoutes(view: ViewBox?, enabled: Boolean) {
        if (!enabled) return
        var launched = 0
        for (e in entries.values) {
            val want = e.wantKey ?: continue
            if (e.routeKey == want) continue
            if (view != null && !view.contains(e.pos.latitude, e.pos.longitude)) continue
            e.routeKey = want
            e.routeJob?.cancel()
            e.routeJob = scope.launch {
                repeat(4) {
                    val r = routes.get(e.vehicle)
                    if (r != null) {
                        e.motion.setRoute(r)
                        return@launch
                    }
                    delay(20_000)
                }
            }
            if (++launched >= MAX_LAUNCH) break
        }
    }

    /**
     * Přepočítá pozice (levné). Vozidla mimo [view] se posouvají jen zhruba jednou za vteřinu,
     * aby po najetí do pohledu nebyla daleko od skutečné polohy. Vrací true, když se aspoň jedna změnila.
     */
    fun tick(now: Long, view: ViewBox? = null): Boolean {
        var changed = false
        for (e in entries.values) {
            if (view != null && !view.contains(e.pos.latitude, e.pos.longitude)) {
                if (now - e.lastTick < OFFSCREEN_TICK_MS) continue
            }
            e.lastTick = now
            val p = e.motion.positionAt(now)
            if (p != e.pos) {
                e.pos = p
                changed = true
            }
        }
        return changed
    }

    /** Posune jediné vozidlo (zvýrazněné, které se kreslí zvlášť a hýbe se plynule). */
    fun tickKey(k: String, now: Long) {
        val e = entries[k] ?: return
        e.lastTick = now
        e.pos = e.motion.positionAt(now)
    }

    fun clear() {
        entries.values.forEach { it.routeJob?.cancel() }
        entries.clear()
        gone.values.forEach { it.first.routeJob?.cancel() }
        gone.clear()
    }

    private companion object {
        const val MAX_LAUNCH = 12
        const val OFFSCREEN_TICK_MS = 1000L
        const val GRACE_MS = 90_000L
        /** Dál se vůz za [GRACE_MS] reálně nedostane; větší skok = jiný vůz pod stejným klíčem, nenavazovat. */
        const val REVIVE_MAX_M = 2_500.0
    }
}
