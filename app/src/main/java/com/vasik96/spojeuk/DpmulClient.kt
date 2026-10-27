package com.vasik96.spojeuk

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val SRC_DUK = "DÚK"
const val SRC_DPMUL = "DPMÚL"
const val DPMUL_URL = "https://dpmxmobappbe.globdata.cz/api/v1/public/Common/skeleton/actual-vehicles"
const val DPMXBE_API_KEY = "924486f2-3dd8-464f-9dd7-1f25aa43f0e9"
const val DPMUL_AGENCY = "Dopravní podnik města Ústí nad Labem"
const val DPMUL_FRESH_MS = 60_000L   // odpověď API trvá i desítky sekund

class DpmulClient(
    private val url: String = DPMUL_URL,
    private val apiKey: String = DPMXBE_API_KEY
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()

    @Volatile var vehicles: List<Vehicle> = emptyList(); private set
    @Volatile var lastOkMs: Long = 0L; private set
    @Volatile var lastError: String? = null; private set

    private val running = AtomicBoolean(false)
    @Volatile private var lastStartMs = 0L
    private val lastPos = HashMap<String, Pair<Double, Double>>()
    private val heads = HashMap<String, Double>()

    val hasData: Boolean get() = lastOkMs > 0L

    fun ageSec(v: Vehicle): Long = v.ageSec + (System.currentTimeMillis() - lastOkMs) / 1000

    fun isFresh(): Boolean {
        if (lastOkMs == 0L || System.currentTimeMillis() - lastOkMs > DPMUL_FRESH_MS) return false
        val ages = vehicles.filter { it.gpsTime.isNotBlank() }.map { it.ageSec }.sorted()
        return ages.isEmpty() || ages[ages.size / 2] <= 180
    }

    /** Spustí dotaz na pozadí, pokud nějaký neběží. Nikdy nečeká na odpověď. */
    fun kick(scope: CoroutineScope, minIntervalMs: Long = 5_000) {
        val now = System.currentTimeMillis()
        if (now - lastStartMs < minIntervalMs) return
        if (!running.compareAndSet(false, true)) return
        lastStartMs = now
        scope.launch {
            try {
                val vs = fetch()
                vehicles = vs
                lastOkMs = System.currentTimeMillis()
                lastError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                Log.w("Dpmul", "dotaz selhal: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    private fun fetch(): List<Vehicle> {
        val req = Request.Builder().url(url)
            .post(ByteArray(0).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "Dart/3.12 (dart:io)")
            .header("Accept", "application/json")
            .header("x-app-env", "prod")
            .header("x-app-version", "2.0.14")
            .header("dpmxbe-api-key", apiKey)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("DPMÚL HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val parsed = DpmulParser.parse(JSONTokener(body).nextValue())
            // směr jízdy z posunu polohy (jako v Pythonu)
            return parsed.map { v ->
                val k = v.uid
                val p = lastPos[k]
                if (p == null) lastPos[k] = v.lat to v.lng
                else if (geoDistM(p.first, p.second, v.lat, v.lng) >= 15) {
                    heads[k] = bearingDeg(p.first, p.second, v.lat, v.lng)
                    lastPos[k] = v.lat to v.lng
                }
                v.copy(heading = heads[k])
            }
        }
    }
}

/** Heuristický parser: odpověď DPMÚL neznám, pole se hledají podle jmen (jako v verze3.py). */
object DpmulParser {
    /** Ruční přemapování, např. mapOf("lat" to "gpsLat", "line" to "lineLabel"). */
    @Volatile var overrides: Map<String, String> = emptyMap()

    private val ALIASES = mapOf(
        "id" to listOf("vehicleid", "vehicleno", "vehiclenumber", "busid", "carid", "id", "vehicle"),
        "lat" to listOf("latitude", "lat", "gpslat", "gpslatitude", "wgslat"),
        "lon" to listOf("longitude", "lon", "lng", "long", "gpslon", "gpslng", "gpslongitude", "wgslon"),
        "line" to listOf("linename", "linelabel", "linenumber", "lineno", "lineshortname", "routeshortname",
            "linenr", "line", "route"),
        "delay" to listOf("delayminutes", "delaymin", "delay", "zpozdeni", "delayseconds", "delaysec"),
        "trip" to listOf("tripnumber", "tripno", "tripid", "rideid", "journeyid", "vehiclejourneyid",
            "connectionid", "spoj"),
        "dest" to listOf("headsign", "destination", "destinationname", "finalstopname", "laststopname", "direction"),
        "time" to listOf("timestamp", "lastupdate", "updatedat", "gpstime", "datetime", "lastactivity", "time"),
        "op" to listOf("operator", "carrier", "agency", "agencyname", "dopravce", "companyname"),
        "wheel" to listOf("iswheelchairaccessible", "wheelchair", "lowfloor"),
        "ac" to listOf("isairconditioned", "airconditioned"),
    )
    private val DELAY_RE = Regex("-?\\d{1,2}:\\d{2}:\\d{2}")
    private var logged = false

    private class Flat(val depth: Int, val key: String, val value: Any)

    private fun normKey(k: String) = k.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private fun flatten(o: JSONObject, depth: Int = 0, out: MutableMap<String, Flat> = HashMap()): MutableMap<String, Flat> {
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = o.opt(k) ?: continue
            if (v === JSONObject.NULL) continue
            when (v) {
                is JSONObject -> if (depth < 2) flatten(v, depth + 1, out)
                is JSONArray -> {
                    val a = (v.opt(0) as? Number)?.toDouble()
                    val b = (v.opt(1) as? Number)?.toDouble()
                    if (v.length() == 2 && a != null && b != null) {
                        for ((la, lo) in listOf(a to b, b to a)) {
                            if (la in 48.0..52.0 && lo in 12.0..19.0) {
                                out.putIfAbsent("latitude", Flat(depth, k, la))
                                out.putIfAbsent("longitude", Flat(depth, k, lo))
                                break
                            }
                        }
                    }
                }
                else -> {
                    val nk = normKey(k)
                    val cur = out[nk]
                    if (cur == null || depth < cur.depth) out[nk] = Flat(depth, k, v)
                }
            }
        }
        return out
    }

    private fun pick(f: Map<String, Flat>, canon: String): Flat? {
        val names = overrides[canon]?.let { listOf(normKey(it)) } ?: ALIASES.getValue(canon)
        for (n in names) {
            val e = f[n] ?: continue
            if (e.value.toString().isNotBlank()) return e
        }
        return null
    }

    private fun findItems(root: Any?): List<JSONObject> {
        var best: List<JSONObject> = emptyList()
        fun hasCoords(o: JSONObject): Boolean {
            val f = flatten(o)
            return pick(f, "lat") != null && pick(f, "lon") != null
        }
        fun walk(x: Any?) {
            when (x) {
                is JSONArray -> {
                    val items = (0 until x.length()).mapNotNull { x.optJSONObject(it) }
                    if (items.size > best.size && items.isNotEmpty() && items.take(5).any { hasCoords(it) }) best = items
                    for (i in 0 until minOf(3, x.length())) walk(x.opt(i))
                }
                is JSONObject -> x.keys().forEach { walk(x.opt(it)) }
            }
        }
        walk(root)
        return best
    }

    private fun dbl(x: Any?): Double? = x?.toString()?.replace(',', '.')?.toDoubleOrNull()

    private fun parseTs(x: Any?): Instant? = runCatching {
        if (x == null) return@runCatching null
        val num = (x as? Number)?.toDouble() ?: x.toString().trim().takeIf { s -> s.isNotEmpty() && s.all { it.isDigit() || it == '.' } }?.toDouble()
        if (num != null) return@runCatching Instant.ofEpochMilli(if (num > 1e11) num.toLong() else (num * 1000).toLong())
        val s = x.toString().trim()
        if (s.isEmpty()) null
        else try { OffsetDateTime.parse(s).toInstant() } catch (_: Exception) { LocalDateTime.parse(s).atZone(PRAGUE_ZONE).toInstant() }
    }.getOrNull()

    fun parse(root: Any?): List<Vehicle> {
        val items = findItems(root)
        if (!logged || items.isEmpty()) {
            logged = true
            Log.i("Dpmul", "položek=${items.size}, první=${items.firstOrNull()?.toString()?.take(700)}")
        }
        return items.mapNotNull { toVehicle(it) }
    }

    private fun toVehicle(o: JSONObject): Vehicle? {
        val f = flatten(o)
        var la = dbl(pick(f, "lat")?.value) ?: return null
        var lo = dbl(pick(f, "lon")?.value) ?: return null
        if (!(la in 48.0..52.0 && lo in 12.0..19.0)) {
            if (lo in 48.0..52.0 && la in 12.0..19.0) { val t = la; la = lo; lo = t } else return null
        }
        // zpoždění: "HH:MM:SS" nebo číslo (minuty / sekundy podle názvu klíče)
        val de = pick(f, "delay")
        val dv = de?.value
        val delayMin: Double =
            if (dv is String && DELAY_RE.matches(dv.trim())) {
                val s = dv.trim()
                val neg = s.startsWith("-")
                val (h, m, x) = s.removePrefix("-").split(":").map { it.toInt() }
                (if (neg) -1 else 1) * (h * 3600 + m * 60 + x) / 60.0
            } else {
                var d = dbl(dv) ?: 0.0
                if (de != null && "sec" in normKey(de.key)) d /= 60.0
                d
            }
        val vid = pick(f, "id")?.value?.toString()
        val timeRaw = pick(f, "time")?.value
        val ts = parseTs(timeRaw)
        val age = ts?.let { maxOf(0L, Instant.now().epochSecond - it.epochSecond).toInt() } ?: 0
        return Vehicle(
            lat = la, lng = lo,
            line = pick(f, "line")?.value?.toString() ?: "?",
            trip = pick(f, "trip")?.value?.toString() ?: "",
            delay = Math.round(delayMin).toInt(),
            train = false,
            destination = prettyName(pick(f, "dest")?.value?.toString() ?: ""),
            agency = pick(f, "op")?.value?.toString() ?: DPMUL_AGENCY,
            vehicleId = vid ?: "",
            gpsTime = if (ts != null) timeRaw.toString() else "",
            id = vid?.toIntOrNull() ?: 0,
            ageSec = age,
            source = SRC_DPMUL,
            accessible = pick(f, "wheel")?.value as? Boolean,
            airConditioned = pick(f, "ac")?.value as? Boolean,
        )
    }
}