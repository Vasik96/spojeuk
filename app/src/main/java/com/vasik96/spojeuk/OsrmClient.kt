package com.vasik96.spojeuk

import android.os.SystemClock
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Klient pro OSRM (veřejný router.project-osrm.org): trasa po silnici přes zastávky
 * (s cache na disku) a vzdálenosti po silnici pro hledání nejbližší zastávky.
 */
class OsrmClient(private val cacheDir: File, private val context: android.content.Context? = null) {
    @Volatile private var lastNetLogMs = 0L

    private val client = OkHttpClient.Builder()
        .dns(StaleDns)
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
                val t0 = SystemClock.elapsedRealtime()
                try {
                    val req = Request.Builder().url(host).head().header("User-Agent", USER_AGENT).build()
                    fastClient.newCall(req).execute().use { resp ->
                        Log.d(TAG, "warmUp $host HTTP ${resp.code} za ${SystemClock.elapsedRealtime() - t0} ms")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "warmUp $host selhal za ${SystemClock.elapsedRealtime() - t0} ms: ${describe(e)}")
                    logNetworkState("warmUp")
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
            val t0 = SystemClock.elapsedRealtime()
            try {
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                val res = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "route r=$radius HTTP ${resp.code} za ${SystemClock.elapsedRealtime() - t0} ms (${chunk.size} bodů)")
                        return@use null     // typicky 400 NoSegment: zkusí se větší poloměr
                    }
                    val body = resp.body.string()
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
                if (res != null) {
                    Log.d(TAG, "route r=$radius OK za ${SystemClock.elapsedRealtime() - t0} ms (${chunk.size} bodů, ${res.size} bodů trasy)")
                    return res
                }
            } catch (e: Exception) {
                Log.w(TAG, "route r=$radius selhal za ${SystemClock.elapsedRealtime() - t0} ms (${chunk.size} bodů): ${describe(e)}")
                logNetworkState("route")
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
     * Vzdálenosti z bodu `from` ke každému bodu v `to` (metry). null, když nejde ani pěší, ani auto server
     * (volající pak použije vzdušnou čáru).
     *
     * Pořadí: pěší server (routed-foot) -> autový server -> null. Oba se ptají SOUČASNĚ, takže pád jednoho nic
     * nezdržuje. Celé to je omezené na [RACE_MAX_MS]; opakuje se jen když oba selhaly rychle (chyba sítě / HTTP),
     * ne když se čekalo na timeout.
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
            val t0 = SystemClock.elapsedRealtime()
            raceTables(footUrl, drivingUrl, to.size)?.let { return it }
            val took = SystemClock.elapsedRealtime() - t0
            // po timeoutu se neopakuje (to by trvalo dvakrát tak dlouho), jen po rychlém selhání
            if (took > RETRY_ONLY_IF_FASTER_MS) return null
            if (attempt < ROAD_ATTEMPTS - 1) Thread.sleep(150L)
        }
        return null
    }

    /**
     * Pěší a auto server najednou. Výsledek:
     *  1. pěší odpověděl platně -> hned pěší;
     *  2. pěší selhal -> auto (pokud ještě nedorazilo, počká se na něj do limitu);
     *  3. auto dorazilo dřív a pěší jen ještě běží -> krátce se počká na pěší ([FOOT_GRACE_MS]), jinak auto;
     *  4. nic -> null.
     */
    private fun raceTables(footUrl: String, drivingUrl: String, n: Int): List<Double?>? {
        val foot = CompletableFuture.supplyAsync({ fetchTable("foot", footUrl, n) }, raceExecutor)
        val car = CompletableFuture.supplyAsync({ fetchTable("car", drivingUrl, n) }, raceExecutor)
        val deadline = SystemClock.elapsedRealtime() + RACE_MAX_MS
        fun left() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

        fun CompletableFuture<List<Double?>?>.await(ms: Long): List<Double?>? =
            try { get(ms, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }

        // počkat na prvního, kdo skončí (úspěšně i neúspěšně)
        runCatching { CompletableFuture.anyOf(foot, car).get(left(), TimeUnit.MILLISECONDS) }

        foot.getNow(null)?.let { return it }                       // 1.
        if (foot.isDone) return car.await(left())                  // 2. pěší selhal
        car.getNow(null)?.let { c -> return foot.await(FOOT_GRACE_MS) ?: c }   // 3.
        return foot.await(left()) ?: car.getNow(null)              // auto selhalo / nic: čekat na pěší do limitu
    }

    private fun fetchTable(label: String, url: String, n: Int): List<Double?>? {
        val t0 = SystemClock.elapsedRealtime()
        fun took() = SystemClock.elapsedRealtime() - t0
        return try {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "$label HTTP ${resp.code} za ${took()} ms")
                    return null
                }
                val body = resp.body.string()
                val row = body.let { JSONObject(it).optJSONArray("distances")?.optJSONArray(0) }
                if (row == null || row.length() < n) {
                    Log.w(TAG, "$label odpověď bez vzdáleností za ${took()} ms: ${body.take(120)}")
                    return null
                }
                val list = List(n) { i -> if (row.isNull(i)) null else row.getDouble(i) }
                // odpověď, kde není žádná platná vzdálenost, ber jako neúspěch (zkusí se druhý server)
                if (list.all { it == null }) {
                    Log.w(TAG, "$label samé null za ${took()} ms")
                    null
                } else {
                    Log.d(TAG, "$label OK za ${took()} ms")
                    list
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$label selhal za ${took()} ms: ${describe(e)}")
            logNetworkState(label)
            null
        }
    }


    /** Stav sítě zařízení (transporty, VPN, Private DNS, validace); po selhání spojení, nejvýš jednou za 5 s. */
    private fun logNetworkState(reason: String) {
        val ctx = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNetLogMs < 5_000L) return
        lastNetLogMs = now
        runCatching {
            val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val lp = net?.let { cm.getLinkProperties(it) }
            Log.w(
                TAG,
                "síť ($reason): aktivní=${net != null} " +
                        "wifi=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)} " +
                        "cell=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)} " +
                        "vpn=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)} " +
                        "internet=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                        "validated=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                        "privateDns=${lp?.isPrivateDnsActive}(${lp?.privateDnsServerName}) dns=${lp?.dnsServers}"
            )
        }
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
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val coords = String.format(
                Locale.US, "%.6f,%.6f;%.6f,%.6f",
                from.second, from.first, to.second, to.first
            )
            val url = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coords" +
                    "?overview=full&geometries=geojson"
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "walk HTTP ${resp.code} za ${SystemClock.elapsedRealtime() - t0} ms")
                    return null
                }
                val body = resp.body.string()
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
            Log.w(TAG, "walk selhal za ${SystemClock.elapsedRealtime() - t0} ms: ${describe(e)}")
            logNetworkState("walk")
            null
        }
    }
}

