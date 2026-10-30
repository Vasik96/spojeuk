package com.vasik96.spojeuk

import kotlin.math.abs

class SourceInput(
    val report: SourceReport,
    val staleVehicleSec: Long,
    val vehicles: List<Vehicle>,
)

/**
 * Slučuje vozy z libovolného počtu zdrojů. Funguje po segmentech (MHD / regionální):
 *  1. stejný vůz = stejný spoj, nebo (bez spoje) stejná linka do [sameSpotM] metrů; z každého zdroje max. jeden;
 *  2. ze skupiny vyhrává poloha, která je na trase spoje dál; při shodě (do [tieM] m) čerstvější,
 *     jinak zdroj s vyšší prioritou;
 *  3. vůz, který zná jen jeden zdroj, se zahodí, pokud je zastaralý a jiný zdroj s vyšší prioritou
 *     ten segment čerstvě pokrývá (tzv. duch).
 */
class VehicleFusion(
    private val sameSpotM: Double = 150.0,
    private val tieM: Double = 40.0,
    private val freshnessSlackSec: Long = 5,
) {
    fun fuse(inputs: List<SourceInput>, progress: TripProgress?, nowMs: Long): List<Vehicle> {
        val ordered = inputs.sortedBy { it.report.priority }
        val byId = ordered.associateBy { it.report.id }
        val out = ArrayList<Vehicle>()
        for (segment in Segment.values()) {
            for (group in group(ordered, segment)) {
                if (group.size > 1) out.add(resolve(group, progress, nowMs))
                else if (!isGhost(group[0], byId, ordered, nowMs)) out.add(group[0])
            }
        }
        return out
    }

    private fun group(ordered: List<SourceInput>, segment: Segment): List<List<Vehicle>> {
        val groups = ArrayList<MutableList<Vehicle>>()
        for (input in ordered) {
            for (v in input.vehicles) {
                if (v.segment != segment) continue
                val target = groups
                    .filter { g -> g.none { it.source == v.source } && sameVehicle(g[0], v) }
                    .minByOrNull { g -> Geo.distanceM(g[0].lat, g[0].lng, v.lat, v.lng) }
                if (target != null) target.add(v) else groups.add(mutableListOf(v))
            }
        }
        return groups
    }

    private fun sameVehicle(a: Vehicle, b: Vehicle): Boolean {
        val distance = Geo.distanceM(a.lat, a.lng, b.lat, b.lng)

        // Stejný identifikátor spoje.
        if (a.tid != 0 && b.tid != 0 && a.tid == b.tid) {
            return true
        }

        // Různé zdroje mohou přiřadit rozdílné tid.
        // V takovém případě rozhoduje shoda linky a blízkost polohy.
        return normLine(a.line) == normLine(b.line) &&
                distance <= sameSpotM
    }

    private fun resolve(group: List<Vehicle>, progress: TripProgress?, nowMs: Long): Vehicle {
        val tid = group.firstOrNull { it.tid != 0 }?.tid ?: 0
        val winner = group.reduce { a, b -> aheadOf(a, b, tid, progress, nowMs) }
        val others = group.filter { it !== winner }
        return winner.copy(
            tid = tid,
            tripMatch = group.maxOf { it.tripMatch },
            accessible = winner.accessible ?: others.firstNotNullOfOrNull { it.accessible },
            airConditioned = winner.airConditioned ?: others.firstNotNullOfOrNull { it.airConditioned },
            destination = winner.destination.ifBlank { others.firstOrNull { it.destination.isNotBlank() }?.destination.orEmpty() },
        )
    }

    /** [a] má vyšší prioritu než [b] (skupina je řazená podle priority). */
    private fun aheadOf(a: Vehicle, b: Vehicle, tid: Int, progress: TripProgress?, nowMs: Long): Vehicle {
        val pa = if (tid != 0) progress?.along(tid, a.lat, a.lng) else null
        val pb = if (tid != 0) progress?.along(tid, b.lat, b.lng) else null
        return when {
            pa != null && pb != null && abs(pa - pb) > tieM -> if (pa > pb) a else b
            b.ageSec(nowMs) + freshnessSlackSec < a.ageSec(nowMs) -> b
            else -> a
        }
    }

    private fun isGhost(v: Vehicle, byId: Map<String, SourceInput>, all: List<SourceInput>, nowMs: Long): Boolean {
        val own = byId[v.source] ?: return false
        if (v.ageSec(nowMs) <= own.staleVehicleSec) return false
        return all.any { o ->
            o.report.priority < own.report.priority &&
                o.report.health == Health.LIVE &&
                v.segment in o.report.segments &&
                v.segment !in o.report.staleSegments
        }
    }
}

/** Skládá text "Zdroj: …" ze stavu zdrojů. */
object SourceLabel {
    private const val NONE = "žádný zdroj"

    fun build(reports: List<SourceReport>): String {
        val ordered = reports.sortedBy { it.priority }
        val live = ordered.filter { it.health == Health.LIVE }
        if (live.isNotEmpty()) {
            return live.joinToString(", ") { r ->
                // zastaralý segment se hlásí jen tehdy, když ho čerstvě nepokrývá jiný živý zdroj
                val uncovered = r.staleSegments.filter { seg ->
                    live.none { o -> o !== r && seg in o.segments && seg !in o.staleSegments }
                }
                r.name + staleNote(r, uncovered)
            }
        }
        val stale = ordered.firstOrNull { it.health == Health.STALE } ?: return NONE
        return stale.name + staleNote(stale, stale.staleSegments)
    }

    private fun staleNote(r: SourceReport, segments: Collection<Segment>): String = when {
        segments.isEmpty() -> ""
        segments.toSet() == r.segments && segments.size > 1 -> " (zastaralé)"
        else -> " (${segments.joinToString { it.label }} zastaralé)"
    }
}
