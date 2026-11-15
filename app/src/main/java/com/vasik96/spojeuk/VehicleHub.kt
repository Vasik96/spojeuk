package com.vasik96.spojeuk

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Výsledek jednoho kola: vozy, stav zdrojů a hotový text pro UI. */
data class Fleet(
    val vehicles: List<Vehicle>,
    val reports: List<SourceReport>,
    /** "DPMÚL, DÚK", "DÚK (MHD zastaralé)", … */
    val label: String,
    /** Kolik poloh je jen "držených" z minula a nikdo jiný je nepokrývá čerstvě. */
    val warningCount: Int,
) {
    private val byTid: Map<Int, List<Vehicle>> by lazy { vehicles.filter { it.tid != 0 }.groupBy { it.tid } }

    /** Vůz, který právě jede spoj [leg] (přednost má přesné přiřazení a čerstvé polohy). */
    fun liveOn(leg: Leg, day: LocalDate, nowMs: Long = System.currentTimeMillis()): Vehicle? {
        val now = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), PRAGUE_ZONE)
        val rel = secondsSinceMidnight(day, now)
        if (rel < leg.t0 - 3600 || rel > leg.t1 + 3600) return null
        val candidates = byTid[leg.tid]?.filter { it.state != 255 && it.delay > -30 && it.ageSec(nowMs) <= 300 } ?: return null
        return candidates.firstOrNull { it.tripMatch == TripMatch.EXACT }
            ?: candidates.firstOrNull { it.tripMatch == TripMatch.HEURISTIC }
    }

    companion object {
        val EMPTY = Fleet(emptyList(), emptyList(), "", 0)
    }
}

data class VehicleSourceOption(
    val id: String,
    val name: String
)

/** Směr jízdy z posunu polohy, pro vozy, které azimut nedávají (nebo dávají 0). */
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
 * Drží poslední odpověď serveru (`/api/vehicles`), obohacuje vozy o spoje z jízdního řádu a skládá [Fleet].
 * Slučování zdrojů dělá server (nejčerstvější poloha, doplnění chybějících údajů, mazání "duchů").
 */
