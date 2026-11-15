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
    }
    override fun callFailed(call: Call, ioe: IOException) {
        Log.w("VehicleApi", "SELHALO po ${SystemClock.elapsedRealtime() - t0} ms (${ioe.javaClass.simpleName}: ${ioe.message}): $parts")
    }
}

/**
 * Klient vlastního serveru (API v2). Server sám dotahuje DPMÚL, DÚK i Správu železnic a vrací jedno sloučené pole
 * vozidel, appka už nepouští dotazy na třetí strany.
 */
class VehicleApi(private val url: String = API_VEHICLES_URL) {

    /**
     * DNS s vlastní cache: IPv4 adresy mají přednost (rozbité IPv6 jinak zdržuje spojení) a úspěšný překlad se drží
     * [DNS_TTL_MS], takže pomalé / nespolehlivé DNS (DuckDNS, Private DNS) neplatí každý dotaz. Když překlad selže,
     * použije se poslední známá adresa. Po chybě spojení se cache zahodí ([invalidate]), aby se zachytila změna IP.
     */
    private class FallbackDns : Dns {
        private class Entry(val addrs: List<InetAddress>, val atMs: Long)
        private val cache = ConcurrentHashMap<String, Entry>()

        fun invalidate() = cache.clear()

        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            cache[hostname]?.let { if (now - it.atMs < DNS_TTL_MS) return it.addrs }
            return try {
                val sorted = Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
                if (sorted.isNotEmpty()) cache[hostname] = Entry(sorted, now)
                sorted
            } catch (e: UnknownHostException) {
                cache[hostname]?.addrs ?: throw e
            }
        }
    }

    private val dns = FallbackDns()

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

    /** Blokující dotaz (volat z IO). Vyhodí výjimku při chybě sítě / HTTP / JSON. */
    fun fetch(): ApiSnapshot {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        try {
            return client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val snap = parse(body, System.currentTimeMillis())
                lastBody = body
                snap
            }
        } catch (e: Exception) {
            // zaseknuté / mrtvé spojení by se jinak znovu použilo pro každý další dotaz, dokud se appka nerestartuje
            resetConnections()
            throw e
        }
    }

    companion object {
        private const val DNS_TTL_MS = 5 * 60_000L

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