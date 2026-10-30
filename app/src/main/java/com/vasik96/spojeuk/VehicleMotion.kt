package com.vasik96.spojeuk

import android.os.SystemClock
import androidx.compose.runtime.withFrameNanos
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.MarkerState
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_R = 6_371_008.8
private const val MIN_MOVE_M = 4.0
private const val MOVE_ANIM_MS = 500.0
private const val MAX_SPEED_MPS = 30.0

private const val MAX_LEAD_S = 24.0
private const val STALE_DECEL_S = 12.0

private const val MAX_ON_ROUTE_OFFSET_M = 50.0
private const val UI_STEP_MS = 12L

private const val KEY_MATCH_M = 500f

private const val STOP_BELOW_M = 50.0
private const val SLOW_BELOW_M = 100.0
private const val SLOW_SPEED_FACTOR = 0.5

private fun haversineMeters(a: LatLng, b: LatLng): Double {
    val p1 = Math.toRadians(a.latitude)
    val p2 = Math.toRadians(b.latitude)
    val dp = p2 - p1
    val dl = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_R * atan2(sqrt(h), sqrt(1 - h))
}

private fun lerpLatLng(a: LatLng, b: LatLng, t: Double) = LatLng(
    a.latitude + (b.latitude - a.latitude) * t,
    a.longitude + (b.longitude - a.longitude) * t
)

/** Stejné vozidlo i když se mezi polly změnil objekt (a u id == 0 i uid). */
internal fun Vehicle.sameAs(o: Vehicle): Boolean =
    this === o || uid == o.uid ||
            (cis.isNotBlank() && trip.isNotBlank() && source == o.source && cis == o.cis && trip == o.trip)

// ───────────────────────── trasa jako polyline ─────────────────────────

internal class PathProjection(val s: Double, val offsetMeters: Double)

/** Trasa jako polyline s kumulativními vzdálenostmi (v metrech). Body musí být ve směru jízdy. */
internal class PathMeasure(private val points: List<LatLng>) {
    private val n = points.size
    private val xs = DoubleArray(n)
    private val ys = DoubleArray(n)
    private val cum = DoubleArray(n)
    val totalMeters: Double

    init {
        require(n >= 2)
        val k = cos(Math.toRadians(points[0].latitude))
        for (i in 0 until n) {
            xs[i] = Math.toRadians(points[i].longitude) * k * EARTH_R
            ys[i] = Math.toRadians(points[i].latitude) * EARTH_R
            if (i > 0) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        totalMeters = cum[n - 1]
    }

    private fun scan(px: Double, py: Double, lo: Double, hi: Double): PathProjection? {
        var bestS = 0.0
        var bestD = Double.MAX_VALUE
        var found = false
        for (i in 0 until n - 1) {
            if (cum[i + 1] < lo || cum[i] > hi) continue
            val ax = xs[i]
            val ay = ys[i]
            val dx = xs[i + 1] - ax
            val dy = ys[i + 1] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-9) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(px - (ax + dx * t), py - (ay + dy * t))
            if (d < bestD) {
                bestD = d
                bestS = cum[i] + (cum[i + 1] - cum[i]) * t
                found = true
            }
        }
        return if (found) PathProjection(bestS, bestD) else null
    }

    /** Průmět bodu na trasu. Vrací null, když je bod dál než maxOffset od trasy. */
    fun project(p: LatLng, nearS: Double? = null, maxOffset: Double = MAX_ON_ROUTE_OFFSET_M): PathProjection? {
        val px = Math.toRadians(p.longitude) * cos(Math.toRadians(points[0].latitude)) * EARTH_R
        val py = Math.toRadians(p.latitude) * EARTH_R
        if (nearS != null) {
            scan(px, py, nearS - 150.0, nearS + 2_500.0)
                ?.takeIf { it.offsetMeters <= maxOffset }
                ?.let { return it }
        }
        return scan(px, py, -1.0, Double.MAX_VALUE)?.takeIf { it.offsetMeters <= maxOffset }
    }

    fun pointAt(s: Double): LatLng {
        val d = s.coerceIn(0.0, totalMeters)
        var idx = java.util.Arrays.binarySearch(cum, d)
        if (idx < 0) idx = -idx - 2
        idx = idx.coerceIn(0, n - 2)
        val seg = cum[idx + 1] - cum[idx]
        val t = if (seg <= 1e-9) 0.0 else ((d - cum[idx]) / seg).coerceIn(0.0, 1.0)
        return lerpLatLng(points[idx], points[idx + 1], t)
    }
}

