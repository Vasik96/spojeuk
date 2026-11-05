package com.vasik96.spojeuk

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorCompletionService
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

    /** Předehřátí spojení (DNS + TLS), aby první hledání nejbližší zastávky nebylo pomalé. */
    fun warmUp() {
        for (host in ROAD_HOSTS) {
            runCatching {
                val req = Request.Builder().url(host).head().header("User-Agent", USER_AGENT).build()
                fastClient.newCall(req).execute().close()
            }
        }
    }

    /** Trasa po silnici přes [stops]; při chybě vrátí původní zastávky (přímé úsečky). */
    fun pathFor(stops: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (stops.size < 2) return stops

        // řídké waypointy: první, poslední a body ≥ 700 m od posledního vybraného
        val way = ArrayList<Pair<Double, Double>>()
        way.add(stops.first())
        for (i in 1 until stops.size - 1) {
            if (distM(way.last(), stops[i]) >= 700.0) way.add(stops[i])
        }
        way.add(stops.last())
        if (way.size < 2) return stops

        val key = md5("v2;" + way.joinToString(";") { "${it.first},${it.second}" })
        val cacheFile = File(cacheDir, "routes/$key.json")

        if (cacheFile.exists()) {
            runCatching {
                val json = JSONArray(cacheFile.readText())
                val res = ArrayList<Pair<Double, Double>>()
                for (i in 0 until json.length()) {
                    val p = json.getJSONArray(i)
                    res.add(p.getDouble(0) to p.getDouble(1))
                }
                return res
            }
        }

        try {
            val out = ArrayList<Pair<Double, Double>>()
            for (i in 0 until way.size - 1 step 80) {
                val chunk = way.subList(i, minOf(way.size, i + 81))
                val coordsStr = chunk.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.second, it.first) }
                val radStr = List(chunk.size) { "30" }.joinToString(";")
                val url = "https://router.project-osrm.org/route/v1/driving/$coordsStr" +
                        "?overview=full&geometries=geojson&continue_straight=false&radiuses=$radStr"
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return stops
                    val body = resp.body?.string() ?: return stops
                    val routes = JSONObject(body).optJSONArray("routes") ?: return stops
                    if (routes.length() == 0) return stops
                    val coords = routes.getJSONObject(0).optJSONObject("geometry")
                        ?.optJSONArray("coordinates") ?: return stops
                    for (j in 0 until coords.length()) {
                        val pt = coords.getJSONArray(j)
                        out.add(pt.getDouble(1) to pt.getDouble(0))
                    }
                }
            }
            if (out.isNotEmpty()) {
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    val arr = JSONArray()
                    for (pt in out) arr.put(JSONArray().put(pt.first).put(pt.second))
                    cacheFile.writeText(arr.toString())
                }
                return out
            }
        } catch (e: Exception) { }
        return stops
    }

    private fun distM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        Geo.distanceM(a.first, a.second, b.first, b.second)

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Vzdálenosti z bodu `from` ke každému bodu v `to` (metry). null jen když selžou všechny pokusy.
     * Dělá až [ROAD_ATTEMPTS] kol; v každém kole se dotazují oba servery současně (pěší a auto)
     * a bere se první platná odpověď, takže jeden pomalý/nedostupný server nic nezpůsobí.
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
        val urls = listOf(
            "https://routing.openstreetmap.de/routed-foot/table/v1/foot/$coords" +
                    "?sources=0&destinations=$dests&annotations=distance",
            "https://router.project-osrm.org/table/v1/driving/$coords" +
                    "?sources=0&destinations=$dests&annotations=distance"
        )

        val pool = Executors.newFixedThreadPool(urls.size)
        try {
            repeat(ROAD_ATTEMPTS) { attempt ->
                val cs = ExecutorCompletionService<List<Double?>?>(pool)
                urls.forEach { u -> cs.submit { fetchTable(u, to.size) } }
                repeat(urls.size) {
                    val res = runCatching { cs.take().get() }.getOrNull()
                    if (res != null) return res
                }
                if (attempt < ROAD_ATTEMPTS - 1) Thread.sleep(250L * (attempt + 1))
            }
        } finally {
            pool.shutdownNow()
        }
        return null
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
private val ROAD_HOSTS = listOf("https://routing.openstreetmap.de/", "https://router.project-osrm.org/")

data class WalkRoute(
    val points: List<Pair<Double, Double>>,
    val distanceM: Double,
    val minutes: Int
)

