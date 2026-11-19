package com.vasik96.spojeuk

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

// ───────────────────────── přiřazení vozu ke spoji ─────────────────────────

/**
 * Jak daleko od zastávky spoje vůz ještě smí být. Stanice metra jsou ~1 km od sebe (mezi nimi bývá souprava
 * přes 400 m od nejbližší), meziměstské autobusy jezdí dlouhé úseky bez zastávek.
 */
internal fun maxStopDistance(v: Vehicle): Double =
    if (v.mode == "metro" || (v.source == SourceIds.PID && isMetroLine(v.line))) 1_500.0 else 700.0

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

    val byCisOrTrain = TripMatcher { v, tt ->
        if (v.train) byTrainNumber.match(v, tt) else byCisNumber.match(v, tt)
    }

    /** Vlaky: zdroj dává číslo vlaku (SŽ `tn`) ve `v.cis`; spojí se s vlakem z CZPTT. */
    val byTrainNumber = TripMatcher { v, tt ->
        tt.findTrainTripId(v.cis)?.let { TripMatchResult(it, exact = true) }
    }

    /** Zdroj číslo spoje nezná: hádá se podle linky, polohy u zastávky, času, směru a cíle. */
    fun byPosition(agencyKeywords: List<String> = emptyList()) = TripMatcher { v, tt ->
        tt.locator.matchByPosition(v, agencyKeywords)?.let { TripMatchResult(it, exact = false) }
    }
}

/** Doplní vozu spoj z jízdního řádu (tid, cis/trip, případně linku). */
object TripEnricher {
    private val dpmulMatcher = TripMatchers.byPosition(DPMUL_AGENCY_KEYWORDS)

    /**
     * Vůz je už sloučený ze zdrojů, takže se strategie volí podle něj: vlak podle čísla vlaku, autobus podle
     * přesného čísla spoje (cis + trip z DÚK) a když chybí a vůz zná DPMÚL (jen poloha), hádá se podle polohy.
     */
    private fun match(v: Vehicle, tt: Timetable): TripMatchResult? = when {
        v.train -> TripMatchers.byTrainNumber.match(v, tt)
        else -> TripMatchers.byCisNumber.match(v, tt)
            ?: if (v.source == SourceIds.DPMUL || SourceIds.DPMUL in v.sources) dpmulMatcher.match(v, tt)
            else if (v.source == SourceIds.PID || SourceIds.PID in v.sources) matchCached(v, tt, pidMatcher)
            else null
    }

    // PID: vozy často nemají číslo spoje, které by sedělo na jízdní řád (jiné ČIS / číslování), takže se podle
    // polohy a času hledá spoj stejné linky, jinak by u pražských vozidel nešel najít vůz ke spoji ani trasa.
    // Hledání je drahé a PID jsou tisíce vozů, proto se výsledek na chvíli pamatuje.
    private val pidMatcher = TripMatchers.byPosition()
    private class CachedMatch(val line: String, val result: TripMatchResult?, val atMs: Long)
    private val pidCache = java.util.concurrent.ConcurrentHashMap<String, CachedMatch>()
    private const val PID_CACHE_MS = 90_000L
    private const val PID_CACHE_MAX = 6_000

    private fun matchCached(v: Vehicle, tt: Timetable, m: TripMatcher): TripMatchResult? {
        val now = System.currentTimeMillis()
        val c = pidCache[v.uid]
        if (c != null && c.line == v.line && now - c.atMs < PID_CACHE_MS) return c.result
        val r = m.match(v, tt)
        if (pidCache.size > PID_CACHE_MAX) pidCache.clear()
        pidCache[v.uid] = CachedMatch(v.line, r, now)
        return r
    }

    /** Linku ze spoje přebírají vozy z DÚK, PID a vlaky; DPMÚL má vlastní (správné) označení linky. */
    private fun adoptsTimetableLine(v: Vehicle) = v.source == SourceIds.DUK || v.source == SourceIds.TRAIN || v.source == SourceIds.PID

