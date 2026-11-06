package com.vasik96.spojeuk

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.MarkerState
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
        val motion = VehicleMotion(vehicle.lat, vehicle.lng, vehicle.observedAtMs)
        val state = MarkerState(LatLng(vehicle.lat, vehicle.lng)) // jen pro Google
        var pos = LatLng(vehicle.lat, vehicle.lng)
        var routeKey: String? = null
        var routeJob: Job? = null
    }

    private val entries = HashMap<String, Entry>()

    private fun obtain(k: String, v: Vehicle) = entries.getOrPut(k) { Entry(v) }

    fun markerState(k: String, v: Vehicle): MarkerState = obtain(k, v).state

    fun positionOf(k: String): LatLng? = entries[k]?.pos

    /**
     * Voláno při každé nové dávce vozidel. Každý nový fix se VŽDY odanimuje z aktuální pozice do nové (A -> B).
     * [followRoads] jen určuje, jestli se mezi A a B jede po trase spoje (silnici), nebo po přímce.
     */
    fun update(list: List<Pair<String, Vehicle>>, followRoads: Boolean) {
        val alive = HashSet<String>(list.size * 2)
        for ((k, v) in list) {
            alive += k
            val e = obtain(k, v)
            e.vehicle = v
            e.motion.onFix(v.lat, v.lng, v.observedAtMs, predict = followRoads)

            val want = if (followRoads) "${v.tid}|${v.cis}|${v.trip}" else null
            if (e.routeKey != want) {
                e.routeKey = want
                e.routeJob?.cancel()
                e.motion.setRoute(null)
                if (want != null) {
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
                }
            }
        }
        val iter = entries.iterator()
        while (iter.hasNext()) {
            val (k, e) = iter.next()
            if (k !in alive) {
                e.routeJob?.cancel()
                iter.remove()
            }
        }
    }

    /** Přepočítá pozice (levné). Vrací true, když se aspoň jedna změnila. */
    fun tick(now: Long): Boolean {
        var changed = false
        for (e in entries.values) {
            val p = e.motion.positionAt(now)
            if (p != e.pos) {
                e.pos = p
                changed = true
            }
        }
        return changed
    }

    /** Google: zapíše do MarkerState jen vozidla ve viditelné oblasti. */
    fun applyToMarkerStates(view: ViewBox?) {
        for (e in entries.values) {
            val p = e.pos
            if (view != null && !view.contains(p.latitude, p.longitude)) continue
            if (e.state.position != p) e.state.position = p
        }
    }

    fun clear() {
        entries.values.forEach { it.routeJob?.cancel() }
        entries.clear()
    }
}