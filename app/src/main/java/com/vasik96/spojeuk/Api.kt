package com.vasik96.spojeuk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap


data class Stop(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val place: String = name,
    val inreg: Boolean = true
)

data class Leg(
    val kind: String, // "ride" | "walk"
    val fromStop: Int,
    val toStop: Int,
    var dep: Int,
    var arr: Int,
    val tid: Int = 0,
    val stops: List<Triple<Int, Int, Int>> = emptyList(), // stopId, arr, dep
    val t0: Int = 0,
    val t1: Int = 0
)

data class Journey(
    val departure: String,
    val arrival: String,
    val line: String,
    val line2: String? = null,
    val destination: String,
    val agency: String,
    var delay: Int = 0,
    var realtime: Boolean = false,
    var lowFloor: Boolean = false,
    val stopsCount: Int? = null,
    val transferAt: String? = null,
    val transferWait: Int? = null,
    val trip: String,
    val trip2: String? = null,
    val sortKey: Int = 0,
    val day: LocalDate = todayPrague(),
    val rawLegs: List<Leg> = emptyList(),
    val transfers: Int = if (line2 != null) 1 else 0,
    val cis: String = "",
) {
    val dep: Int get() = sortKey
    val arr: Int get() = sortKey + durationMinutes * 60
    val isTransfer get() = line2 != null
    val key get() = "$sortKey|$departure|$arrival|$line|$trip|$line2|$trip2|$transferAt|$day"
    val durationMinutes: Int
        get() {
            var duration = tm(arrival) - tm(departure)
            if (duration < 0) duration += 1440
            return duration
        }
    val arrivalKey get() = sortKey + durationMinutes
    val depDt: LocalDateTime get() = day.atStartOfDay().plusSeconds(sortKey.toLong())
}

data class VehicleTripInfo(
    val finalStop: String?,
    val nextStop: String?)


data class Vehicle(
    val lat: Double,
    val lng: Double,
    val line: String,
    val trip: String,
    val delay: Int,
    val train: Boolean,
    val destination: String = "",
    val agency: String = "",
    val vehicleId: String = "",
    val lastStop: String = "",
    val gpsTime: String = "",
    val accessible: Boolean? = null,
    val state: Int = 0,
    val cis: String = "",
    val id: Int = 0,
    val airConditioned: Boolean? = null,
    val ageSec: Int = 0,
) {
    val vehicleKey: String? get() = if (cis != "000000" && cis.isNotBlank()) "$cis:$trip" else null
}

fun tm(value: String): Int {
    val p = value.take(5).split(":")
    return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
}

fun normalize(text: String): String = foldText(text)

class DukApi(val context: Context? = null) {
    private val http = OkHttpClient()
    private val dbFile: File by lazy {
        val base = context?.filesDir ?: File("/tmp")
        val dir = File(base, "duk-direct")
        dir.mkdirs()
        File(dir, "static.sqlite")
    }
    private val osrmClient by lazy {
        val base = context?.cacheDir ?: File("/tmp")
        OsrmClient(base)
    }
    private val realtimeClient by lazy {
        RealtimeClient(snapshotFile = File(context?.filesDir ?: File("/tmp"), "last_seen.json"))
    }

    private val lineCache = ConcurrentHashMap<String, String>()

    fun heldCount(): Int = realtimeClient.heldCount
    fun vehicleAgeSec(v: Vehicle): Long = realtimeClient.ageSec(v)

    @Volatile
    private var timetable: Timetable? = null

    @Volatile
    private var planner: Planner? = null


    suspend fun ensureTimetable(log: (String) -> Unit = {}, progress: (Float) -> Unit = {}): Timetable = withContext(Dispatchers.IO) {
        timetable?.let { return@withContext it }

        if (!dbFile.exists() || dbFile.length() < 100_000L) {
            log("Nahrávám výchozí jízdní řády…")
            val copied = runCatching {
                context?.assets?.open("static.sqlite")?.use { input ->
                    dbFile.parentFile?.mkdirs()
                    FileOutputStream(dbFile).use { output ->
                        val buffer = ByteArray(65536)
                        var read = input.read(buffer)
                        while (read > 0) {
                            output.write(buffer, 0, read)
                            read = input.read(buffer)
                        }
                    }
                }
                true
            }.getOrDefault(false)

            if (!copied || !dbFile.exists() || dbFile.length() < 100_000L) {
                updateFromGitHub(log, progress)
            }
        }

        val tt = Timetable(dbFile)
        timetable = tt
        planner = Planner(tt)
        tt
    }

