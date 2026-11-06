package com.vasik96.spojeuk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * Jediný vstup pro UI (nahrazuje DukApi). Sám nic nedělá, jen deleguje:
 *  - jízdní řády a plánování  → [TimetableStore]
 *  - živé polohy              → [VehicleHub] (zdroje viz [VehicleSources])
 *  - trasy pro mapu           → [RouteGeometry]
 *  - nejbližší zastávky       → [StopLocator]
 */
class TransitRepository(context: Context? = null) {

    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val osrm = OsrmClient(context?.cacheDir ?: File("/tmp"))

    private val store = TimetableStore(context, http) { onTimetableLoaded(it) }
    private val shapes by lazy { ShapeStore(store.dbFile.parentFile ?: File("/tmp"), context) }
    private val trainShapes by lazy { ShapeStore(store.dbFile.parentFile ?: File("/tmp"), context, "train_shapes") }
    private val geometry by lazy { RouteGeometry(shapes, trainShapes, osrm) }
    private val stopLocator = StopLocator(osrm)
    private val hub = VehicleHub(VehicleSources.create(context), scope) { store.current?.timetable }

    @Volatile private var shapeUpgradeTried = false

    private val autoPolicy = context?.let { AutoUpdatePolicy(it) }

    fun vehicleSourceOptions(): List<VehicleSourceOption> =
        hub.sourceOptions()

    fun setEnabledVehicleSources(ids: Set<String>?) {
        hub.setEnabledSources(ids)
    }
    private fun onTimetableLoaded(tt: Timetable) {
        Log.i(TAG, "DB: hasGid=${tt.hasGid} trips=${tt.meta["trips"]} tag=${tt.meta["tag"]}")
        if (!tt.hasGid) scheduleShapeUpgrade()
        scope.launch {
            runCatching { shapes.sync(http) }.onFailure { Log.w(TAG, "sync tras selhal: ${it.message}") }
            runCatching { trainShapes.sync(http) }.onFailure { Log.w(TAG, "sync tras vlaků selhal: ${it.message}") }
        }
    }

    /** Starší databáze nemá sloupec gid, takže se jednou na pozadí stáhne nová (s trasami). */
    private fun scheduleShapeUpgrade() {
        if (shapeUpgradeTried) return
        shapeUpgradeTried = true
        scope.launch {
            runCatching { store.stageUpdate() }.onFailure { Log.w(TAG, "aktualizace s trasami selhala: ${it.message}") }
        }
    }