class VehicleHub(
    private val api: VehicleApi,
    @Suppress("unused") private val scope: CoroutineScope,
    /** Dřívější cache poloh na disku; už se neukládá, soubor se jen smaže. */
    private val cacheFile: File? = null,
    private val timetable: () -> Timetable?,
) {
    private val headings = HeadingTracker()
    private val fetchMutex = Mutex()

    @Volatile
    var fleet: Fleet = Fleet.EMPTY
        private set

    @Volatile
    private var lastSuccessMs = 0L

    /** Kdy skončil poslední dotaz (úspěšný i neúspěšný); slouží k tomu, aby se souběžná volání nepřetáčela za sebou. */
    @Volatile
    private var lastFetchMs = 0L

    /** Poslední odpověď serveru (vozy už mají směr z [HeadingTracker]). */
    @Volatile
    private var snapshot: ApiSnapshot? = null

    // vozy obohacené o spoje; přepočítává se jen při nové odpovědi nebo novém jízdním řádu
    @Volatile private var enriched: List<Vehicle> = emptyList()
    @Volatile private var enrichedStamp = -1L
    @Volatile private var enrichedFor: Timetable? = null

    @Volatile
    private var initialFetchDone = false

    /** Aspoň jednou server odpověděl (na rozdíl od [initialFetchDone], které se nastaví i po vzdání startovních pokusů). */
    @Volatile
    private var everSucceeded = false

    /** Poslední dotaz na server selhal (polohy se zatím drží z minula). */
    val isFailing: Boolean get() = lastError != null

    @Volatile
    private var lastError: String? = null

    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Tok upozorňující na aktualizaci vozidel (z cache nebo po dokončení dotazu). */
    val updates: SharedFlow<Unit> = _updates.asSharedFlow()

    @Volatile
    private var enabledSourceIds: Set<String>? = null

    @Synchronized
    fun setEnabledSources(ids: Set<String>?) {
        enabledSourceIds = ids?.toSet()
        lastSuccessMs = 0L
        // výběr se projeví hned z poslední odpovědi, bez čekání na síť
        val snap = snapshot
        fleet = if (snap == null) Fleet.EMPTY else runCatching { build(snap, System.currentTimeMillis()) }.getOrDefault(Fleet.EMPTY)
    }

    fun sourceOptions(): List<VehicleSourceOption> =
        SOURCE_NAMES.map { (id, name) -> VehicleSourceOption(id = id, name = name) }

    fun sourceName(id: String): String = SOURCE_NAMES[id] ?: snapshot?.sources?.firstOrNull { it.id == id }?.name ?: id

    /**
     * Zeptá se serveru a vrátí sloučený stav. S [maxAgeMs] > 0 vrátí poslední stav, pokud je dost čerstvý.
     * Dotazy nechodí častěji než jednou za [MIN_FETCH_INTERVAL_MS], i když se refresh volá víckrát (návrat do appky,
     * polling, změna nastavení). Když server nejde, drží se poslední známé polohy jen [KEEP_MS] (zdroje se označí jako zastaralé). Výjimku vyhodí jen tehdy, když server ještě nikdy neodpověděl.
     */
    suspend fun refresh(maxAgeMs: Long = 0): Fleet {
        val now = System.currentTimeMillis()
        if (maxAgeMs > 0 && fleet !== Fleet.EMPTY && lastSuccessMs > 0 && now - lastSuccessMs < maxAgeMs) return fleet
        return fetchMutex.withLock {
            val t = System.currentTimeMillis()
            // souběžné volání už mohlo dotaz udělat (úspěšný i neúspěšný) – nečekat za něj znovu na další timeout
            if (t - lastFetchMs < MIN_FETCH_INTERVAL_MS) {
                if (fleet !== Fleet.EMPTY) return@withLock fleet
                if (snapshot == null && lastError != null) throw IllegalStateException(lastError)
            }
            try {
                val raw = withContext(Dispatchers.IO) { api.fetch() }
                val snap = raw.copy(vehicles = raw.vehicles.map { headings.track(it) })
                snapshot = snap
                lastError = null
                initialFetchDone = true
                everSucceeded = true
                lastSuccessMs = System.currentTimeMillis()
                withContext(Dispatchers.Default) { rebuild(snap, System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "dotaz na server poloh selhal: ${e.message}")
                val old = snapshot ?: throw IllegalStateException(e.message ?: "Server poloh neodpovídá")
                withContext(Dispatchers.Default) { rebuild(old, System.currentTimeMillis()) }
            } finally {
                lastFetchMs = System.currentTimeMillis()
            }
        }
    }

    /**
     * Síť se objevila / změnila (appka mohla startovat bez připojení, přepnutí Wi-Fi ↔ data): zahodí stará spojení
     * a hned zkusí stáhnout polohy, bez čekání na další kolo pollingu.
     */
    fun onNetworkAvailable() {
        api.resetConnections()
        lastFetchMs = 0L
        scope.launch(Dispatchers.IO) { runCatching { refresh() } }
    }

    @Synchronized
    private fun rebuild(snap: ApiSnapshot, now: Long): Fleet {
        val result = build(snap, now)
        fleet = result
        _updates.tryEmit(Unit)
        return result
    }

    private fun build(snap: ApiSnapshot, now: Long): Fleet {
        val enabled = enabledSourceIds
        val active = snap.sources.filter { enabled == null || it.id in enabled }
        if (enabled != null && active.isEmpty()) return Fleet.EMPTY

        // server nedostupný: poslední polohy se drží jen krátce (KEEP_MS), pak se zahodí.
        // Považovat zdroje za zastaralé až po úspěšném prvním stažení nebo po selhání opakovaných pokusů při startu.
        val serverStale = initialFetchDone && (now - snap.receivedMs > SERVER_STALE_MS)
        val dropped = now - snap.receivedMs > KEEP_MS

        val vehicles = if (dropped) emptyList() else {
            val all = enrichedVehicles(snap, timetable())
            val filtered = all.filter { v -> v.ageSec(now) <= 300 }
            if (enabled == null) filtered
            else filtered.filter { v -> v.sources.ifEmpty { listOf(v.source) }.any { it in enabled } }
        }
        val reports = active.map { report(it, snap, serverStale, dropped) }
        return Fleet(vehicles, reports, SourceLabel.build(reports), warningCount(reports))
    }

    private fun report(s: ApiSource, snap: ApiSnapshot, serverStale: Boolean, dropped: Boolean): SourceReport {
        val segments = SOURCE_SEGMENTS[s.id] ?: Segment.entries.toSet()
        val health = if (serverStale && s.health == Health.LIVE) Health.STALE else s.health
        val stale: Set<Segment> = when (health) {
            Health.DOWN -> emptySet()
            Health.STALE -> segments
            Health.LIVE -> if (s.id == SourceIds.DUK && snap.outage) segments else emptySet()
        }
        return SourceReport(
            id = s.id,
            name = SOURCE_NAMES[s.id] ?: s.name,
            priority = s.priority,
            segments = segments,
            health = health,
            staleSegments = stale,
            heldCount = if (s.id == SourceIds.DUK) snap.held else 0,
            vehicleCount = if (dropped) 0 else s.vehicles,
        )
    }

    private fun enrichedVehicles(snap: ApiSnapshot, tt: Timetable?): List<Vehicle> {
        if (tt == null) return snap.vehicles
        if (enrichedStamp != snap.receivedMs || enrichedFor !== tt) {
            enriched = snap.vehicles.map { TripEnricher.enrich(it, tt) }
            enrichedStamp = snap.receivedMs
            enrichedFor = tt
        }
        return enriched
    }

    /** Držené polohy se nehlásí, když jejich zastaralé segmenty čerstvě pokrývá jiný zdroj. */
    private fun warningCount(reports: List<SourceReport>): Int = reports.sumOf { r ->
        val covered = r.staleSegments.isNotEmpty() && r.staleSegments.all { seg ->
            reports.any { o -> o !== r && o.health == Health.LIVE && seg in o.segments && seg !in o.staleSegments }
        }
        if (covered) 0 else r.heldCount
    }

    init {
        // starý soubor s polohami z minula už se nepoužívá: po startu se nikdy nezobrazují staré polohy
        cacheFile?.let { runCatching { it.delete() } }
        // Okamžitě při startu spustit refresh na pozadí; při neúspěchu zopakovat s krátkým odstupem
        scope.launch(Dispatchers.IO) {
            var attempts = 0
            while (!everSucceeded && attempts < STARTUP_MAX_ATTEMPTS) {
                attempts++
                runCatching { refresh() }
                if (everSucceeded) break
                if (attempts == STARTUP_GIVE_UP_STALE_AFTER && !initialFetchDone) {
                    // po pár neúspěších označit polohy z cache jako zastaralé, ale dál zkoušet
                    initialFetchDone = true
                    snapshot?.let { snap ->
                        withContext(Dispatchers.Default) { rebuild(snap, System.currentTimeMillis()) }
                    }
                }
                // víc než MIN_FETCH_INTERVAL_MS, jinak by se další pokus zahodil jako "příliš brzy"
                delay((MIN_FETCH_INTERVAL_MS + 1_000L * attempts).coerceAtMost(8_000L))
            }
            initialFetchDone = true
        }
    }

    private companion object {
        const val TAG = "VehicleHub"
        const val MIN_FETCH_INTERVAL_MS = 2_000L
        const val STARTUP_MAX_ATTEMPTS = 15
        const val STARTUP_GIVE_UP_STALE_AFTER = 3
        const val SERVER_STALE_MS = 20_000L
        /** Jak dlouho se po výpadku serveru drží poslední polohy v paměti (krátký škytnutí sítě); pak se mapa vyprázdní. */
        const val KEEP_MS = 30_000L

        /** Zdroje, které server zná (pořadí = pořadí v nastavení). */
        val SOURCE_NAMES = linkedMapOf(
            SourceIds.DPMUL to "DPMÚL",
            SourceIds.DUK to "DÚK",
            SourceIds.TRAIN to "Správa Železnic",
        )

        val SOURCE_SEGMENTS = mapOf(
            SourceIds.DPMUL to setOf(Segment.CITY),
            SourceIds.DUK to setOf(Segment.CITY, Segment.REGIONAL),
            SourceIds.TRAIN to setOf(Segment.REGIONAL),
        )
    }
}