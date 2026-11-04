package com.vasik96.spojeuk

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

// Import statických jízdních řádů: stažený tar → gtfs.zip → SQLite (static.sqlite).
// Konstanty (BBOX_LAT, ...) jsou v Config.kt, pomocné funkce (intArrayToBytes, nowPrague) v Util.kt.
// parseTsec už se nepoužívá, časy se parsují přímo z bajtů (parseTimeSec níže).
// Párování vlakových stanic s autobusovými zastávkami je v StationPairing.kt.

// ───────────────────────── Tar ─────────────────────────

object TarUtils {
    /** Streamuje gtfs.zip z tarInputStream přímo do souboru na disku */
    fun streamGtfsZipToFile(tarInputStream: InputStream, destZipFile: File): Boolean {
        destZipFile.delete()
        val buffer = ByteArray(32768)
        val bis = BufferedInputStream(tarInputStream, 32768)
        val copyBuf = ByteArray(32768)

        while (true) {
            var read = 0
            while (read < 512) {
                val r = bis.read(buffer, read, 512 - read)
                if (r < 0) return false
                read += r
            }
            if (buffer.all { it == 0.toByte() }) return false

            val fileName = String(buffer, 0, 100, Charsets.US_ASCII).trimEnd('\u0000', ' ')
            val sizeOctal = String(buffer, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ')
            val size = sizeOctal.toLongOrNull(8) ?: 0L
            val padding = ((512 - (size % 512)) % 512).toInt()

            if (fileName.endsWith("gtfs.zip")) {
                FileOutputStream(destZipFile).use { fos ->
                    var remaining = size
                    while (remaining > 0) {
                        val toRead = minOf(remaining, copyBuf.size.toLong()).toInt()
                        val r = bis.read(copyBuf, 0, toRead)
                        if (r < 0) break
                        fos.write(copyBuf, 0, r)
                        remaining -= r
                    }
                }
                return true
            } else {
                var remaining = size + padding
                while (remaining > 0) {
                    val skip = bis.skip(remaining)
                    if (skip <= 0) {
                        val r = bis.read(copyBuf, 0, minOf(remaining, copyBuf.size.toLong()).toInt())
                        if (r < 0) break
                        remaining -= r
                    } else {
                        remaining -= skip
                    }
                }
            }
        }
    }
}

// ───────────────────────── Rychlé CSV (bez alokací na řádek) ─────────────────────────

private const val NL: Byte = 10
private const val CR: Byte = 13
private const val COMMA: Byte = 44
private const val QUOTE: Byte = 34

private val WS = Regex("\\s+")

/** Soubory z GTFS, které opravdu potřebujeme (shapes.txt apod. se vůbec nerozbalují). */
private val NEEDED_FILES = setOf(
    "stops.txt", "agency.txt", "routes.txt", "trips.txt",
    "stop_times.txt", "calendar.txt", "calendar_dates.txt"
)

/** Čte soubor po řádcích přímo z bajtů; žádný String na řádek. */
private class ByteLineReader(file: File, bufSize: Int = 1 shl 18) : Closeable {
    private val input = FileInputStream(file)
    var buf = ByteArray(bufSize)
        private set
    private var pos = 0
    private var end = 0
    private var eof = false
    var lineStart = 0
        private set
    var lineEnd = 0
        private set
    var bytesRead = 0L
        private set

    fun next(): Boolean {
        while (true) {
            var i = pos
            while (i < end && buf[i] != NL) i++
            if (i < end) {
                lineStart = pos
                lineEnd = if (i > pos && buf[i - 1] == CR) i - 1 else i
                pos = i + 1
                return true
            }
            if (eof) {
                if (pos < end) {
                    lineStart = pos
                    lineEnd = if (end > pos && buf[end - 1] == CR) end - 1 else end
                    pos = end
                    return true
                }
                return false
            }
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, end - pos)
                end -= pos
                pos = 0
            } else if (end == buf.size) {
                buf = buf.copyOf(buf.size * 2)
            }
            val r = input.read(buf, end, buf.size - end)
            if (r < 0) eof = true else { end += r; bytesRead += r }
        }
    }

    override fun close() = input.close()
}