    fun enrich(v: Vehicle, tt: Timetable): Vehicle {
        val m = match(v, tt)
        if (m == null) {
            // vlak, který teď podle JŘ nejede (souprava stojí po oběhu, DÚK posílá vlastní číslo linky "905"):
            // aspoň skutečná linka podle čísla vlaku (U5, S88) místo "905" / "Os 19024"
            if (v.train && adoptsTimetableLine(v)) tt.trainLine(v.cis)?.let { return v.copy(line = it) }
            return v
        }
        val trip = tt.trips[m.tid] ?: return v
        var out = v.copy(tid = m.tid, tripMatch = if (m.exact) TripMatch.EXACT else TripMatch.HEURISTIC)
        // PMDP: trip je číslo spoje dopravce, podle něj se stahuje trasa (TransitRepository.pmdpTrip); nepřepisovat
        if (!m.exact && v.source != SourceIds.PMDP) out = out.copy(cis = trip.cis.ifBlank { v.cis }, trip = trip.num.ifBlank { v.trip })
        if (adoptsTimetableLine(v) && trip.line.isNotBlank() && trip.line != "?") out = out.copy(line = trip.line)

        if (v.agency.isBlank() && trip.agency.isNotBlank()) {
            out = out.copy(agency = trip.agency)
        }

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
            val s = tt.stop(sid) ?: continue
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
        val maxDist = maxStopDistance(v)
        var best: Int? = null
        var bestScore = SCORE_LIMIT
        for (daysBack in 0L..1L) {
            val day = now.toLocalDate().minusDays(daysBack)
            val rel = secondsSinceMidnight(day, now) - v.delay * 60.0
            val active = tt.activeSvcs(day)
            val trips = tt.trips
            for (tid in tt.tripsOfLine(v.line, agencyKeywords)) {
                // filtr přes sloupce: DbTrip (nový objekt) až pro spoje, které v tu dobu jedou; linka má tisíce
                // spojů a párují se stovky vozů => jinak stovky MB odpadu a GC zasekávající UI
                if (!trips.has(tid) || trips.svc[tid] !in active || rel < trips.t0[tid] - 900 || rel > trips.t1[tid] + 900) continue
                val trip = trips[tid] ?: continue
                val score = scoreTrip(v, tid, trip, rel, wantedHead, maxDist) ?: continue
                if (score < bestScore) { best = tid; bestScore = score }
            }
        }
        return best
    }

    private fun scoreTrip(v: Vehicle, tid: Int, trip: DbTrip, rel: Double, wantedHead: String, maxDist: Double): Double? {
        val times = tt.tripTimes(tid) ?: return null
        // Vzdálenost k ÚSEKU mezi dvěma po sobě jdoucími zastávkami a čas interpolovaný po něm (ne jen k zastávkám):
        // vůz uprostřed dlouhého úseku (AE Dejvická → Letiště, příměstské linky) je od zastávek daleko, ale na trase.
        var score = Double.MAX_VALUE
        var legHeading = Double.NaN   // NaN = žádný úsek (bez zabaleného Double?)
        val k = cos(Math.toRadians(v.lat)) * Geo.M_PER_DEG_LNG
        val px = v.lng * k
        val py = v.lat * Geo.M_PER_DEG_LAT
        var prev = -1
        for (j in times.stops.indices) {
            val b = tt.stop(times.stops[j]) ?: continue
            if (!b.hasCoords) continue
            val i = prev
            prev = j
            if (i < 0) continue
            val a = tt.stop(times.stops[i])!!
            val ax = a.lng * k; val ay = a.lat * Geo.M_PER_DEG_LAT
            val dx = b.lng * k - ax; val dy = b.lat * Geo.M_PER_DEG_LAT - ay
            val len2 = dx * dx + dy * dy
            val f = if (len2 < 1e-9) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(px - ax - dx * f, py - ay - dy * f)
            if (d > maxDist) continue
            val t = times.dep[i] + (times.arr[j] - times.dep[i]) * f
            val c = d + 0.5 * abs(t - rel)
            if (c < score) { score = c; legHeading = Geo.bearingDeg(a.lat, a.lng, b.lat, b.lng) }
        }
        if (score == Double.MAX_VALUE) return null

        val vehicleHeading = v.heading
        if (vehicleHeading != null && !legHeading.isNaN()) {
            if (Geo.angleDiff(vehicleHeading, legHeading) > 90) return null
        }
        val tripHead = tt.foldedHead(trip.head)
        if (wantedHead.isNotBlank() && tripHead.isNotBlank()) {
            if (wantedHead in tripHead || tripHead in wantedHead) {
                score -= 250
            } else {
                return null
            }
        }
        if (v.finalStation != 0 && times.stops.isNotEmpty()) {
            if (v.finalStation == times.stops.last()) {
                score -= 300
            } else if (v.finalStation == times.stops.first()) {
                return null
            }
        }
        if (v.trip.isNotBlank() && trip.num == v.trip) score -= 1000
        return score
    }

    /** Záloha pro vozy bez použitelného cis: dnešní spoj stejné linky vedoucí nejblíž vozu. */
    fun nearestOnLine(line: String, lat: Double, lng: Double, maxDist: Double = DEFAULT_STOP_DIST_M): Int? {
        val now = nowPrague()
        val today = now.toLocalDate()
        val rel = secondsSinceMidnight(today, now)
        val active = tt.activeSvcs(today)
        var best: Int? = null
        var bestDist = Double.MAX_VALUE
        val trips = tt.trips
        for (tid in 0..trips.maxId) {
            if (trips.line[tid] != line || trips.svc[tid] !in active) continue
            if (rel < trips.t0[tid] - 600 || rel > trips.t1[tid] + 600) continue
            for (sid in tt.tripStops(tid)) {
                val s = tt.stop(sid) ?: continue
                if (!s.hasCoords) continue
                val d = Geo.distanceM(lat, lng, s.lat, s.lng)
                if (d < bestDist) { bestDist = d; best = tid }
            }
        }
        return if (bestDist <= maxDist) best else null
    }

    private companion object {
        const val SCORE_LIMIT = 800.0
        const val DEFAULT_STOP_DIST_M = 700.0
    }
}