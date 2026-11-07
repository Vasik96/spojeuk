package com.vasik96.spojeuk

import com.google.android.gms.maps.model.LatLng

class VehicleMatch(val vehicle: Vehicle, val approximate: Boolean)

private const val MAX_ROUTE_OFFSET_M = 50f     // max. vzdálenost autobusu od trasy
private const val MAX_APPROACH_M = 8_000f      // max. vzdálenost před zastávkou po trase (v metrech)

private const val MAX_HEADING_DIFF = 70.0   // max. úhlová odchylka od směru trasy (°)

private fun bearingDeg(a: LatLng, b: LatLng): Double {
    val r = FloatArray(2)
    android.location.Location.distanceBetween(
        a.latitude, a.longitude, b.latitude, b.longitude, r
    )
    // initialBearing je ve druhém prvku jen pokud je pole délky >= 3
    val res = FloatArray(3)
    android.location.Location.distanceBetween(
        a.latitude, a.longitude, b.latitude, b.longitude, res
    )
    return ((res[1].toDouble() % 360.0) + 360.0) % 360.0
}

private fun angleDiff(a: Double, b: Double): Double {
    val d = Math.abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}


internal fun distanceMeters(a: LatLng, b: LatLng): Float {
    val result = FloatArray(1)
    android.location.Location.distanceBetween(
        a.latitude, a.longitude,
        b.latitude, b.longitude,
        result
    )
    return result[0]
}

/** Najde na mapě vůz, který jede spoj [ref]. */
internal fun findVehicle(list: List<Vehicle>, ref: TripRef, route: List<LatLng>): VehicleMatch? {
    val live = list.filter { it.state in 0..1 }

    // 1) vozy spárované s tímto spojem (přesné ID, případně shoda podle JŘ). Kandidátů může být víc
    //    (např. vůz na točně, který má v zdroji stejné číslo spoje, a vůz, který už opravdu jede),
    //    proto se nebere první, ale ten nejvěrohodnější.
    val sameTrip = live.filter { v ->
        val byKey = ref.cis.isNotBlank() && ref.trip.isNotBlank() && v.cis == ref.cis && v.trip == ref.trip
        val byTid = ref.tid != 0 && v.tid == ref.tid
        (v.tripMatch == TripMatch.EXACT || v.tripMatch == TripMatch.HEURISTIC) && (byKey || byTid)
    }
    if (sameTrip.isNotEmpty()) {
        val best = pickMostPlausible(sameTrip, ref, route)
        if (best != null) return VehicleMatch(best, approximate = false)
    }

    // U minulých odjezdů (past / earlier) netipujeme polohu vozu, pokud nemáme přesné ID - nedava smysl to tipovat v tomto pripade
    if (ref.isPast) return null

    // Odhad polohy nepoužíváme, pokud do odjezdu zbývá více než 30 minut - nedava smysl to tipovat v tomto pripade
    val departure = ref.departureTimeMillis
    if (departure != null) {
        val minutesUntilDeparture =
            (departure - System.currentTimeMillis()) / 60_000

        if (minutesUntilDeparture > 30) return null
    }



    // 2) fallback: nejbližší autobus stejné linky PŘED nástupní zastávkou, správným směrem
    val bLat = ref.boardLat ?: return null
    val bLng = ref.boardLng ?: return null
    if (route.size < 2) return null

    val board = LatLng(bLat, bLng)
    val boardIdx = route.indices.minByOrNull { distanceMeters(board, route[it]) } ?: return null

    val cum = FloatArray(route.size)
    for (i in 1 until route.size) cum[i] = cum[i - 1] + distanceMeters(route[i - 1], route[i])

    val wantedDest = foldText(ref.destination)

    var best: Vehicle? = null
    var bestGap = Float.MAX_VALUE
    for (v in live) {
        if (v.line != ref.line) continue
        val p = LatLng(v.lat, v.lng)
        val idx = route.indices.minByOrNull { distanceMeters(p, route[it]) } ?: continue
        if (distanceMeters(p, route[idx]) > MAX_ROUTE_OFFSET_M) continue
        if (idx > boardIdx) continue

        // Ověření cílové stanice: pokud známe cíl spoje i cílovou stanice vozu, nesmí jít o jiný směr
        val vehDest = foldText(v.destination)
        if (wantedDest.isNotBlank() && vehDest.isNotBlank()) {
            if (wantedDest !in vehDest && vehDest !in wantedDest) continue
        }

        // směr: úsek trasy kolem idx vs. heading vozu (0 = neznámý)
        val h = v.heading?.takeIf { it != 0.0 }
        if (h != null) {
            val a = route[idx]
            val b = route[minOf(route.lastIndex, idx + 3)]
            if (a != b) {
                val routeBearing = bearingDeg(a, b)
                if (angleDiff(routeBearing, h) > MAX_HEADING_DIFF) continue
            }
        }

        val gap = cum[boardIdx] - cum[idx]
        if (gap <= MAX_APPROACH_M && gap < bestGap) { bestGap = gap; best = v }
    }
    return best?.let { VehicleMatch(it, approximate = true) }
}

private const val IMPLAUSIBLE = 10_000f

/**
 * Z vozů se stejným spojem vybere ten, který opravdu jede [ref]. Skóre (nižší = lepší):
 * přesné ID před shodou podle JŘ, vzdálenost od trasy, stáří polohy a hlavně: vůz, který už podle
 * času odjezdu (+ jeho zpoždění) měl dávno odjet z nástupní zastávky, ale stojí na trase pořád
 * před ní (typicky na točně), je nevěrohodný. Pokud jsou nevěrohodní všichni, vrátí null.
 */
private fun pickMostPlausible(cands: List<Vehicle>, ref: TripRef, route: List<LatLng>): Vehicle? {
    val nowMs = System.currentTimeMillis()
    val bLat = ref.boardLat
    val bLng = ref.boardLng
    val haveGeometry = route.size >= 2 && bLat != null && bLng != null

    var boardIdx = -1
    var cum: FloatArray? = null
    if (haveGeometry) {
        val board = LatLng(bLat!!, bLng!!)
        boardIdx = route.indices.minByOrNull { distanceMeters(board, route[it]) } ?: -1
        val c = FloatArray(route.size)
        for (i in 1 until route.size) c[i] = c[i - 1] + distanceMeters(route[i - 1], route[i])
        cum = c
    }

    var best: Vehicle? = null
    var bestScore = Float.MAX_VALUE
    for (v in cands) {
        var score = if (v.tripMatch == TripMatch.EXACT) 0f else 500f
        score += minOf(v.ageSec(nowMs), 600L) / 2f

        if (cum != null && boardIdx >= 0) {
            val p = LatLng(v.lat, v.lng)
            val idx = route.indices.minByOrNull { distanceMeters(p, route[it]) } ?: continue
            score += minOf(distanceMeters(p, route[idx]), 2_000f)

            val dep = ref.departureTimeMillis
            if (dep != null) {
                val actualDep = dep + maxOf(v.delay, 0) * 60_000L
                val shouldHaveLeft = nowMs - actualDep > 2 * 60_000L
                val metersBeforeBoard = cum[boardIdx] - cum[idx]
                if (shouldHaveLeft && metersBeforeBoard > 200f) score += IMPLAUSIBLE
            }
        }
        if (score < bestScore) { bestScore = score; best = v }
    }
    return if (bestScore >= IMPLAUSIBLE) null else best
}
