package com.vasik96.spojeuk

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/** Výsledek jednoho kola: sloučené vozy, stav zdrojů a hotový text pro UI. */
data class Fleet(
    val vehicles: List<Vehicle>,
    val reports: List<SourceReport>,
    /** "DPMÚL, DÚK", "DÚK (MHD zastaralé)", … */
    val label: String,
    /** Kolik poloh je jen "držených" z minula a nikdo jiný je nepokrývá čerstvě. */
    val warningCount: Int,
) {
    private val byTid: Map<Int, List<Vehicle>> by lazy { vehicles.filter { it.tid != 0 }.groupBy { it.tid } }

    /** Vůz, který právě jede spoj [leg] (jen přesně přiřazené spoje, ne hádané). */
    fun liveOn(leg: Leg, day: LocalDate): Vehicle? {
        val rel = secondsSinceMidnight(day)
        if (rel < leg.t0 - 3600 || rel > leg.t1 + 3600) return null
        return byTid[leg.tid]?.firstOrNull { it.tripMatch == TripMatch.EXACT && it.state in 0..1 && it.delay > -30 }
    }

    companion object {
        val EMPTY = Fleet(emptyList(), emptyList(), "", 0)
    }
}

/** Směr jízdy z posunu polohy, pro zdroje, které azimut nedávají (nebo dávají 0). */
class HeadingTracker(private val minMoveM: Double = 15.0) {
    private val lastPos = HashMap<String, Pair<Double, Double>>()
    private val headings = HashMap<String, Double>()

    @Synchronized
    fun track(v: Vehicle): Vehicle {
        val prev = lastPos[v.uid]
        if (prev == null) {
            lastPos[v.uid] = v.lat to v.lng
        } else if (Geo.distanceM(prev.first, prev.second, v.lat, v.lng) >= minMoveM) {
            headings[v.uid] = Geo.bearingDeg(prev.first, prev.second, v.lat, v.lng)
            lastPos[v.uid] = v.lat to v.lng
        }
        return if (v.heading != null) v else v.copy(heading = headings[v.uid])
    }
}

/**
 * Drží všechny zdroje poloh. Zná jen rozhraní [VehicleSource]: dotazuje je, obohacuje vozy o spoje z jízdního řádu,
 * vyhodnocuje aktuálnost, slučuje vozy z více zdrojů a skládá štítek zdrojů.
 */
