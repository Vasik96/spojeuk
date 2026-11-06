package com.vasik96.spojeuk

import android.os.SystemClock
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_R = 6_371_008.8
private const val MAX_ON_ROUTE_OFFSET_M = 50.0

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
            (id != 0 && o.id != 0 && sameVehicleId(id, o.id)) ||
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

    /** Index úseku, ve kterém (nebo těsně před kterým) leží vzdálenost d. */
    private fun segmentStart(d: Double): Int {
        if (d <= 0.0) return 0
        var idx = java.util.Arrays.binarySearch(cum, d)
        if (idx < 0) idx = -idx - 2
        return idx.coerceIn(0, n - 2)
    }

    private fun scan(px: Double, py: Double, lo: Double, hi: Double): PathProjection? {
        var bestS = 0.0
        var bestD = Double.MAX_VALUE
        var found = false
        var i = segmentStart(lo)
        while (i < n - 1) {
            if (cum[i] > hi) break
            if (cum[i + 1] >= lo) {
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
            i++
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

/** Délka animace bez predikce z bodu A do bodu B (1 s). */
private const val FAST_ANIM_DURATION_MS = 1_000.0

/** Výchozí délka animace při predikci pohybu (5.8 s, odpovídá ~6s pollingu). */
private const val PREDICT_ANIM_DURATION_MS = 5_800.0

/** Skok delší než tohle není jízda (výměna vozu za jiný, chyba zdroje): bez animace. */
private const val TELEPORT_M = 3_000.0

/**
 * Každá nová poloha (bod B) se vždy odanimuje z právě zobrazené pozice (bod A) do B.
 * Bez trasy po přímce, s trasou po silnici. Další fix navazuje z aktuálně zobrazené pozice,
 * takže se vůz nikdy neskokne. Pohyb je čistá funkce času ([positionAt]), bez Compose state
 * a bez vlastních coroutin; volá ho jedna smyčka z [MotionHub] pro všchna vozidla.
 */
internal class VehicleMotion(lat: Double, lng: Double, obsMs: Long) {
    private var fixObsMs = obsMs
    private var fixRecvMs = SystemClock.elapsedRealtime()

    // aktuální animace A -> B
    private var from = LatLng(lat, lng)
    private var to = from
    private var t0 = fixRecvMs
    private var durMs = 1.0

    private var route: PathMeasure? = null
    private var sFrom = Double.NaN
    private var sTo = Double.NaN
    // odchylka skutečných bodů od trasy (lat/lng delta), aby animace začínala a končila přesně v A a B
    private var offFromLat = 0.0
    private var offFromLng = 0.0
    private var offToLat = 0.0
    private var offToLng = 0.0

    private var shown = from

    /** Naplánuje animaci A -> B po trase (pokud je a sedí), jinak po přímce. */
    private fun plan(a: LatLng, b: LatLng, now: Long, ms: Double) {
        from = a
        to = b
        t0 = now
        durMs = ms.coerceAtLeast(1.0)
        sFrom = Double.NaN
        sTo = Double.NaN

        val r = route ?: return
        val sb = r.project(b)?.s ?: return
        val sa = r.project(a, nearS = sb - 400.0)?.s ?: return
        val straight = haversineMeters(a, b)
        val along = sb - sa
        // trasa musí vést ve směru jízdy a nesmí být výrazně delší než vzdušná čára (jiná větev / smyčka)
        if (along < 0.0 || along > straight * 1.6 + 60.0) return

        val pa = r.pointAt(sa)
        val pb = r.pointAt(sb)
        offFromLat = a.latitude - pa.latitude
        offFromLng = a.longitude - pa.longitude
        offToLat = b.latitude - pb.latitude
        offToLng = b.longitude - pb.longitude
        sFrom = sa
        sTo = sb
    }

    fun setRoute(r: PathMeasure?) {
        if (r === route) return
        val now = SystemClock.elapsedRealtime()
        val a = positionAt(now)
        route = r
        if (r == null) {
            sFrom = Double.NaN
            sTo = Double.NaN
        }
        val maxDur = if (r != null) PREDICT_ANIM_DURATION_MS else FAST_ANIM_DURATION_MS
        val remaining = (t0 + durMs - now).coerceIn(100.0, maxDur)
        plan(a, to, now, remaining)
    }

    /** Zaregistruje nový fix (= nový bod B). Stejný fix podruhé ignoruje. */
    fun onFix(lat: Double, lng: Double, obsMs: Long, predict: Boolean = false): Boolean {
        val np = LatLng(lat, lng)
        if (np == to) {
            fixObsMs = obsMs
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val dtRecv = (now - fixRecvMs).toDouble()

        fixObsMs = obsMs
        fixRecvMs = now

        val a = positionAt(now)   // navazuje se z toho, co je právě vidět
        if (haversineMeters(a, np) > TELEPORT_M) {
            from = np; to = np; t0 = now; durMs = 1.0
            sFrom = Double.NaN; sTo = Double.NaN
            shown = np
            return true
        }

        val targetDur = if (predict) {
            if (dtRecv in 3_000.0..10_000.0) dtRecv.coerceIn(4_500.0, 6_000.0)
            else PREDICT_ANIM_DURATION_MS
        } else {
            FAST_ANIM_DURATION_MS
        }

        plan(a, np, now, targetDur)
        return true
    }

    /** Pozice v čase [now] (SystemClock.elapsedRealtime). Levné, bez alokací kromě LatLng. */
    fun positionAt(now: Long): LatLng {
        val f = ((now - t0) / durMs).coerceIn(0.0, 1.0)
        val r = route
        val pos = when {
            f >= 1.0 -> to
            r == null || sFrom.isNaN() -> lerpLatLng(from, to, f)
            else -> {
                val p = r.pointAt(sFrom + (sTo - sFrom) * f)
                LatLng(
                    p.latitude + offFromLat + (offToLat - offFromLat) * f,
                    p.longitude + offFromLng + (offToLng - offFromLng) * f
                )
            }
        }
        shown = pos
        return pos
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

    @Synchronized
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

        // 1. Zachování identity v rámci stejného zdroje nebo přes stejné ID vozidla.
        for ((i, v) in list.withIndex()) {
            val pi = prev.indices.firstOrNull { p ->
                if (p in usedPrev) return@firstOrNull false

                val old = prev[p]

                when {
                    v.id != 0 && old.id != 0 && sameVehicleId(v.id, old.id) -> true
                    old.source == v.source && v.id != 0 -> old.id == v.id
                    old.source == v.source && v.cis.isNotBlank() && v.trip.isNotBlank() ->
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
        // stavba PathMeasure (trigonometrie přes celou trasu) mimo main vlákno
        val pm = withContext(Dispatchers.Default) {
            if (pts.size >= 2) PathMeasure(pts.map { LatLng(it.first, it.second) }) else null
        }
        byTid[tid] = pm
        return pm
    }
}