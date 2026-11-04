package com.vasik96.spojeuk

typealias LatLngList = List<Pair<Double, Double>>

/** Trasy pro mapu: přesné tvary z GTFS shapes, a když spoj tvar nemá, silnice z OSRM. */
class RouteGeometry(
    private val shapes: ShapeStore,
    private val trainShapes: ShapeStore,
    private val osrm: OsrmClient,
) {
    /** Tvar spoje z odpovídající databáze (vlaky mají vlastní). */
    private fun shapeOf(tt: Timetable, tid: Int): LatLngList? {
        val trip = tt.trips[tid] ?: return null
        return (if (trip.isTrain) trainShapes else shapes).points(trip.gid)
    }

    /** Celá trasa spoje. Vlak bez tvaru se nikdy nevede po silnici (OSRM), jen přímo mezi zastávkami. */
    fun forTrip(tt: Timetable, tid: Int): LatLngList {
        shapeOf(tt, tid)?.let { return it }
        if (tt.trips[tid]?.isTrain == true) return stopCoords(tt, tid)
        return osrm.pathFor(stopCoords(tt, tid))
    }

    /** Trasy jednotlivých jízd spojení (samostatné úseky pro zamezení rovných čar mezi přestupy). */
    fun forJourneySegments(tt: Timetable, j: Journey): List<LatLngList> =
        j.rawLegs.filter { it.kind == "ride" }.mapNotNull { leg ->
            (legShape(tt, leg) ?: legFallback(tt, leg)).takeIf { it.isNotEmpty() }
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

    /** Jedna zastávka na název, seřazené podle vzdálenosti. */
    fun nearest(tt: Timetable, lat: Double, lng: Double, limit: Int = 8): List<Pair<Stop, Double>> {
        val bestByName = HashMap<String, Pair<Stop, Double>>()
        for (s in tt.stops.values) {
            if (!s.hasCoords) continue
            val d = Geo.distanceM(lat, lng, s.lat, s.lng)
            val cur = bestByName[s.name]
            if (cur == null || d < cur.second) bestByName[s.name] = s to d
        }
        return bestByName.values.sortedBy { it.second }.take(limit)
    }

    /** Upřesnění podle cesty po silnici (jedno volání OSRM); null, když OSRM selže. */
    fun refineByRoad(lat: Double, lng: Double, candidates: List<Pair<Stop, Double>>): Pair<Stop, Double>? {
        val road = osrm.roadDistances(lat to lng, candidates.map { it.first.lat to it.first.lng }) ?: return null
        val best = candidates.indices.filter { road[it] != null }.minByOrNull { road[it]!! } ?: return null
        return candidates[best].first to road[best]!!
    }
}