/**
 * Najde požadované sloupce podle hlavičky a pro každý řádek jen vyplní offsety (fs/fe) do bufferu.
 * Řetězce se vytvářejí jen na požádání přes [str].
 */
private class CsvScanner(file: File, private val wanted: Array<String>) : Closeable {
    private val rd = ByteLineReader(file)
    private val idx = IntArray(wanted.size) { -1 }
    private var maxCol = -1
    private var cs = IntArray(0)
    private var ce = IntArray(0)
    private var valid = false
    val fs = IntArray(wanted.size)
    val fe = IntArray(wanted.size)

    val buf: ByteArray get() = rd.buf
    val bytesRead: Long get() = rd.bytesRead

    init {
        if (rd.next()) {
            var header = String(rd.buf, rd.lineStart, rd.lineEnd - rd.lineStart, Charsets.UTF_8)
            if (header.startsWith("\uFEFF")) header = header.substring(1)
            val names = header.split(',').map { it.trim().trim('"') }
            for (k in wanted.indices) idx[k] = names.indexOf(wanted[k])
            maxCol = idx.maxOrNull() ?: -1
            cs = IntArray(maxCol + 1)
            ce = IntArray(maxCol + 1)
            valid = maxCol >= 0
        }
    }

    fun has(k: Int) = idx[k] >= 0

    fun nextRow(): Boolean {
        if (!valid) return false
        while (true) {
            if (!rd.next()) return false
            val ls = rd.lineStart
            val le = rd.lineEnd
            if (le <= ls) continue
            val b = rd.buf

            var p = ls
            var col = 0
            while (col <= maxCol && p <= le) {
                if (p < le && b[p] == QUOTE) {
                    val s = p + 1
                    var q = s
                    while (q < le) {
                        if (b[q] == QUOTE) {
                            if (q + 1 < le && b[q + 1] == QUOTE) q += 2 else break
                        } else q++
                    }
                    cs[col] = s; ce[col] = q
                    var c = q + 1
                    while (c < le && b[c] != COMMA) c++
                    p = c + 1
                } else {
                    var c = p
                    while (c < le && b[c] != COMMA) c++
                    cs[col] = p; ce[col] = c
                    p = c + 1
                }
                col++
            }
            while (col <= maxCol) { cs[col] = 0; ce[col] = 0; col++ }

            for (k in wanted.indices) {
                val i = idx[k]
                if (i < 0) { fs[k] = 0; fe[k] = 0 } else { fs[k] = cs[i]; fe[k] = ce[i] }
            }
            return true
        }
    }

    fun str(k: Int): String {
        val s = fs[k]
        val e = fe[k]
        if (e <= s) return ""
        val r = String(rd.buf, s, e - s, Charsets.UTF_8)
        return if (r.indexOf('"') >= 0) r.replace("\"\"", "\"") else r
    }

    fun strOr(k: Int, default: String): String = if (has(k)) str(k) else default

    override fun close() = rd.close()
}

private inline fun readCsv(file: File, wanted: Array<String>, block: (CsvScanner) -> Unit) {
    if (!file.exists()) return
    CsvScanner(file, wanted).use { sc ->
        while (sc.nextRow()) block(sc)
    }
}

/** "HH:MM:SS" (HH i >24) → sekundy; -1 když chybí/je neplatné. */
private fun parseTimeSec(b: ByteArray, s: Int, e: Int): Int {
    var total = 0
    var cur = 0
    var digits = 0
    var colons = 0
    var i = s
    while (i < e) {
        val c = b[i].toInt()
        when {
            c in 48..57 -> { cur = cur * 10 + (c - 48); digits++ }
            c == 58 -> {
                if (digits == 0) return -1
                total = total * 60 + cur
                cur = 0; digits = 0; colons++
            }
            c == 32 -> {}
            else -> return -1
        }
        i++
    }
    if (digits == 0 || colons == 0) return -1
    total = total * 60 + cur
    return if (colons == 1) total * 60 else total
}

