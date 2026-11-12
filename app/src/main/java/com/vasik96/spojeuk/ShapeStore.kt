package com.vasik96.spojeuk

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/** Release "shapes" ve tvém (veřejném) repozitáři, kam workflow Build shapes nahrává soubory. */
const val SHAPES_BASE_URL = "https://github.com/Vasik96/spojeuk-data/releases/download/shapes"

/**
 * Přesné trasy spojů. Data jsou v samostatném shapes.sqlite (klíč = GTFS trip_id),
 * který se jednou za pár hodin zkontroluje a když je na GitHubu novější, stáhne se (~6 MB).
 * Bez souboru (offline, první start) vrací null a volající použije OSRM.
 */
class ShapeStore(
    private val dir: File,
    private val context: Context? = null,
    /** "shapes" = autobusy, "train_shapes" = vlaky (soubory a názvy v releasu jsou podle toho). */
    private val name: String = "shapes",
) {
    private val dbFile = File(dir, "$name.sqlite")
    private val metaFile = File(dir, "$name.json")
    private val checkedFile = File(dir, "$name.checked")
    private var db: SQLiteDatabase? = null

    private val cache = object : LinkedHashMap<String, List<Pair<Double, Double>>>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<Pair<Double, Double>>>?
        ): Boolean = size > 48
    }

    init {
        dir.mkdirs()
        seedFromAssets()
    }

    /** Volitelné: když přibalíš assets/shapes.sqlite, appka má trasy hned při prvním startu. */
    private fun seedFromAssets() {
        if (dbFile.exists()) return
        val ctx = context ?: return
        runCatching {
            ctx.assets.open("$name.sqlite").use { i -> FileOutputStream(dbFile).use { o -> i.copyTo(o) } }
        }.onFailure { dbFile.delete() }
    }

    @Synchronized
    fun points(gid: String): List<Pair<Double, Double>>? {
        if (gid.isBlank() || !dbFile.exists()) return null
        cache[gid]?.let { return it }
        val d = db
            ?: (runCatching { SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY) }
                .getOrNull()?.also { db = it })
            ?: return null
        val pts = runCatching {
            d.rawQuery(
                "SELECT s.pts FROM trip_shape t JOIN shape s ON s.id = t.shape WHERE t.gid = ?",
                arrayOf(gid)
            ).use { c -> if (c.moveToFirst()) decode(c.getBlob(0)) else null }
        }.getOrNull()
        if (pts != null && pts.size >= 2) {
            cache[gid] = pts
            return pts
        }
        return null
    }


    // ───────── záložní párování tvaru (stejně jako web: lib/shapes.mjs → buildShapePack) ─────────
    // gid spoje z jízdního řádu (jdf:trip:<linka>:<n>) se po přegenerování tras nemusí shodovat s trip_shape.gid.
    // Pak se vezmou všechny tvary stejné linky (prefix jdf:trip:<linka>:) a vybere se ten, který nejlíp sedí na zastávky.

    private val fuzzyCache = object : LinkedHashMap<String, List<Pair<Double, Double>>>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<Pair<Double, Double>>>?
        ): Boolean = size > 48
    }
    private val fuzzyMiss = HashSet<String>()

    /**
     * Tvar spoje: přesně podle [gid], jinak nejlépe sedící tvar téže linky podle [stops] (lat, lng v pořadí jízdy).
     * Vlaky (czptt:) se nepárují, tam je shoda jen přesná.
     */
    @Synchronized
    fun pointsFor(gid: String, stops: List<Pair<Double, Double>>): List<Pair<Double, Double>>? {
        points(gid)?.let { return it }
        if (gid.isBlank() || gid.startsWith("czptt:") || stops.size < 2 || !dbFile.exists()) return null
        fuzzyCache[gid]?.let { return it }
        if (gid in fuzzyMiss) return null
        val found = runCatching { matchByRoute(gid, stops) }.getOrNull()
        if (found != null) fuzzyCache[gid] = found else fuzzyMiss.add(gid)
        return found
    }

    private fun routePrefix(gid: String): String? =
        Regex("^jdf:trip:([^:]+):").find(gid)?.let { "jdf:trip:${it.groupValues[1]}" }

    private fun openDb(): SQLiteDatabase? =
        db ?: runCatching { SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY) }
            .getOrNull()?.also { db = it }

    /** (verze yymmdd, výluka?) z gid `jdf:trip:<linka>:<yymmdd>[:det]…`, nebo null. */
    private fun gidVersion(gid: String): Pair<String, Boolean>? =
        Regex("^jdf:trip:[^:]+:(\\d{6})(:det)?(?::|$)").find(gid)?.let { it.groupValues[1] to it.groupValues[2].isNotEmpty() }

    private fun matchByRoute(gid: String, stops: List<Pair<Double, Double>>): List<Pair<Double, Double>>? {
        val prefix = routePrefix(gid) ?: return null
        val d = openDb() ?: return null
        val want = gidVersion(gid)

        // shape id -> nejlepší "afinita" k hledanému gid:
        // 2 = stejná verze jízdního řádu i stejný druh (běžný / výluka), 1 = jen stejný druh
        // Tvar běžné trasy nikdy nepoužijeme pro výlukový spoj (a naopak): zastávky na něm sedí,
        // ale úsek mezi nimi vede jinudy, což je přesně "část trasy je špatně".
        val affinity = HashMap<Long, Int>()
        d.rawQuery(
            "SELECT shape, gid FROM trip_shape WHERE gid >= ? AND gid < ?",
            arrayOf("$prefix:", "$prefix;")
        ).use { c ->
            while (c.moveToNext()) {
                val g = gidVersion(c.getString(1))
                val a = when {
                    want == null || g == null -> 1
                    g.second != want.second -> continue
                    g.first == want.first -> 2
                    else -> 1
                }
                val id = c.getLong(0)
                if (a > (affinity[id] ?: 0)) affinity[id] = a
            }
        }

        var best: List<Pair<Double, Double>>? = null
        var bestAff = -1
        var bestMean = Double.MAX_VALUE
        var bestMax = Double.MAX_VALUE
        for ((id, aff) in affinity) {
            val pts = d.rawQuery("SELECT pts FROM shape WHERE id = ?", arrayOf(id.toString())).use { c ->
                if (c.moveToFirst()) decode(c.getBlob(0)) else null
            } ?: continue
            val sc = shapeMatchScore(pts, stops) ?: continue
            if (sc.mean > 100.0 || sc.max > 350.0) continue
            val better = aff > bestAff ||
                    (aff == bestAff && (sc.mean < bestMean || (sc.mean == bestMean && sc.max < bestMax)))
            if (better) {
                // tvar delší než spoj (jiná varianta linky) se ořízne na úsek první až poslední zastávka
                best = sliceBetween(pts, sc.sFirst, sc.sLast)
                bestAff = aff; bestMean = sc.mean; bestMax = sc.max
            }
        }
        return best?.takeIf { it.size >= 2 }
    }

    private class MatchScore(val mean: Double, val max: Double, val sFirst: Double, val sLast: Double)

    /** Úsek tvaru mezi kumulativními vzdálenostmi [a, b] (m od začátku). */
    private fun sliceBetween(pts: List<Pair<Double, Double>>, a: Double, b: Double): List<Pair<Double, Double>> {
        if (pts.size < 2 || b <= a) return pts
        val cosLat = Math.cos(Math.toRadians(pts[0].first))
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] + Math.hypot(
                (pts[i].second - pts[i - 1].second) * 111320.0 * cosLat,
                (pts[i].first - pts[i - 1].first) * 110540.0
            )
        }
        if (a <= 1.0 && b >= cum.last() - 1.0) return pts
        fun at(s: Double): Pair<Double, Double> {
            if (s <= 0.0) return pts.first()
            if (s >= cum.last()) return pts.last()
            var i = 1
            while (i < cum.size - 1 && cum[i] < s) i++
            val seg = cum[i] - cum[i - 1]
            val t = if (seg > 0.0) (s - cum[i - 1]) / seg else 0.0
            return (pts[i - 1].first + t * (pts[i].first - pts[i - 1].first)) to
                    (pts[i - 1].second + t * (pts[i].second - pts[i - 1].second))
        }
        val out = ArrayList<Pair<Double, Double>>()
        out.add(at(a))
        for (i in pts.indices) if (cum[i] > a && cum[i] < b) out.add(pts[i])
        out.add(at(b))
        return out
    }

    /** Průměrná a největší odchylka zastávek od tvaru (přiřazení neklesající podél tvaru) + pozice první a poslední zastávky na tvaru; null = nesedí. */
    private fun shapeMatchScore(pts: List<Pair<Double, Double>>, stops: List<Pair<Double, Double>>): MatchScore? {
        if (pts.size < 2 || stops.size < 2) return null
        val lat0 = pts[0].first
        val lng0 = pts[0].second
        val cosLat = Math.cos(Math.toRadians(lat0))
        val n = pts.size
        val xs = DoubleArray(n) { (pts[it].second - lng0) * 111320.0 * cosLat }
        val ys = DoubleArray(n) { (pts[it].first - lat0) * 110540.0 }
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])

        class Cand(val s: Double, val dist: Double) { var cost = 0.0; var max = 0.0; var s0 = 0.0 }
        var previous: List<Cand>? = null
        for ((lat, lng) in stops) {
            val x = (lng - lng0) * 111320.0 * cosLat
            val y = (lat - lat0) * 110540.0
            val cands = ArrayList<Cand>()
            for (i in 0 until n - 1) {
                val dx = xs[i + 1] - xs[i]
                val dy = ys[i + 1] - ys[i]
                val l2 = dx * dx + dy * dy
                val t = if (l2 > 0.0) (((x - xs[i]) * dx + (y - ys[i]) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val dist = Math.hypot(x - xs[i] - t * dx, y - ys[i] - t * dy)
                if (dist <= 500.0) cands.add(Cand(cum[i] + t * (cum[i + 1] - cum[i]), dist))
            }
            if (cands.isEmpty()) return null

            val prev = previous
            if (prev != null) {
                var pi = 0
                var bestPrev: Cand? = null
                for (c in cands) {
                    while (pi < prev.size && prev[pi].s <= c.s + 30.0) {
                        val cand = prev[pi++]
                        val bp = bestPrev
                        if (bp == null || cand.cost < bp.cost || (cand.cost == bp.cost && cand.max < bp.max)) bestPrev = cand
                    }
                    val bp = bestPrev
                    c.cost = if (bp != null) bp.cost + c.dist else Double.POSITIVE_INFINITY
                    c.max = if (bp != null) maxOf(bp.max, c.dist) else Double.POSITIVE_INFINITY
                    c.s0 = bp?.s0 ?: 0.0
                }
                previous = cands.filter { it.cost.isFinite() }
                if (previous.isNullOrEmpty()) return null
            } else {
                for (c in cands) { c.cost = c.dist; c.max = c.dist; c.s0 = c.s }
                previous = cands
            }
        }
        val last = previous ?: return null
        var best = last[0]
        for (c in last) if (c.cost < best.cost || (c.cost == best.cost && c.max < best.max)) best = c
        return MatchScore(best.cost / stops.size, best.max, best.s0, best.s)
    }

    /** Pro ladění: proč má / nemá spoj přesný tvar (zobrazuje se v detailu vozidla). */
    @Synchronized
    fun explain(gid: String): String {
        if (gid.isBlank()) return "spoj nemá gid"
        if (!dbFile.exists()) return "soubor tras ($name.sqlite) v telefonu chybí"
        val built = getBuiltDate() ?: "?"
        val n = points(gid)?.size
        return if (n != null) "přesný tvar ($n bodů, build $built)"
        else "tvar pro tento spoj v $name.sqlite není (build $built), použije se OSRM\n${lineVersions(gid)}"
    }

    /** Pro ladění: jaké verze téže linky (a kolik spojů) shapes.sqlite zná, např. "261001: 120, 261105 výluka: 0". */
    private fun lineVersions(gid: String): String {
        val prefix = routePrefix(gid) ?: return ""
        val d = openDb() ?: return ""
        val counts = java.util.TreeMap<String, Int>()
        runCatching {
            d.rawQuery("SELECT gid FROM trip_shape WHERE gid >= ? AND gid < ?", arrayOf("$prefix:", "$prefix;")).use { c ->
                while (c.moveToNext()) {
                    val v = gidVersion(c.getString(0)) ?: continue
                    val k = v.first + if (v.second) " výluka" else ""
                    counts[k] = (counts[k] ?: 0) + 1
                }
            }
        }
        val want = gidVersion(gid)?.let { it.first + if (it.second) " výluka" else "" } ?: "?"
        return "hledáno: $want; v $name.sqlite pro linku: " + (if (counts.isEmpty()) "nic" else counts.entries.joinToString { "${it.key}: ${it.value}" })
    }

    fun getBuiltDate(): String? {
        if (!metaFile.exists()) return null
        return runCatching { JSONObject(metaFile.readText()).optString("built") }.getOrNull()
    }

    /**
     * Zkontroluje shapes.json na GitHubu (pár set bajtů) a když je novější, stáhne shapes.sqlite.
     * Vrací true, když se soubor vyměnil. Kontrola nejvýš jednou za 12 h, pokud není [force].
     */
    suspend fun sync(
        http: OkHttpClient,
        force: Boolean = false,
        forceDownload: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        syncLock.withLock {
            val now = System.currentTimeMillis()
            if (!force && !forceDownload && dbFile.exists() &&
                now - checkedFile.lastModified() < 12 * 3600_000L
            ) return@withLock false

            val remote = JSONObject(httpGet(http, "$SHAPES_BASE_URL/$name.json"))
            val localBuilt = runCatching { JSONObject(metaFile.readText()).optString("built") }.getOrDefault("")
            val localSha = runCatching { JSONObject(metaFile.readText()).optString("sha256") }.getOrDefault("")
            // stejné "built" nestačí: kdyby se soubor přegeneroval (např. ruční úpravy tras), musí se stáhnout i podle sha256
            if (!forceDownload && dbFile.exists() && remote.optString("built") == localBuilt &&
                remote.optString("sha256") == localSha
            ) {
                checkedFile.writeText(now.toString())
                return@withLock false
            }

            val expected = remote.optInt("trips")
            if (expected <= 0) error("shapes.json neobsahuje počet spojů")

            val tmp = File(dir, "$name.sqlite.tmp")
            try {
                val md = MessageDigest.getInstance("SHA-256")
                val req = Request.Builder().url("$SHAPES_BASE_URL/$name.sqlite").header("User-Agent", USER_AGENT).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("shapes.sqlite HTTP ${resp.code}")
                    val body = resp.body ?: error("prázdná odpověď")
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(65536)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                            }
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (sha != remote.optString("sha256")) {
                    error("sha256 nesedí (nahrává se nová verze?), zkusí se příště")
                }
                val ok = runCatching {
                    SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                        d.rawQuery("SELECT count(*) FROM trip_shape", null).use {
                            it.moveToFirst() && it.getInt(0) >= expected * 9 / 10
                        }
                    }
                }.getOrDefault(false)
                if (!ok) error("stažený shapes.sqlite je poškozený nebo neúplný")

                synchronized(this@ShapeStore) {
                    db?.close(); db = null; cache.clear(); fuzzyCache.clear(); fuzzyMiss.clear()
                    if (!tmp.renameTo(dbFile)) { dbFile.delete(); tmp.renameTo(dbFile) }
                    metaFile.writeText(remote.toString())
                }
                checkedFile.writeText(now.toString())
                android.util.Log.i("Shapes", "shapes.sqlite aktualizován: ${remote.optString("built")}, spojů $expected")
                true
            } finally {
                tmp.delete() // po úspěšném renameTo už neexistuje, při chybě uklidí zbytek
            }
        }
    }

    private fun httpGet(http: OkHttpClient, url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} ($url)")
            return resp.body?.string() ?: error("prázdná odpověď")
        }
    }

    companion object {
        private val syncLock = Mutex()

        /** zlib( varint(počet) + zigzag-varint delty lat/lon v mikrostupních ), viz tools/build_shapes.py */
        fun decode(blob: ByteArray): List<Pair<Double, Double>> {
            val raw = InflaterInputStream(blob.inputStream()).use { it.readBytes() }
            var p = 0
            fun varint(): Long {
                var r = 0L
                var s = 0
                while (true) {
                    val b = raw[p++].toInt() and 0xff
                    r = r or ((b and 0x7f).toLong() shl s)
                    if ((b and 0x80) == 0) return r
                    s += 7
                }
            }
            fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

            val n = varint().toInt()
            val out = ArrayList<Pair<Double, Double>>(n)
            var la = 0L
            var lo = 0L
            repeat(n) {
                la += zigzag(varint())
                lo += zigzag(varint())
                out.add(la / 1e6 to lo / 1e6)
            }
            return out
        }
    }
}