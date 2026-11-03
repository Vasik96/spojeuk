package com.vasik96.spojeuk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** GitHub API: poslední release s GTFS z CIS JŘ. */
const val TRAIN_RELEASE_API =
    "https://api.github.com/repos/tangero/jizdni-rady-czech-republic/releases/latest"

/**
 * Tabulka "číslo vlaku (tn ze SŽ) → linka (S1, R22, ...)".
 *
 * Soubory ve filesDir:
 *  - train_lines.json / train_lines.tag                aktivní tabulka + verze releasu
 *  - train_lines.pending.json / .pending.tag           stažená nová, nastoupí při dalším init()
 */
object TrainLines {
    private const val TAG = "TrainLines"
    private const val ACTIVE = "train_lines.json"
    private const val ACTIVE_TAG = "train_lines.tag"
    private const val PENDING = "train_lines.pending.json"
    private const val PENDING_TAG = "train_lines.pending.tag"
    private const val DEFAULT_CHECK_EVERY_DAYS = 1L

    @Volatile private var map: Map<String, String> = emptyMap()
    private val mutex = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ───────── veřejné API ─────────

    fun lineFor(trainNumber: String): String? = map[normKey(trainNumber)]
    val size: Int get() = map.size
    val isLoaded: Boolean get() = map.isNotEmpty()
    fun sample(n: Int = 10): List<Map.Entry<String, String>> = map.entries.take(n)

    /** Při startu: pending → active a načtení do paměti. Rychlé, bez sítě. */
    fun init(context: Context) {
        val dir = context.filesDir
        val active = File(dir, ACTIVE)
        val pending = File(dir, PENDING)

        if (pending.exists()) {
            val ok = runCatching {
                active.delete()
                val moved = pending.renameTo(active)
                File(dir, ACTIVE_TAG).delete()
                File(dir, PENDING_TAG).renameTo(File(dir, ACTIVE_TAG))
                moved
            }.getOrDefault(false)
            Log.i(TAG, if (ok) "pending → active" else "výměna pending selhala")
        }
        map = readFile(active)
        Log.i(TAG, "načteno ${map.size} vlaků")
    }

    /** Doplní chybějící čísla z autobusového/DÚK JŘ. GTFS má přednost. */
    fun populateFromTimetable(tt: Timetable) {
        val newMap = HashMap(map)
        var added = 0
        for (trip in tt.trips.values) {
            val num = normKey(trip.num)
            val line = trip.line.trim()
            if (num.isNotEmpty() && line.isNotEmpty() && line != "?" && !newMap.containsKey(num)) {
                newMap[num] = line
                added++
            }
        }
        map = newMap
        if (added > 0) Log.i(TAG, "Doplněno $added linek z JŘ (celkem ${map.size})")
    }

    /**
     * Doporučené volání při startu (na IO coroutině):
     *  - nic nemáme → stáhne a použije hned,
     *  - pending už čeká → nic,
     *  - aktivní starší než [checkEveryDays] → zeptá se GitHubu na nejnovější release;
     *    je-li novější, stáhne do pending, jinak jen obnoví čas kontroly.
     */
    suspend fun refreshIfStale(context: Context, checkEveryDays: Long = DEFAULT_CHECK_EVERY_DAYS) {
        val dir = context.filesDir
        val active = File(dir, ACTIVE)
        when {
            !active.exists() || map.isEmpty() -> downloadFresh(context)
            File(dir, PENDING).exists() -> Unit
            ageDays(active) >= checkEveryDays -> downloadPending(context, onlyIfNewer = true)
        }
    }

    /** Stáhne nejnovější release a použije ho OKAMŽITĚ. */
    suspend fun downloadFresh(context: Context): Boolean =
        build(context, File(context.filesDir, ACTIVE), File(context.filesDir, ACTIVE_TAG),
            onlyIfNewer = false) { map = it }

    /** Stáhne nejnovější release do pending (nastoupí při dalším init()). */
    suspend fun downloadPending(context: Context, onlyIfNewer: Boolean = false): Boolean =
        build(context, File(context.filesDir, PENDING), File(context.filesDir, PENDING_TAG),
            onlyIfNewer) { }

    fun discardPending(context: Context) {
        File(context.filesDir, PENDING).delete()
        File(context.filesDir, PENDING_TAG).delete()
    }

    // ───────── interní ─────────

    private class Release(val tag: String, val zipUrl: String)

