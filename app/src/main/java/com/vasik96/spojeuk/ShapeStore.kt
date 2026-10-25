package com.vasik96.spojeuk

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
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
class ShapeStore(private val dir: File, private val context: Context? = null) {
    private val dbFile = File(dir, "shapes.sqlite")
    private val metaFile = File(dir, "shapes.json")
    private val checkedFile = File(dir, "shapes.checked")
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
            ctx.assets.open("shapes.sqlite").use { i -> FileOutputStream(dbFile).use { o -> i.copyTo(o) } }
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

    /**
     * Zkontroluje shapes.json na GitHubu (pár set bajtů) a když je novější, stáhne shapes.sqlite.
     * Vrací true, když se soubor vyměnil. Kontrola nejvýš jednou za 12 h, pokud není [force].
     */
    suspend fun sync(http: OkHttpClient, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && dbFile.exists() && now - checkedFile.lastModified() < 12 * 3600_000L) return@withContext false

        val remote = JSONObject(httpGet(http, "$SHAPES_BASE_URL/shapes.json"))
        val localBuilt = runCatching { JSONObject(metaFile.readText()).optString("built") }.getOrDefault("")
        if (!force && dbFile.exists() && remote.optString("built") == localBuilt) {
            checkedFile.writeText(now.toString())
            return@withContext false
        }

        val tmp = File(dir, "shapes.sqlite.tmp")
        val md = MessageDigest.getInstance("SHA-256")
        val req = Request.Builder().url("$SHAPES_BASE_URL/shapes.sqlite").header("User-Agent", USER_AGENT).build()
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
            tmp.delete()
            error("sha256 nesedí (nahrává se nová verze?), zkusí se příště")
        }
        val expected = remote.optInt("trips")
        val ok = runCatching {
            SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                d.rawQuery("SELECT count(*) FROM trip_shape", null).use { it.moveToFirst() && it.getInt(0) >= expected * 9 / 10 }
            }
        }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            error("stažený shapes.sqlite je poškozený nebo neúplný")
        }

        synchronized(this@ShapeStore) {
            db?.close(); db = null; cache.clear()
            if (!tmp.renameTo(dbFile)) { dbFile.delete(); tmp.renameTo(dbFile) }
            metaFile.writeText(remote.toString())
        }
        checkedFile.writeText(now.toString())
        android.util.Log.i("Shapes", "shapes.sqlite aktualizován: ${remote.optString("built")}, spojů ${remote.optInt("trips")}")
        true
    }

    private fun httpGet(http: OkHttpClient, url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} ($url)")
            return resp.body?.string() ?: error("prázdná odpověď")
        }
    }

    companion object {
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