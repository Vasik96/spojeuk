package com.vasik96.spojeuk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * Jediný vstup pro UI (nahrazuje DukApi). Sám nic nedělá, jen deleguje:
 *  - jízdní řády a plánování  → [TimetableStore]
 *  - živé polohy              → [VehicleHub] (vlastní server, API v2: /api/vehicles)
 *  - trasy pro mapu           → [RouteGeometry]
 *  - nejbližší zastávky       → [StopLocator]
 */
class TransitRepository(context: Context? = null) {

    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val osrm = OsrmClient(context?.cacheDir ?: File("/tmp"), context)

    private val store = TimetableStore(context, http) { onTimetableLoaded(it) }
    private val shapes by lazy { ShapeStore(store.dbFile.parentFile ?: File("/tmp"), context) }
    private val trainShapes by lazy { ShapeStore(store.dbFile.parentFile ?: File("/tmp"), context, "train_shapes") }
    /** Tvary linek PID, které v CIS nejsou (AE, výlukové X…; spoje "pidgtfs:…"), viz spojeuk-data add_pid_gtfs.py. */
    private val pidShapes by lazy { ShapeStore(store.dbFile.parentFile ?: File("/tmp"), context, "pid_shapes") }
    private val geometry by lazy { RouteGeometry(shapes, trainShapes, pidShapes, osrm) }
    private val stopLocator = StopLocator(osrm)
    private val hub = VehicleHub(VehicleApi(), scope, context?.let { File(it.filesDir, "last_vehicles.json") }) { store.current?.timetable }

    @Volatile private var shapeUpgradeTried = false

    // číslo vlaku → linka z posledního jízdního řádu (pár set kB); načte se hned, celý JŘ trvá sekundy
    private val trainLinesFile = context?.let { File(it.filesDir, "train_lines_quick.tsv") }

    init {
        scope.launch {
            val f = trainLinesFile ?: return@launch
            val table = runCatching {
                if (!f.exists()) return@runCatching emptyMap<String, String>()
                val m = HashMap<String, String>(16_384)
                f.forEachLine { l ->
                    val tab = l.indexOf('\t')
                    if (tab > 0) m[l.substring(0, tab)] = l.substring(tab + 1)
                }
                m
            }.getOrDefault(emptyMap())
            Log.i(TAG, "rychlá tabulka linek vlaků: ${table.size}")
            if (table.isNotEmpty()) hub.setQuickTrainLines(table)
        }
    }

    private fun saveTrainLines(table: Map<String, String>) {
        val f = trainLinesFile ?: return
        if (table.isEmpty()) return
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.bufferedWriter().use { w -> for ((k, v) in table) { w.write(k); w.write('\t'.code); w.write(v); w.write('\n'.code) } }
        if (!tmp.renameTo(f)) tmp.delete()
    }

    private val autoPolicy = context?.let { AutoUpdatePolicy(it) }

    fun vehicleSourceOptions(): List<VehicleSourceOption> =
        hub.sourceOptions()

    fun setDisabledVehicleSources(ids: Set<String>) {
        hub.setDisabledSources(ids)
    }
    private fun onTimetableLoaded(tt: Timetable) {
        Log.i(TAG, "DB: hasGid=${tt.hasGid} trips=${tt.meta["trips"]} tag=${tt.meta["tag"]}")
        prewarmPlanner()
        hub.onTimetableLoaded()
        scope.launch { runCatching { saveTrainLines(tt.trainLineTable()) }.onFailure { Log.w(TAG, "uložení linek vlaků selhalo: ${it.message}") } }
        if (!tt.hasGid) scheduleShapeUpgrade()
        scope.launch {
            runCatching { shapes.sync(http) }.onFailure { Log.w(TAG, "sync tras selhal: ${it.message}") }
            runCatching { trainShapes.sync(http) }.onFailure { Log.w(TAG, "sync tras vlaků selhal: ${it.message}") }
            runCatching { pidShapes.sync(http) }.onFailure { Log.w(TAG, "sync tras PID selhal: ${it.message}") }
        }
    }

    // ── zastávky na mapě ──
    private val stopIndexLock = Any()
    private var stopIndex: Pair<Timetable, StopIndex>? = null

    private fun stopIndexFor(b: TimetableBundle): StopIndex = synchronized(stopIndexLock) {
        stopIndex?.takeIf { it.first === b.timetable }?.second
            ?: StopIndex.build(b.timetable, b.timetable.netCache)
                .also { stopIndex = b.timetable to it }
    }

    /** Index zastávek pro mapu, jakmile je načtený jízdní řád (jinak null, nic se nečeká). */
    suspend fun mapStops(): StopIndex? = withContext(Dispatchers.Default) {
        val b = store.current ?: return@withContext null
        stopIndexFor(b)
    }

    /** Druh dopravy spoje z jízdního řádu (barva trasy), null = jízdní řád ještě není načtený. */
    internal fun tripStyleOf(tid: Int): VehicleStyle? {
        val tt = store.current?.timetable ?: return null
        val line = tt.trips.lineOf(tid) ?: return null
        return tripStyle(tt.trips.category(tid), line, tt.trips.agency[tid].orEmpty())
    }