private fun parseIntBytes(b: ByteArray, s: Int, e: Int): Int {
    var i = s
    var neg = false
    if (i < e && b[i] == 45.toByte()) { neg = true; i++ }
    var r = 0
    while (i < e) {
        val d = b[i] - 48
        if (d < 0 || d > 9) return 0
        r = r * 10 + d
        i++
    }
    return if (neg) -r else r
}

/** Growable primitive buffery pro jeden trip (žádný boxing, žádný objekt na řádek). */
private class TripBuffer {
    var n = 0
    var hasRegion = false
    var code = IntArray(64)
    var seq = IntArray(64)
    var arr = IntArray(64)
    var dep = IntArray(64)

    fun add(c: Int, s: Int, a: Int, d: Int) {
        if (n == code.size) {
            val m = n * 2
            code = code.copyOf(m); seq = seq.copyOf(m); arr = arr.copyOf(m); dep = dep.copyOf(m)
        }
        code[n] = c; seq[n] = s; arr[n] = a; dep[n] = d
        n++
    }

    fun clear() { n = 0; hasRegion = false }
}

/** Mapuje stop_id → malé int "code" (pro rychlé porovnávání) a code → ID v DB. */
private class StopInterner(private val region: Set<String>, expected: Int) {
    private val map = HashMap<String, Int>(expected * 2 + 16)
    val names = ArrayList<String>()
    var isRegion = BooleanArray(expected + 16)
        private set
    private var dbId = IntArray(expected + 16)
    val order = ArrayList<Int>()      // pořadí přidělení DB ID (dbId = index + 1)

    fun code(name: String): Int {
        val existing = map[name]
        if (existing != null) return existing
        val c = names.size
        names.add(name)
        map[name] = c
        if (c >= isRegion.size) {
            isRegion = isRegion.copyOf(c * 2)
            dbId = dbId.copyOf(c * 2)
        }
        isRegion[c] = name in region
        return c
    }

    fun dbIdFor(code: Int): Int {
        var id = dbId[code]
        if (id == 0) {
            order.add(code)
            id = order.size
            dbId[code] = id
        }
        return id
    }
}

// ───────────────────────── GTFS → SQLite ─────────────────────────

/** Jeden GTFS zip k importu. [rail] = vlaky z CZPTT: širší oblast a číslo vlaku místo ČIS čísla. */
class GtfsFeed(val zip: File, val rail: Boolean = false)

class GtfsImporter(private val dbFile: File) {