    suspend fun autoUpdate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val staged = store.hasPending() || autoPolicy?.timetableStagedRecently() == true
            if (!staged) {
                store.stageUpdate()
                autoPolicy?.timetableStagedMs = System.currentTimeMillis()
            }
            shapes.sync(http, force = true)
            runCatching { trainShapes.sync(http, force = true) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "auto-update selhal: ${e.message}")
            false
        }
    }

    // ───────────────────────── jízdní řády ─────────────────────────

    fun isFirstRun(): Boolean = store.isFirstRun()

    suspend fun loadStops(log: (String) -> Unit = {}, progress: (Float) -> Unit = {}): List<Stop> =
        withContext(Dispatchers.IO) {
            store.ensure(log, progress).timetable.stops.values.distinctBy { it.name }
        }

    fun getTimetableMeta(): Map<String, String> = store.meta()
    fun getShapesMeta(): String? = shapes.getBuiltDate()

    suspend fun updateTimetable(log: (String) -> Unit = {}, progress: (Float) -> Unit = {}): Unit =
        store.stageUpdate(log, progress)

    suspend fun updateShapes(log: (String) -> Unit = {}, forceDownload: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            log("Kontroluji trasy spojů (Shapes)…")
            val updated = shapes.sync(http, force = true, forceDownload = forceDownload)
            runCatching { trainShapes.sync(http, force = true, forceDownload = forceDownload) }
                .onFailure { Log.w(TAG, "sync tras vlaků selhal: ${it.message}") }
            log(if (updated) "Trasy spojů byly aktualizovány." else "Trasy spojů jsou již aktuální.")
            updated
        }

    fun clearOsrmCache(log: (String) -> Unit = {}): Int {
        log("Čistím cache OSRM tras…")
        val count = osrm.clearCache()
        log("Cache tras vyčištěna ($count souborů smazáno).")
        return count
    }

    suspend fun updateAllData(
        log: (String) -> Unit = {},
        progress: (Float) -> Unit = {},
        forceShapes: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        log("Zahajuji kompletní aktualizaci všech dat…")
        progress(0.05f)

        log("1/4: Stahuji nejnovější jízdní řády (GTFS)…")
        updateTimetable(log = { log("1/4: $it") }, progress = { progress(0.05f + it * 0.5f) })

        log("2/4: Stahuji trasy a geometrie (Shapes)…")
        progress(0.6f)
        updateShapes(log = { log("2/4: $it") }, forceDownload = forceShapes)

        log("3/4: Obnovuji živá data vozidel…")
        progress(0.8f)
        updateRealtime(log = { log("3/4: $it") })

        log("4/4: Čistím cache tras OSRM…")
        progress(0.95f)
        clearOsrmCache(log = { log("4/4: $it") })

        progress(1.0f)
        log("Kompletní aktualizace dat byla úspěšně dokončena!")
    }

    // ───────────────────────── vyhledávání spojení ─────────────────────────

    fun newSearch(fromName: String, toName: String, modeFilter: ModeFilter = ModeFilter.ALL): JourneySearch =
        JourneySearch(this, fromName, toName, modeFilter)

    suspend fun searchConnections(
        fromName: String,
        toName: String,
        startTime: LocalDateTime = nowPrague(),
        count: Int = 8,
        modeFilter: ModeFilter = ModeFilter.ALL,
    ): List<Journey> = withContext(Dispatchers.IO) {
        val b = store.ensure()

        val journeys = b.planner.find(fromName, toName, startTime, count, modeFilter = modeFilter)
        withRouteStops(withLiveDelays(journeys))
    }

    suspend fun searchMoreLater(
        fromName: String,
        toName: String,
        current: List<Journey>,
        modeFilter: ModeFilter = ModeFilter.ALL,
    ): List<Journey> =
        withContext(Dispatchers.IO) {
            val b = store.ensure()
            withRouteStops(withLiveDelays(b.planner.moreLater(fromName, toName, current, modeFilter = modeFilter)))
        }

    suspend fun searchMoreEarlier(
        fromName: String,
        toName: String,
        current: List<Journey>,
        hours: Int = 2,
        modeFilter: ModeFilter = ModeFilter.ALL,
    ): List<Journey> = withContext(Dispatchers.IO) {
        val b = store.ensure()
        withRouteStops(withLiveDelays(b.planner.moreEarlier(fromName, toName, current, hours = hours, modeFilter = modeFilter)))
    }

    /** Doplní do spojení živé zpoždění (z posledního sloučeného stavu vozů). */
    private suspend fun withLiveDelays(journeys: List<Journey>): List<Journey> {
        runCatching { hub.refresh(maxAgeMs = 10_000) }
        val fleet = hub.fleet
        for (j in journeys) {
            for (leg in j.rawLegs) {
                if (leg.kind != "ride") continue
                val v = fleet.liveOn(leg, j.day) ?: continue
                j.delay = v.delay
                j.realtime = true
            }
        }
        return journeys
    }


    private suspend fun withRouteStops(
        journeys: List<Journey>
    ): List<Journey> {
        val tt = store.ensure().timetable

        fun norm(name: String): String {
            val label = splitStopName(prettyName(name)).first.ifBlank { name }
            return foldText(label).replace(Regex("\\bhl\\.?n\\.?\\b"), "hlavni nadrazi").replace(Regex("\\s+"), "")
        }

        for (journey in journeys) {
            val rideLegs = journey.rawLegs.filter { it.kind == "ride" }

            val names = mutableListOf<String>()

            rideLegs.forEach { leg ->
                val fromName = tt.stops[leg.fromStop]?.name?.let { prettyName(it) }
                val toName = tt.stops[leg.toStop]?.name?.let { prettyName(it) }

                if (fromName != null && (names.isEmpty() || norm(names.last()) != norm(fromName))) {
                    names.add(fromName)
                }

                if (toName != null && (names.isEmpty() || norm(names.last()) != norm(toName))) {
                    names.add(toName)
                }
            }

            journey.routeStops = names
        }

        return journeys
    }


    /** Všechny pěší trasy po silnici v rámci spojení (před nástupem i při přestupech) **/
    suspend fun journeyWalks(j: Journey): List<WalkRoute> = withContext(Dispatchers.IO) {
        val tt = store.ensure().timetable
        val routes = mutableListOf<WalkRoute>()
        for (leg in j.rawLegs) {
            if (leg.kind != "walk") continue
            val a = tt.stops[leg.fromStop]?.takeIf { it.hasCoords } ?: continue
            val b = tt.stops[leg.toStop]?.takeIf { it.hasCoords } ?: continue
            if (leg.fromStop == leg.toStop || (a.lat == b.lat && a.lng == b.lng)) continue
            val route = osrm.walkRoute(a.lat to a.lng, b.lat to b.lng)
            if (route != null) {
                routes.add(route)
            } else {
                val dist = Geo.distanceM(a.lat, a.lng, b.lat, b.lng)
                val min = maxOf(1, (dist / 80.0).toInt())
                routes.add(WalkRoute(listOf(a.lat to a.lng, b.lat to b.lng), dist, min))
            }
        }
        routes
    }

    /** pěší trasa po silnici před nástupem **/
    suspend fun boardWalk(j: Journey): WalkRoute? = withContext(Dispatchers.IO) {
        journeyWalks(j).firstOrNull()
    }

    // ───────────────────────── živé polohy ─────────────────────────

    /** Signál, že se na pozadí dotáhla čerstvá data od pomalého zdroje (DPMÚL). */
    val vehicleUpdates get() = hub.updates

    /** Poslední sloučený stav bez nového dotazu. */
    fun currentVehicles(): List<Vehicle> = hub.fleet.vehicles

    /** Rozjede dotazy na zdroje poloh hned při startu, ať pomalé API (DPMÚL, 10-15 s) odpovídá už před otevřením mapy. */
    suspend fun prewarmVehicles() {
        runCatching { hub.refresh() }
    }

    /** Sloučené polohy ze všech zdrojů. Vyhodí výjimku, dokud žádný zdroj neodpověděl. */
    suspend fun vehiclePositions(): List<Vehicle> = hub.refresh().vehicles

    suspend fun updateRealtime(log: (String) -> Unit = {}): List<Vehicle> {
        log("Obnovuji živá data vozidel…")
        val vehicles = vehiclePositions()
        log("Živá data obnovena (${vehicles.size} vozidel).")
        return vehicles
    }

    /** Text pro UI: "DPMÚL, DÚK", "DÚK (MHD zastaralé)", … */
    fun sourceLabel(): String = hub.fleet.label

    /** Kolik poloh je jen držených z minula (UI varuje od [OUTAGE_MIN]). */
    fun heldCount(): Int = hub.fleet.warningCount

    fun sourceName(id: String): String = hub.sourceName(id)

    // ───────────────────────── trasy a detail spoje ─────────────────────────

    suspend fun tripGeometry(v: Vehicle): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val tt = store.ensure().timetable
        val tid = v.tid.takeIf { it != 0 }
            ?: tt.findTripId(v.cis, v.trip)
            ?: tt.locator.nearestOnLine(v.line, v.lat, v.lng)
            ?: return@withContext emptyList()
        geometry.forTrip(tt, tid)
    }

    suspend fun tripStopLocations(v: Vehicle): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val tt = store.ensure().timetable
        val tid = v.tid.takeIf { it != 0 }
            ?: tt.findTripId(v.cis, v.trip)
            ?: tt.locator.nearestOnLine(v.line, v.lat, v.lng)
            ?: return@withContext emptyList()
        val stopIds = tt.tripStops(tid)
        stopIds.asIterable().mapNotNull { id -> tt.stops[id]?.takeIf { it.hasCoords }?.let { it.lat to it.lng } }
    }

    suspend fun resolveTripId(v: Vehicle): Int? = withContext(Dispatchers.IO) {
        val tt = store.ensure().timetable
        v.tid.takeIf { it != 0 }
            ?: tt.findTripId(v.cis, v.trip)
            ?: tt.locator.nearestOnLine(v.line, v.lat, v.lng)
    }

    suspend fun routeForTrip(tid: Int): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        geometry.forTrip(store.ensure().timetable, tid)
    }

    suspend fun journeySegments(j: Journey): List<List<Pair<Double, Double>>> = withContext(Dispatchers.IO) {
        geometry.forJourneySegments(store.ensure().timetable, j)
    }

    suspend fun journeyGeometry(j: Journey): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        geometry.forJourney(store.ensure().timetable, j)
    }

    suspend fun firstLegFullRoute(j: Journey): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        geometry.firstLegFull(store.ensure().timetable, j)
    }

    suspend fun firstLegStopLocations(j: Journey): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val tt = store.ensure().timetable
        val leg = j.rawLegs.firstOrNull { it.kind == "ride" } ?: return@withContext emptyList()
        val stopIds = tt.tripStops(leg.tid)
        stopIds.asIterable().mapNotNull { id -> tt.stops[id]?.takeIf { it.hasCoords }?.let { it.lat to it.lng } }
    }

    fun boardingPoint(j: Journey): Pair<Double, Double>? {
        val tt = store.current?.timetable ?: return null
        val leg = j.rawLegs.firstOrNull { it.kind == "ride" } ?: return null
        return tt.stops[leg.fromStop]?.takeIf { it.hasCoords }?.let { it.lat to it.lng }
    }

    suspend fun vehicleTripInfo(v: Vehicle): VehicleTripInfo = withContext(Dispatchers.IO) {
        val none = VehicleTripInfo(null, null)
        val tt = store.ensure().timetable
        val tid = v.tid.takeIf { it != 0 } ?: tt.findTripId(v.cis, v.trip) ?: return@withContext none
        val trip = tt.trips[tid] ?: return@withContext none
        val times = tt.tripTimes(tid) ?: return@withContext none
        if (times.stops.isEmpty()) return@withContext none

        fun name(i: Int) = tt.stops[times.stops[i]]?.name?.let { prettyName(it) }

        val finalName = name(times.stops.lastIndex)?.takeIf { it.isNotBlank() }
            ?: trip.head.takeIf { it.isNotBlank() }?.let { prettyName(it) }

        // plánovaný čas "teď" = skutečný čas minus zpoždění
        var rel = nowPrague().toLocalTime().toSecondOfDay() - v.delay * 60
        if (rel < trip.t0 - 3600 && rel + 86400 <= trip.t1 + 3600) rel += 86400

        val next = times.stops.indices.firstOrNull { times.arr[it] > rel }
        VehicleTripInfo(finalName, next?.let { name(it) })
    }

    // ───────────────────────── nejbližší zastávka ─────────────────────────

    /** Druhy dopravy na zastávkách (vlak / bus), počítá se jednou na pozadí. */
    suspend fun loadStopModes(): StopModes = withContext(Dispatchers.IO) {
        store.ensure().timetable.computeStopModes()
    }

    /** Předehřátí spojení na routovací servery, ať první hledání nejbližší zastávky nečeká na TLS. */
    suspend fun warmUpRouting() = withContext(Dispatchers.IO) { osrm.warmUp() }

    suspend fun nearestCandidates(lat: Double, lng: Double, limit: Int = 8): List<Pair<Stop, Double>> =
        withContext(Dispatchers.IO) { stopLocator.nearest(store.ensure().timetable, lat, lng, limit) }

    suspend fun refineByRoad(lat: Double, lng: Double, cands: List<Pair<Stop, Double>>): Pair<Stop, Double>? =
        withContext(Dispatchers.IO) { stopLocator.refineByRoad(lat, lng, cands) }

    private companion object {
        const val TAG = "TransitRepository"
    }
}