// ───────────────────────── pohyb jednoho vozidla ─────────────────────────

internal class VehicleMotion(lat: Double, lng: Double, obsMs: Long) {
    private var fixPos = LatLng(lat, lng)
    private var fixObsMs = obsMs
    private var fixRecvMs = SystemClock.elapsedRealtime()
    private var prevPos: LatLng? = null
    private var prevObsMs = 0L
    private var prevRecvMs = 0L

    private var route: PathMeasure? = null
    private var fixS: Double? = null
    private var speedMps = 0.0
    private var dispS: Double? = null      // poslední zobrazená pozice podél trasy
    private var stationaryFixCount = 0

    private val speedSamples = mutableListOf<Double>()

    fun setRoute(r: PathMeasure?) {
        if (r === route) return
        route = r
        dispS = null
        recompute()
    }

    /** Zaregistruje nový fix. Stejný fix podruhé ignoruje. */

    fun onFix(lat: Double, lng: Double, obsMs: Long): Boolean {
        val np = LatLng(lat, lng)
        if (np == fixPos && obsMs == fixObsMs) return false

        val now = SystemClock.elapsedRealtime()

        prevPos = fixPos
        prevObsMs = fixObsMs
        prevRecvMs = fixRecvMs

        fixPos = np
        fixObsMs = obsMs
        fixRecvMs = now

        recompute()
        return true
    }


    /** Určí polohu fixu na trase a rychlost z posledních dvou fixů. Bez trasy se nic nehádá. */
    private fun recompute() {
        val r = route
        if (r == null) {
            fixS = null
            speedMps = 0.0
            stationaryFixCount = 0
            speedSamples.clear()
            return
        }

        val s = r.project(fixPos)?.s
        fixS = s

        val pp = prevPos
        if (s == null || pp == null) {
            speedMps = 0.0
            stationaryFixCount = 0
            speedSamples.clear()
            return
        }

        val ps = r.project(pp, nearS = s - 1_000.0)?.s
        if (ps == null) {
            speedMps = 0.0
            stationaryFixCount = 0
            speedSamples.clear()
            return
        }

        val actualDistance = haversineMeters(fixPos, pp)
        val routeDistance = s - ps

        // Pod 50 m zastavit až do další aktualizace.
        if (actualDistance < STOP_BELOW_M || routeDistance <= 0.0) {
            speedMps = 0.0
            stationaryFixCount++
            speedSamples.clear()
            return
        }

        val dtObs = (fixObsMs - prevObsMs) / 1000.0
        val dtRecv = (fixRecvMs - prevRecvMs) / 1000.0
        val dt = if (dtObs in 1.0..30.0) dtObs else dtRecv

        if (dt !in 1.0..30.0) return

        var measuredSpeed = (routeDistance / dt)
            .coerceIn(0.0, MAX_SPEED_MPS)

        // Mezi 50 a 100 m zpomalit.
        if (actualDistance < SLOW_BELOW_M) {
            measuredSpeed *= SLOW_SPEED_FACTOR
        }

        // Uchováváme posledních 5 měření.
        speedSamples.add(measuredSpeed)

        while (speedSamples.size > 5) {
            speedSamples.removeAt(0)
        }

        // Medián potlačí náhodné GPS skoky.
        val sorted = speedSamples.sorted()
        val medianSpeed = sorted[sorted.size / 2]

        // Plynulé přibližování k vypočítané rychlosti.
        speedMps = if (speedMps < 0.5) {
            medianSpeed
        } else {
            speedMps * 0.65 + medianSpeed * 0.35
        }

        stationaryFixCount = 0
    }

    /** Skončí sama, když je pohyb hotový. Nový fix ji zruší (LaunchedEffect) a spustí znovu od aktuální značky. */
    suspend fun animate(marker: MarkerState, predict: Boolean) {
        val r = route
        val sFix = fixS
        if (predict && r != null && sFix != null) {
            followRoute(marker, r, sFix)
        } else {
            dispS = null
            glide(marker, fixPos)
        }
    }

