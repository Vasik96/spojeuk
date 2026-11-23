package com.vasik96.spojeuk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

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

    /** Dokončí se ve chvíli, kdy je jízdní řád načtený; kdo na něj čeká, nemusí dotazovat dokola (viz [awaitCurrent]). */
    private val ready = CompletableDeferred<TimetableBundle>()

    /**
     * Počká na načtený jízdní řád, ale sám ho nespouští ani nestahuje (jako [ensure]). Bez zpoždění, jakmile je hotový.
     */
    suspend fun awaitCurrent(): TimetableBundle = current ?: ready.await()

    private val loadLock = Mutex()
    private val importLock = Mutex()

    val pendingFile: File by lazy { File(dbFile.parentFile, "static.sqlite.new") }
    fun hasPending() = pendingFile.exists()

    fun isFirstRun(): Boolean = !dbFile.exists() || dbFile.length() < MIN_DB_BYTES

    /**
     * Zastávky přímo z DB bez načtení celého jízdního řádu, nebo null, když to nejde
     * (první spuštění, čeká nová DB na výměnu – ta se vymění až v [ensure]).
     */
    fun quickStops(): List<Stop>? {
        if (isFirstRun() || hasPending()) return null
        return runCatching { Timetable.readStops(dbFile) }
            .onFailure { Log.w(TAG, "rychlé načtení zastávek selhalo: ${it.message}") }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

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
                    log(tr(R.string.dl_default_tt))
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
        ready.complete(bundle)
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
        log(tr(R.string.dl_checking_latest))
        val remote = JSONObject(open("$REGION_BASE_URL/gtfs.json").use { it.body?.string().orEmpty() })
        val tag = remote.optString("tag").ifBlank { return false }
        val stagedTag = if (pendingFile.exists()) runCatching { Timetable.readMeta(pendingFile)["tag"] }.getOrNull() else null
        if (target == pendingFile && (meta()["tag"] == tag || stagedTag == tag)) { log(tr(R.string.dl_up_to_date)); return true }

        // nejrychlejší: hotová databáze postavená v GitHub Actions (stačí stáhnout a rozbalit, žádný import)
        val prebuilt = remote.optJSONObject("db")
        if (prebuilt != null && prebuilt.optInt("schema") == PREBUILT_SCHEMA) {
            if (runCatching { downloadPrebuilt(prebuilt, tag, target, log, progress) }
                    .onFailure { Log.w(TAG, "hotová DB selhala, importuji GTFS: ${it.message}") }
                    .getOrDefault(false)) return true
            log(tr(R.string.dl_prebuilt_failed))
            progress(0f)
        }

        val dir = dbFile.parentFile
        val names = listOf("jdf-region.zip" to false, "czptt-region.zip" to true)

        // Oba zipy se stahují souběžně a import autobusů začne, jakmile je stažený jejich zip
        // (vlaky se mezitím dostahují). Celkový postup: 20 % stahování, 80 % import.
        val total = java.util.concurrent.atomic.AtomicLongArray(names.size)
        val done = java.util.concurrent.atomic.AtomicLongArray(names.size)
        val importMilli = java.util.concurrent.atomic.AtomicInteger(0)
        val lastReported = java.util.concurrent.atomic.AtomicInteger(-1)
        fun report() {
            var t = 0L; var d = 0L
            for (i in names.indices) { t += total[i]; d += done[i] }
            val dl = if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else 0f
            val p = 0.2f * dl + 0.8f * importMilli.get() / 1000f
            val pct = (p * 1000).toInt()
            val prev = lastReported.get()
            if (pct > prev && lastReported.compareAndSet(prev, pct)) progress(p)
        }

        val pool = java.util.concurrent.Executors.newFixedThreadPool(names.size)
        val feeds = names.mapIndexed { i, (name, rail) ->
            val f = File(dir, if (rail) "gtfs_rail.zip" else "gtfs.zip")
            val job = pool.submit {
                open("$REGION_BASE_URL/$name").use { resp ->
                    val body = resp.body ?: error("Prázdná odpověď")
                    total.set(i, body.contentLength().coerceAtLeast(0L))
                    log(tr(R.string.dl_downloading_file, name, (body.contentLength().coerceAtLeast(0L) / 1048576).toInt()))
                    body.byteStream().use { input ->
                        FileOutputStream(f).use { out ->
                            val buf = ByteArray(1 shl 17)
                            while (true) {
                                val r = input.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                done.addAndGet(i, r.toLong())
                                report()
                            }
                        }
                    }
                }
            }
            GtfsFeed(f, rail) {
                try {
                    job.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
        }
        try {
            GtfsImporter(target).importGtfs(feeds, tag, log) { importMilli.set((it * 1000).toInt()); report() }
        } finally {
            pool.shutdownNow()
            feeds.forEach { it.zip.delete() }
        }
        return true
    }

    /**
     * Stáhne hotovou `static.sqlite` (gzip) z releasu a za letu ji rozbalí do [target]. Kontroluje sha256 staženého
     * souboru i obsah (tag a počet spojů), jinak vyhodí výjimku a volající použije import ze zipů.
     */
    private fun downloadPrebuilt(
        info: JSONObject,
        tag: String,
        target: File,
        log: (String) -> Unit,
        progress: (Float) -> Unit,
    ): Boolean {
        val name = info.optString("name").ifBlank { return false }
        val expectedSha = info.optString("sha256")
        val expectedTrips = info.optInt("trips")
        val tmp = File(target.parentFile, target.name + ".dl")
        tmp.delete()
        try {
            val md = MessageDigest.getInstance("SHA-256")
            open("$REGION_BASE_URL/$name").use { resp ->
                val body = resp.body ?: error("Prázdná odpověď")
                val total = body.contentLength().takeIf { it > 0 } ?: info.optLong("size", -1L)
                val totalMb = if (total > 0) "%.1f".format(total / 1048576.0) else "?"
                var read = 0L
                var lastMb = -1L
                // počítadlo bajtů ze sítě (komprimovaných), pro postup a hlášku
                val counting = object : java.io.FilterInputStream(body.byteStream()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            read += n
                            val mb = read / (1 shl 20)
                            if (mb != lastMb) {
                                lastMb = mb
                                log(tr(R.string.dl_downloading_tt_progress, mb.toInt(), totalMb))
                                if (total > 0) progress(0.95f * (read.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                        return n
                    }
                }
                GZIPInputStream(DigestInputStream(counting, md), 1 shl 16).use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out, 1 shl 17) }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha.isNotEmpty() && sha != expectedSha) error("sha256 nesedí (nahrává se nová verze?)")

            log(tr(R.string.dl_checking_db))
            progress(0.97f)
            val trips = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                val m = HashMap<String, String>()
                d.rawQuery("SELECT k, v FROM meta", null).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getString(1) }
                val trips = d.rawQuery("SELECT count(*) FROM trip", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                if (m["tag"] != tag) error("DB má jinou verzi (${m["tag"]} místo $tag)")
                if (trips <= 0 || (expectedTrips > 0 && trips != expectedTrips) || m["trips"] != trips.toString()) {
                    error("DB je neúplná ($trips spojů)")
                }
                trips
            }
            val bytes = tmp.length()

            // rename přepíše soubor atomicky; stará DB, kterou ještě někdo čte, nezmizí uprostřed
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) error("nelze přesunout staženou DB")
            }
            progress(1f)
            log(trPlural(R.plurals.dl_downloaded_trips, trips, trips))
            Log.i(TAG, "hotová DB $tag stažena ($bytes B, $trips spojů)")
            return true
        } finally {
            tmp.delete()   // po úspěšném přesunu už neexistuje
        }
    }

    private fun downloadFromObehy(target: File, log: (String) -> Unit, progress: (Float) -> Unit) {
        log(tr(R.string.dl_checking_github))
        val release = JSONObject(open(RELEASES_API).use { it.body?.string().orEmpty() })
        val tag = release.optString("tag_name", "latest")
        val assets = release.optJSONArray("assets") ?: JSONArray()
        fun asset(name: String) = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == name }

        val busAsset = asset(ASSET_NAME) ?: error("Release $tag neobsahuje asset $ASSET_NAME")
        val dir = dbFile.parentFile

        val busZip = File(dir, "gtfs.zip")
        log(tr(R.string.dl_downloading_file, ASSET_NAME, (busAsset.optLong("size") / 1048576).toInt()))
        fetchGtfsZip(busAsset.getString("browser_download_url"), busZip, log)

        // Vlaky jsou doplněk: když se nepovedou, appka zůstane s autobusy a příště to zkusí znovu.
        val railZip = File(dir, "gtfs_rail.zip")
        val railOk = asset(RAIL_ASSET_NAME)?.let { a ->
            runCatching {
                log(tr(R.string.dl_downloading_trains, RAIL_ASSET_NAME))
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
            log(tr(R.string.dl_unpacking))
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
        /** Verze formátu hotové DB z GitHubu (tools/build_timetable_db.py SCHEMA); jiná => import ze zipů. */
        const val PREBUILT_SCHEMA = 1
        const val TAG = "GtfsImport"
    }
}