class VehicleHub(
    sources: List<VehicleSource>,
    private val scope: CoroutineScope,
    private val timetable: () -> Timetable?,
) {
    private class Runner(val source: VehicleSource) {
        @Volatile var raw: List<Vehicle> = emptyList()
        @Volatile var lastOkMs = 0L
        @Volatile var lastStartMs = 0L
        @Volatile var lastError: String? = null
        val running = AtomicBoolean(false)

        // vozy obohacené o spoje; přepočítává se jen při novém dotazu nebo novém jízdním řádu
        @Volatile var enriched: List<Vehicle> = emptyList()
        @Volatile var enrichedStamp = -1L
        @Volatile var enrichedFor: Timetable? = null
    }

    private val runners = sources.sortedBy { it.priority }.map { Runner(it) }
    private val headings = HeadingTracker()
    private val fusion = VehicleFusion()

    @Volatile
    var fleet: Fleet = Fleet.EMPTY
        private set

    @Volatile
    private var builtAtMs = 0L

    fun sourceName(id: String): String = runners.firstOrNull { it.source.id == id }?.source?.name ?: id

    /**
     * Spustí dotazy na zdroje, počká na ty s [VehicleSource.awaitResult] a vrátí sloučený stav.
     * S [maxAgeMs] > 0 vrátí poslední stav, pokud je dost čerstvý.
     * Vyhodí výjimku, dokud žádný zdroj neodpověděl.
     */
    suspend fun refresh(maxAgeMs: Long = 0): Fleet {
        val now = System.currentTimeMillis()
        if (maxAgeMs > 0 && fleet !== Fleet.EMPTY && now - builtAtMs < maxAgeMs) return fleet
        runners.mapNotNull { r -> start(r)?.takeIf { r.source.awaitResult } }.joinAll()
        return withContext(Dispatchers.Default) { rebuild() }
    }

    private fun start(r: Runner): Job? {
        val now = System.currentTimeMillis()
        if (now - r.lastStartMs < r.source.minIntervalMs) return null
        if (!r.running.compareAndSet(false, true)) return null
        r.lastStartMs = now
        return scope.launch {
            try {
                val vehicles = r.source.fetch().map { headings.track(it) }
                r.raw = vehicles
                r.lastOkMs = System.currentTimeMillis()
                r.lastError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                r.lastError = e.message
                Log.w("VehicleHub", "${r.source.id}: dotaz selhal: ${e.message}")
            } finally {
                r.running.set(false)
            }
        }
    }

    @Synchronized
    private fun rebuild(): Fleet {
        if (runners.none { it.lastOkMs > 0 }) {
            throw IllegalStateException(runners.mapNotNull { it.lastError }.firstOrNull() ?: "Žádný zdroj poloh zatím neodpověděl")
        }
        val now = System.currentTimeMillis()
        val tt = timetable()
        val reports = runners.map { assess(it, now) }
        val inputs = runners.mapIndexed { i, r ->
            SourceInput(reports[i], r.source.freshness.staleVehicleSec, enrichedVehicles(r, tt))
        }
        val vehicles = fusion.fuse(inputs, tt?.locator, now)
        val result = Fleet(vehicles, reports, SourceLabel.build(reports), warningCount(reports))
        fleet = result
        builtAtMs = now
        return result
    }

    private fun enrichedVehicles(r: Runner, tt: Timetable?): List<Vehicle> {
        if (tt == null) return r.raw
        if (r.enrichedStamp != r.lastOkMs || r.enrichedFor !== tt) {
            r.enriched = r.raw.map { TripEnricher.enrich(it, r.source, tt) }
            r.enrichedStamp = r.lastOkMs
            r.enrichedFor = tt
        }
        return r.enriched
    }

    // ───────── vyhodnocení aktuálnosti ─────────

    private fun assess(r: Runner, now: Long): SourceReport {
        val s = r.source
        val f = s.freshness
        val hasData = r.lastOkMs > 0
        val recent = hasData && now - r.lastOkMs <= f.maxFetchAgeMs
        val health = when {
            !hasData -> Health.DOWN
            recent && medianAgeOk(r, f) -> Health.LIVE
            else -> Health.STALE
        }
        val watched = if (f.watchedSegments.isNotEmpty()) f.watchedSegments else s.segments
        val staleSegments: Set<Segment> = when (health) {
            Health.DOWN -> emptySet()
            Health.STALE -> s.segments
            Health.LIVE -> watched.filterTo(HashSet()) { seg ->
                s.inOutage || isSegmentStale(r.raw.filter { it.segment == seg }, now, f.staleVehicleSec)
            }
        }
        return SourceReport(s.id, s.name, s.priority, s.segments, health, staleSegments, s.heldCount, vehicleCount = r.raw.size)
    }

    private fun medianAgeOk(r: Runner, f: Freshness): Boolean {
        val limit = f.maxMedianAgeSec ?: return true
        val ages = r.raw.filter { it.hasTimestamp }.map { (r.lastOkMs - it.observedAtMs) / 1000 }.sorted()
        return ages.isEmpty() || ages[ages.size / 2] <= limit
    }

    private fun isSegmentStale(vehicles: List<Vehicle>, now: Long, limitSec: Long): Boolean {
        if (vehicles.isEmpty()) return true
        val freshCount = vehicles.count { it.ageSec(now) <= limitSec }
        return freshCount == 0 || freshCount * 2 < vehicles.size
    }

    /** Držené polohy se nehlásí, když jejich zastaralé segmenty čerstvě pokrývá jiný zdroj. */
    private fun warningCount(reports: List<SourceReport>): Int = reports.sumOf { r ->
        val covered = r.staleSegments.isNotEmpty() && r.staleSegments.all { seg ->
            reports.any { o -> o !== r && o.health == Health.LIVE && seg in o.segments && seg !in o.staleSegments }
        }
        if (covered) 0 else r.heldCount
    }
}