/** Stránkování výsledků hledání (načíst další / dřívější). */
class JourneySearch(
    private val api: TransitRepository,
    private val fromName: String,
    private val toName: String,
    private val modeFilter: ModeFilter = ModeFilter.ALL,
) {
    private val currentJourneys = mutableListOf<Journey>()
    private val mutex = Mutex()
    private val startTime: LocalDateTime = nowPrague().withSecond(0).withNano(0)

    /** Konec hledání dopředu: 04:00 následujícího dne. */
    private val cutoff: LocalDateTime = startTime.toLocalDate().plusDays(1).atStartOfDay().plusHours(4)

    var hasMore: Boolean = true
        private set
    var hasEarlier: Boolean = true
        private set

    fun fresh(): JourneySearch = JourneySearch(api, fromName, toName, modeFilter)

    suspend fun loadMore(minNew: Int = 1): List<Journey> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (currentJourneys.isEmpty()) {
                val initial = api.searchConnections(fromName, toName, startTime, minNew, modeFilter = modeFilter)
                currentJourneys.addAll(initial)
                if (initial.isEmpty()) hasMore = false
            } else {
                val more = api.searchMoreLater(fromName, toName, currentJourneys.toList(), modeFilter = modeFilter).filter { it.depDt < cutoff }
                if (more.size <= currentJourneys.size) {
                    hasMore = false
                } else {
                    currentJourneys.clear()
                    currentJourneys.addAll(more)
                }
            }
            currentJourneys.toList() // kopie, ať Compose vidí změnu
        }
    }

    suspend fun loadEarlier(): List<Journey> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (currentJourneys.isNotEmpty()) {
                var grew = false
                for (hours in intArrayOf(2, 6, 12)) {
                    val more = api.searchMoreEarlier(fromName, toName, currentJourneys.toList(), hours, modeFilter = modeFilter)
                    if (more.size > currentJourneys.size) {
                        currentJourneys.clear()
                        currentJourneys.addAll(more)
                        grew = true
                        break
                    }
                }
                if (!grew) hasEarlier = false
            }
            currentJourneys.toList()
        }
    }
}