    suspend fun updateFromGitHub(log: (String) -> Unit = {}, progress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        log("Zjišťuji nejnovější build z GitHubu…")
        val req = Request.Builder().url(RELEASES_API).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub API HTTP ${resp.code}")
            val body = resp.body?.string() ?: "{}"
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "latest")
            val assets = json.optJSONArray("assets") ?: JSONArray()
            var downloadUrl: String? = null
            var assetSize = 0L

            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == ASSET_NAME) {
                    downloadUrl = a.optString("browser_download_url")
                    assetSize = a.optLong("size", 0)
                    break
                }
            }

            if (downloadUrl == null) error("Release $tagName neobsahuje asset $ASSET_NAME")

            log("Stahuji $ASSET_NAME (${assetSize / 1048576} MB)…")
            val dlReq = Request.Builder().url(downloadUrl).header("User-Agent", USER_AGENT).build()
            http.newCall(dlReq).execute().use { dlResp ->
                if (!dlResp.isSuccessful) error("Download error HTTP ${dlResp.code}")
                val inputStream = dlResp.body?.byteStream() ?: error("Empty response body")
                log("Rozbaluji GTFS…")
                val gtfsZipFile = File(dbFile.parentFile, "gtfs.zip")
                val ok = TarUtils.streamGtfsZipToFile(inputStream, gtfsZipFile)
                if (!ok || !gtfsZipFile.exists()) error("V souboru $ASSET_NAME nebylo nalezeno gtfs.zip")
                try {
                    val importer = GtfsImporter(dbFile)
                    importer.importGtfs(gtfsZipFile, tagName, log, progress)
                } finally {
                    gtfsZipFile.delete()
                }
            }
        }
        val tt = Timetable(dbFile)
        timetable = tt
        planner = Planner(tt)
    }

    suspend fun loadStops(): List<Stop> = withContext(Dispatchers.IO) {
        val tt = ensureTimetable()
        tt.stops.values.distinctBy { it.name }
    }

    fun newSearch(from: List<Stop>, to: List<Stop>, fromName: String, toName: String): JourneySearch {
        return JourneySearch(this, fromName, toName)
    }

    suspend fun searchConnections(
        fromName: String,
        toName: String,
        startTime: LocalDateTime = nowPrague(),
        count: Int = 8
    ): List<Journey> = withContext(Dispatchers.IO) {
        val tt = ensureTimetable()
        val pl = planner ?: Planner(tt).also { planner = it }
        val js = pl.find(fromName, toName, startTime, count)
        runCatching { realtimeClient.refresh(10_000) }

        js.map { j ->
            j.rawLegs.forEach { leg ->
                if (leg.kind == "ride") {
                    val v = realtimeClient.live(tt, leg, j.day)
                    if (v != null) {
                        j.delay = v.delay
                        j.realtime = true
                    }
                }
            }
            j
        }
    }

    suspend fun searchMoreLater(fromName: String, toName: String, current: List<Journey>): List<Journey> = withContext(Dispatchers.IO) {
        val tt = ensureTimetable()
        val pl = planner ?: Planner(tt).also { planner = it }
        val js = pl.moreLater(fromName, toName, current)
        runCatching { realtimeClient.refresh(10_000) }
        js.forEach { j ->
            j.rawLegs.forEach { leg ->
                if (leg.kind == "ride") {
                    val v = realtimeClient.live(tt, leg, j.day)
                    if (v != null) {
                        j.delay = v.delay
                        j.realtime = true
                    }
                }
            }
        }
        js
    }

    suspend fun searchMoreEarlier(
        fromName: String, toName: String, current: List<Journey>, hours: Int = 2
    ): List<Journey> = withContext(Dispatchers.IO) {
        val tt = ensureTimetable()
        val pl = planner ?: Planner(tt).also { planner = it }
        val js = pl.moreEarlier(fromName, toName, current, hours = hours)
        runCatching { realtimeClient.refresh(10_000) }
        js.forEach { j ->
            j.rawLegs.forEach { leg ->
                if (leg.kind == "ride") {
                    val v = realtimeClient.live(tt, leg, j.day)
                    if (v != null) {
                        j.delay = v.delay
                        j.realtime = true
                    }
                }
            }
        }
        js
    }

    suspend fun vehiclePositions(): List<Vehicle> = withContext(Dispatchers.IO) {
        val list = realtimeClient.refresh(0)
        val tt = timetable ?: return@withContext list
        list.map { v ->
            val key = "${v.cis}:${v.trip}:${v.line}"
            val cached = lineCache[key]
            val line = cached ?: run {
                val tid = tt.findTripId(v.cis, v.trip)
                val resolved = tid?.let { tt.trips[it]?.line }
                    ?.takeIf { it.isNotBlank() && it != "?" }
                if (resolved != null) lineCache[key] = resolved   // nepropisovat nenalezené
                resolved ?: v.line
            }
            if (line == v.line) v else v.copy(line = line)
        }
    }

    suspend fun tripGeometry(v: Vehicle): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val tt = ensureTimetable()
        val tid = tt.findTripId(v.cis, v.trip)
            ?: tt.findTripByLine(v.line, v.lat, v.lng)
            ?: return@withContext emptyList()
        val coords = tt.tripStops(tid).toList().mapNotNull { sid ->
            tt.stops[sid]?.takeIf { it.lat != 0.0 || it.lng != 0.0 }?.let { it.lat to it.lng }
        }
        osrmClient.pathFor(coords)
    }

    // DukApi
    suspend fun journeyGeometry(j: Journey): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val tt = ensureTimetable()
        fun xy(sid: Int) = tt.stops[sid]?.takeIf { it.lat != 0.0 || it.lng != 0.0 }?.let { it.lat to it.lng }
        j.rawLegs.flatMap { leg ->
            if (leg.kind == "ride") osrmClient.pathFor(leg.stops.mapNotNull { xy(it.first) })
            else listOfNotNull(xy(leg.fromStop), xy(leg.toStop))
        }
    }

    suspend fun vehicleTripInfo(v: Vehicle): VehicleTripInfo = withContext(Dispatchers.IO) {
        val none = VehicleTripInfo(null, null)
        val tt = ensureTimetable()
        val tid = tt.findTripId(v.cis, v.trip) ?: return@withContext none
        val t = tt.trips[tid] ?: return@withContext none
        val (stopIds, arr, _) = tt.tripTimes(tid) ?: return@withContext none
        if (stopIds.isEmpty()) return@withContext none

        fun name(i: Int) = tt.stops[stopIds[i]]?.name?.let { prettyName(it) }

        val finalName = name(stopIds.lastIndex)?.takeIf { it.isNotBlank() }
            ?: t.head.takeIf { it.isNotBlank() }?.let { prettyName(it) }

        // plánovaný čas "teď" = skutečný čas minus zpoždění
        var rel = nowPrague().toLocalTime().toSecondOfDay() - v.delay * 60
        if (rel < t.t0 - 3600 && rel + 86400 <= t.t1 + 3600) rel += 86400

        val idx = stopIds.indices.firstOrNull { arr[it] > rel }
        VehicleTripInfo(finalName, idx?.let { name(it) })
    }


    /** Kandidáti podle vzdušné čáry (jeden stojan na název), rychlé. */
    suspend fun nearestCandidates(lat: Double, lng: Double, limit: Int = 8): List<Pair<Stop, Double>> =
        withContext(Dispatchers.IO) {
            val tt = ensureTimetable()
            val cosLat = Math.cos(Math.toRadians(lat))
            val bestByName = HashMap<String, Pair<Stop, Double>>()
            for (s in tt.stops.values) {
                if (s.lat == 0.0 && s.lng == 0.0) continue
                val dy = (s.lat - lat) * 110540.0
                val dx = (s.lng - lng) * 111320.0 * cosLat
                val d = Math.hypot(dx, dy)
                val cur = bestByName[s.name]
                if (cur == null || d < cur.second) bestByName[s.name] = s to d
            }
            bestByName.values.sortedBy { it.second }.take(limit)
        }

    /** Upřesnění podle cesty po silnici (jedno volání OSRM). */
    suspend fun refineByRoad(
        lat: Double, lng: Double, cands: List<Pair<Stop, Double>>
    ): Pair<Stop, Double>? = withContext(Dispatchers.IO) {
        val road = osrmClient.roadDistances(lat to lng, cands.map { it.first.lat to it.first.lng })
            ?: return@withContext null
        val i = cands.indices.filter { road[it] != null }.minByOrNull { road[it]!! }
            ?: return@withContext null
        cands[i].first to road[i]!!
    }


}

