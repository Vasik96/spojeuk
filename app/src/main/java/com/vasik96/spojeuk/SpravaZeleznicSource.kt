package com.vasik96.spojeuk

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Správa železnic – mapa vlaků (neoficiální, bez autorizace). `\` v názvu modulu musí být %5C. */
const val SZ_URL =
    "https://mapy.spravazeleznic.cz/serverside/request2.php?module=Layers%5COsVlaky&action=load"

/**
 * SŽ banuje IP adresy, které na ně pouštějí služby. Proto:
 *  - žádný User-Agent,
 *  - min. 10 s mezi požadavky, max. 1 souběžný,
 *  - 403 = zablokováno → dlouhá pauza s exponenciálním zpožděním.
 */
class SpravaZeleznicSource(
    private val urls: List<String> = listOf(SZ_URL),
) : VehicleSource {

    override val id = ID
    override val name = "Správa Železnic"
    override val priority = 2
    override val segments = setOf(Segment.REGIONAL)          // TODO: pokud máš Segment.RAIL, dej sem něj
    override val freshness = Freshness(
        maxFetchAgeMs = 15_000,
        watchedSegments = setOf(Segment.REGIONAL),
    )
    override val tripMatcher = TripMatchers.byTrainNumber     // číslo vlaku (tn) → vlak z CZPTT v jízdním řádu
    override val adoptsTimetableLine = true

    @Volatile private var held = 0
    override val heldCount: Int get() = held
    @Volatile private var blockedUntilMs = 0L
    @Volatile private var blockedCount = 0
    override val inOutage: Boolean get() = System.currentTimeMillis() < blockedUntilMs

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // network interceptor běží PO BridgeInterceptoru, takže odstraní "okhttp/x.y" UA
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().removeHeader("User-Agent").build())
        }
        .build()

    private val mutex = Mutex()
    private var lastRequestMs = 0L
    private var cache: List<Vehicle> = emptyList()
    private var cacheMs = 0L

    override suspend fun fetch(): List<Vehicle> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            if (now < blockedUntilMs) return@withContext holdOrEmpty(now)
            if (now - lastRequestMs < MIN_INTERVAL_MS) return@withContext holdOrEmpty(now)

            lastRequestMs = now
            val body = try {
                request()
            } catch (e: BlockedException) {
                val pause = min(BLOCK_BASE_MS shl blockedCount.coerceAtMost(3), BLOCK_MAX_MS)
                blockedCount++
                blockedUntilMs = now + pause
                Log.w(TAG, "SŽ vrátila 403, pauza ${pause / 1000}s")
                return@withContext holdOrEmpty(now)
            }

            blockedCount = 0
            val list = parse(JSONObject(body), now)
            cache = list
            cacheMs = now
            held = 0
            list
        }
    }

    /** Při pauze/výpadku vrací poslední data, ale jen krátce. */
    private fun holdOrEmpty(now: Long): List<Vehicle> {
        if (cache.isEmpty() || now - cacheMs > HOLD_MS) { held = 0; return emptyList() }
        held = cache.size
        return cache
    }

    private class BlockedException : Exception()

    private fun request(): String {
        var last: Exception? = null
        for (url in urls) {
            try {
                val req = Request.Builder().url(url)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 403) throw BlockedException()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} ($url)")
                    return resp.body?.string() ?: "{}"
                }
            } catch (e: BlockedException) {
                throw e
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "zdroj $url selhal: ${e.message}")
            }
        }
        throw last ?: IllegalStateException("Žádná URL")
    }

    // ───────── parsování ─────────

    private fun parse(data: JSONObject, nowMs: Long): List<Vehicle> {
        val arr = data.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<Vehicle>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: continue
            if (p.optString("type") != "V") continue                    // jen vlaky

            val g = f.optJSONObject("geometry") ?: continue
            val c = g.optJSONArray("coordinates") ?: continue
            if (c.length() < 2) continue
            val (lat, lng) = SJtsk.toWgs84(c.optDouble(0), c.optDouble(1)) ?: continue

            val cat = p.optString("tt").trim()          // Os, R, Sp, ...
            val num = p.optString("tn").trim()          // číslo vlaku
            // linku (S1, R22, ...) doplní TripEnricher z jízdního řádu podle čísla vlaku; bez shody zůstane "Os 6805"
            val line = if (cat.isNotEmpty()) "$cat $num" else num
            val dest = p.optString("ln").trim()
            val delayMin = p.optInt("de", 0)


            out.add(
                Vehicle(
                    source = ID,
                    lat = lat,
                    lng = lng,
                    line = line,                       // předběžně "Os 6805", po spárování S1 / R22 / U5
                    delay = delayMin,                  // zpoždění v minutách
                    observedAtMs = nowMs,
                    id = num.toIntOrNull() ?: (i + 1), // keyOf → cis:line:trip
                    trip = p.optString("id"),
                    cis = num,                         // číslo vlaku, podle něj se páruje (byTrainNumber)
                    destination = prettyName(dest),
                    agency = p.optString("d"),
                    segment = Segment.REGIONAL,
                    state = 0,
                    train = true,                      // ikona a modré zvýraznění vlaku
                )
            )
        }
        return out
    }

    companion object {
        const val ID = "train" // vlaky oznacovat vzdycky jako train
        private const val TAG = "SzSource"
        private const val MIN_INTERVAL_MS = 10_000L
        private const val HOLD_MS = 2 * 60_000L
        private const val BLOCK_BASE_MS = 10 * 60_000L
        private const val BLOCK_MAX_MS = 60 * 60_000L
    }
}

/** S-JTSK / Krovak East North (EPSG:5514) → WGS84. */
private object SJtsk {
    private const val A = 6377397.155
    private const val F = 1 / 299.1528128
    private val E2 = 2 * F - F * F
    private val E = sqrt(E2)

    private val PHI_C = Math.toRadians(49.5)
    private val LAMBDA_0 = Math.toRadians(24.0 + 50.0 / 60.0)
    private val ALPHA_C = Math.toRadians(30.0 + 17.0 / 60.0 + 17.3031 / 3600.0)
    private val PHI_P = Math.toRadians(78.5)
    private const val K_P = 0.9999

    private val A_ = A * sqrt(1 - E2) / (1 - E2 * sin(PHI_C).pow(2))
    private val B = sqrt(1 + E2 * cos(PHI_C).pow(4) / (1 - E2))
    private val GAMMA_0 = asin(sin(PHI_C) / B)
    private val T0 = tan(PI / 4 + GAMMA_0 / 2) *
            ((1 + E * sin(PHI_C)) / (1 - E * sin(PHI_C))).pow(E * B / 2) /
            tan(PI / 4 + PHI_C / 2).pow(B)
    private val N = sin(PHI_P)
    private val R0 = K_P * A_ / tan(PHI_P)

    fun toWgs84(east: Double, north: Double): Pair<Double, Double>? {
        if (east.isNaN() || north.isNaN() || east == 0.0 && north == 0.0) return null
        val xp = -north        // southing
        val yp = -east         // westing
        val r = hypot(xp, yp)
        if (r == 0.0) return null
        val d = atan2(yp, xp) / N
        val s = 2 * (atan((R0 / r).pow(1 / N) * tan(PI / 4 + PHI_P / 2)) - PI / 4)
        val u = asin(cos(ALPHA_C) * sin(s) - sin(ALPHA_C) * cos(s) * cos(d))
        val v = asin(cos(s) * sin(d) / cos(u))

        var phi = u
        repeat(8) {
            phi = 2 * (atan(
                T0.pow(-1 / B) * tan(u / 2 + PI / 4).pow(1 / B) *
                        ((1 + E * sin(phi)) / (1 - E * sin(phi))).pow(E / 2)
            ) - PI / 4)
        }
        val lambda = LAMBDA_0 - v / B

        val (la, lo) = besselToWgs84(phi, lambda)
        if (la !in 47.0..52.0 || lo !in 11.0..20.0) return null   // mimo ČR = chybná data
        return la to lo
    }

    // Helmert (position vector), EPSG:15992 S-JTSK → WGS84
    private fun besselToWgs84(phi: Double, lam: Double): Pair<Double, Double> {
        val n = A / sqrt(1 - E2 * sin(phi).pow(2))
        val x = n * cos(phi) * cos(lam)
        val y = n * cos(phi) * sin(lam)
        val z = n * (1 - E2) * sin(phi)

        val sec = PI / 648000.0
        val rx = 4.998 * sec; val ry = 1.587 * sec; val rz = 5.261 * sec
        val ds = 1 + 3.56e-6
        val x2 = 570.8 + ds * (x - rz * y + ry * z)
        val y2 = 85.7 + ds * (rz * x + y - rx * z)
        val z2 = 462.8 + ds * (-ry * x + rx * y + z)

        val a2 = 6378137.0
        val e2 = 0.00669437999014
        val p = hypot(x2, y2)
        var la = atan2(z2, p * (1 - e2))
        repeat(6) {
            val nn = a2 / sqrt(1 - e2 * sin(la).pow(2))
            la = atan2(z2 + e2 * nn * sin(la), p)
        }
        return Math.toDegrees(la) to Math.toDegrees(atan2(y2, x2))
    }
}