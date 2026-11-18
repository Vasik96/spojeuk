package com.vasik96.spojeuk

import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

/** Identifikátory zdrojů poloh tak, jak je posílá server (pole `source` / `sources`). */
object SourceIds {
    const val DPMUL = "dpmul"
    const val DUK = "duk"
    const val DPMD = "dpmd"
    const val TRAIN = "train" // Správa železnic
    const val PID = "pid" // Pražská integrovaná doprava
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

/** Posledních pár řádků o dotazech na server poloh, k zobrazení v appce (bez adb / logcatu). */
object FeedDiag {
    private val lines = java.util.ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addFirst("${fmt.format(java.util.Date())}  $msg")
        while (lines.size > 8) lines.removeLast()
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) tr(R.string.diag_no_requests) else lines.joinToString("\n")
}

/** Do logcatu (tag VehicleApi) vypíše, kde se u dotazu ztrácí čas: DNS / TCP / TLS / odpověď serveru / chyba. */
private class TimingListener : EventListener() {
    private val t0 = SystemClock.elapsedRealtime()
    private var mark = t0
    private val parts = StringBuilder()

    private fun lap(name: String) {
        val now = SystemClock.elapsedRealtime()
        parts.append(name).append('=').append(now - mark).append("ms ")
        mark = now
    }

    override fun dnsStart(call: Call, domainName: String) { mark = SystemClock.elapsedRealtime() }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        lap("dns(${inetAddressList.joinToString { it.hostAddress ?: "?" }})")
    }
    override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { mark = SystemClock.elapsedRealtime() }
    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) { lap("tls") }
    override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) { lap("connect($protocol)") }
    override fun connectFailed(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?, ioe: IOException) {
        lap("connectFAILED(${inetSocketAddress.address?.hostAddress}: ${ioe.javaClass.simpleName})")
    }
    override fun requestHeadersEnd(call: Call, request: Request) { mark = SystemClock.elapsedRealtime() }
    override fun responseHeadersStart(call: Call) { lap("server-wait") }
    override fun callEnd(call: Call) {
        Log.d("VehicleApi", "OK celkem ${SystemClock.elapsedRealtime() - t0} ms: $parts")
        FeedDiag.add("OK ${SystemClock.elapsedRealtime() - t0} ms: $parts")
    }
    override fun callFailed(call: Call, ioe: IOException) {
        Log.w("VehicleApi", "SELHALO po ${SystemClock.elapsedRealtime() - t0} ms (${ioe.javaClass.simpleName}: ${ioe.message}): $parts")
        FeedDiag.add("SELHALO ${SystemClock.elapsedRealtime() - t0} ms ${ioe.javaClass.simpleName}: ${ioe.message}: $parts")
    }
}

/**
 * Klient vlastního serveru (API v2). Server sám dotahuje DPMÚL, DÚK i Správu železnic a vrací jedno sloučené pole
 * vozidel, appka už nepouští dotazy na třetí strany.
 */
class VehicleApi(private val url: String = API_VEHICLES_URL) {

    /**
     * DNS, které se nesmí zaseknout:
     *  - překlad běží na vlastním vlákně a čeká se na něj jen [DNS_WAIT_MS]; když systémové DNS (DuckDNS, Private DNS,
     *    mobilní síť po startu) váhá, použije se poslední známá adresa serveru, nebo pevná záložní IP z [FEED_FALLBACK_IPS];
     *  - úspěšný překlad se drží [DNS_TTL_MS], takže se DNS neplatí při každém dotazu;
     *  - IPv4 má přednost (rozbité IPv6 jinak zdržuje spojení).
     * Po chybě spojení se cache jen označí jako prošlá ([invalidate]), adresy zůstávají jako záloha.
     */
    private class FallbackDns(private val fallbackIps: List<String>) : Dns {
        private class Entry(val addrs: List<InetAddress>, val atMs: Long)
        private val cache = ConcurrentHashMap<String, Entry>()
        private val refreshing = ConcurrentHashMap.newKeySet<String>()

        init {
            // studený start: hned mám co použít
            val seed = fallbackIps.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            if (seed.isNotEmpty()) cache[HOST_SEED] = Entry(seed, 0L)
        }

        fun invalidate() { cache.replaceAll { _, e -> Entry(e.addrs, 0L) } }

        private fun refreshAsync(host: String) {
            if (!refreshing.add(host)) return
            DNS_POOL.execute {
                try {
                    val r = Dns.SYSTEM.lookup(host).sortedBy { if (it is Inet4Address) 0 else 1 }
                    if (r.isNotEmpty()) cache[host] = Entry(r, System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.w("VehicleApi", "DNS refresh selhal: ${e.message}")
                } finally { refreshing.remove(host) }
            }
        }

        override fun lookup(hostname: String): List<InetAddress> {
            val e = cache[hostname] ?: cache[HOST_SEED]?.also { cache[hostname] = it }
            if (e != null) {
                if (System.currentTimeMillis() - e.atMs >= DNS_TTL_MS) refreshAsync(hostname)
                return e.addrs                    // okamžitě, bez čekání na DNS
            }
            return Dns.SYSTEM.lookup(hostname)    // úplně bez zálohy: nic jiného nezbývá
        }
    }

