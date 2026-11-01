package com.vasik96.spojeuk

import android.content.Context
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
        val dir = File(context?.filesDir ?: File("/tmp"), "duk-direct")
        dir.mkdirs()
        File(dir, "static.sqlite")
    }

    @Volatile
    var current: TimetableBundle? = null
        private set

    private val loadLock = Mutex()

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
                if (isFirstRun()) {
                    log("Nahrávám výchozí jízdní řády…")
                    if (!copyBundledDb()) downloadAndImport(log, progress)
                }
                install(Timetable(dbFile)).also { onLoaded(it.timetable) }
            }
        }

    suspend fun update(log: (String) -> Unit = {}, progress: (Float) -> Unit = {}): TimetableBundle =
        withContext(Dispatchers.IO) {
            downloadAndImport(log, progress)
            install(Timetable(dbFile))
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

    private fun downloadAndImport(log: (String) -> Unit, progress: (Float) -> Unit) {
        log("Zjišťuji nejnovější build z GitHubu…")
        val release = JSONObject(open(RELEASES_API).use { it.body?.string().orEmpty() })
        val tag = release.optString("tag_name", "latest")
        val assets = release.optJSONArray("assets") ?: JSONArray()
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
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
        val resp = http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            error("HTTP $code ($url)")
        }
        return resp
    }

    private companion object {
        const val MIN_DB_BYTES = 100_000L
    }
}
