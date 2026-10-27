package com.vasik96.spojeuk

import kotlin.math.abs

data class MergeResult(val vehicles: List<Vehicle>, val label: String)

fun isMhdVehicle(v: Vehicle): Boolean {
    if (v.source == SRC_DPMUL || v.id in 300000..309999) return true
    val a = foldText(v.agency)
    return "dopravni podnik mesta usti" in a || "dpmul" in a
}

object VehicleMerger {
    private const val TIE_M = 40.0        // rozdíl v postupu po trase, pod kterým rozhoduje čerstvost
    private const val MAX_PAIR_M = 150.0   // párování bez spoje: stejná linka a max. tato vzdálenost
    private const val DUK_FRESH_S = 40L

    /** Přiřadí DPMÚL vozu spoj z GTFS a doplní cis/trip, aby fungovalo zbytek appky. */
    fun attachTrip(tt: Timetable, v: Vehicle): Vehicle {
        val tid = tt.matchDpmulTrip(v) ?: return v
        val t = tt.trips[tid] ?: return v
        return v.copy(tid = tid, cis = t.cis.ifBlank { v.cis }, trip = t.num.ifBlank { v.trip })
    }

    fun merge(
        tt: Timetable?,
        duk: List<Vehicle>, dukOk: Boolean, dukOutage: Boolean,
        dpmul: List<Vehicle>, dpmulFresh: Boolean, dpmulHasData: Boolean,
        ageOf: (Vehicle) -> Long
    ): MergeResult {
        val dukMhd = duk.filter { isMhdVehicle(it) }
        val dukOther = duk.filter { !isMhdVehicle(it) }
        val dukTid = IntArray(dukMhd.size) { i -> tt?.findTripId(dukMhd[i].cis, dukMhd[i].trip) ?: 0 }
        val used = BooleanArray(dukMhd.size)
        val out = ArrayList<Vehicle>(duk.size + dpmul.size)

        for (d in dpmul) {
            var p = -1
            var pDist = Double.MAX_VALUE
            for (i in dukMhd.indices) {
                if (used[i]) continue
                val u = dukMhd[i]
                val dist = geoDistM(d.lat, d.lng, u.lat, u.lng)
                val same = if (d.tid != 0 && dukTid[i] != 0) d.tid == dukTid[i]
                else normLine(d.line) == normLine(u.line) && dist <= MAX_PAIR_M
                if (same && dist < pDist) { p = i; pDist = dist }
            }
            if (p < 0) { out += d; continue }
            used[p] = true
            val tid = if (d.tid != 0) d.tid else dukTid[p]
            out += pickAhead(tt, tid, d, dukMhd[p], ageOf)
        }

        for (i in dukMhd.indices) {
            if (used[i]) continue
            // DPMÚL je čerstvé a tenhle vůz nezná a DUK ho drží staré → duch, zahodit
            if (!dpmulFresh || ageOf(dukMhd[i]) <= DUK_FRESH_S) out += dukMhd[i]
        }
        out += dukOther

        val dukMhdStale = dukOutage ||
                (dukMhd.isNotEmpty() && dukMhd.count { ageOf(it) > DUK_FRESH_S } * 2 > dukMhd.size)
        val label = when {
            dpmulFresh && dukOk -> "DPMÚL, DÚK"
            dpmulFresh -> "DPMÚL"
            dukOk && !dukMhdStale -> "DÚK"
            dukOk -> "DÚK (MHD zastaralé)"
            dpmulHasData -> "DPMÚL (MHD zastaralé)"
            else -> "žádný zdroj"
        }
        return MergeResult(out, label)
    }

    /** Vyhrává zdroj, jehož poloha je na trase dál. Při shodě čerstvější, pak DPMÚL. */
    private fun pickAhead(tt: Timetable?, tid: Int, d: Vehicle, u: Vehicle, ageOf: (Vehicle) -> Long): Vehicle {
        val pd = if (tt != null && tid != 0) tt.progressOnTrip(tid, d.lat, d.lng) else null
        val pu = if (tt != null && tid != 0) tt.progressOnTrip(tid, u.lat, u.lng) else null
        val winner = when {
            pd != null && pu != null && abs(pd - pu) > TIE_M -> if (pd > pu) d else u
            ageOf(u) + 5 < ageOf(d) -> u
            else -> d
        }
        val other = if (winner === d) u else d
        return winner.copy(
            tid = tid,
            accessible = winner.accessible ?: other.accessible,
            airConditioned = winner.airConditioned ?: other.airConditioned,
            destination = winner.destination.ifBlank { other.destination }
        )
    }
}