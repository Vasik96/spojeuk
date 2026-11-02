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
        log("Zjišťuji nejnovější build z GitHubu…")
        val release = JSONObject(open(RELEASES_API).use { it.body?.string().orEmpty() })
        val tag = release.optString("tag_name", "latest")
        val assets = release.optJSONArray("assets") ?: JSONArray()
        val asset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == ASSET_NAME }
            ?: error("Release $tag neobsahuje asset $ASSET_NAME")

        log("Stahuji $ASSET_NAME (${asset.optLong("size") / 1048576} MB)…")
        val zip = File(dbFile.parentFile, "gtfs.zip")
        open(asset.getString("browser_download_url")).use { resp ->
            val body = resp.body ?: error("Prázdná odpověď")
            log("Rozbaluji GTFS…")
            if (!TarUtils.streamGtfsZipToFile(body.byteStream(), zip) || !zip.exists()) {
                error("V souboru $ASSET_NAME nebylo nalezeno gtfs.zip")
            }
        }

        try {
            GtfsImporter(dbFile).importGtfs(zip, tag, log, progress)
        } finally {
            zip.delete()
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