    private val dns = FallbackDns(FEED_FALLBACK_IPS)

    private val client = OkHttpClient.Builder()
        .dns(dns)
        // delší než dřív: studená mobilní síť po startu / uspání potřebuje pár sekund na probuzení rádia
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .eventListenerFactory { TimingListener() }
        .pingInterval(10, TimeUnit.SECONDS)   // rozpozná mrtvé HTTP/2 spojení (po změně sítě / uspání) dřív než timeout
        .fastFallback(true)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    /** Surové tělo poslední úspěšné odpovědi (hub ho ukládá na disk, ať polohy přežijí restart appky). */
    @Volatile
    var lastBody: String = ""
        private set

    /** Zahodí pooled spojení, ať další dotaz naváže čisté (po změně sítě nebo po selhání). */
    fun resetConnections() {
        client.connectionPool.evictAll()
        dns.invalidate()
    }

    /** Klient pro záložní pokusy: vlastní pool spojení, ať se nepoužije stejné (možná zaseknuté) HTTP/2 spojení. */
    private val hedgeClient by lazy {
        client.newBuilder().connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES)).build()
    }

    /**
     * Blokující dotaz (volat z IO). Vyhodí výjimku při chybě sítě / HTTP / JSON.
     *
     * Dotaz se "zajišťuje": když první pokus do [HEDGE_AFTER_MS] neodpoví (zasekl se DNS / spojení / TLS, mrtvé pooled
     * spojení), spustí se vedle něj další na čistém spojení (a po dalších [HEDGE_AFTER_MS] třetí). Platí první úspěšná
     * odpověď, ostatní se zruší. Když pokus rovnou selže, nastoupí hned další bez čekání. Celkem nejvýš [TOTAL_BUDGET_MS].
     */
    fun fetch(): ApiSnapshot {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        class Outcome(val snap: ApiSnapshot?, val body: String?, val error: Exception?)

        val results = LinkedBlockingQueue<Outcome>()
        val calls = CopyOnWriteArrayList<Call>()
        var started = 0
        var failed = 0
        var lastError: Exception? = null
        val t0 = SystemClock.elapsedRealtime()

        fun launch() {
            val c = if (started == 0) client else hedgeClient
            val call = c.newCall(request)
            started++
            calls.add(call)
            ATTEMPT_POOL.execute {
                results.add(
                    try {
                        call.execute().use { resp ->
                            if (!resp.isSuccessful) error("HTTP ${resp.code}")
                            val body = resp.body?.string().orEmpty()
                            Outcome(parse(body, System.currentTimeMillis()), body, null)
                        }
                    } catch (e: Exception) {
                        Outcome(null, null, e)
                    }
                )
            }
        }

        launch()
        try {
            while (true) {
                val remaining = TOTAL_BUDGET_MS - (SystemClock.elapsedRealtime() - t0)
                if (remaining <= 0) throw lastError ?: IOException("Server poloh neodpověděl včas")
                val wait = if (started < MAX_ATTEMPTS) minOf(HEDGE_AFTER_MS, remaining) else remaining
                val r = results.poll(wait, TimeUnit.MILLISECONDS)
                if (r == null) {
                    if (started < MAX_ATTEMPTS) launch()   // pomalé: zkusit ještě jednou vedle
                    continue
                }
                if (r.snap != null) {
                    lastBody = r.body.orEmpty()
                    if (started > 1) {
                        Log.i("VehicleApi", "odpověď po ${SystemClock.elapsedRealtime() - t0} ms (pokusů: $started)")
                        FeedDiag.add("odpověď po ${SystemClock.elapsedRealtime() - t0} ms, pokusů: $started")
                    }
                    return r.snap
                }
                failed++
                lastError = r.error
                if (failed >= started) {
                    if (started >= MAX_ATTEMPTS) throw lastError ?: IOException("Server poloh neodpovídá")
                    launch()   // vše dosud selhalo: hned další pokus, bez čekání
                }
            }
        } catch (e: Exception) {
            // zaseknuté / mrtvé spojení by se jinak znovu použilo pro každý další dotaz, dokud se appka nerestartuje
            resetConnections()
            throw e
        } finally {
            calls.forEach { runCatching { it.cancel() } }
        }
    }

    companion object {
        private const val DNS_TTL_MS = 5 * 60_000L
        private const val DNS_WAIT_MS = 2_500L
        private const val DNS_MAX_WAIT_MS = 10_000L
        private const val HOST_SEED = "_seed"
        private const val HEDGE_AFTER_MS = 2_500L
        private const val MAX_ATTEMPTS = 3
        private const val TOTAL_BUDGET_MS = 15_000L

        private val DNS_POOL = Executors.newCachedThreadPool { r -> Thread(r, "vehicle-dns").apply { isDaemon = true } }
        private val ATTEMPT_POOL = Executors.newCachedThreadPool { r -> Thread(r, "vehicle-fetch").apply { isDaemon = true } }

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
                mode = o.optStringClean("mode"),
            )
        }
    }
}