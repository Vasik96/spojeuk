package com.vasik96.spojeuk

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

/** Klíč zachycený v provozu oficiální aplikace DPMÚL (veřejné API). Když se změní, stačí ho tu přepsat. */
const val DPMUL_API_KEY = "924486f2-3dd8-464f-9dd7-1f25aa43f0e9"
const val DPMUL_AGENCY = "Dopravní podnik města Ústí nad Labem"

/**
 * Oficiální API aplikace DPMÚL (Globdata): jen MHD Ústí, nejčerstvější polohy, ale bez čísla spoje,
 * takže vozy se ke spojům přiřazují heuristicky. Odpověď trvá i desítky sekund, proto běží na pozadí.
 */
class DpmulSource(
    private val url: String = DPMUL_URL,
    private val apiKey: String = DPMUL_API_KEY,
) : VehicleSource {

    override val id = ID
    override val name = "DPMÚL"
    override val priority = 0
    override val segments = setOf(Segment.CITY)
    override val freshness = Freshness(maxFetchAgeMs = 60_000, maxMedianAgeSec = 180)
    override val tripMatcher = TripMatchers.byPosition(DPMUL_AGENCY_KEYWORDS)
    override val awaitResult = false
    override val minIntervalMs = 5_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(): List<Vehicle> = withContext(Dispatchers.IO) {
        // API očekává POST s prázdným tělem, ne GET
        val request = Request.Builder().url(url)
            .post(ByteArray(0).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "Dart/3.12 (dart:io)")
            .header("Accept", "application/json")
            .header("x-app-env", "prod")
            .header("x-app-version", "2.0.14")
            .header("dpmxbe-api-key", apiKey)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("DPMÚL HTTP ${resp.code}")
            DpmulParser.parse(resp.body?.string().orEmpty(), System.currentTimeMillis())
        }
    }

    companion object {
        const val ID = "dpmul"
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
