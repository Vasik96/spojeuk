package com.vasik96.spojeuk

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

// ───────────────────────── přiřazení vozu ke spoji ─────────────────────────

data class TripMatchResult(val tid: Int, val exact: Boolean)

/** Strategie, jak z polohy vozu zjistit spoj z jízdního řádu. Každý zdroj si vybírá svou. */
fun interface TripMatcher {
    fun match(v: Vehicle, tt: Timetable): TripMatchResult?
}

object TripMatchers {
    /** Zdroj dává přesné číslo spoje (ČIS cis + trip). */
    val byCisNumber = TripMatcher { v, tt ->
        tt.findTripId(v.cis, v.trip)?.let { TripMatchResult(it, exact = true) }
    }

    /** Zdroj číslo spoje nezná: hádá se podle linky, polohy u zastávky, času, směru a cíle. */
    fun byPosition(agencyKeywords: List<String> = emptyList()) = TripMatcher { v, tt ->
        tt.locator.matchByPosition(v, agencyKeywords)?.let { TripMatchResult(it, exact = false) }
    }
}

/** Doplní vozu spoj z jízdního řádu (tid, cis/trip, případně linku). */
object TripEnricher {
    fun enrich(v: Vehicle, source: VehicleSource, tt: Timetable): Vehicle {
        val m = source.tripMatcher.match(v, tt) ?: return v
        val trip = tt.trips[m.tid] ?: return v
        var out = v.copy(tid = m.tid, tripMatch = if (m.exact) TripMatch.EXACT else TripMatch.HEURISTIC)
        if (!m.exact) out = out.copy(cis = trip.cis.ifBlank { v.cis }, trip = trip.num.ifBlank { v.trip })
        if (source.adoptsTimetableLine && trip.line.isNotBlank() && trip.line != "?") out = out.copy(line = trip.line)
        return out
    }
}

// ───────────────────────── geometrie spoje ─────────────────────────

/** Kolik metrů od začátku spoje (po lomené čáře zastávek) vůz je. */
fun interface TripProgress {
    fun along(tid: Int, lat: Double, lng: Double): Double?
}

class TripLocator(private val tt: Timetable) : TripProgress {

    private class Polyline(val lat: DoubleArray, val lng: DoubleArray, val cum: DoubleArray)

    private val polylines = ConcurrentHashMap<Int, Polyline>()

    // ───────── postup po trase ─────────