    /** Odkaz na spoj odjíždějící ze zastávky na mapě (pro nalezení vozu a zvýraznění). */
    suspend fun stopDepartureRef(d: StopDeparture, stop: MapStop): TripRef = withContext(Dispatchers.Default) {
        val tt = store.ensure().timetable
        val t = tt.trips[d.tid]
        val at = d.day.atStartOfDay(PRAGUE_ZONE).plusSeconds(d.depSec.toLong())
        TripRef(
            cis = t?.cis ?: "", trip = t?.num ?: "", line = d.line,
            boardLat = stop.lat, boardLng = stop.lng, destination = d.headsign,
            departureTimeMillis = at.toInstant().toEpochMilli(), tid = d.tid,
        )
    }

    /** Celá trasa spoje z jízdního řádu: tvar trasy a polohy zastávek (na nástupištích). */
    suspend fun tripRouteById(tid: Int): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> =
        withContext(Dispatchers.IO) {
            val tt = store.ensure().timetable
            geometry.forTrip(tt, tid) to platformLocations(tt, tt.tripStops(tid))
        }

    // ── poslední karta odjezdů z nejbližší zastávky: ukáže se hned po startu, než se načte jízdní řád ──
    private val nearbyFile = context?.let { File(it.filesDir, "last_nearby.json") }

