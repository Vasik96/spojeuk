package com.vasik96.spojeuk

typealias LatLngList = List<Pair<Double, Double>>

/** Spoje doplněné z PID GTFS (linky, které v CIS nejsou); tvary mají v pid_shapes.sqlite. */
private const val PID_GTFS_PREFIX = "pidgtfs:"

/** Trasy pro mapu: přesné tvary z GTFS shapes, a když spoj tvar nemá, silnice z OSRM. */
class RouteGeometry(
    private val shapes: ShapeStore,
    private val trainShapes: ShapeStore,
    private val pidShapes: ShapeStore,
    private val osrm: OsrmClient,
) {
    /** Tvar spoje z odpovídající databáze (vlaky mají vlastní). */
    private fun shapeOf(tt: Timetable, tid: Int): LatLngList? {
        val trip = tt.trips[tid] ?: return null
        val gid = tt.gidOf(tid)
        if (trip.isTrain) return trainShapes.points(gid)
        if (gid.startsWith(PID_GTFS_PREFIX)) return pidShapes.points(gid)
        // přesný gid, a když v shapes.sqlite není (zastaralé jdf:trip:…), nejlépe sedící tvar téže linky podle zastávek
        return shapes.pointsFor(gid, stopCoords(tt, tid))
    }

    /** Pro ladění: odkud se trasa spoje bere (přesný tvar, nebo OSRM a proč). */
    fun describe(tt: Timetable, tid: Int): String {
        val trip = tt.trips[tid] ?: return "spoj nenalezen"
        val gid = tt.gidOf(tid)
        if (gid.isBlank()) return "spoj nemá gid (DB hasGid=${tt.hasGid}), použije se OSRM"
        if (trip.isTrain) return trainShapes.explain(gid)
        if (gid.startsWith(PID_GTFS_PREFIX)) return pidShapes.explain(gid)
        val exact = shapes.explain(gid)
        if (shapes.points(gid) != null) return exact
        val fuzzy = shapes.pointsFor(gid, stopCoords(tt, tid))
        return if (fuzzy != null) "tvar nalezen podle zastávek téže linky (${fuzzy.size} bodů); přesný gid v DB chybí\n$exact"
        else exact
    }

    /** Celá trasa spoje. Vlak bez tvaru se nikdy nevede po silnici (OSRM), jen přímo mezi zastávkami. */
    fun forTrip(tt: Timetable, tid: Int): LatLngList {
        shapeOf(tt, tid)?.let { return it }
        if (tt.trips[tid]?.isTrain == true) return stopCoords(tt, tid)
        return osrm.pathFor(stopCoords(tt, tid))
    }

    /** Trasy jednotlivých jízd spojení (samostatné úseky pro zamezení rovných čar mezi přestupy). */
    fun forJourneySegments(tt: Timetable, j: Journey): List<LatLngList> =
        forJourneySegmentsByRide(tt, j).map { it.second }

    /** Úseky jízd spojení i se spojem (tid), ke kterému patří (barva úseku podle druhu dopravy). */
    fun forJourneySegmentsByRide(tt: Timetable, j: Journey): List<Pair<Int, LatLngList>> =
        j.rawLegs.filter { it.kind == "ride" }.mapNotNull { leg ->
            (legShape(tt, leg) ?: legFallback(tt, leg)).takeIf { it.isNotEmpty() }?.let { leg.tid to it }
        }

    /** Trasa všech jízd spojení. */
    fun forJourney(tt: Timetable, j: Journey): LatLngList =
        forJourneySegments(tt, j).flatten()

    /** Celá trasa prvního spoje (i před nástupní zastávkou), pro hledání autobusu, který ještě nedojel. */
    fun firstLegFull(tt: Timetable, j: Journey): LatLngList {
        val leg = j.rawLegs.firstOrNull { it.kind == "ride" } ?: return emptyList()
        return forTrip(tt, leg.tid)
    }

    // ───────── pomocné ─────────

    private fun coord(tt: Timetable, stopId: Int): Pair<Double, Double>? =
        tt.stops[stopId]?.takeIf { it.hasCoords }?.let { it.lat to it.lng }

    private fun stopCoords(tt: Timetable, tid: Int): LatLngList =
        tt.tripStops(tid).asIterable().mapNotNull { coord(tt, it) }

    /** Úsek přesné trasy mezi nástupní a výstupní zastávkou, nebo null (spoj nemá tvar / zastávky nesedí). */
    private fun legFallback(tt: Timetable, leg: Leg): LatLngList {
        val pts = leg.stops.mapNotNull { coord(tt, it.first) }
        return if (tt.trips[leg.tid]?.isTrain == true) pts else osrm.pathFor(pts)
    }

    private fun legShape(tt: Timetable, leg: Leg): LatLngList? {
        val shape = shapeOf(tt, leg.tid) ?: return null
        val seq = leg.stops.map { it.first }
        if (seq.size < 2) return null

        val ids = tt.tripStops(leg.tid)
        val board = indexOfSubsequence(ids, seq)
        if (board < 0) return null

        // jen zastávky se souřadnicemi; indexy nástupu a výstupu se přepočítají do tohoto seznamu
        val valid = ids.indices.filter { tt.stops[ids[it]]?.hasCoords == true }
        val boardIdx = valid.indexOf(board)
        val alightIdx = valid.indexOf(board + seq.size - 1)
        if (boardIdx < 0 || alightIdx < 0) return null
        val coords = valid.map { i -> tt.stops.getValue(ids[i]).let { it.lat to it.lng } }
        return ShapeGeometry.sliceBetween(shape, coords, boardIdx, alightIdx)
    }

    private fun indexOfSubsequence(ids: IntArray, seq: List<Int>): Int {
        var i = 0
        while (i + seq.size <= ids.size) {
            if (seq.indices.all { k -> ids[i + k] == seq[k] }) return i
            i++
        }
        return -1
    }
}