private const val TAG = "Osrm"
private const val ROAD_ATTEMPTS = 2
private const val FOOT_GRACE_MS = 600L
private const val RACE_MAX_MS = 4_500L
private const val RETRY_ONLY_IF_FASTER_MS = 1_500L

/**
 * DNS s pamětí: dřív přeložená adresa se vrací hned (i prošlá) a obnovuje se na pozadí, takže pomalé DNS
 * (soukromé DNS s blokováním reklam, studená mobilní síť) nezdržuje každý dotaz. Úplně první překlad je systémový.
 */
private object StaleDns : Dns {
    private class Entry(val addrs: List<InetAddress>, val atMs: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private const val TTL_MS = 5 * 60_000L

    override fun lookup(hostname: String): List<InetAddress> {
        val e = cache[hostname]
        if (e != null) {
            if (System.currentTimeMillis() - e.atMs >= TTL_MS && refreshing.add(hostname)) {
                Thread({
                    try { resolve(hostname) } catch (_: Exception) { } finally { refreshing.remove(hostname) }
                }, "osrm-dns").apply { isDaemon = true }.start()
            }
            return e.addrs
        }
        return resolve(hostname)
    }

    private fun resolve(host: String): List<InetAddress> {
        val t0 = SystemClock.elapsedRealtime()
        val r = Dns.SYSTEM.lookup(host).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
        Log.d(TAG, "DNS $host -> ${r.joinToString { it.hostAddress ?: "?" }} za ${SystemClock.elapsedRealtime() - t0} ms")
        if (r.isNotEmpty()) cache[host] = Entry(r, System.currentTimeMillis())
        return r
    }
}


/**
 * Výjimka i s celým řetězcem příčin. OkHttp u ConnectException píše jen "Failed to connect to host/ip:443",
 * skutečný důvod (ECONNREFUSED, ENETUNREACH, EPERM, ETIMEDOUT…) je v `cause`.
 */
private fun describe(e: Throwable): String {
    val sb = StringBuilder()
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth < 5) {
        if (depth > 0) sb.append(" <- ")
        sb.append(t.javaClass.simpleName).append(": ").append(t.message)
        t = t.cause?.takeIf { it !== t }
        depth++
    }
    return sb.toString()
}

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