package com.vasik96.spojeuk

import com.google.android.gms.maps.model.LatLng

class VehicleMatch(val vehicle: Vehicle, val approximate: Boolean)

private const val MAX_ROUTE_OFFSET_M = 50f     // max. vzdálenost autobusu od trasy
private const val MAX_APPROACH_M = 8_000f      // max. vzdálenost před zastávkou po trase (v metrech)

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

    // 1) přesná shoda
    live.firstOrNull { it.cis == ref.cis && it.trip == ref.trip }
        ?.let { return VehicleMatch(it, approximate = false) }

    // 2) fallback: nejbližší autobus stejné linky PŘED nástupní zastávkou
    val bLat = ref.boardLat ?: return null
    val bLng = ref.boardLng ?: return null
    if (route.size < 2) return null

    val board = LatLng(bLat, bLng)
    val boardIdx = route.indices.minByOrNull { distanceMeters(board, route[it]) } ?: return null

    // kumulativní vzdálenost podél trasy
    val cum = FloatArray(route.size)
    for (i in 1 until route.size) cum[i] = cum[i - 1] + distanceMeters(route[i - 1], route[i])

    var best: Vehicle? = null
    var bestGap = Float.MAX_VALUE
    for (v in live) {
        if (v.line != ref.line) continue
        val p = LatLng(v.lat, v.lng)
        val idx = route.indices.minByOrNull { distanceMeters(p, route[it]) } ?: continue
        if (distanceMeters(p, route[idx]) > MAX_ROUTE_OFFSET_M) continue   // není na trase
        if (idx > boardIdx) continue                                       // už zastávku projel
        val gap = cum[boardIdx] - cum[idx]
        if (gap <= MAX_APPROACH_M && gap < bestGap) { bestGap = gap; best = v }
    }
    return best?.let { VehicleMatch(it, approximate = true) }
}