/** Nejbližší zastávky k poloze uživatele (vzdušnou čarou a pak upřesnění po silnici). */
class StopLocator(private val osrm: OsrmClient) {

    /**
     * Mřížka zastávek (buňka cca 1 km). Dřív se při každém dotazu procházely všechny zastávky
     * (s Prahou desítky tisíc), teď jen pár buněk kolem polohy.
     */
    private class Grid(stops: Collection<Stop>) {
        val cells = HashMap<Long, ArrayList<Stop>>()
        init {
            for (s in stops) {
                if (!s.hasCoords) continue
                cells.getOrPut(key(cy(s.lat), cx(s.lng))) { ArrayList(8) }.add(s)
            }
        }
        companion object {
            const val DLAT = 0.01
            const val DLNG = 0.015
            fun cy(lat: Double) = kotlin.math.floor(lat / DLAT).toInt()
            fun cx(lng: Double) = kotlin.math.floor(lng / DLNG).toInt()
            fun key(y: Int, x: Int): Long = (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)
        }
    }

    @Volatile private var grid: Pair<Collection<Stop>, Grid>? = null

    private fun gridFor(stops: Collection<Stop>): Grid {
        grid?.let { (src, g) -> if (src === stops) return g }
        return synchronized(this) {
            grid?.takeIf { it.first === stops }?.second ?: Grid(stops).also { grid = stops to it }
        }
    }

    /** Jedna zastávka na název, seřazené podle vzdálenosti. */
    fun nearest(tt: Timetable, lat: Double, lng: Double, limit: Int = 8): List<Pair<Stop, Double>> =
        nearest(tt.stops.values, lat, lng, limit)

    fun nearest(stops: Collection<Stop>, lat: Double, lng: Double, limit: Int = 8): List<Pair<Stop, Double>> {
        val g = gridFor(stops)
        val y0 = Grid.cy(lat)
        val x0 = Grid.cx(lng)
        // nejmenší vzdálenost, kterou prstenec buněk o poloměru r zaručeně pokrývá
        val cellM = minOf(Grid.DLAT * 111_320.0, Grid.DLNG * 111_320.0 * kotlin.math.cos(Math.toRadians(lat)))
        val bestByName = HashMap<String, Pair<Stop, Double>>()

        fun scan(y: Int, x: Int) {
            for (s in g.cells[Grid.key(y, x)] ?: return) {
                val d = Geo.distanceM(lat, lng, s.lat, s.lng)
                val cur = bestByName[s.name]
                if (cur == null || d < cur.second) bestByName[s.name] = s to d
            }
        }

        scan(y0, x0)
        var r = 0
        while (r < MAX_RING) {
            r++
            // přidat jen okraj prstence r
            for (x in x0 - r..x0 + r) { scan(y0 - r, x); scan(y0 + r, x) }
            for (y in y0 - r + 1..y0 + r - 1) { scan(y, x0 - r); scan(y, x0 + r) }
            if (bestByName.size >= limit) {
                val sorted = bestByName.values.sortedBy { it.second }
                // výsledek je jistý, když i limit-tá zastávka leží uvnitř zaručeně prohledané oblasti
                if (sorted[limit - 1].second <= r * cellM) return sorted.take(limit)
            }
        }
        if (bestByName.size < limit) {
            // řídce pokryté okolí (mimo region): záloha přes všechny zastávky
            bestByName.clear()
            for (s in stops) {
                if (!s.hasCoords) continue
                val d = Geo.distanceM(lat, lng, s.lat, s.lng)
                val cur = bestByName[s.name]
                if (cur == null || d < cur.second) bestByName[s.name] = s to d
            }
        }
        return bestByName.values.sortedBy { it.second }.take(limit)
    }

    /** Upřesnění podle cesty po silnici (jedno volání OSRM); null, když OSRM selže. */
    fun refineByRoad(lat: Double, lng: Double, candidates: List<Pair<Stop, Double>>): Pair<Stop, Double>? {
        val road = osrm.roadDistances(lat to lng, candidates.map { it.first.lat to it.first.lng }) ?: return null
        val best = candidates.indices.filter { road[it] != null }.minByOrNull { road[it]!! } ?: return null
        return candidates[best].first to road[best]!!
    }

    private companion object {
        const val MAX_RING = 40   // ~40 km
    }
}