    /** Rozbalí jen potřebné soubory (ZipFile = náhodný přístup, shapes.txt se vůbec nečte). */
    private fun unzipToDirectory(zipFile: File, destDir: File) {
        destDir.deleteRecursively()
        destDir.mkdirs()
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (name !in NEEDED_FILES) continue
                zf.getInputStream(entry).use { ins ->
                    FileOutputStream(File(destDir, name)).use { out -> ins.copyTo(out, 65536) }
                }
            }
        }
    }

    fun importGtfs(
        gtfsZipFile: File,
        tag: String,
        log: (String) -> Unit,
        progress: (Float) -> Unit
    ) = importGtfs(listOf(GtfsFeed(gtfsZipFile)), tag, log, progress)

    /** Importuje víc feedů (autobusy + vlaky) do jedné databáze. ID v nich jsou prefixovaná, takže se nepletou. */
    fun importGtfs(
        feeds: List<GtfsFeed>,
        tag: String,
        log: (String) -> Unit,
        progress: (Float) -> Unit
    ) {
        require(feeds.isNotEmpty())
        val tempDir = File(dbFile.parentFile, "gtfs_temp")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        try {
            data class StationData(val name: String, val lat: Double?, val lon: Double?)
            data class PostData(val parent: String, val name: String, val lat: Double?, val lon: Double?)

            // sdílené mezi feedy
            val stations = HashMap<String, StationData>()
            val posts = HashMap<String, PostData>()
            val region = HashSet<String>()
            val railIds = HashSet<String>()   // stop_id ze vlakových feedů (pro párování nádraží)
            val svcIndexMap = HashMap<String, Int>()
            val stops = StopInterner(region, 200_000)
            var countTrips = 0

            fun coords(g: String): Pair<Double?, Double?> {
                val p = posts[g] ?: return null to null
                if (p.lat != null && p.lon != null) return p.lat to p.lon
                val parent = stations[p.parent]
                return (parent?.lat) to (parent?.lon)
            }

            val tmpDb = File(dbFile.parentFile, dbFile.name + ".tmp")
            tmpDb.delete()

            val db = SQLiteDatabase.openOrCreateDatabase(tmpDb, null)
            db.rawQuery("PRAGMA synchronous = OFF;", null).close()
            db.rawQuery("PRAGMA journal_mode = MEMORY;", null).close()
            db.rawQuery("PRAGMA temp_store = MEMORY;", null).close()

            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);")
                db.execSQL("CREATE TABLE stop(id INTEGER PRIMARY KEY, name TEXT, place TEXT, lat REAL, lon REAL, inreg INTEGER);")
                db.execSQL("CREATE TABLE trip(id INTEGER PRIMARY KEY, line TEXT, agency TEXT, cis TEXT, num TEXT, head TEXT, svc INTEGER, wheel INTEGER, t0 INTEGER, t1 INTEGER, gid TEXT DEFAULT '');")
                db.execSQL("CREATE TABLE trip_times(trip INTEGER PRIMARY KEY, stops BLOB, arr BLOB, dep BLOB);")
                db.execSQL("CREATE TABLE svc(id INTEGER PRIMARY KEY, dow TEXT, d0 TEXT, d1 TEXT);")
                db.execSQL("CREATE TABLE svc_date(svc INTEGER, d TEXT, type INTEGER);")

                val stmtTrip = db.compileStatement("INSERT INTO trip VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                val stmtTimes = db.compileStatement("INSERT INTO trip_times VALUES(?,?,?,?)")
                val stmtSvc = db.compileStatement("INSERT INTO svc VALUES(?,?,?,?)")
                val stmtSvcDate = db.compileStatement("INSERT INTO svc_date VALUES(?,?,?)")

                for ((fi, feed) in feeds.withIndex()) {
                    val rail = feed.rail
                    val latR = if (rail) RAIL_BBOX_LAT else BBOX_LAT
                    val lonR = if (rail) RAIL_BBOX_LON else BBOX_LON
                    // postup jednoho feedu (0..1) → celkový
                    fun prog(p: Float) = progress(((fi + p) / feeds.size) * 0.95f)

                    log(if (rail) "Rozbaluji GTFS vlaků…" else "Rozbaluji GTFS…")
                    prog(0.05f)
                    unzipToDirectory(feed.zip, tempDir)
                    feed.zip.delete()   // zip už nepotřebujeme, šetří místo

                    log(if (rail) "Čtu zastávky (vlaky)…" else "Čtu zastávky…")
                    prog(0.1f)

                    readCsv(
                        File(tempDir, "stops.txt"),
                        arrayOf("stop_id", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station")
                    ) { sc ->
                        val stopId = sc.str(0)
                        if (stopId.isEmpty()) return@readCsv
                        if (rail) railIds.add(stopId)
                        val name = sc.str(1)
                        val lat = sc.str(2).toDoubleOrNull()
                        val lon = sc.str(3).toDoubleOrNull()
                        val locType = sc.strOr(4, "0")
                        val parent = sc.str(5)

                        if (locType == "1") {
                            stations[stopId] = StationData(name, lat, lon)
                        } else {
                            posts[stopId] = PostData(parent, name, lat, lon)
                        }
                    }

                    // zastávky tohoto feedu v oblasti (musí být naplněno dřív, než se čtou stop_times)
                    for (g in posts.keys) {
                        if (g in region) continue
                        val (la, lo) = coords(g)
                        if (la != null && lo != null && la in latR && lo in lonR) region.add(g)
                    }
                    log("Zastávek v oblasti: ${region.size}")
                    prog(0.2f)

                    val agencies = HashMap<String, String>()
                    readCsv(File(tempDir, "agency.txt"), arrayOf("agency_id", "agency_name")) { sc ->
                        agencies[sc.str(0)] = sc.str(1)
                    }

                    data class RouteData(val shortName: String, val agency: String)
                    val routes = HashMap<String, RouteData>()
                    readCsv(File(tempDir, "routes.txt"), arrayOf("route_id", "route_short_name", "agency_id")) { sc ->
                        val id = sc.str(0)
                        if (id.isEmpty()) return@readCsv
                        routes[id] = RouteData(sc.strOr(1, "?"), agencies[sc.str(2)] ?: "")
                    }

                    data class TripMeta(
                        val routeId: String,
                        val serviceId: String,
                        val headsign: String,
                        val shortName: String,
                        val wheel: Int
                    )
                    val tripsMeta = HashMap<String, TripMeta>()
                    readCsv(
                        File(tempDir, "trips.txt"),
                        arrayOf("trip_id", "route_id", "service_id", "trip_headsign", "trip_short_name", "wheelchair_accessible")
                    ) { sc ->
                        val tripId = sc.str(0)
                        if (tripId.isEmpty()) return@readCsv
                        tripsMeta[tripId] = TripMeta(
                            routeId = sc.str(1),
                            serviceId = sc.str(2),
                            headsign = sc.str(3),
                            shortName = sc.str(4),
                            wheel = sc.str(5).toIntOrNull() ?: 0
                        )
                    }

                    log("Zpracovávám jízdní řády…")
                    prog(0.4f)

                    /** Uloží trip, pokud projíždí regionem. Vrací true při uložení. */
                    fun processTrip(tripId: String, meta: TripMeta, tb: TripBuffer): Boolean {
                        val n = tb.n
                        if (!tb.hasRegion || n < 2) return false

                        // většinou už je seřazeno podle stop_sequence → řazení jen když je potřeba
                        var sorted = true
                        for (i in 1 until n) if (tb.seq[i] < tb.seq[i - 1]) { sorted = false; break }
                        val order: IntArray? =
                            if (sorted) null else (0 until n).sortedBy { tb.seq[it] }.toIntArray()

                        var m = 0
                        for (i in 0 until n) if (tb.arr[i] >= 0 || tb.dep[i] >= 0) m++
                        if (m < 2) return false

                        val st = IntArray(m)
                        val ar = IntArray(m)
                        val de = IntArray(m)
                        var j = 0
                        for (k in 0 until n) {
                            val i = order?.get(k) ?: k
                            val a = tb.arr[i]
                            val d = tb.dep[i]
                            if (a < 0 && d < 0) continue
                            st[j] = stops.dbIdFor(tb.code[i])
                            ar[j] = if (a >= 0) a else d
                            de[j] = if (d >= 0) d else a
                            j++
                        }

                        val tid = countTrips + 1

                        val cis: String
                        val num: String
                        if (rail) {
                            // trip_short_name = "R 1193" → číslo vlaku "1193"; ČIS číslo vlaky nemají
                            cis = ""
                            num = meta.shortName.substringAfterLast(' ').filter { it.isDigit() }.trimStart('0')
                        } else {
                            val sn = meta.shortName.split(WS)
                            if (sn.size == 2 && sn[0].all { it.isDigit() }) {
                                cis = sn[0].padStart(6, '0')
                                num = sn[1]
                            } else {
                                val p = tripId.split(":")
                                if (p.size >= 5) {
                                    cis = p[2].padStart(6, '0')
                                    num = p.last()
                                } else {
                                    cis = ""
                                    num = ""
                                }
                            }
                        }

                        val route = routes[meta.routeId] ?: RouteData("?", "")
                        val svcId = svcIndexMap.getOrPut(meta.serviceId) { svcIndexMap.size + 1 }

                        stmtTrip.bindLong(1, tid.toLong())
                        stmtTrip.bindString(2, route.shortName)
                        stmtTrip.bindString(3, route.agency)
                        stmtTrip.bindString(4, cis)
                        stmtTrip.bindString(5, num)
                        stmtTrip.bindString(6, meta.headsign)
                        stmtTrip.bindLong(7, svcId.toLong())
                        stmtTrip.bindLong(8, meta.wheel.toLong())
                        stmtTrip.bindLong(9, de[0].toLong())
                        stmtTrip.bindLong(10, ar[m - 1].toLong())
                        stmtTrip.bindString(11, tripId)
                        stmtTrip.executeInsert()

                        stmtTimes.bindLong(1, tid.toLong())
                        stmtTimes.bindBlob(2, intArrayToBytes(st))
                        stmtTimes.bindBlob(3, intArrayToBytes(ar))
                        stmtTimes.bindBlob(4, intArrayToBytes(de))
                        stmtTimes.executeInsert()

                        return true
                    }

                    // ───── stop_times.txt: hlavní úzké hrdlo, jeden průchod nad bajty ─────
                    val stopTimesFile = File(tempDir, "stop_times.txt")
                    if (stopTimesFile.exists()) {
                        val total = stopTimesFile.length().coerceAtLeast(1L).toFloat()
                        val sc = CsvScanner(
                            stopTimesFile,
                            arrayOf("trip_id", "stop_id", "stop_sequence", "arrival_time", "departure_time")
                        )
                        sc.use {
                            val tb = TripBuffer()

                            var curBuf = ByteArray(128)
                            var curLen = -1
                            var curId = ""
                            var curMeta: TripMeta? = null
                            var rows = 0
                            var lastReported = 0.4f

                            while (sc.nextRow()) {
                                val b = sc.buf
                                val ts = sc.fs[0]
                                val tl = sc.fe[0] - ts
                                val ss = sc.fs[1]
                                val sl = sc.fe[1] - ss
                                if (tl <= 0 || sl <= 0) continue

                                // stejný trip jako minule? porovnání bajtů bez alokace
                                var same = tl == curLen
                                if (same) {
                                    var i = 0
                                    while (i < tl) {
                                        if (b[ts + i] != curBuf[i]) { same = false; break }
                                        i++
                                    }
                                }
                                if (!same) {
                                    val prev = curMeta
                                    if (prev != null && processTrip(curId, prev, tb)) countTrips++
                                    tb.clear()
                                    if (tl > curBuf.size) curBuf = ByteArray(tl * 2)
                                    System.arraycopy(b, ts, curBuf, 0, tl)
                                    curLen = tl
                                    curId = String(b, ts, tl, Charsets.UTF_8)
                                    curMeta = tripsMeta[curId]   // 1 lookup na trip, ne na řádek
                                }
                                if (curMeta == null) continue

                                val code = stops.code(String(b, ss, sl, Charsets.UTF_8))
                                if (stops.isRegion[code]) tb.hasRegion = true
                                tb.add(
                                    code,
                                    parseIntBytes(b, sc.fs[2], sc.fe[2]),
                                    parseTimeSec(b, sc.fs[3], sc.fe[3]),
                                    parseTimeSec(b, sc.fs[4], sc.fe[4])
                                )

                                if ((++rows and 0x7FFF) == 0) {
                                    val p = 0.4f + 0.45f * (sc.bytesRead / total)
                                    if (p - lastReported >= 0.01f) {
                                        lastReported = p
                                        prog(p)
                                    }
                                }
                            }

                            val last = curMeta
                            if (last != null && processTrip(curId, last, tb)) countTrips++
                        }
                    }
                    prog(0.9f)

                    // ───── kalendář tohoto feedu (svcIndexMap už obsahuje jen služby uložených spojů) ─────
                    readCsv(
                        File(tempDir, "calendar.txt"),
                        arrayOf(
                            "service_id", "monday", "tuesday", "wednesday", "thursday",
                            "friday", "saturday", "sunday", "start_date", "end_date"
                        )
                    ) { sc ->
                        val sid = svcIndexMap[sc.str(0)] ?: return@readCsv
                        val dow = StringBuilder(7)
                        for (k in 1..7) dow.append(sc.strOr(k, "0"))

                        stmtSvc.bindLong(1, sid.toLong())
                        stmtSvc.bindString(2, dow.toString())
                        stmtSvc.bindString(3, sc.str(8))
                        stmtSvc.bindString(4, sc.str(9))
                        stmtSvc.executeInsert()
                    }

                    readCsv(
                        File(tempDir, "calendar_dates.txt"),
                        arrayOf("service_id", "date", "exception_type")
                    ) { sc ->
                        val sid = svcIndexMap[sc.str(0)] ?: return@readCsv
                        stmtSvcDate.bindLong(1, sid.toLong())
                        stmtSvcDate.bindString(2, sc.str(1))
                        stmtSvcDate.bindLong(3, (sc.str(2).toIntOrNull() ?: 1).toLong())
                        stmtSvcDate.executeInsert()
                    }

                    tempDir.deleteRecursively()
                    tempDir.mkdirs()
                    log("Feed ${fi + 1}/${feeds.size} hotový, spojů celkem: $countTrips")
                }

                // ───── párování vlakových stanic s autobusovými zastávkami (viz StationPairing.kt) ─────
                val pairInput = stops.order.map { code ->
                    val g = stops.names[code]
                    val post = posts[g]
                    val parentName = if (post != null) stations[post.parent]?.name else null
                    val name = post?.name ?: g
                    val (la, lo) = coords(g)
                    PairStop(
                        id = g,
                        name = name,
                        place = if (!parentName.isNullOrEmpty()) parentName else name,
                        lat = la,
                        lon = lo,
                        rail = g in railIds,
                    )
                }
                val railGroups = pairRailStations(pairInput, log = log)

                // ───── zastávky, které se skutečně použily ─────
                val stmtStop = db.compileStatement("INSERT INTO stop VALUES(?,?,?,?,?,?)")
                for ((i, code) in stops.order.withIndex()) {
                    val g = stops.names[code]
                    val post = posts[g]
                    val parentName = if (post != null) stations[post.parent]?.name else null
                    val paired = railGroups[g]
                    val name = paired ?: post?.name ?: g
                    val place = paired ?: if (!parentName.isNullOrEmpty()) parentName else name
                    val (la, lo) = coords(g)

                    stmtStop.bindLong(1, (i + 1).toLong())
                    stmtStop.bindString(2, name)
                    stmtStop.bindString(3, place)
                    if (la != null) stmtStop.bindDouble(4, la) else stmtStop.bindNull(4)
                    if (lo != null) stmtStop.bindDouble(5, lo) else stmtStop.bindNull(5)
                    stmtStop.bindLong(6, if (g in region) 1L else 0L)
                    stmtStop.executeInsert()
                }

                val stmtMeta = db.compileStatement("INSERT INTO meta VALUES(?,?)")
                stmtMeta.bindString(1, "tag"); stmtMeta.bindString(2, tag); stmtMeta.executeInsert()
                stmtMeta.bindString(1, "built"); stmtMeta.bindString(2, nowPrague().toString()); stmtMeta.executeInsert()
                stmtMeta.bindString(1, "trips"); stmtMeta.bindString(2, countTrips.toString()); stmtMeta.executeInsert()

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                db.close()
            }

            progress(0.97f)

            // rename přepíše soubor atomicky; stará DB, kterou ještě někdo čte, nezmizí uprostřed
            if (!tmpDb.renameTo(dbFile)) {
                dbFile.delete()
                java.io.RandomAccessFile(tmpDb, "rw").use { it.fd.sync() }
                tmpDb.renameTo(dbFile)
            }

            progress(1.0f)
            log("Hotovo! Importováno $countTrips spojů.")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}