    /** Pozice 1 -> pozice 2, vždy MOVE_ANIM_MS, bez hádání. */
    private suspend fun glide(marker: MarkerState, to: LatLng) {
        val from = marker.position
        if (from == to) return
        val t0 = SystemClock.elapsedRealtime()
        var lastUi = t0 - UI_STEP_MS
        while (true) {
            withFrameNanos { }
            val now = SystemClock.elapsedRealtime()
            val frac = ((now - t0) / MOVE_ANIM_MS).coerceIn(0.0, 1.0)
            if (frac >= 1.0) {
                marker.position = to; return
            }
            if (now - lastUi >= UI_STEP_MS) {
                marker.position = lerpLatLng(from, to, frac)
                lastUi = now
            }
        }
    }

    /** Predikce podél trasy. Prvních MOVE_ANIM_MS se značka srovná na predikovanou pozici, pak ji sleduje. */
    private suspend fun followRoute(
        marker: MarkerState,
        r: PathMeasure,
        sFix: Double
    ) {
        val startPos = marker.position
        val t0 = SystemClock.elapsedRealtime()
        var lastUi = t0 - UI_STEP_MS

        while (true) {
            withFrameNanos { }

            val now = SystemClock.elapsedRealtime()
            val sinceRecv = ((now - fixRecvMs) / 1000.0).coerceAtLeast(0.0)

            val predictedSeconds = when {
                sinceRecv <= MAX_LEAD_S -> sinceRecv

                sinceRecv >= MAX_LEAD_S + STALE_DECEL_S ->
                    MAX_LEAD_S + STALE_DECEL_S / 2.0

                else -> {
                    val x = (sinceRecv - MAX_LEAD_S) / STALE_DECEL_S
                    MAX_LEAD_S + STALE_DECEL_S * (x - x * x / 2.0)
                }
            }

            var s = min(
                sFix + speedMps * predictedSeconds,
                r.totalMeters
            )

            dispS = s


            val frac = ((now - t0) / MOVE_ANIM_MS).coerceIn(0.0, 1.0)
            val target = r.pointAt(s)

            val pos = if (frac >= 1.0) {
                target
            } else {
                lerpLatLng(startPos, target, frac)
            }

            if (now - lastUi >= UI_STEP_MS) {
                marker.position = pos
                lastUi = now
            }
        }
    }
}

// ───────────────────────── stabilní klíče vozidel ─────────────────────────

/**
 * Stabilní klíč pro Compose `key(...)`. uid u vozidel s id == 0 obsahuje souřadnice,
 * takže se při každém pohybu mění a marker by se pokaždé zahodil a vytvořil jinde.
 */


internal class VehicleKeyer {

    private data class Prev(
        val key: String,
        val source: String,
        val id: Int,
        val cis: String,
        val trip: String,
        val tid: Int,
        val line: String,
        val pos: LatLng,
        val heading: Double?
    )

    private data class Match(
        val distance: Float,
        val currentIndex: Int,
        val previousIndex: Int
    )

    private var prev: List<Prev> = emptyList()
    private var seq = 0

    private companion object {
        const val CROSS_SOURCE_MATCH_M = 500f
        const val LOOSE_MATCH_M = 100f
        const val AMBIGUITY_MARGIN_M = 60f
        const val MAX_DIRECTION_DIFF = 55.0
    }

    private fun normLine(line: String): String =
        line.trim().uppercase().filterNot { it.isWhitespace() }

    private fun directionCompatible(a: Double?, b: Double?): Boolean {
        if (a == null || b == null) return true

        val diff = kotlin.math.abs((a - b + 540.0) % 360.0 - 180.0)
        return diff <= MAX_DIRECTION_DIFF
    }

