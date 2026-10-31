package com.vasik96.spojeuk

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

const val TRAFFIC_URL = "https://tabule.portabo.cz/api/v1-tabule/cis/GetTraffic/0"

/** Vlastní server (neoficiální), drží starší spoje, aby MHD nemizelo. Zkouší se jako první. */
const val FEED_URL = "http://37.46.211.70:8787/traffic"

/** Od kolika "držených" poloh se feed považuje za výpadek. */
const val OUTAGE_MIN = 18

/**
 * DÚK / Portabo: regionální doprava i MHD, přesné číslo spoje (CISLineID + RouteID).
 * Vozy, které server přestane posílat, drží z minula (MHD 20 min, ostatní 10 min), aby nemizely z mapy.
 */
class PortaboSource(
    private val snapshotFile: File? = null,
    private val urls: List<String> = listOf(FEED_URL, TRAFFIC_URL),
) : VehicleSource {

    override val id = ID
    override val name = "DÚK"
    override val priority = 1
    override val segments = setOf(Segment.CITY, Segment.REGIONAL)
    override val freshness = Freshness(
        maxFetchAgeMs = 15_000,
        watchedSegments = setOf(Segment.CITY, Segment.REGIONAL),
    )
    override val tripMatcher = TripMatchers.byCisNumber
    override val adoptsTimetableLine = true

    @Volatile private var held = 0
    override val heldCount: Int get() = held
    override val inOutage: Boolean get() = held > OUTAGE_MIN

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Poslední známá poloha každého vozu (včetně držených). */
    private val lastSeen = HashMap<String, Vehicle>()

    init {
        loadSnapshot()
    }

    override suspend fun fetch(): List<Vehicle> = withContext(Dispatchers.IO) { refreshBlocking() }

    // ───────── dotaz + držení vozů ─────────

    @Synchronized
    private fun refreshBlocking(): List<Vehicle> {
        val fresh = parse(JSONObject(fetchBody()))
        val nowMs = System.currentTimeMillis()
        val freshKeys = fresh.mapTo(HashSet()) { keyOf(it) }
        for (v in fresh) lastSeen[keyOf(v)] = v

        val missing = lastSeen.keys.filter { it !in freshKeys }
        val outage = missing.size >= OUTAGE_MIN
        if (!outage) missing.forEach { lastSeen.remove(it) }   // vozy, které server přestal posílat, drží se jen při výpadku

        lastSeen.values.removeAll { v ->
            val age = nowMs - v.observedAtMs
            age > holdMs(v) || (v.state !in 0..1 && age > INACTIVE_DROP_MS)
        }
        held = fresh.count { it.ageSec(nowMs) > 30 } +
            (if (outage) lastSeen.keys.count { it !in freshKeys } else 0)

        saveSnapshot()
        return lastSeen.values.toList()
    }

    private fun holdMs(v: Vehicle) = if (v.id in MHD_IDS) HOLD_MHD_MS else HOLD_OTHER_MS

    private fun keyOf(v: Vehicle) = if (v.id != 0) "id${v.id}" else "${v.cis}:${v.line}:${v.trip}"

    private fun fetchBody(): String {
        var last: Exception? = null
        for (url in urls) {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code} ($url)")
                    return resp.body?.string() ?: "{}"
                }
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "zdroj $url selhal: ${e.message}")
            }
        }
        throw last ?: IllegalStateException("Žádná URL")
    }

    // ───────── parsování ─────────

    private fun parse(data: JSONObject): List<Vehicle> {
        val arr = data.optJSONArray("VehicleList") ?: return emptyList()
        val out = ArrayList<Vehicle>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val lat = e.optDouble("Latitude", 0.0)
            val lon = e.optDouble("Longitude", 0.0)
            val state = e.optInt("State", 255)
            val id = e.optInt("ID", 0)
            val serverAgeSec = e.optInt("_ageSec", 0)
            if (state == 255 || lat == 0.0 || lon == 0.0) continue
            if (serverAgeSec == 0 && isTooOld(e, id)) continue

            val agency = e.optStringClean("agency", e.optStringClean("agency_name", ""))
            val destination = e.optStringClean("headsign", e.optStringClean("destination", e.optStringClean("last_stop", "")))
            out.add(
                Vehicle(
                    source = ID,
                    lat = lat,
                    lng = lon,
                    line = when (
                        e.optStringClean("qride_linename", e.optStringClean("LineID", "?")).trim()
                    ) {
                        "200" -> "X"
                        else -> e.optStringClean("qride_linename", e.optStringClean("LineID", "?")).trim()
                    },
                    delay = e.optInt("Delay", 0),
                    // server může vůz držet; skutečné stáří poskytuje _ageSec
                    observedAtMs = System.currentTimeMillis() - serverAgeSec * 1000L,
                    id = id,
                    trip = e.optStringClean("RouteID", ""),
                    cis = String.format(Locale.ROOT, "%06d", e.optInt("CISLineID", 0)),
                    destination = prettyName(destination),
                    agency = agency,
                    segment = segmentOf(id, agency),
                    state = state,
                    accessible = optBool(e, "HasLowfloor"),
                    airConditioned = optBool(e, "isAirConditioned"),
                )
            )
        }
        return out
    }

    private fun optBool(e: JSONObject, key: String): Boolean? =
        if (e.has(key) && !e.isNull(key)) e.optBoolean(key) else null

    private fun isTooOld(e: JSONObject, id: Int): Boolean {
        val at = runCatching { OffsetDateTime.parse(e.optStringClean("LastActivityDT", "")) }.getOrNull() ?: return false
        val limitMin = if (id in MHD_IDS) 45L else 10L
        return Duration.between(at.toInstant(), Instant.now()).abs().toMinutes() > limitMin
    }

    private fun segmentOf(id: Int, agency: String): Segment {
        if (id in MHD_IDS) return Segment.CITY
        val a = foldText(agency)
        return if (DPMUL_AGENCY_KEYWORDS.any { it in a }) Segment.CITY else Segment.REGIONAL
    }

    // ───────── snapshot na disku (přežije restart appky) ─────────

    private fun loadSnapshot() {
        val f = snapshotFile ?: return
        runCatching {
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val nowMs = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ts = o.getLong("ts")
                if (nowMs - ts > 20 * 60_000L) continue
                val id = o.getInt("id")
                val agency = o.optString("agency")
                val v = Vehicle(
                    source = ID,
                    lat = o.getDouble("lat"),
                    lng = o.getDouble("lng"),
                    line = o.getString("line"),
                    delay = o.getInt("delay"),
                    observedAtMs = ts,
                    id = id,
                    trip = o.getString("trip"),
                    cis = o.getString("cis"),
                    agency = agency,
                    segment = segmentOf(id, agency),
                    state = o.getInt("state"),
                    accessible = if (o.has("low")) o.getBoolean("low") else null,
                    airConditioned = if (o.has("ac")) o.getBoolean("ac") else null,
                )
                lastSeen[keyOf(v)] = v
            }
        }
    }

    private fun saveSnapshot() {
        val f = snapshotFile ?: return
        runCatching {
            val arr = JSONArray()
            for (v in lastSeen.values) {
                arr.put(JSONObject().apply {
                    put("lat", v.lat); put("lng", v.lng); put("line", v.line)
                    put("trip", v.trip); put("delay", v.delay); put("agency", v.agency)
                    put("state", v.state); put("cis", v.cis); put("id", v.id); put("ts", v.observedAtMs)
                    put("low", v.accessible); put("ac", v.airConditioned)
                })
            }
            f.writeText(arr.toString())
        }
    }

    companion object {
        const val ID = "duk"
        private const val TAG = "PortaboSource"
        private val MHD_IDS = 300000..309999
        private const val HOLD_MHD_MS = 20 * 60_000L
        private const val HOLD_OTHER_MS = 10 * 60_000L
        private const val INACTIVE_DROP_MS = 30_000L
    }
}
