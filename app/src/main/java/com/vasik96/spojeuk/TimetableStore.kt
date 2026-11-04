package com.vasik96.spojeuk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Jízdní řád a plánovač, které patří k sobě (vyměňují se společně). */
class TimetableBundle(val timetable: Timetable, val planner: Planner)

/**
 * Stará se o statickou databázi: kopie z assets při prvním startu, stažení a import GTFS z GitHubu,
 * a držení aktuálního [TimetableBundle].
 */
class TimetableStore(
    private val context: Context?,
    private val http: OkHttpClient,
    private val onLoaded: (Timetable) -> Unit = {},
) {
    val dbFile: File by lazy {
        val dir = File(context?.filesDir ?: File("/tmp"), "spojeuk")
        dir.mkdirs()
        File(dir, "static.sqlite")
    }



    @Volatile
    var current: TimetableBundle? = null
        private set

    private val loadLock = Mutex()
    private val importLock = Mutex()

    val pendingFile: File by lazy { File(dbFile.parentFile, "static.sqlite.new") }
    fun hasPending() = pendingFile.exists()

    fun isFirstRun(): Boolean = !dbFile.exists() || dbFile.length() < MIN_DB_BYTES

    /** Metadata (verze, datum) bez načítání celé databáze. */
    fun meta(): Map<String, String> =
        current?.timetable?.meta
            ?: if (dbFile.exists()) runCatching { Timetable.readMeta(dbFile) }.getOrDefault(emptyMap()) else emptyMap()

    suspend fun ensure(log: (String) -> Unit = {}, progress: (Float) -> Unit = {}): TimetableBundle =
        withContext(Dispatchers.IO) {
            current?.let { return@withContext it }
            loadLock.withLock {
                current?.let { return@withLock it }

                applyPendingIfAny()

                if (isFirstRun()) {
                    log("Nahrávám výchozí jízdní řády…")
                    if (!copyBundledDb()) importLock.withLock { downloadAndImport(dbFile, log, progress) }
                }
                val bundle = install(Timetable(dbFile))
                onLoaded(bundle.timetable)
                bundle
            }
        }

    suspend fun stageUpdate(log: (String) -> Unit = {}, progress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            importLock.withLock { downloadAndImport(pendingFile, log, progress) }
        }


    private fun applyPendingIfAny() {
        if (!importLock.tryLock()) return   // jiný import běží, nesahat na jeho soubory
        try {
            val dir = dbFile.parentFile
            // zbytky po přerušeném importu
            File(dir, "static.sqlite.new.tmp").delete()
            File(dir, "static.sqlite.tmp").delete()
            File(dir, "gtfs.zip").delete()
            File(dir, "gtfs_rail.zip").delete()
            File(dir, "gtfs_temp").deleteRecursively()

            val p = pendingFile
            if (!p.exists()) return
            fun trips(f: File) = runCatching { Timetable.readMeta(f)["trips"]?.toIntOrNull() }.getOrNull() ?: 0
            val newTrips = trips(p)
            val oldTrips = if (dbFile.exists()) trips(dbFile) else 0
            // ochrana proti rozbitému buildu na GitHubu
            if (newTrips > 0 && newTrips * 2 >= oldTrips) p.renameTo(dbFile) else p.delete()
        } finally {
            importLock.unlock()
        }
    }



    private fun install(tt: Timetable): TimetableBundle {
        val bundle = TimetableBundle(tt, Planner(tt))
        current = bundle
        return bundle
    }

    // ───────── zdroje dat ─────────

    private fun copyBundledDb(): Boolean {
        val ctx = context ?: return false
        val copied = runCatching {
            ctx.assets.open("static.sqlite").use { input ->
                FileOutputStream(dbFile).use { output -> input.copyTo(output) }
            }
        }.isSuccess
        return copied && !isFirstRun()
    }

    private fun downloadAndImport(target: File, log: (String) -> Unit, progress: (Float) -> Unit) {
        // rychlá cesta: malé předfiltrované zipy z mého GitHubu; když selže, stará cesta přes Oběhy
        if (runCatching { downloadRegion(target, log, progress) }
                .onFailure { Log.w(TAG, "region GTFS selhalo, jdu přes Oběhy: ${it.message}") }
                .getOrDefault(false)) return
        downloadFromObehy(target, log, progress)
    }

    /** Vrací true i když je vše aktuální (nic se nestahuje). */
    private fun downloadRegion(target: File, log: (String) -> Unit, progress: (Float) -> Unit): Boolean {
        log("Zjišťuji nejnovější data…")
        val remote = JSONObject(open("$REGION_BASE_URL/gtfs.json").use { it.body?.string().orEmpty() })
        val tag = remote.optString("tag").ifBlank { return false }
        val stagedTag = if (pendingFile.exists()) runCatching { Timetable.readMeta(pendingFile)["tag"] }.getOrNull() else null
        if (target == pendingFile && (meta()["tag"] == tag || stagedTag == tag)) { log("Data jsou aktuální."); return true }

        val dir = dbFile.parentFile
        val feeds = listOf("jdf-region.zip" to false, "czptt-region.zip" to true).map { (name, rail) ->
            val f = File(dir, if (rail) "gtfs_rail.zip" else "gtfs.zip")
            log("Stahuji $name…")
            open("$REGION_BASE_URL/$name").use { resp ->
                val body = resp.body ?: error("Prázdná odpověď")
                FileOutputStream(f).use { out -> body.byteStream().copyTo(out, 65536) }
            }
            GtfsFeed(f, rail)
        }
        try {
            GtfsImporter(target).importGtfs(feeds, tag, log, progress)
        } finally {
            feeds.forEach { it.zip.delete() }
        }
        return true
    }

    private fun downloadFromObehy(target: File, log: (String) -> Unit, progress: (Float) -> Unit) {
        log("Zjišťuji nejnovější build z GitHubu…")
        val release = JSONObject(open(RELEASES_API).use { it.body?.string().orEmpty() })
        val tag = release.optString("tag_name", "latest")
        val assets = release.optJSONArray("assets") ?: JSONArray()
        fun asset(name: String) = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == name }

        val busAsset = asset(ASSET_NAME) ?: error("Release $tag neobsahuje asset $ASSET_NAME")
        val dir = dbFile.parentFile

        val busZip = File(dir, "gtfs.zip")
        log("Stahuji $ASSET_NAME (${busAsset.optLong("size") / 1048576} MB)…")
        fetchGtfsZip(busAsset.getString("browser_download_url"), busZip, log)

        // Vlaky jsou doplněk: když se nepovedou, appka zůstane s autobusy a příště to zkusí znovu.
        val railZip = File(dir, "gtfs_rail.zip")
        val railOk = asset(RAIL_ASSET_NAME)?.let { a ->
            runCatching {
                log("Stahuji vlaky ($RAIL_ASSET_NAME)…")
                fetchGtfsZip(a.getString("browser_download_url"), railZip, log)
                railZip.exists()
            }.getOrElse {
                Log.w(TAG, "vlaky se nepodařilo stáhnout: ${it.message}")
                railZip.delete()
                false
            }
        } ?: false.also { Log.w(TAG, "release $tag nemá $RAIL_ASSET_NAME") }

        val feeds = buildList {
            add(GtfsFeed(busZip, rail = false))
            if (railOk) add(GtfsFeed(railZip, rail = true))
        }
        try {
            GtfsImporter(target).importGtfs(feeds, tag, log, progress)
        } finally {
            busZip.delete()
            railZip.delete()
        }
    }

    /** Streamuje tar z GitHubu a vytáhne z něj gtfs.zip (zbytek, třeba parquet, se nestahuje). */
    private fun fetchGtfsZip(url: String, dest: File, log: (String) -> Unit) {
        open(url).use { resp ->
            val body = resp.body ?: error("Prázdná odpověď")
            log("Rozbaluji GTFS…")
            if (!TarUtils.streamGtfsZipToFile(body.byteStream(), dest) || !dest.exists()) {
                error("V souboru nebylo nalezeno gtfs.zip ($url)")
            }
        }
    }

    private fun open(url: String): Response {
        val resp = http.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        ).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            error("HTTP $code ($url)")
        }
        return resp
    }

    private companion object {
        const val MIN_DB_BYTES = 100_000L
        const val TAG = "GtfsImport"
    }
}