    fun assign(list: List<Vehicle>): List<Pair<String, Vehicle>> {
        val usedKeys = HashSet<String>()
        val usedPrev = HashSet<Int>()
        val out = arrayOfNulls<String>(list.size)

        fun unique(base: String): String {
            var key = base
            var n = 2
            while (!usedKeys.add(key)) {
                key = "$base~${n++}"
            }
            return key
        }

        // 1. Zachování identity v rámci stejného zdroje.
        for ((i, v) in list.withIndex()) {
            val pi = prev.indices.firstOrNull { p ->
                if (p in usedPrev) return@firstOrNull false

                val old = prev[p]
                if (old.source != v.source) return@firstOrNull false

                when {
                    v.id != 0 -> old.id == v.id
                    v.cis.isNotBlank() && v.trip.isNotBlank() ->
                        old.cis == v.cis && old.trip == v.trip
                    else -> false
                }
            }

            if (pi != null) {
                val key = prev[pi].key
                if (usedKeys.add(key)) {
                    out[i] = key
                    usedPrev.add(pi)
                }
            }
        }

        // 2. Kandidáti pro spárování mezi DÚK a DPMÚL.
        val candidates = ArrayList<Match>()

        for (i in list.indices) {
            if (out[i] != null) continue

            val v = list[i]
            val line = normLine(v.line)

            if (line.isBlank()) continue

            for (pi in prev.indices) {
                if (pi in usedPrev) continue

                val old = prev[pi]

                if (old.source == v.source) continue
                if (line != old.line) continue

                val distance = distanceMeters(
                    LatLng(v.lat, v.lng),
                    old.pos
                )

                val sameTid =
                    v.tid != 0 &&
                            old.tid != 0 &&
                            v.tid == old.tid

                val sameTrip =
                    v.cis.isNotBlank() &&
                            v.trip.isNotBlank() &&
                            old.cis == v.cis &&
                            old.trip == v.trip

                val strongIdentity = sameTid || sameTrip

                val maxDistance = if (strongIdentity) {
                    CROSS_SOURCE_MATCH_M
                } else {
                    LOOSE_MATCH_M
                }

                if (distance > maxDistance) continue

                if (!directionCompatible(v.heading, old.heading)) {
                    continue
                }

                candidates.add(
                    Match(distance, i, pi)
                )
            }
        }

        // 3. Najdeme jednoznačně nejlepší shody z obou stran.
        val bestCurrent = candidates
            .groupBy { it.currentIndex }
            .mapValues { (_, matches) ->
                val sorted = matches.sortedBy { it.distance }

                if (
                    sorted.size == 1 ||
                    sorted[1].distance - sorted[0].distance >= AMBIGUITY_MARGIN_M
                ) {
                    sorted[0]
                } else {
                    null
                }
            }

        val bestPrevious = candidates
            .groupBy { it.previousIndex }
            .mapValues { (_, matches) ->
                val sorted = matches.sortedBy { it.distance }

                if (
                    sorted.size == 1 ||
                    sorted[1].distance - sorted[0].distance >= AMBIGUITY_MARGIN_M
                ) {
                    sorted[0]
                } else {
                    null
                }
            }

        // 4. Přijmeme pouze vzájemně nejlepší shody.
        for (match in candidates.sortedBy { it.distance }) {
            val i = match.currentIndex
            val pi = match.previousIndex

            if (out[i] != null || pi in usedPrev) continue

            if (bestCurrent[i] != match) continue
            if (bestPrevious[pi] != match) continue

            val key = prev[pi].key
            if (!usedKeys.add(key)) continue

            out[i] = key
            usedPrev.add(pi)
        }

        // 5. Nová vozidla dostanou vlastní klíč.
        for (i in list.indices) {
            if (out[i] != null) continue

            val v = list[i]

            val base = when {
                v.id != 0 -> v.uid

                v.cis.isNotBlank() && v.trip.isNotBlank() ->
                    "${v.source}#${v.cis}/${v.trip}"

                else -> "${v.source}#h${++seq}"
            }

            out[i] = unique(base)
        }

        // 6. Uložíme aktuální vozidla pro další aktualizaci.
        prev = list.indices.map { i ->
            val v = list[i]

            Prev(
                key = out[i]!!,
                source = v.source,
                id = v.id,
                cis = v.cis,
                trip = v.trip,
                tid = v.tid,
                line = normLine(v.line),
                pos = LatLng(v.lat, v.lng),
                heading = v.heading
            )
        }

        return list.indices.map { i ->
            out[i]!! to list[i]
        }
    }
}



// ───────────────────────── trasy všech vozidel ─────────────────────────

/** Trasa spoje pro každé vozidlo (shapes, jinak OSRM), cache podle tid. Nezávisí na tom, co je vykreslené. */
internal class VehicleRoutes(private val api: TransitRepository) {
    private val byTid = object : LinkedHashMap<Int, PathMeasure?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PathMeasure?>?) = size > 150
    }
    private val gate = Semaphore(3)   // OSRM / SQLite nezahltíme sty souběžných dotazů

    suspend fun get(v: Vehicle): PathMeasure? {
        val tid = api.resolveTripId(v) ?: return null
        if (byTid.containsKey(tid)) return byTid[tid]
        val pts = gate.withPermit { api.routeForTrip(tid) }
        val pm = if (pts.size >= 2) PathMeasure(pts.map { LatLng(it.first, it.second) }) else null
        byTid[tid] = pm
        return pm
    }
}