class JourneySearch(
    private val api: DukApi,
    private val fromName: String,
    private val toName: String
) {
    private val currentJourneys = mutableListOf<Journey>()
    private val mutex = Mutex()
    private val startTime: LocalDateTime = nowPrague()
    // konec hledání dopředu: 04:00 následujícího dne
    private val cutoff: LocalDateTime =
        startTime.toLocalDate().plusDays(1).atStartOfDay().plusHours(4)

    var hasMore: Boolean = true
        private set
    var hasEarlier: Boolean = true
        private set

    fun fresh(): JourneySearch = JourneySearch(api, fromName, toName)

    suspend fun loadMore(minNew: Int = 1, maxWindows: Int = 6): List<Journey> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (currentJourneys.isEmpty()) {
                    val initial = api.searchConnections(fromName, toName, startTime, minNew)
                    currentJourneys.addAll(initial)
                    if (initial.isEmpty()) hasMore = false
                } else {
                    val more = api.searchMoreLater(fromName, toName, currentJourneys.toList())
                        .filter { it.depDt < cutoff }
                    if (more.size <= currentJourneys.size) {
                        hasMore = false
                    } else {
                        currentJourneys.clear()
                        currentJourneys.addAll(more)
                    }
                }
                currentJourneys.toList()   // KOPIE, ať Compose vidí změnu
            }
        }

    suspend fun loadEarlier(): List<Journey> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (currentJourneys.isNotEmpty()) {
                var grew = false
                for (h in intArrayOf(2, 6, 12)) {
                    val more = api.searchMoreEarlier(fromName, toName, currentJourneys.toList(), h)
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