    /** Uloží poslední výsledek [nearbyDepartures] (k poloze [lat], [lng]) pro okamžité zobrazení po příštím startu. */
    suspend fun saveNearbySnapshot(lat: Double, lng: Double, nb: NearbyDepartures) = withContext(Dispatchers.IO) {
        val f = nearbyFile ?: return@withContext
        runCatching {
            val deps = JSONArray()
            for ((d, p) in nb.departures) deps.put(
                JSONObject().put("tid", d.tid).put("dep", d.depSec).put("day", d.day.toString())
                    .put("line", d.line).put("head", d.headsign).put("cat", d.category.name).put("ag", d.agency)
                    .put("pLat", p.lat).put("pLng", p.lng).put("pName", p.name)
            )
            val o = JSONObject().put("tag", store.meta()["tag"] ?: "")
                .put("uLat", lat).put("uLng", lng)
                .put("name", nb.stop.name).put("lat", nb.stop.lat).put("lng", nb.stop.lng).put("dist", nb.distanceM)
                .put("deps", deps)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) tmp.delete()
        }.onFailure { Log.w(TAG, "uložení odjezdů z blízké zastávky selhalo: ${it.message}") }
    }

    /**
     * Uložená karta pro stejné místo (do 150 m) a stejný jízdní řád, jen s odjezdy, které ještě neodjely.
     * Má příznak [NearbyDepartures.cached]: zastávka je jen popisek a ťuknutí se řeší až s opravdovými daty.
     */
    suspend fun cachedNearby(lat: Double, lng: Double): NearbyDepartures? = withContext(Dispatchers.IO) {
        val f = nearbyFile?.takeIf { it.exists() } ?: return@withContext null
        runCatching {
            val o = JSONObject(f.readText())
            if (o.optString("tag") != (store.meta()["tag"] ?: "")) return@runCatching null
            if (Geo.distanceM(lat, lng, o.getDouble("uLat"), o.getDouble("uLng")) > 150.0) return@runCatching null
            val now = nowPrague()
            val arr = o.getJSONArray("deps")
            val deps = ArrayList<Pair<StopDeparture, MapStop>>()
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val day = java.time.LocalDate.parse(j.getString("day"))
                val dep = j.getInt("dep")
                if (!day.atStartOfDay().plusSeconds(dep.toLong()).isAfter(now)) continue
                deps += StopDeparture(
                    j.getInt("tid"), dep, day, j.getString("line"), j.getString("head"),
                    MapCategory.valueOf(j.getString("cat")), j.optString("ag", ""),
                ) to MapStop("", j.getDouble("pLat"), j.getDouble("pLng"), j.getString("pName"), IntArray(0))
            }
            if (deps.isEmpty()) return@runCatching null
            val stop = MapStop("", o.getDouble("lat"), o.getDouble("lng"), o.getString("name"), IntArray(0))
            NearbyDepartures(stop, o.getDouble("dist"), deps, cached = true)
        }.getOrNull()
    }

    /**
     * Nejbližší zastávka k poloze (do [maxM] m vzdušnou čarou) a její nejbližší odjezdy ze všech nástupišť
     * (oba směry), seřazené podle času. null = žádná zastávka v dosahu nebo jízdní řád ještě není načtený.
     */
    suspend fun nearbyDepartures(
        lat: Double, lng: Double,
        /** Nejbližší zastávka po cestě (jako u předvyplnění "Odkud") a vzdálenost k ní; null = vzdušnou čarou. */
        preferred: Pair<Stop, Double>? = null,
        maxM: Double = 800.0, limit: Int = 4,
    ): NearbyDepartures? =
        withContext(Dispatchers.Default) {
            // čeká se na dokončení načtení (bez dotazování po třech vteřinách), samo načítání už běží na pozadí
            val b = store.awaitCurrent()
            val idx = stopIndexFor(b)
            val nearest: MapStop
            val dist: Double
            val pref = preferred?.takeIf { it.first.hasCoords }
            if (pref != null) {
                val want = prettyName(pref.first.name)   // MapStop.name je ve tvaru prettyName
                nearest = idx.stops.filter { it.name == want }
                    .minByOrNull { Geo.distanceM(pref.first.lat, pref.first.lng, it.lat, it.lng) } ?: return@withContext null
                dist = pref.second
            } else {
                nearest = idx.stops.minByOrNull { Geo.distanceM(lat, lng, it.lat, it.lng) } ?: return@withContext null
                dist = Geo.distanceM(lat, lng, nearest.lat, nearest.lng)
            }
            if (dist > maxM) return@withContext null
            // nástupiště stejné zastávky (oba směry, vlaky), ne zastávka stejného jména na druhém konci města
            val platforms = idx.stops.filter { it.name == nearest.name && Geo.distanceM(nearest.lat, nearest.lng, it.lat, it.lng) < 300.0 }
            val now = nowPrague()
            val seen = HashSet<Long>()
            val deps = platforms.flatMap { p -> stopDepartures(b.planner, p, now, 2).map { it to p } }
                .filter { (d, _) -> seen.add(d.tid.toLong() shl 20 or (d.depSec.toLong() and 0xFFFFF)) }
                .sortedBy { (d, _) -> d.day.atStartOfDay().plusSeconds(d.depSec.toLong()) }
                .take(limit)
            NearbyDepartures(nearest, dist, deps)
        }

    /** Odjezdy ze zastávky na mapě v nejbližších [hours] hodinách (podle jízdního řádu). */
    suspend fun stopDepartures(stop: MapStop, hours: Int = 3): List<StopDeparture> = withContext(Dispatchers.Default) {
        val b = store.ensure()
        stopDepartures(b.planner, stop, nowPrague(), hours)
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
            runCatching { pidShapes.sync(http, force = true) }
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

    private val stopsLock = Mutex()
    @Volatile private var quickStops: List<Stop>? = null

    /** Zastávky z jízdního řádu, a dokud se ten načítá, zastávky přečtené rovnou z DB. */
    private suspend fun lookupStops(): Collection<Stop> {
        store.current?.let { return it.timetable.stops.values }
        quickStops?.let { return it }
        return stopsLock.withLock {
            store.current?.timetable?.stops?.values
                ?: quickStops
                ?: store.quickStops()?.also { quickStops = it; preloadTimetable() }
                ?: store.ensure().timetable.stops.values
        }
    }

    /**
     * Při startu procesu (Application.onCreate): jízdní řád se začne načítat hned, ne až po vykreslení první obrazovky
     * (appka po startu často rovnou opakuje poslední hledání). Při prvním spuštění se nic nedělá, data teprve stahuje
     * obrazovka stahování.
     */
    fun warmUpTimetable() {
        if (store.isFirstRun() || store.hasPending()) return
        preloadTimetable()
    }

    /** Celý jízdní řád (statisíce spojů) se dočte na pozadí; zastávky už mezitím fungují. */
    private fun preloadTimetable() {
        scope.launch {
            runCatching { store.ensure() }.onFailure { Log.w(TAG, "načtení jízdního řádu selhalo: ${it.message}") }
            quickStops = null
        }
    }

    suspend fun loadStops(log: (String) -> Unit = {}, progress: (Float) -> Unit = {}): List<Stop> =
        withContext(Dispatchers.IO) {
            store.current?.let { return@withContext it.timetable.stops.values.distinctBy { s -> s.name } }
            // rychlá cesta: jen tabulka zastávek, spoje se dočtou na pozadí. Při prvním spuštění ne: data se teprve
            // stahují a obrazovka stahování potřebuje hlášky a postup (rychlá cesta by stahovala bez nich)
            if (!store.hasPending() && !store.isFirstRun()) {
                val quick = runCatching { lookupStops() }.getOrNull()
                if (quick != null && store.current == null) return@withContext quick.distinctBy { it.name }
            }
            store.ensure(log, progress).timetable.stops.values.distinctBy { it.name }
        }

    fun getTimetableMeta(): Map<String, String> = store.meta()
    fun getShapesMeta(): String? = shapes.getBuiltDate()

    fun getLastTimetableUpdateMs(): Long = autoPolicy?.lastTimetableUpdateMs ?: 0L
    fun getLastShapesUpdateMs(): Long = autoPolicy?.lastShapesUpdateMs ?: 0L
    fun getLastRealtimeUpdateMs(): Long = autoPolicy?.lastRealtimeUpdateMs ?: 0L
    fun getLastOsrmClearMs(): Long = autoPolicy?.lastOsrmClearMs ?: 0L
    fun getLastCompleteUpdateMs(): Long = autoPolicy?.lastSuccessMs ?: 0L

    suspend fun updateTimetable(log: (String) -> Unit = {}, progress: (Float) -> Unit = {}): Unit {
        store.stageUpdate(log, progress)
        autoPolicy?.lastTimetableUpdateMs = System.currentTimeMillis()
    }

    suspend fun updateShapes(log: (String) -> Unit = {}, forceDownload: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            log(tr(R.string.dl_checking_shapes))
            val updated = shapes.sync(http, force = true, forceDownload = forceDownload)
            runCatching { trainShapes.sync(http, force = true, forceDownload = forceDownload) }
                .onFailure { Log.w(TAG, "sync tras vlaků selhal: ${it.message}") }
            runCatching { pidShapes.sync(http, force = true, forceDownload = forceDownload) }
                .onFailure { Log.w(TAG, "sync tras PID selhal: ${it.message}") }
            autoPolicy?.lastShapesUpdateMs = System.currentTimeMillis()
            log(tr(if (updated) R.string.dl_shapes_updated else R.string.dl_shapes_current))
            updated
        }

    fun clearOsrmCache(log: (String) -> Unit = {}): Int {
        log(tr(R.string.dl_clearing_osrm))
        val count = osrm.clearCache()
        autoPolicy?.lastOsrmClearMs = System.currentTimeMillis()
        log(trPlural(R.plurals.dl_osrm_cleared, count, count))
        return count
    }

    suspend fun updateAllData(
        log: (String) -> Unit = {},
        progress: (Float) -> Unit = {},
        forceShapes: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        log(tr(R.string.dl_full_start))
        progress(0.05f)

        log(tr(R.string.dl_step, 1, tr(R.string.dl_step_tt)))
        updateTimetable(log = { log(tr(R.string.dl_step, 1, it)) }, progress = { progress(0.05f + it * 0.5f) })

        log(tr(R.string.dl_step, 2, tr(R.string.dl_step_shapes)))
        progress(0.6f)
        updateShapes(log = { log(tr(R.string.dl_step, 2, it)) }, forceDownload = forceShapes)

        log(tr(R.string.dl_step, 3, tr(R.string.dl_step_rt)))
        progress(0.8f)
        updateRealtime(log = { log(tr(R.string.dl_step, 3, it)) })

        log(tr(R.string.dl_step, 4, tr(R.string.dl_step_osrm)))
        progress(0.95f)
        clearOsrmCache(log = { log(tr(R.string.dl_step, 4, it)) })

        val now = System.currentTimeMillis()
        autoPolicy?.let {
            it.lastTimetableUpdateMs = now
            it.lastShapesUpdateMs = now
            it.lastRealtimeUpdateMs = now
            it.lastOsrmClearMs = now
            it.lastSuccessMs = now
        }

        progress(1.0f)
        log(tr(R.string.dl_full_done))
    }

    // ───────────────────────── vyhledávání spojení ─────────────────────────

    /** [departAt] = zvolený čas odjezdu; null = hned teď. */
    fun newSearch(
        fromName: String,
        toName: String,
        modeFilter: ModeFilter = ModeFilter.ALL,
        departAt: LocalDateTime? = null,
        arriveBy: Boolean = false,
    ): JourneySearch = JourneySearch(this, fromName, toName, modeFilter, departAt, arriveBy)

    suspend fun searchConnections(
        fromName: String,
        toName: String,
        startTime: LocalDateTime = nowPrague(),
        count: Int = 8,
        modeFilter: ModeFilter = ModeFilter.ALL,
    ): List<Journey> = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        val b = store.ensure()
        val t1 = System.nanoTime()
        val journeys = b.planner.find(fromName, toName, startTime, count, modeFilter = modeFilter)
        val t2 = System.nanoTime()
        val live = withLiveDelays(journeys)
        val t3 = System.nanoTime()
        val out = withRouteStops(live)
        val t4 = System.nanoTime()
        fun ms(a: Long, b: Long) = (b - a) / 1_000_000
        Log.d("SearchPerf", "$fromName → $toName: JŘ ${ms(t0, t1)} ms, plánovač ${ms(t1, t2)} ms, " +
                "živá zpoždění ${ms(t2, t3)} ms, zastávky ${ms(t3, t4)} ms, celkem ${ms(t0, t4)} ms (${out.size} spojení)")
        out
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

    /**
     * Síť spojů pro dnešek (a večer i pro zítřek, kam hledání přetéká přes půlnoc) se postaví na pozadí hned po načtení
     * jízdního řádu: stavba projde všechny spoje a trvá sekundy, dřív ji zaplatilo až první "Najít spojení".
     */
    private fun prewarmPlanner() {
        val b = store.current ?: return
        // Karta odjezdů z nejbližší zastávky potřebuje index zastávek a síť dnešního dne: ty se stavějí hned a souběžně.
        // Pěší přestupy (až pro hledání spojení) přijdou na řadu teprve potom, ať neberou procesor kartě.
        // stopIndexFor je zamčený, takže kdo index potřebuje dřív, jen počká na rozdělanou stavbu.
        val indexJob = scope.async(Dispatchers.Default) {
            runCatching { stopIndexFor(b) }.onFailure { Log.w(TAG, "příprava indexu zastávek selhala: ${it.message}") }
        }
        val netJob = scope.async(Dispatchers.Default) {
            val now = nowPrague()
            runCatching {
                val today = now.toLocalDate()
                b.planner.prepare(if (now.hour >= 18) listOf(today, today.plusDays(1)) else listOf(today))
            }.onFailure { Log.w(TAG, "příprava plánovače selhala: ${it.message}") }
            val rt = Runtime.getRuntime()
            Log.d("SearchPerf", "halda po přípravě: ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB z ${rt.maxMemory() / 1048576} MB")
        }
        scope.launch(Dispatchers.Default) {
            indexJob.await(); netJob.await()
            val t0 = System.nanoTime()
            runCatching { b.timetable.foot }.onFailure { Log.w(TAG, "příprava pěších přestupů selhala: ${it.message}") }
            Log.d("SearchPerf", "pěší přestupy připraveny za ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    /** Doplní do spojení živé zpoždění (z posledního sloučeného stavu vozů). */
    private suspend fun withLiveDelays(journeys: List<Journey>): List<Journey> {
        // na čerstvé polohy se čeká jen krátce: na pomalé síti by hledání čekalo až 15 s (opakované pokusy),
        // jinak se vezmou poslední známé polohy
        runCatching { kotlinx.coroutines.withTimeoutOrNull(LIVE_DELAY_WAIT_MS) { hub.refresh(maxAgeMs = 10_000) } }
        val fleet = hub.fleet
        for (j in journeys) {
            val delays = HashMap<Int, Int>()
            j.rawLegs.forEachIndexed { i, leg ->
                if (leg.kind != "ride") return@forEachIndexed
                val v = fleet.liveOn(leg, j.day) ?: return@forEachIndexed
                delays[i] = v.delay
                j.delay = v.delay
                j.realtime = true
            }
            j.legDelays = delays
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

    /** Podrobnosti spojení pro detail: všechny zastávky jednotlivých jízd, linky, dopravci, chůze. */
    internal suspend fun journeyDetail(j: Journey): List<DetailLeg> = withContext(Dispatchers.Default) {
        detailOf(store.ensure().timetable, j)
    }

    /** Detail hned (bez čekání), když už je jízdní řád v paměti: sheet / obrazovka pak neposkočí po načtení. */
    internal fun journeyDetailNow(j: Journey): List<DetailLeg>? = store.current?.timetable?.let { detailOf(it, j) }

    /** Živé vozy z posledního známého stavu (bez dotazu na server), pro první snímek detailu. */
    fun journeyLiveNow(j: Journey): Map<Int, Vehicle> {
        val fleet = hub.fleet
        val out = HashMap<Int, Vehicle>()
        j.rawLegs.forEachIndexed { i, leg -> if (leg.kind == "ride") fleet.liveOn(leg, j.day)?.let { out[i] = it } }
        return out
    }

    private fun detailOf(tt: Timetable, j: Journey): List<DetailLeg> {
        fun name(id: Int) = tt.stops[id]?.name?.let { prettyName(it) } ?: "?"
        return j.rawLegs.mapIndexedNotNull { idx, leg ->
            if (leg.kind == "ride") {
                val t = tt.trips[leg.tid]
                val line = t?.line ?: "?"
                val cat = tt.trips.category(leg.tid)
                DetailLeg.Ride(
                    index = idx,
                    line = line,
                    headsign = prettyName(t?.head.orEmpty()),
                    agency = cleanAgencyName(t?.agency.orEmpty()),
                    category = cat,
                    style = tripStyle(cat, line, t?.agency.orEmpty()),
                    trainNumber = t?.takeIf { it.isTrain }?.num?.takeIf { it.isNotBlank() },
                    wheelchair = t?.wheel == 1,
                    stops = leg.stops.map { (sid, arr, dep) -> DetailStop(name(sid), arr, dep) },
                )
            } else {
                if (leg.fromStop == leg.toStop || leg.arr - leg.dep <= 0) return@mapIndexedNotNull null
                val a = tt.stops[leg.fromStop]
                val b = tt.stops[leg.toStop]
                val dist = if (a != null && b != null && a.hasCoords && b.hasCoords)
                    Geo.distanceM(a.lat, a.lng, b.lat, b.lng) else null
                DetailLeg.Walk(
                    index = idx,
                    fromName = name(leg.fromStop),
                    toName = name(leg.toStop),
                    dep = leg.dep,
                    arr = leg.arr,
                    distanceM = dist,
                )
            }
        }
    }

    /**
     * Jen jedna jízda spojení jako samostatné "spojení": mapa pak ukáže a vyhledá vůz právě téhle jízdy
     * (ne vždy té první, jako u celého spojení s přestupem).
     */
    fun legJourney(j: Journey, legIndex: Int): Journey? {
        val leg = j.rawLegs.getOrNull(legIndex)?.takeIf { it.kind == "ride" } ?: return null
        val t = store.current?.timetable?.trips?.get(leg.tid)
        val line = t?.line ?: "?"
        return j.copy(
            departure = hhmm(leg.dep),
            arrival = hhmm(leg.arr),
            line = line,
            line2 = null,
            destination = prettyName(t?.head.orEmpty()),
            agency = t?.agency.orEmpty(),
            stopsCount = maxOf(0, leg.stops.size - 1),
            transferAt = null,
            transferWait = null,
            trip = t?.num?.ifBlank { null } ?: leg.tid.toString(),
            trip2 = null,
            sortKey = leg.dep,
            rawLegs = listOf(leg),
            transfers = 0,
            lines = listOf(line),
            transferList = emptyList(),
            cis = t?.cis.orEmpty(),
            boardWalkMin = null,
            boardStopName = null,
            transferWalkMin = null,
        )
    }

    /**
     * Která jízda spojení se má ukázat na mapě (index v [Journey.rawLegs]): první, dokud z nástupní zastávky
     * neodjela před víc než 3 minutami (odjezd podle JŘ + živé zpoždění), pak další atd.
     */
    fun activeLegIndex(j: Journey, now: java.time.LocalDateTime = nowPrague()): Int {
        val fleet = hub.fleet
        return activeLegIndexOf(j.rawLegs, secondsSinceMidnight(j.day, now)) { i ->
            fleet.liveOn(j.rawLegs[i], j.day)?.delay ?: 0
        }
    }

    /** Živé vozy na jízdách spojení (index úseku v [Journey.rawLegs] → vůz); čerstvé polohy se čekají jen krátce. */
    suspend fun journeyLive(j: Journey): Map<Int, Vehicle> {
        runCatching { kotlinx.coroutines.withTimeoutOrNull(LIVE_DELAY_WAIT_MS) { hub.refresh(maxAgeMs = 10_000) } }
        val fleet = hub.fleet
        val out = HashMap<Int, Vehicle>()
        j.rawLegs.forEachIndexed { i, leg ->
            if (leg.kind == "ride") fleet.liveOn(leg, j.day)?.let { out[i] = it }
        }
        return out
    }

    /** pěší trasa po silnici před nástupem **/
    suspend fun boardWalk(j: Journey): WalkRoute? = withContext(Dispatchers.IO) {
        journeyWalks(j).firstOrNull()
    }

    // ───────────────────────── živé polohy ─────────────────────────

    /** Zůstává kvůli UI; server odpovídá hned, takže se už nic nedotahuje na pozadí a tok nic nevysílá. */
    val vehicleUpdates get() = hub.updates

    /** Poslední sloučený stav bez nového dotazu. */
    fun currentVehicles(): List<Vehicle> = hub.fleet.vehicles

    /** Stáhne polohy hned při startu, ať jsou na mapě už při jejím otevření. */
    suspend fun prewarmVehicles() {
        runCatching { hub.refresh() }
    }

    /** Poslední dotaz na server poloh selhal (zobrazené polohy jsou jen z minula). */
    fun vehicleFetchFailing(): Boolean = hub.isFailing

    /** Volá se, když se objeví / změní síť: zahodí stará spojení a hned stáhne polohy. */
    fun onNetworkAvailable() = hub.onNetworkAvailable()

    /** Spustí stažení poloh asynchronně na pozadí hned při spuštění appky. */
    fun prewarmVehiclesAsync() {
        scope.launch {
            runCatching { hub.refresh() }
        }
    }

    /** Sloučené polohy ze všech zdrojů. S [maxAgeMs] > 0 vrátí stávající, pokud jsou dostatečně čerstvé. */
    suspend fun vehiclePositions(maxAgeMs: Long = 0): List<Vehicle> = hub.refresh(maxAgeMs).vehicles

    suspend fun updateRealtime(log: (String) -> Unit = {}): List<Vehicle> {
        log(tr(R.string.dl_step_rt))
        val vehicles = vehiclePositions()
        autoPolicy?.lastRealtimeUpdateMs = System.currentTimeMillis()
        log(trPlural(R.plurals.dl_rt_refreshed, vehicles.size, vehicles.size))
        return vehicles
    }

    /** Text pro UI: "DPMÚL, DÚK", "DÚK (MHD zastaralé)", … */
    fun sourceLabel(): String = hub.fleet.label

    /** Kolik poloh je jen držených z minula (UI varuje od [OUTAGE_MIN]). */
    fun heldCount(): Int = hub.fleet.warningCount

    fun sourceName(id: String): String = hub.sourceName(id)

    // ───────────────────────── trasy a detail spoje ─────────────────────────

    suspend fun tripGeometry(v: Vehicle): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        pmdpTrip(v)?.let { t ->
            val stops = t.stops.filter { it.hasCoords() }.map { it.lat to it.lng }
            return@withContext t.route.takeIf { it.size >= 2 } ?: stops
        }
        val tt = store.ensure().timetable
        val tid = v.tid.takeIf { it != 0 }
            ?: tt.findTripId(v.cis, v.trip)
            ?: tripFallback(tt, v)
            ?: return@withContext emptyList()
        geometry.forTrip(tt, tid)
    }

    /**
     * Spoj k vozu, když ho nedalo obohacení (VehicleHub): vlak podle čísla (i když teď podle JŘ nejede, např. souprava
     * stojí po oběhu nebo jde o jiný den: trasa vlaku s tím číslem), ostatní podle polohy a času, nakonec nejbližší
     * dnešní spoj linky.
     */
    private fun tripFallback(tt: Timetable, v: Vehicle): Int? =
        if (v.train) tt.findTrainTripIdAnyDay(v.cis)
        else tt.locator.matchByPosition(v, emptyList())
            ?: tt.locator.nearestOnLine(v.line, v.lat, v.lng, maxStopDistance(v))

    /** Pro ladění: z čeho se kreslí trasa tohoto vozidla (viz řádek „Trasa“ v detailu). */
    suspend fun routeSourceInfo(v: Vehicle): String = withContext(Dispatchers.IO) {
        val tt = store.ensure().timetable
        val tid = v.tid.takeIf { it != 0 }
            ?: tt.findTripId(v.cis, v.trip)
            ?: tripFallback(tt, v)
            ?: return@withContext "spoj z jízdního řádu nenalezen"
        "spoj $tid, ${geometry.describe(tt, tid)}\ngid: ${tt.gidOf(tid)}"
    }

    suspend fun tripStopLocations(v: Vehicle): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        pmdpTrip(v)?.let { t -> return@withContext t.stops.filter { it.hasCoords() }.map { it.lat to it.lng } }
        val tt = store.ensure().timetable
        val tid = v.tid.takeIf { it != 0 }
            ?: tt.findTripId(v.cis, v.trip)
            ?: tripFallback(tt, v)
            ?: return@withContext emptyList()
        platformLocations(tt, tt.tripStops(tid))
    }

    /**
     * Polohy zastávek jízdy [stopIds] (v pořadí jízdy) na stejném místě jako nástupiště na mapě (strana silnice
     * podle směru jízdy); kde nástupiště není, poloha z jízdního řádu.
     */
    private fun platformLocations(tt: Timetable, stopIds: IntArray): List<Pair<Double, Double>> {
        val idx = store.current?.takeIf { it.timetable === tt }?.let { runCatching { stopIndexFor(it) }.getOrNull() }
        return stopIds.indices.mapNotNull { i ->
            val id = stopIds[i]
            idx?.platformFor(id, stopIds.getOrElse(i - 1) { id }, stopIds.getOrElse(i + 1) { id })
                ?.let { return@mapNotNull it.lat to it.lng }
            tt.stops[id]?.takeIf { it.hasCoords }?.let { it.lat to it.lng }
        }
    }

    suspend fun resolveTripId(v: Vehicle): Int? = withContext(Dispatchers.IO) {
        val tt = store.ensure().timetable
        v.tid.takeIf { it != 0 }
            ?: tt.findTripId(v.cis, v.trip)
            ?: tripFallback(tt, v)
    }

    suspend fun routeForTrip(tid: Int): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        geometry.forTrip(store.ensure().timetable, tid)
    }

    suspend fun stopCoordsForTrip(tid: Int): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val tt = store.ensure().timetable
        val stopIds = tt.tripStops(tid)
        stopIds.asIterable().mapNotNull { id -> tt.stops[id]?.takeIf { it.hasCoords }?.let { it.lat to it.lng } }
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
        platformLocations(tt, tt.tripStops(leg.tid))
    }

    /**
     * Přesné polohy zastávek, kterými spojení projíždí (všechny jízdy, od nástupu po výstup), bez úplně první
     * a úplně poslední zastávky spojení; ty mají na mapě vlastní značky. Přestupní zastávky tu jsou.
     */
    suspend fun journeyStopLocations(j: Journey): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val tt = store.ensure().timetable
        // polohy po jízdách (směr jízdy je v rámci jedné jízdy), přestup na stejné zastávce se nezdvojuje
        val ids = ArrayList<Int>()
        val locs = ArrayList<Pair<Double, Double>?>()
        for (leg in j.rawLegs) {
            if (leg.kind != "ride") continue
            val legIds = leg.stops.map { it.first }.toIntArray()
            val legLocs = platformLocations(tt, legIds)
            // platformLocations vynechá zastávky bez souřadnic: párovat jen při shodě počtu
            for ((i, id) in legIds.withIndex()) {
                if (ids.lastOrNull() == id) continue
                ids.add(id)
                locs.add(if (legLocs.size == legIds.size) legLocs[i] else tt.stops[id]?.takeIf { it.hasCoords }?.let { it.lat to it.lng })
            }
        }
        if (ids.size <= 2) return@withContext emptyList()
        locs.subList(1, locs.size - 1).filterNotNull()
    }

    fun boardingPoint(j: Journey): Pair<Double, Double>? {
        val tt = store.current?.timetable ?: return null
        val leg = j.rawLegs.firstOrNull { it.kind == "ride" } ?: return null
        return tt.stops[leg.fromStop]?.takeIf { it.hasCoords }?.let { it.lat to it.lng }
    }

    // ───────── Plzeň (PMDP): spoj přímo od dopravce ─────────
    // Spoje PMDP jsou v celostátním jízdním řádu pod jinými čísly, takže zastávky, časy a tvar trasy dodává
    // feed server (/api/trip/pmdp, z mapy PMDP) podle čísla spoje ve [Vehicle.trip]. Stejně to dělá web.

    private class RemoteStop(val name: String, val lat: Double, val lng: Double, val arr: Int)
    private class RemoteTrip(val stops: List<RemoteStop>, val route: List<Pair<Double, Double>>)

    /** číslo spoje → (kdy, spoj nebo null = server ho nezná) */
    private val remoteTrips = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, RemoteTrip?>>()

    private fun pmdpTrip(v: Vehicle): RemoteTrip? {
        if (v.source != SourceIds.PMDP || v.trip.isBlank()) return null
        val now = System.currentTimeMillis()
        val cached = remoteTrips[v.trip]
        if (cached != null && now - cached.first < (if (cached.second != null) 600_000L else 60_000L)) return cached.second
        val trip = try {
            val req = Request.Builder().url("$SERVER_IP/api/trip/pmdp?conn=${java.net.URLEncoder.encode(v.trip, "UTF-8")}").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else parseRemoteTrip(r.body?.string().orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Trasa PMDP ${v.trip}: ${e.message}")
            return cached?.second // výpadek sítě: poslední známá, nic se nezapamatuje
        }
        if (remoteTrips.size > 300) remoteTrips.clear()
        remoteTrips[v.trip] = now to trip
        return trip
    }

    private fun parseRemoteTrip(body: String): RemoteTrip? {
        val t = JSONObject(body).optJSONObject("trip") ?: return null
        val st = t.optJSONArray("stops") ?: return null
        val stops = (0 until st.length()).mapNotNull { i ->
            val a = st.optJSONArray(i) ?: return@mapNotNull null
            RemoteStop(prettyName(a.optString(0)), a.optDouble(1), a.optDouble(2), a.optInt(3, -1))
        }
        val rt = t.optJSONArray("route")
        val route = if (rt == null) emptyList() else (0 until rt.length()).mapNotNull { i ->
            rt.optJSONArray(i)?.let { it.optDouble(0) to it.optDouble(1) }
        }
        return if (stops.isEmpty()) null else RemoteTrip(stops, route)
    }

    private fun RemoteStop.hasCoords() = lat != 0.0 || lng != 0.0

    private fun remoteTripInfo(v: Vehicle, t: RemoteTrip): VehicleTripInfo {
        // plánovaný čas "teď" = skutečný čas minus zpoždění; spoj přes půlnoc má časy za 86400
        var rel = nowPrague().toLocalTime().toSecondOfDay() - v.delay * 60
        val first = t.stops.first().arr
        if (first >= 0 && rel < first - 3600 && rel + 86400 <= t.stops.last().arr + 3600) rel += 86400
        val next = t.stops.drop(1).firstOrNull { it.arr > rel }
        return VehicleTripInfo(t.stops.last().name.takeIf { it.isNotBlank() }, next?.name)
    }

    suspend fun vehicleTripInfo(v: Vehicle): VehicleTripInfo = withContext(Dispatchers.IO) {
        val none = VehicleTripInfo(null, null)
        pmdpTrip(v)?.let { return@withContext remoteTripInfo(v, it) }
        val tt = store.ensure().timetable
        // příští / konečná zastávka jen ze spoje, který opravdu jede (ne ranní oběh stojící soupravy)
        val tid = v.tid.takeIf { it != 0 } ?: tt.findTripId(v.cis, v.trip)
            ?: (if (v.train) tt.findTrainTripId(v.cis) else tripFallback(tt, v))
            ?: return@withContext none
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
        withContext(Dispatchers.IO) { stopLocator.nearest(lookupStops(), lat, lng, limit) }

    suspend fun refineByRoad(lat: Double, lng: Double, cands: List<Pair<Stop, Double>>): Pair<Stop, Double>? =
        withContext(Dispatchers.IO) { stopLocator.refineByRoad(lat, lng, cands) }

    companion object {
        const val TAG = "TransitRepository"

        @Volatile
        private var instance: TransitRepository? = null

        fun get(context: Context): TransitRepository =
            instance ?: synchronized(this) {
                instance ?: TransitRepository(context.applicationContext).also { instance = it }
            }
    }
}

/** Jak dlouho hledání spojení čeká na čerstvé polohy vozů (živá zpoždění), než použije poslední známé. */
private const val LIVE_DELAY_WAIT_MS = 1_200L

/** Jak daleko dopředu se dá ve výsledcích scrollovat. */
private const val SEARCH_SPAN_HOURS = 24L

/** Stránkování výsledků hledání (načíst další / dřívější). */
/** Nejbližší zastávka (pro hlavní obrazovku hledání) a její odjezdy s nástupištěm, ze kterého jedou. */
class NearbyDepartures(
    val stop: MapStop, val distanceM: Double, val departures: List<Pair<StopDeparture, MapStop>>,
    /** Uložený výsledek z minula (zobrazí se hned po startu), ne čerstvě spočítaný. */
    val cached: Boolean = false,
)

class JourneySearch(
    private val api: TransitRepository,
    private val fromName: String,
    private val toName: String,
    private val modeFilter: ModeFilter = ModeFilter.ALL,
    private val departAt: LocalDateTime? = null,
    /** [departAt] je nejpozdější příjezd (ne odjezd): hledají se spojení, která do té doby dorazí. */
    private val arriveBy: Boolean = false,
) {
    private val currentJourneys = mutableListOf<Journey>()
    private val mutex = Mutex()
    private val startTime: LocalDateTime = (departAt ?: nowPrague()).withSecond(0).withNano(0)

    /** Konec hledání dopředu: 24 hodin od začátku hledání. */
    private val cutoff: LocalDateTime = startTime.plusHours(SEARCH_SPAN_HOURS)

    var hasMore: Boolean = true
        private set
    var hasEarlier: Boolean = true
        private set

    fun fresh(): JourneySearch = JourneySearch(api, fromName, toName, modeFilter, departAt, arriveBy)

    suspend fun loadMore(minNew: Int = 1): List<Journey> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (arriveBy) {
                // příjezd: později už nic (spoj by nedorazil včas); jen jednou úvodní hledání
                if (currentJourneys.isEmpty()) currentJourneys.addAll(searchArriveBy(minNew))
                hasMore = false
                return@withLock currentJourneys.toList()
            }
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

    /**
     * Spojení s příjezdem nejpozději v [startTime]. Plánovač hledá jen dopředu, takže se hledá od okamžiku
     * před cílovým časem (okno se zvětšuje, dokud se nenajdou aspoň 3 vyhovující) a vezmou se ta s nejpozdějším
     * odjezdem. Výsledek je seřazený podle odjezdu jako u běžného hledání ("Dřívější" funguje stejně).
     */
    private suspend fun searchArriveBy(want: Int): List<Journey> {
        val deadline = startTime
        val now = nowPrague()
        var best: List<Journey> = emptyList()
        for (windowMin in longArrayOf(90, 180, 360, 720)) {
            var from = deadline.minusMinutes(windowMin)
            // dnešní cíl: do minulosti se nehledá (větší okno by už nic nového nepřineslo)
            val clamped = deadline.isAfter(now) && from.isBefore(now)
            if (clamped) from = now
            val found = api.searchConnections(fromName, toName, from, (want * 2).coerceAtLeast(12), modeFilter = modeFilter)
            val ok = found.filter { arrivalDt(it) <= deadline }
            if (ok.size > best.size) best = ok
            if (ok.size >= 3 || clamped) break
        }
        return best.sortedBy { it.depDt }.takeLast(want.coerceAtLeast(4))
    }

    private fun arrivalDt(j: Journey): LocalDateTime = j.day.atStartOfDay().plusSeconds(j.arr.toLong())

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