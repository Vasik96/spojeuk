package com.vasik96.spojeuk

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

const val DPMUL_URL = "https://dpmxmobappbe.globdata.cz/api/v1/public/Common/skeleton/actual-vehicles"

const val MAIN_URL_DPMUL = SERVER_IP + "/dpmul/traffic"

/** Klíč zachycený v provozu oficiální aplikace DPMÚL (veřejné API). Když se změní, stačí ho tu přepsat. */
const val DPMUL_API_KEY = "924486f2-3dd8-464f-9dd7-1f25aa43f0e9"
const val DPMUL_AGENCY = "Dopravní podnik města Ústí nad Labem"

/**
 * Oficiální API aplikace DPMÚL (Globdata): jen MHD Ústí, nejčerstvější polohy, ale bez čísla spoje,
 * takže vozy se ke spojům přiřazují heuristicky. Přímá odpověď trvá několik sekund (a kolísá),
 * proto se čte z vlastního serveru ([MAIN_URL_DPMUL]), který ji dotahuje na pozadí a vrací hotovou.
 * Když server nejde nebo hlásí, že nemá čerstvá data, appka se zeptá přímo (pomalejší cesta).
 */
class DpmulSource(
    private val serverUrl: String = MAIN_URL_DPMUL,
    private val directUrl: String = DPMUL_URL,
    private val apiKey: String = DPMUL_API_KEY,
) : VehicleSource {

    override val id = ID
    override val name = "DPMÚL"
    override val priority = 0
    override val segments = setOf(Segment.CITY)
    override val freshness = Freshness(maxFetchAgeMs = 60_000, maxMedianAgeSec = 180, staleVehicleSec = 90)
    override val tripMatcher = TripMatchers.byPosition(DPMUL_AGENCY_KEYWORDS)
    override val awaitResult = false
    override val minIntervalMs = 3_000L

    /** Vlastní server: odpovídá hned, takže krátké timeouty. */
    private val serverClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Přímé API DPMÚL: pomalé a kolísavé, proto dlouhé timeouty. */
    private val directClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(): List<Vehicle> = withContext(Dispatchers.IO) {
        val fromServer = runCatching { fetchFromServer() }
            .onFailure { Log.w(TAG, "vlastní server DPMÚL selhal, jdu přímo: ${it.message}") }
            .getOrNull()
        fromServer ?: fetchDirect()
    }

    /** null = server nemá použitelná čerstvá data (chyba, ještě nic nestáhl nebo je jeho odpověď moc stará). */
    private fun fetchFromServer(): List<Vehicle>? {
        val request = Request.Builder().url(serverUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return serverClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            // X-Age-Sec: stáří odpovědi na serveru; -1 = ještě žádná, server po 120 s starou odpověď nevrací (posílá [])
            val age = resp.header("X-Age-Sec")?.toLongOrNull()
            val vehicles = DpmulParser.parse(body, System.currentTimeMillis())
            if (vehicles.isEmpty() && (age == null || age < 0 || age > SERVER_MAX_AGE_SEC)) null else vehicles
        }
    }

    private fun fetchDirect(): List<Vehicle> {
        // API očekává POST s prázdným tělem, ne GET
        val request = Request.Builder().url(directUrl)
            .post(ByteArray(0).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "Dart/3.12 (dart:io)")
            .header("Accept", "application/json")
            .header("x-app-env", "prod")
            .header("x-app-version", "2.0.14")
            .header("dpmxbe-api-key", apiKey)
            .build()
        return directClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("DPMÚL HTTP ${resp.code}")
            DpmulParser.parse(resp.body?.string().orEmpty(), System.currentTimeMillis())
        }
    }

    companion object {
        const val ID = "dpmul"
        private const val TAG = "DpmulSource"
        private const val SERVER_MAX_AGE_SEC = 60L
    }
}

/**
 * Parser odpovědi DPMÚL. Pole jsou známá z reálné odpovědi:
 * vehicleNumber, lineName, delay ("HH:MM:SS" nebo null), latitude/longitude (mohou být null),
 * lastActivity (ISO s offsetem), isWheelchairAccessible, isAirConditioned.
 * Nepoužívá se azimuth (je vždy 0) ani routeNumber (je to oběh, ne číslo spoje).
 */