    private suspend fun build(
        context: Context,
        target: File,
        targetTag: File,
        onlyIfNewer: Boolean,
        onDone: (Map<String, String>) -> Unit,
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val activeTag = File(context.filesDir, ACTIVE_TAG)
            val zipFile = File(context.cacheDir, "train_gtfs.zip")
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                val rel = latestRelease()

                if (onlyIfNewer && activeTag.exists() && activeTag.readText().trim() == rel.tag) {
                    Log.i(TAG, "release ${rel.tag} je aktuální, nestahuji")
                    File(context.filesDir, ACTIVE).setLastModified(System.currentTimeMillis())
                    return@withContext false
                }

                Log.i(TAG, "stahuji ${rel.tag}")
                download(rel.zipUrl, zipFile)
                val table = parseGtfs(zipFile)
                if (table.isEmpty()) error("GTFS neobsahuje žádné vlaky s linkou")

                tmp.writeText(JSONObject(table as Map<*, *>).toString())
                target.delete()
                if (!tmp.renameTo(target)) error("rename selhal")
                targetTag.writeText(rel.tag)

                onDone(table)
                Log.i(TAG, "uloženo ${table.size} vlaků (${rel.tag}) → ${target.name}")
                true
            } catch (e: Exception) {
                Log.w(TAG, "stažení/parsování selhalo: ${e.message}")
                tmp.delete()
                false   // stará data zůstávají nedotčená
            } finally {
                zipFile.delete()
            }
        }
    }

    /** Zeptá se GitHubu na latest release a vybere GTFS zip. */
    private fun latestRelease(): Release {
        val req = Request.Builder().url(TRAIN_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub API HTTP ${resp.code}")
            val o = JSONObject(resp.body?.string() ?: error("prázdná odpověď"))
            val tag = o.getString("tag_name")
            val assets = o.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val n = a.getString("name")
                if (n.startsWith("gtfs") && n.endsWith(".zip")) {
                    url = a.getString("browser_download_url"); break
                }
            }
            return Release(tag, url ?: error("release $tag nemá gtfs*.zip"))
        }
    }

    private fun download(url: String, dest: File) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("prázdná odpověď")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    /** routes.txt + trips.txt po řádcích, v paměti jen vlakové linky. */
    private fun parseGtfs(zipFile: File): Map<String, String> {
        ZipFile(zipFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val routesName = names.filter { it.endsWith("routes.txt") }
                .let { l -> l.firstOrNull { "merged" in it } ?: l.firstOrNull() }
                ?: error("chybí routes.txt (v zipu: ${names.take(10)})")
            val prefix = routesName.removeSuffix("routes.txt")
            val tripsName = prefix + "trips.txt"
            val tripsEntry = zip.getEntry(tripsName) ?: error("chybí $tripsName")
            val routesEntry = zip.getEntry(routesName)!!

            // 1) route_id → linka, jen vlaky a jen označení s číslicí (S1, R22)
            val routeLine = HashMap<String, String>()
            zip.getInputStream(routesEntry).bufferedReader().use { r ->
                val h = headerOf(r.readLine() ?: return emptyMap())
                val iId = h["route_id"] ?: error("route_id")
                val iShort = h["route_short_name"] ?: error("route_short_name")
                val iType = h["route_type"] ?: error("route_type")
                for (line in r.lineSequence()) {
                    if (line.isBlank()) continue
                    val c = csv(line)
                    val type = c.getOrNull(iType)?.trim()?.toIntOrNull() ?: continue
                    if (type != 2 && type !in 100..199) continue
                    val short = c.getOrNull(iShort)?.trim().orEmpty()
                    if (short.none { it.isDigit() }) continue
                    routeLine[c.getOrNull(iId)?.trim() ?: continue] = short
                }
            }
            Log.i(TAG, "vlakových linek s označením: ${routeLine.size}")
            if (routeLine.isEmpty()) return emptyMap()

            // 2) trips: číslo vlaku → nejčastější linka
            val votes = HashMap<String, HashMap<String, Int>>()
            zip.getInputStream(tripsEntry).bufferedReader().use { r ->
                val h = headerOf(r.readLine() ?: return emptyMap())
                val iRoute = h["route_id"] ?: error("route_id")
                val iNum = h["trip_short_name"]
                val iTripId = h["trip_id"]
                for (line in r.lineSequence()) {
                    if (line.isBlank()) continue
                    val c = csv(line)
                    val rl = routeLine[c.getOrNull(iRoute)?.trim()] ?: continue
                    val raw = (iNum?.let { c.getOrNull(it) }?.trim().orEmpty())
                        .ifEmpty { iTripId?.let { c.getOrNull(it) }?.trim().orEmpty() }
                    val num = normKey(raw)
                    if (num.isEmpty()) continue
                    val m = votes.getOrPut(num) { HashMap() }
                    m[rl] = (m[rl] ?: 0) + 1
                }
            }
            return votes.mapValues { (_, m) -> m.maxByOrNull { it.value }!!.key }
        }
    }

    /** "R 1193" / "01193" / "1193/1" → "1193" */
    private fun normKey(raw: String): String =
        raw.trim().takeWhile { it != '/' }.filter { it.isDigit() }.trimStart('0')

    private fun headerOf(line: String): Map<String, Int> =
        csv(line.removePrefix("\uFEFF")).withIndex().associate { it.value.trim() to it.index }

    private fun csv(line: String): List<String> {
        val out = ArrayList<String>(16)
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQ && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQ = !inQ
                ch == ',' && !inQ -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun readFile(f: File): Map<String, String> {
        if (!f.exists()) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun ageDays(f: File): Long =
        (System.currentTimeMillis() - f.lastModified()) / (24 * 60 * 60 * 1000L)
}