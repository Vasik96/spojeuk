package com.vasik96.spojeuk

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Identifikátory zdrojů poloh tak, jak je posílá server (pole `source` / `sources`). */
object SourceIds {
    const val DPMUL = "dpmul"
    const val DUK = "duk"
    const val TRAIN = "train" // Správa železnic
}

/** Jeden zdroj ve stavu, jak ho hlásí server v `/api/vehicles` → `sources`. */
data class ApiSource(
    val id: String,
    val name: String,
    val priority: Int,
    val health: Health,
    /** Kolik vozů zdroj dodal před slučováním. */
    val vehicles: Int,
)

/** Odpověď `/api/vehicles`: už sloučené vozy (server vybral nejčerstvější polohu) a stav zdrojů. */
data class ApiSnapshot(
    val vehicles: List<Vehicle>,
    val sources: List<ApiSource>,
    /** Kolik poloh DÚK je jen držených z minula. */
    val held: Int,
    /** Server hlásí výpadek hlavního feedu (DÚK). */
    val outage: Boolean,
    /** Kdy odpověď dorazila do appky; stáří poloh se počítá od tohoto okamžiku. */
    val receivedMs: Long,
)

/**
 * Klient vlastního serveru (API v2). Server sám dotahuje DPMÚL, DÚK i Správu železnic a vrací jedno sloučené pole
 * vozidel, appka už nepouští dotazy na třetí strany.
 */
class VehicleApi(private val url: String = API_VEHICLES_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Surové tělo poslední úspěšné odpovědi (hub ho ukládá na disk, ať polohy přežijí restart appky). */
    @Volatile
    var lastBody: String = ""
        private set

    /** Blokující dotaz (volat z IO). Vyhodí výjimku při chybě sítě / HTTP / JSON. */
    fun fetch(): ApiSnapshot {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val snap = parse(body, System.currentTimeMillis())
            lastBody = body
            snap
        }
    }

    companion object {
        fun parse(body: String, nowMs: Long): ApiSnapshot {
            val root = JSONObject(body)
            val arr = root.optJSONArray("vehicles") ?: JSONArray()
            val vehicles = ArrayList<Vehicle>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                toVehicle(o, nowMs)?.let { vehicles.add(it) }
            }
            val srcArr = root.optJSONArray("sources") ?: JSONArray()
            val sources = ArrayList<ApiSource>(srcArr.length())
            for (i in 0 until srcArr.length()) {
                val o = srcArr.optJSONObject(i) ?: continue
                sources.add(
                    ApiSource(
                        id = o.optString("id"),
                        name = o.optStringClean("name", o.optString("id")),
                        priority = o.optInt("priority", 0),
                        health = when (o.optString("health")) {
                            "live" -> Health.LIVE
                            "stale" -> Health.STALE
                            else -> Health.DOWN
                        },
                        vehicles = o.optInt("vehicles", 0),
                    )
                )
            }
            return ApiSnapshot(
                vehicles = vehicles,
                sources = sources,
                held = root.optInt("held", 0),
                outage = root.optBoolean("outage", false),
                receivedMs = nowMs,
            )
        }

        private fun optBool(o: JSONObject, key: String): Boolean? =
            if (o.isNull(key)) null else o.optBoolean(key)

        private fun toVehicle(o: JSONObject, nowMs: Long): Vehicle? {
            val lat = o.optDouble("lat", 0.0)
            val lng = o.optDouble("lng", 0.0)
            if (lat == 0.0 || lng == 0.0) return null
            val ageSec = o.optLong("ageSec", 0L).coerceAtLeast(0L)
            val sources = o.optJSONArray("sources")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
            val ids = o.optJSONArray("ids")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
            return Vehicle(
                source = o.optString("source"),
                lat = lat,
                lng = lng,
                line = o.optStringClean("line", "?"),
                delay = o.optInt("delay", 0),
                // stáří poloh dodává server; počítá se od okamžiku, kdy odpověď dorazila
                observedAtMs = nowMs - ageSec * 1000L,
                id = o.optInt("id", 0),
                trip = o.optStringClean("trip"),
                cis = o.optStringClean("cis"),
                destination = prettyName(o.optStringClean("dest")),
                agency = o.optStringClean("agency"),
                segment = if (o.optString("segment") == "city") Segment.CITY else Segment.REGIONAL,
                state = o.optInt("state", 0),
                train = o.optBoolean("train", false),
                accessible = optBool(o, "low"),
                airConditioned = optBool(o, "ac"),
                heading = if (o.isNull("heading")) null else o.optDouble("heading"),
                station = o.optInt("station", 0),
                finalStation = o.optInt("finalStation", 0),
                sources = sources,
                ids = ids,
                serverUid = o.optString("uid"),
            )
        }
    }
}