object DpmulParser {
    private val DELAY_RE = Regex("(-?)(\\d{1,2}):(\\d{2}):(\\d{2})")

    fun parse(body: String, nowMs: Long): List<Vehicle> {
        val arr = JSONArray(body)
        val out = ArrayList<Vehicle>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            toVehicle(o, nowMs)?.let { out.add(it) }
        }
        return out
    }

    private fun toVehicle(o: JSONObject, nowMs: Long): Vehicle? {
        if (o.isNull("latitude") || o.isNull("longitude")) return null
        val lat = o.optDouble("latitude")
        val lon = o.optDouble("longitude")
        if (lat !in 48.0..52.0 || lon !in 12.0..19.0) return null

        val observed = parseInstant(o.optStringClean("lastActivity")) ?: parseLocal(o.optStringClean("gpsTimestamp"))
        if (observed != null && nowMs - observed.toEpochMilli() > 10 * 60_000L) return null

        val station = o.optInt("stationNumber", 0)
        val final = o.optInt("finalStationNumber", 0)
        val atStop = o.optBoolean("arrivedAt") || o.optBoolean("stoppedAt")

        val heading = if (atStop) {
            StationBook.learn(station, lat, lon)
            null
        } else {
            val st = StationBook.get(station)
            if (st != null && Geo.distanceM(lat, lon, st.first, st.second) > 30.0) {
                when {
                    o.optBoolean("inTransitTo") || o.optBoolean("incomingAt") ->
                        bearing(lat, lon, st.first, st.second)      // stanice je před vozem
                    o.optBoolean("leftAt") ->
                        bearing(st.first, st.second, lat, lon)      // stanice je za vozem
                    else -> null
                }
            } else null
        }

        return Vehicle(
            source = DpmulSource.ID,
            lat = lat,
            lng = lon,
            line = when (o.optStringClean("lineName", "?").trim()) {
                "200" -> "X"
                else -> o.optStringClean("lineName", "?")
            },
            delay = parseDelayMinutes(o.optStringClean("delay")),
            observedAtMs = observed?.toEpochMilli() ?: nowMs,
            id = o.optInt("vehicleNumber", 0),
            agency = DPMUL_AGENCY,
            segment = Segment.CITY,
            hasTimestamp = observed != null,
            accessible = optBool(o, "isWheelchairAccessible"),
            airConditioned = optBool(o, "isAirConditioned"),
            heading = heading,
            station = station,
            finalStation = final,
        )
    }

    private fun optBool(o: JSONObject, key: String): Boolean? =
        if (o.has(key) && !o.isNull(key)) o.optBoolean(key) else null

    /** "00:05:00" → 5, "-00:01:30" → -2 (zaokrouhleno), číslo bez dvojteček se bere jako minuty. */
    private fun parseDelayMinutes(s: String): Int {
        if (s.isBlank()) return 0
        val m = DELAY_RE.matchEntire(s.trim())
        if (m != null) {
            val (sign, h, min, sec) = m.destructured
            val total = h.toInt() * 3600 + min.toInt() * 60 + sec.toInt()
            return Math.round((if (sign == "-") -total else total) / 60.0).toInt()
        }
        return Math.round(s.replace(',', '.').toDoubleOrNull() ?: 0.0).toInt()
    }

    private fun parseInstant(s: String): Instant? =
        if (s.isBlank()) null else runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

    /** gpsTimestamp je místní čas bez zóny. */
    private fun parseLocal(s: String): Instant? =
        if (s.isBlank()) null else runCatching { LocalDateTime.parse(s).atZone(PRAGUE_ZONE).toInstant() }.getOrNull()
}

/** Naučené polohy stanic DPMÚL (stationNumber → poloha), plní se z vozů, které na zastávce stojí. */
object StationBook {
    private val pos = java.util.concurrent.ConcurrentHashMap<Int, Pair<Double, Double>>()
    fun learn(station: Int, lat: Double, lng: Double) { if (station != 0) pos[station] = lat to lng }
    fun get(station: Int): Pair<Double, Double>? = pos[station]
}

private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val f1 = Math.toRadians(lat1); val f2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lng2 - lng1)
    val y = Math.sin(dl) * Math.cos(f2)
    val x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl)
    return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
}