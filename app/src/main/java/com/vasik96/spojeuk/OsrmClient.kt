package com.vasik96.spojeuk

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Klient pro OSRM (veřejný router.project-osrm.org): trasa po silnici přes zastávky
 * (s cache na disku) a vzdálenosti po silnici pro hledání nejbližší zastávky.
 */
class OsrmClient(private val cacheDir: File) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // rychlejší klient pro hledání nejbližší zastávky: krátké timeouty, ale víc pokusů
    private val fastClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    // vlákna pro souběžné dotazy (daemon, ať nebrání ukončení procesu)
    private val raceExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "osrm-race").apply { isDaemon = true }
    }

    /** Předehřátí spojení (DNS + TLS) všech serverů najednou, aby první hledání nejbližší zastávky nebylo pomalé. */
    fun warmUp() {
        ROAD_HOSTS.map { host ->
            CompletableFuture.runAsync({
                runCatching {
                    val req = Request.Builder().url(host).head().header("User-Agent", USER_AGENT).build()
                    fastClient.newCall(req).execute().close()
                }
            }, raceExecutor)
        }.forEach { runCatching { it.get(6, TimeUnit.SECONDS) } }
    }

    /**
     * Trasa po silnici přes [stops]. Jako waypointy se posílají jen zastávky vzdálené aspoň [MIN_GAP_M] od předchozí
     * (stejně jako na webu): každá další zastávka je další šance, že ji OSRM nenajde na silnici v okolí 30 m a odmítne
     * celý dotaz (NoSegment) – celá linka by pak skončila jako rovné čáry. Když dotaz selže, zkusí se větší poloměr
     * a až nakonec se rovně nakreslí jen ten úsek, který opravdu selhal; nepovedená trasa se neukládá do cache.
     */
    fun pathFor(stops: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (stops.size < 2) return stops

        val way = ArrayList<Pair<Double, Double>>()
        way.add(stops.first())
        for (i in 1 until stops.size - 1) {
            if (distM(way.last(), stops[i]) >= MIN_GAP_M) way.add(stops[i])
        }
        way.add(stops.last())
        if (way.size < 2 || (way.size == 2 && distM(way[0], way[1]) < 10.0)) return stops

        val key = md5("v4;" + way.joinToString(";") { "${it.first},${it.second}" })
        val cacheFile = File(cacheDir, "routes/$key.json")

        if (cacheFile.exists()) {
            runCatching {
                val json = JSONArray(cacheFile.readText())
                val res = ArrayList<Pair<Double, Double>>()
                for (i in 0 until json.length()) {
                    val p = json.getJSONArray(i)
                    res.add(p.getDouble(0) to p.getDouble(1))
                }
                if (res.size >= 2) return res
            }
        }

        val out = ArrayList<Pair<Double, Double>>()
        var allOk = true
        var i = 0
        while (i < way.size - 1) {
            val chunk = way.subList(i, minOf(way.size, i + CHUNK + 1))
            val routed = routeChunk(chunk)
            if (routed != null) out.addAll(routed)
            else { allOk = false; out.addAll(chunk) }   // jen tento úsek rovně, zbytek zůstane po silnici
            i += CHUNK
        }
        if (out.size < 2) return stops

        if (allOk) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val arr = JSONArray()
                for (pt in out) arr.put(JSONArray().put(pt.first).put(pt.second))
                cacheFile.writeText(arr.toString())
            }
        }
        return out
    }

    /** Jeden OSRM dotaz přes [chunk]; při selhání s větším poloměrem a po chybě sítě ještě jednou. Null = nepovedlo se. */
    private fun routeChunk(chunk: List<Pair<Double, Double>>): List<Pair<Double, Double>>? {
        val coordsStr = chunk.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.second, it.first) }
        for (radius in RADII_M) {
            val radStr = List(chunk.size) { radius.toString() }.joinToString(";")
            val url = "https://router.project-osrm.org/route/v1/driving/$coordsStr" +
                    "?overview=full&geometries=geojson&continue_straight=false&radiuses=$radStr"
            try {
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                val res = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null     // typicky 400 NoSegment: zkusí se větší poloměr
                    val body = resp.body?.string() ?: return@use null
                    val routes = JSONObject(body).optJSONArray("routes")
                    if (routes == null || routes.length() == 0) return@use null
                    val coords = routes.getJSONObject(0).optJSONObject("geometry")?.optJSONArray("coordinates")
                        ?: return@use null
                    val list = ArrayList<Pair<Double, Double>>(coords.length())
                    for (j in 0 until coords.length()) {
                        val pt = coords.getJSONArray(j)
                        list.add(pt.getDouble(1) to pt.getDouble(0))
                    }
                    list.takeIf { it.size >= 2 }
                }
                if (res != null) return res
            } catch (e: Exception) {
                android.util.Log.w("OsrmClient", "OSRM dotaz selhal: ${e.message}")
            }
        }
        return null
    }

    private fun distM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        Geo.distanceM(a.first, a.second, b.first, b.second)

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Vzdálenosti z bodu `from` ke každému bodu v `to` (metry). null jen když selžou všechny pokusy.
     * Přednost má vždy pěší trasování (routed-foot); auto trasování je jen záloha při výpadku pěšího serveru.
     */
    fun roadDistances(
        from: Pair<Double, Double>,
        to: List<Pair<Double, Double>>
    ): List<Double?>? {
        if (to.isEmpty()) return emptyList()
        val coords = (listOf(from) + to).joinToString(";") {
            String.format(Locale.US, "%.6f,%.6f", it.second, it.first)
        }
        val dests = (1..to.size).joinToString(";")
        val footUrl = "https://routing.openstreetmap.de/routed-foot/table/v1/foot/$coords" +
                "?sources=0&destinations=$dests&annotations=distance"
        val drivingUrl = "https://router.project-osrm.org/table/v1/driving/$coords" +
                "?sources=0&destinations=$dests&annotations=distance"

        repeat(ROAD_ATTEMPTS) { attempt ->
            raceTables(footUrl, drivingUrl, to.size)?.let { return it }
            if (attempt < ROAD_ATTEMPTS - 1) Thread.sleep(200L * (attempt + 1))
        }
        return null
    }

    /**
     * Pěší a auto server se ptají SOUČASNĚ, ne jeden po druhém (dřív se na pomalý/mrtvý pěší server čekalo
     * celý timeout, než se vůbec zkusil druhý). Pěší výsledek má přednost; když dorazí jako první, vrací se hned.
     * Když dorazí první auto, počká se na pěší už jen krátce ([FOOT_GRACE_MS]), aby se výsledek neprotahoval.
     */
    private fun raceTables(footUrl: String, drivingUrl: String, n: Int): List<Double?>? {
        val foot = CompletableFuture.supplyAsync({ fetchTable(footUrl, n) }, raceExecutor)
        val driving = CompletableFuture.supplyAsync({ fetchTable(drivingUrl, n) }, raceExecutor)

        fun CompletableFuture<List<Double?>?>.await(ms: Long): List<Double?>? =
            try { get(ms, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }

        runCatching { CompletableFuture.anyOf(foot, driving).get(RACE_MAX_MS, TimeUnit.MILLISECONDS) }

        if (foot.isDone) return foot.await(0) ?: driving.await(RACE_MAX_MS)
        // první dorazilo auto
        val d = driving.await(0) ?: return foot.await(RACE_MAX_MS)
        return foot.await(FOOT_GRACE_MS) ?: d
    }

    private fun fetchTable(url: String, n: Int): List<Double?>? = try {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val row = JSONObject(body).optJSONArray("distances")?.optJSONArray(0) ?: return null
            if (row.length() < n) return null
            val list = List(n) { i -> if (row.isNull(i)) null else row.getDouble(i) }
            // odpověď, kde není žádná platná vzdálenost, ber jako neúspěch (zkusí se druhý server / další kolo)
            if (list.all { it == null }) null else list
        }
    } catch (e: Exception) {
        null
    }

    fun clearCache(): Int {
        val dir = File(cacheDir, "routes")
        if (!dir.exists()) return 0
        val files = dir.listFiles() ?: return 0
        var count = 0
        for (f in files) {
            if (f.delete()) count++
        }
        return count
    }


    /** Pěší trasa z bodu `from` do bodu `to`. null při chybě. Blokující, volat mimo main vlákno. */
    fun walkRoute(from: Pair<Double, Double>, to: Pair<Double, Double>): WalkRoute? {
        return try {
            val coords = String.format(
                Locale.US, "%.6f,%.6f;%.6f,%.6f",
                from.second, from.first, to.second, to.first
            )
            val url = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coords" +
                    "?overview=full&geometries=geojson"
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val routes = JSONObject(body).optJSONArray("routes") ?: return null
                if (routes.length() == 0) return null
                val r = routes.getJSONObject(0)
                val dist = r.getDouble("distance")
                val coordsArr = r.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
                val pts = ArrayList<Pair<Double, Double>>()
                for (i in 0 until coordsArr.length()) {
                    val p = coordsArr.getJSONArray(i)
                    pts.add(p.getDouble(1) to p.getDouble(0))
                }
                // ~4,8 km/h = 80 m/min (duration z OSRM foot serveru bývá nespolehlivá)
                val min = maxOf(1, Math.ceil(dist / 80.0).toInt())
                WalkRoute(pts, dist, min)
            }
        } catch (e: Exception) {
            null
        }
    }
}

private const val ROAD_ATTEMPTS = 3
private const val FOOT_GRACE_MS = 600L
private const val RACE_MAX_MS = 6_000L

/** Zastávky blíž než tohle k předchozímu waypointu se do OSRM neposílají (stejně jako na webu). */
private const val MIN_GAP_M = 700.0
private const val CHUNK = 80
private val RADII_M = listOf(30, 200)
private val ROAD_HOSTS = listOf("https://routing.openstreetmap.de/", "https://router.project-osrm.org/")

data class WalkRoute(
    val points: List<Pair<Double, Double>>,
    val distanceM: Double,
    val minutes: Int
)