    override fun along(tid: Int, lat: Double, lng: Double): Double? {
        val line = polylines[tid] ?: buildPolyline(tid) ?: return null
        val cosLat = cos(Math.toRadians(lat))
        var bestDist = Double.MAX_VALUE
        var bestPos = 0.0
        for (i in 0 until line.lat.size - 1) {
            val ax = (line.lng[i] - lng) * Geo.M_PER_DEG_LNG * cosLat
            val ay = (line.lat[i] - lat) * Geo.M_PER_DEG_LAT
            val dx = (line.lng[i + 1] - lng) * Geo.M_PER_DEG_LNG * cosLat - ax
            val dy = (line.lat[i + 1] - lat) * Geo.M_PER_DEG_LAT - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestDist) {
                bestDist = d
                bestPos = line.cum[i] + t * (line.cum[i + 1] - line.cum[i])
            }
        }
        return bestPos
    }

    private fun buildPolyline(tid: Int): Polyline? {
        val pts = ArrayList<Stop>()
        for (sid in tt.tripStops(tid)) {
            val s = tt.stops[sid] ?: continue
            if (s.hasCoords) pts.add(s)
        }
        if (pts.size < 2) return null
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] + Geo.distanceM(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng)
        }
        val line = Polyline(DoubleArray(pts.size) { pts[it].lat }, DoubleArray(pts.size) { pts[it].lng }, cum)
        polylines[tid] = line
        return line
    }

    // ───────── přiřazení podle polohy ─────────

    /**
     * Spoj, který vůz právě jede. Skóre (nižší = lepší): vzdálenost od zastávky + půl vteřiny odchylky
     * od jízdního řádu (po odečtení zpoždění); penalizace za opačný směr, bonus za shodný cíl a číslo spoje.
     */
    fun matchByPosition(v: Vehicle, agencyKeywords: List<String>): Int? {
        val now = nowPrague()
        val wantedHead = foldText(v.destination)
        var best: Int? = null
        var bestScore = SCORE_LIMIT
        for (daysBack in 0L..1L) {
            val day = now.toLocalDate().minusDays(daysBack)
            val rel = secondsSinceMidnight(day, now) - v.delay * 60.0
            val active = tt.activeSvcs(day)
            for (tid in tt.tripsOfLine(v.line, agencyKeywords)) {
                val trip = tt.trips[tid] ?: continue
                if (trip.svc !in active || rel < trip.t0 - 900 || rel > trip.t1 + 900) continue
                val score = scoreTrip(v, tid, trip, rel, wantedHead) ?: continue
                if (score < bestScore) { best = tid; bestScore = score }
            }
        }
        return best
    }

    private fun scoreTrip(v: Vehicle, tid: Int, trip: DbTrip, rel: Double, wantedHead: String): Double? {
        val times = tt.tripTimes(tid) ?: return null
        var score = Double.MAX_VALUE
        var legHeading: Double? = null
        for (i in times.stops.indices) {
            val s = tt.stops[times.stops[i]] ?: continue
            if (!s.hasCoords) continue
            val d = Geo.distanceM(v.lat, v.lng, s.lat, s.lng)
            if (d > MAX_STOP_DIST_M) continue
            val c = d + 0.5 * abs(times.dep[i] - rel)
            if (c < score) { score = c; legHeading = headingAt(times.stops, i) }
        }
        if (score == Double.MAX_VALUE) return null

        val vehicleHeading = v.heading
        if (vehicleHeading != null && legHeading != null && Geo.angleDiff(vehicleHeading, legHeading) > 100) score += 600
        val tripHead = foldText(trip.head)
        if (wantedHead.isNotBlank() && tripHead.isNotBlank() && (wantedHead in tripHead || tripHead in wantedHead)) score -= 250
        if (v.trip.isNotBlank() && trip.num == v.trip) score -= 1000
        return score
    }

    private fun headingAt(ids: IntArray, i: Int): Double? {
        val j = if (i + 1 < ids.size) i else i - 1
        if (j < 0) return null
        val a = tt.stops[ids[j]]
        val b = tt.stops[ids[j + 1]]
        if (a == null || b == null || !a.hasCoords || !b.hasCoords) return null
        return Geo.bearingDeg(a.lat, a.lng, b.lat, b.lng)
    }

    /** Záloha pro vozy bez použitelného cis: dnešní spoj stejné linky vedoucí nejblíž vozu. */
    fun nearestOnLine(line: String, lat: Double, lng: Double): Int? {
        val now = nowPrague()
        val today = now.toLocalDate()
        val rel = secondsSinceMidnight(today, now)
        val active = tt.activeSvcs(today)
        var best: Int? = null
        var bestDist = Double.MAX_VALUE
        for ((tid, t) in tt.trips) {
            if (t.line != line || t.svc !in active) continue
            if (rel < t.t0 - 600 || rel > t.t1 + 600) continue
            for (sid in tt.tripStops(tid)) {
                val s = tt.stops[sid] ?: continue
                if (!s.hasCoords) continue
                val d = Geo.distanceM(lat, lng, s.lat, s.lng)
                if (d < bestDist) { bestDist = d; best = tid }
            }
        }
        return if (bestDist <= MAX_STOP_DIST_M) best else null
    }

    private companion object {
        const val SCORE_LIMIT = 800.0
        const val MAX_STOP_DIST_M = 400.0
    }
}
