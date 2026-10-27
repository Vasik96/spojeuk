package com.vasik96.spojeuk

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.vasik96.spojeuk.geoDistM
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

// ───────────────────────── Konfigurace a konstanty ─────────────────────────

val PRAGUE_ZONE: ZoneId = ZoneId.of("Europe/Prague")
const val TRAFFIC_URL = "https://tabule.portabo.cz/api/v1-tabule/cis/GetTraffic/0"
const val OUTAGE_MIN = 18

const val FEED_URL = "http://37.46.211.70:8787/traffic" // custom server, neni officialni ale muj, nechava starsi spoje aby nemizelo MHD

const val RELEASES_API = "https://api.github.com/repos/Sap1k/obehy/releases/latest"
const val ASSET_NAME = "jdf-filtered.tar"
val BBOX_LAT = 50.45..50.85
val BBOX_LON = 13.75..14.40
const val FOOT_MAX_M = 250.0
const val WALK_MS = 1.1
const val NEXT_DAY_CUTOFF = 6 * 3600
const val MIN_TRANSFER = 120
const val USER_AGENT = "duk-direct/0.1 (Android)"

fun nowPrague(): LocalDateTime = LocalDateTime.now(PRAGUE_ZONE)
fun todayPrague(): LocalDate = LocalDate.now(PRAGUE_ZONE)

fun JSONObject.optStringClean(key: String, fallback: String = ""): String {
    if (this.isNull(key)) return fallback
    val s = this.optString(key, fallback)
    return if (s == "null" || s.isBlank()) fallback else s
}

fun foldText(s: String): String {
    val norm = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val sb = StringBuilder()
    for (ch in norm) {
        if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
            sb.append(ch)
        }
    }
    return sb.toString()
}

fun prettyName(name: String): String = name.replace(Regex(",(?=\\S)"), ", ").replace("null", "").trim()

fun hhmm(sec: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", (sec / 3600) % 24, (sec % 3600) / 60)

fun parseTsec(s: String?): Int? {
    if (s.isNullOrBlank()) return null
    val p = s.split(":")
    if (p.size < 2) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    val x = if (p.size >= 3) p[2].toIntOrNull() ?: 0 else 0
    return h * 3600 + m * 60 + x
}

fun pluralTransfers(n: Int): String =
    if (n == 0) "přímo" else "$n " + if (n == 1) "přestup" else if (n in 2..4) "přestupy" else "přestupů"

fun bytesToIntArray(bytes: ByteArray): IntArray {
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val arr = IntArray(bytes.size / 4)
    for (i in arr.indices) arr[i] = bb.getInt()
    return arr
}

fun intArrayToBytes(arr: IntArray): ByteArray {
    val bb = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in arr) bb.putInt(v)
    return bb.array()
}

fun geoDistM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double =
    hypot((lo2 - lo1) * 111320.0 * cos(Math.toRadians(la1)), (la2 - la1) * 110540.0)

fun bearingDeg(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val y = (lo2 - lo1) * cos(Math.toRadians(la1))
    val x = la2 - la1
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

fun angDiff(a: Double, b: Double): Double { val d = abs(a - b) % 360; return if (d > 180) 360 - d else d }

fun normLine(s: String): String {
    val t = s.trim()
    return if (t.isNotEmpty() && t.all { it.isDigit() }) (t.toIntOrNull()?.toString() ?: t) else foldText(t)
}


// ───────────────────────── Modely pro DB a RAPTOR ─────────────────────────

data class DbTrip(
    val id: Int,
    val line: String,
    val agency: String,
    val cis: String,
    val num: String,
    val head: String,
    val svc: Int,
    val wheel: Int,
    val t0: Int,
    val t1: Int,
    val gid: String = "" // GTFS trip_id, klíč do shapes.sqlite
)

data class SvcInfo(
    val id: Int,
    val dow: String,
    val d0: String,
    val d1: String
)

data class SvcDateInfo(
    val svc: Int,
    val date: String,
    val type: Int
)

// ───────────────────────── Tar & GTFS Import ─────────────────────────

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

class GtfsImporter(private val dbFile: File) {

    private fun parseCsvLine(line: String): List<String> {
        if (line.indexOf('"') < 0) return line.split(',')
        val result = ArrayList<String>()
        val sb = java.lang.StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString())
                sb.clear()
            } else {
                sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    private fun unzipToDirectory(zipFile: File, destDir: File) {
        destDir.deleteRecursively()
        destDir.mkdirs()
        val buffer = ByteArray(32768)
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile), 32768)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name.isNotBlank()) {
                    val file = File(destDir, name)
                    FileOutputStream(file).use { fos ->
                        var r = zis.read(buffer)
                        while (r > 0) {
                            fos.write(buffer, 0, r)
                            r = zis.read(buffer)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun readCsvFromFile(
        file: File,
        block: (headers: Map<String, Int>, row: List<String>) -> Unit
    ) {
        if (!file.exists()) return
        file.bufferedReader(Charsets.UTF_8, 32768).use { reader ->
            val headerLine = reader.readLine() ?: return
            val cleanHeader = if (headerLine.startsWith("\uFEFF")) headerLine.substring(1) else headerLine
            val headers = parseCsvLine(cleanHeader).mapIndexed { idx, h -> h to idx }.toMap()

            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val row = parseCsvLine(line)
                    block(headers, row)
                }
                line = reader.readLine()
            }
        }
    }

    fun importGtfs(
        gtfsZipFile: File,
        tag: String,
        log: (String) -> Unit,
        progress: (Float) -> Unit
    ) {
        log("Rozbaluji GTFS…")
        progress(0.1f)
        val tempDir = File(dbFile.parentFile, "gtfs_temp")
        unzipToDirectory(gtfsZipFile, tempDir)

        try {
            log("Čtu zastávky…")
            progress(0.2f)

            data class StationData(val name: String, val lat: Double?, val lon: Double?)
            data class PostData(val parent: String, val name: String, val lat: Double?, val lon: Double?)

            val stations = HashMap<String, StationData>()
            val posts = HashMap<String, PostData>()

            readCsvFromFile(File(tempDir, "stops.txt")) { h, r ->
                val stopId = r.getOrNull(h["stop_id"] ?: -1) ?: return@readCsvFromFile
                val name = r.getOrNull(h["stop_name"] ?: -1) ?: ""
                val lat = r.getOrNull(h["stop_lat"] ?: -1)?.toDoubleOrNull()
                val lon = r.getOrNull(h["stop_lon"] ?: -1)?.toDoubleOrNull()
                val locType = r.getOrNull(h["location_type"] ?: -1) ?: "0"
                val parent = r.getOrNull(h["parent_station"] ?: -1) ?: ""

                if (locType == "1") {
                    stations[stopId] = StationData(name, lat, lon)
                } else {
                    posts[stopId] = PostData(parent, name, lat, lon)
                }
            }

            fun coords(g: String): Pair<Double?, Double?> {
                val p = posts[g] ?: return null to null
                if (p.lat != null && p.lon != null) return p.lat to p.lon
                val parent = stations[p.parent]
                return (parent?.lat) to (parent?.lon)
            }

            fun inreg(g: String): Boolean {
                val (la, lo) = coords(g)
                return la != null && lo != null && la in BBOX_LAT && lo in BBOX_LON
            }

            val region = posts.keys.filter { inreg(it) }.toSet()
            log("Zastávek v oblasti: ${region.size}")
            progress(0.3f)

            val agencies = HashMap<String, String>()
            readCsvFromFile(File(tempDir, "agency.txt")) { h, r ->
                val id = r.getOrNull(h["agency_id"] ?: -1) ?: ""
                val name = r.getOrNull(h["agency_name"] ?: -1) ?: ""
                agencies[id] = name
            }

            data class RouteData(val shortName: String, val agency: String)
            val routes = HashMap<String, RouteData>()
            readCsvFromFile(File(tempDir, "routes.txt")) { h, r ->
                val id = r.getOrNull(h["route_id"] ?: -1) ?: return@readCsvFromFile
                val short = r.getOrNull(h["route_short_name"] ?: -1) ?: "?"
                val agId = r.getOrNull(h["agency_id"] ?: -1) ?: ""
                routes[id] = RouteData(short, agencies[agId] ?: "")
            }

            data class TripMeta(val routeId: String, val serviceId: String, val headsign: String, val shortName: String, val wheel: String, val shapeId: String)
            val tripsMeta = HashMap<String, TripMeta>()
            readCsvFromFile(File(tempDir, "trips.txt")) { h, r ->
                val tripId = r.getOrNull(h["trip_id"] ?: -1) ?: return@readCsvFromFile
                val routeId = r.getOrNull(h["route_id"] ?: -1) ?: ""
                val svcId = r.getOrNull(h["service_id"] ?: -1) ?: ""
                val head = r.getOrNull(h["trip_headsign"] ?: -1) ?: ""
                val short = r.getOrNull(h["trip_short_name"] ?: -1) ?: ""
                val wheel = r.getOrNull(h["wheelchair_accessible"] ?: -1) ?: "0"
                val shapeId = r.getOrNull(h["shape_id"] ?: -1) ?: ""
                tripsMeta[tripId] = TripMeta(routeId, svcId, head, short, wheel, shapeId)
            }

            log("Zpracovávám jízdní řády…")
            progress(0.5f)

            val tmpDb = File(dbFile.parentFile, dbFile.name + ".tmp")
            tmpDb.delete()

            val db = SQLiteDatabase.openOrCreateDatabase(tmpDb, null)
            db.rawQuery("PRAGMA synchronous = OFF;", null).close()
            db.rawQuery("PRAGMA journal_mode = MEMORY;", null).close()
            db.rawQuery("PRAGMA temp_store = MEMORY;", null).close()

            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);")
            db.execSQL("CREATE TABLE stop(id INTEGER PRIMARY KEY, name TEXT, place TEXT, lat REAL, lon REAL, inreg INTEGER);")
            db.execSQL("CREATE TABLE trip(id INTEGER PRIMARY KEY, line TEXT, agency TEXT, cis TEXT, num TEXT, head TEXT, svc INTEGER, wheel INTEGER, t0 INTEGER, t1 INTEGER, gid TEXT DEFAULT '');")
            db.execSQL("CREATE TABLE trip_times(trip INTEGER PRIMARY KEY, stops BLOB, arr BLOB, dep BLOB);")
            db.execSQL("CREATE TABLE svc(id INTEGER PRIMARY KEY, dow TEXT, d0 TEXT, d1 TEXT);")
            db.execSQL("CREATE TABLE svc_date(svc INTEGER, d TEXT, type INTEGER);")

            val tripIndexMap = HashMap<String, Int>()
            val stopIndexMap = HashMap<String, Int>()
            val svcIndexMap = HashMap<String, Int>()

            data class StopTimeRow(val stopId: String, val seq: Int, val arr: Int?, val dep: Int?)

            fun processTripStopTimes(
                rows: List<StopTimeRow>,
                tripId: String,
                stmtTrip: SQLiteStatement,
                stmtTimes: SQLiteStatement
            ): Boolean {
                if (!rows.any { it.stopId in region }) return false
                val sortedRows = rows.sortedBy { it.seq }

                val st = ArrayList<Int>()
                val ar = ArrayList<Int>()
                val de = ArrayList<Int>()

                for (r in sortedRows) {
                    val a = r.arr
                    val d = r.dep
                    if (a == null && d == null) continue
                    val sid = stopIndexMap.getOrPut(r.stopId) { stopIndexMap.size + 1 }
                    st.add(sid)
                    ar.add(a ?: d ?: 0)
                    de.add(d ?: a ?: 0)
                }

                if (st.size < 2) return false

                val tid = tripIndexMap.size + 1
                tripIndexMap[tripId] = tid
                val meta = tripsMeta[tripId] ?: return false

                val sn = meta.shortName.split(Regex("\\s+"))
                val cis: String
                val num: String
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

                val route = routes[meta.routeId] ?: RouteData("?", "")
                val svcId = svcIndexMap.getOrPut(meta.serviceId) { svcIndexMap.size + 1 }

                stmtTrip.bindLong(1, tid.toLong())
                stmtTrip.bindString(2, route.shortName)
                stmtTrip.bindString(3, route.agency)
                stmtTrip.bindString(4, cis)
                stmtTrip.bindString(5, num)
                stmtTrip.bindString(6, meta.headsign)
                stmtTrip.bindLong(7, svcId.toLong())
                stmtTrip.bindLong(8, (meta.wheel.toIntOrNull() ?: 0).toLong())
                stmtTrip.bindLong(9, de[0].toLong())
                stmtTrip.bindLong(10, ar.last().toLong())
                stmtTrip.bindString(11, tripId)
                stmtTrip.executeInsert()

                stmtTimes.bindLong(1, tid.toLong())
                stmtTimes.bindBlob(2, intArrayToBytes(st.toIntArray()))
                stmtTimes.bindBlob(3, intArrayToBytes(ar.toIntArray()))
                stmtTimes.bindBlob(4, intArrayToBytes(de.toIntArray()))
                stmtTimes.executeInsert()

                return true
            }

            var countTrips = 0
            db.beginTransaction()
            try {
                val stmtTrip = db.compileStatement("INSERT INTO trip VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                val stmtTimes = db.compileStatement("INSERT INTO trip_times VALUES(?,?,?,?)")

                var currentTripId: String? = null
                val currentRows = ArrayList<StopTimeRow>()

                readCsvFromFile(File(tempDir, "stop_times.txt")) { h, r ->
                    val tripId = r.getOrNull(h["trip_id"] ?: -1) ?: return@readCsvFromFile
                    if (tripId !in tripsMeta) return@readCsvFromFile

                    val stopId = r.getOrNull(h["stop_id"] ?: -1) ?: return@readCsvFromFile
                    val seq = r.getOrNull(h["stop_sequence"] ?: -1)?.toIntOrNull() ?: 0
                    val arr = parseTsec(r.getOrNull(h["arrival_time"] ?: -1))
                    val dep = parseTsec(r.getOrNull(h["departure_time"] ?: -1))

                    val cId = currentTripId
                    if (cId != null && tripId != cId) {
                        if (processTripStopTimes(currentRows, cId, stmtTrip, stmtTimes)) {
                            countTrips++
                        }
                        currentRows.clear()
                    }
                    currentTripId = tripId
                    currentRows.add(StopTimeRow(stopId, seq, arr, dep))
                }

                val finalId = currentTripId
                if (finalId != null && currentRows.isNotEmpty()) {
                    if (processTripStopTimes(currentRows, finalId, stmtTrip, stmtTimes)) {
                        countTrips++
                    }
                }



                val stmtStop = db.compileStatement("INSERT INTO stop VALUES(?,?,?,?,?,?)")
                for ((g, sid) in stopIndexMap) {
                    val post = posts[g]
                    val parentName = if (post != null) stations[post.parent]?.name else null
                    val name = post?.name ?: g
                    val place = if (!parentName.isNullOrEmpty()) parentName else name
                    val (la, lo) = coords(g)

                    stmtStop.bindLong(1, sid.toLong())
                    stmtStop.bindString(2, name)
                    stmtStop.bindString(3, place)
                    if (la != null) stmtStop.bindDouble(4, la) else stmtStop.bindNull(4)
                    if (lo != null) stmtStop.bindDouble(5, lo) else stmtStop.bindNull(5)
                    stmtStop.bindLong(6, if (g in region) 1L else 0L)
                    stmtStop.executeInsert()
                }

                val stmtSvc = db.compileStatement("INSERT INTO svc VALUES(?,?,?,?)")
                readCsvFromFile(File(tempDir, "calendar.txt")) { h, r ->
                    val serviceId = r.getOrNull(h["service_id"] ?: -1) ?: return@readCsvFromFile
                    if (serviceId in svcIndexMap) {
                        val dow = ("monday,tuesday,wednesday,thursday,friday,saturday,sunday").split(",").joinToString("") {
                            r.getOrNull(h[it] ?: -1) ?: "0"
                        }
                        val d0 = r.getOrNull(h["start_date"] ?: -1) ?: ""
                        val d1 = r.getOrNull(h["end_date"] ?: -1) ?: ""
                        val sid = svcIndexMap[serviceId]!!

                        stmtSvc.bindLong(1, sid.toLong())
                        stmtSvc.bindString(2, dow)
                        stmtSvc.bindString(3, d0)
                        stmtSvc.bindString(4, d1)
                        stmtSvc.executeInsert()
                    }
                }

                val stmtSvcDate = db.compileStatement("INSERT INTO svc_date VALUES(?,?,?)")
                readCsvFromFile(File(tempDir, "calendar_dates.txt")) { h, r ->
                    val serviceId = r.getOrNull(h["service_id"] ?: -1) ?: return@readCsvFromFile
                    if (serviceId in svcIndexMap) {
                        val d = r.getOrNull(h["date"] ?: -1) ?: ""
                        val type = r.getOrNull(h["exception_type"] ?: -1)?.toIntOrNull() ?: 1
                        val sid = svcIndexMap[serviceId]!!

                        stmtSvcDate.bindLong(1, sid.toLong())
                        stmtSvcDate.bindString(2, d)
                        stmtSvcDate.bindLong(3, type.toLong())
                        stmtSvcDate.executeInsert()
                    }
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

            // rename přepíše soubor atomicky; stará DB, kterou ještě někdo čte, nezmizí uprostřed
            if (!tmpDb.renameTo(dbFile)) {
                dbFile.delete()
                tmpDb.renameTo(dbFile)
            }
            progress(1.0f)
            log("Hotovo! Importováno $countTrips spojů.")
        } finally {
            tempDir.deleteRecursively()
            System.gc()
        }
    }
}

// ───────────────────────── Timetable ─────────────────────────

class Timetable(val dbFile: File) {

    val stops = HashMap<Int, Stop>()
    val places = HashMap<String, MutableList<Int>>()
    val placeInreg = HashMap<String, Boolean>()
    val index = ArrayList<Pair<String, String>>() // (foldedPrettyPlace, originalPlace)

    val trips = HashMap<Int, DbTrip>()
    val byKey = HashMap<String, MutableList<Int>>()

    val svcs = HashMap<Int, SvcInfo>()
    val svcDates = HashMap<String, MutableList<SvcDateInfo>>()
    val meta = HashMap<String, String>()

    private val actCache = HashMap<LocalDate, Set<Int>>()
    var foot: Map<Int, List<Pair<Int, Int>>>? = null

    private val timesCache = ConcurrentHashMap<Int, Triple<IntArray, IntArray, IntArray>>()

    /** DB je ve verzi s tabulkou shape (starší static.sqlite ji nemá). */
    var hasGid = false
        private set

    init {
        loadFromDb()
    }


    fun tripsServing(sids: Set<Int>): List<Int> {
        val out = ArrayList<Int>()
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT trip, stops FROM trip_times", null).use { c ->
                while (c.moveToNext()) {
                    if (bytesToIntArray(c.getBlob(1)).any { it in sids }) out.add(c.getInt(0))
                }
            }
        } finally { db.close() }
        return out
    }

    private fun loadFromDb() {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT k, v FROM meta", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
            }

            db.rawQuery("SELECT id, name, place, lat, lon, inreg FROM stop", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getInt(0)
                    val name = c.getString(1) ?: ""
                    val place = c.getString(2) ?: name
                    val lat = if (c.isNull(3)) 0.0 else c.getDouble(3)
                    val lon = if (c.isNull(4)) 0.0 else c.getDouble(4)
                    val inreg = c.getInt(5) == 1

                    val s = Stop(id.toString(), name, lat, lon, place, inreg)
                    stops[id] = s
                    places.getOrPut(place) { mutableListOf() }.add(id)
                    if (inreg) placeInreg[place] = true
                }
            }

            for (p in places.keys) {
                index.add(foldText(prettyName(p)) to p)
            }

            db.rawQuery("PRAGMA table_info(trip)", null).use { c ->
                while (c.moveToNext()) if (c.getString(1) == "gid") hasGid = true
            }

            val gidCol = if (hasGid) ", gid" else ""
            db.rawQuery("SELECT id, line, agency, cis, num, head, svc, wheel, t0, t1$gidCol FROM trip", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getInt(0)
                    val line = c.getString(1) ?: "?"
                    val agency = c.getString(2) ?: ""
                    val cis = c.getString(3) ?: ""
                    val num = c.getString(4) ?: ""
                    val head = c.getString(5) ?: ""
                    val svc = c.getInt(6)
                    val wheel = c.getInt(7)
                    val t0 = c.getInt(8)
                    val t1 = c.getInt(9)
                    val gid = if (hasGid) c.getString(10) ?: "" else ""


                    val t = DbTrip(id, line, agency, cis, num, head, svc, wheel, t0, t1, gid)
                    trips[id] = t
                    if (cis.isNotBlank()) {
                        byKey.getOrPut("$cis:$num") { mutableListOf() }.add(id)
                    }
                }
            }

            db.rawQuery("SELECT id, dow, d0, d1 FROM svc", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getInt(0)
                    svcs[id] = SvcInfo(id, c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "")
                }
            }

            db.rawQuery("SELECT svc, d, type FROM svc_date", null).use { c ->
                while (c.moveToNext()) {
                    val svc = c.getInt(0)
                    val d = c.getString(1) ?: ""
                    val type = c.getInt(2)
                    svcDates.getOrPut(d) { mutableListOf() }.add(SvcDateInfo(svc, d, type))
                }
            }
        } finally {
            db.close()
        }
    }

    fun buildFoot() {
        if (foot != null) return
        val cell = HashMap<Pair<Int, Int>, MutableList<Int>>()
        for ((sid, stop) in stops) {
            if (stop.lat != 0.0 || stop.lng != 0.0) {
                val cy = (stop.lat / 0.003).toInt()
                val cx = (stop.lng / 0.0045).toInt()
                cell.getOrPut(cy to cx) { mutableListOf() }.add(sid)
            }
        }

        val footMap = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for ((key, ids) in cell) {
            val (cy, cx) = key
            for (a in ids) {
                val stopA = stops[a] ?: continue
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val neighbors = cell[cy + dy to cx + dx] ?: continue
                        for (b in neighbors) {
                            if (b == a) continue
                            val stopB = stops[b] ?: continue
                            val d = Math.hypot(
                                (stopB.lng - stopA.lng) * 111320.0 * Math.cos(Math.toRadians(stopA.lat)),
                                (stopB.lat - stopA.lat) * 110540.0
                            )
                            if (d <= FOOT_MAX_M) {
                                val dur = maxOf(60, (d / WALK_MS).toInt())
                                footMap.getOrPut(a) { mutableListOf() }.add(b to dur)
                            }
                        }
                    }
                }
            }
        }
        foot = footMap
    }

    fun activeSvcs(d: LocalDate): Set<Int> {
        return actCache.getOrPut(d) {
            val ds = d.format(DateTimeFormatter.BASIC_ISO_DATE)
            val pyWd = d.dayOfWeek.value - 1 // Mon=0 .. Sun=6
            val act = HashSet<Int>()

            for ((sid, svc) in svcs) {
                if (svc.d0 <= ds && ds <= svc.d1 && svc.dow.getOrNull(pyWd) == '1') {
                    act.add(sid)
                }
            }
            for (sd in svcDates[ds] ?: emptyList()) {
                if (sd.type == 1) act.add(sd.svc)
                else if (sd.type == 2) act.remove(sd.svc)
            }
            act
        }
    }

    fun searchPlaces(q: String, usage: Map<String, Int>, limit: Int = 40): List<String> {
        val terms = foldText(q).replace(",", " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val hits = if (terms.isNotEmpty()) {
            index.filter { (norm, _) -> terms.all { t -> norm.contains(t) } }.map { it.second }
        } else {
            usage.keys.filter { it in places }
        }

        val firstTerm = terms.firstOrNull() ?: ""
        return hits.sortedWith(
            compareByDescending<String> { usage[it] ?: 0 }
                .thenBy { placeInreg[it] != true }
                .thenBy { if (firstTerm.isNotBlank()) !foldText(prettyName(it)).replace(",", " ").startsWith(firstTerm) else false }
                .thenBy { it }
        ).take(limit)
    }

    fun tripTimes(tid: Int): Triple<IntArray, IntArray, IntArray>? {
        timesCache[tid]?.let { return it }
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT stops, arr, dep FROM trip_times WHERE trip=?", arrayOf(tid.toString())).use { c ->
                if (c.moveToFirst()) {
                    val t = Triple(bytesToIntArray(c.getBlob(0)), bytesToIntArray(c.getBlob(1)), bytesToIntArray(c.getBlob(2)))
                    timesCache[tid] = t
                    return t
                }
            }
        } finally { db.close() }
        return null
    }

    fun findTripId(cis: String, num: String): Int? {
        val c = cis.trim().padStart(6, '0')
        if (c == "000000" || num.isBlank()) return null
        val cands = byKey["$c:$num"] ?: return null
        val now = nowPrague()
        var best: Int? = null
        var bestGap = Int.MAX_VALUE
        for (d in 0L..1L) {
            val day = now.toLocalDate().minusDays(d)
            val rel = Duration.between(day.atStartOfDay(), now).seconds.toInt()
            val act = activeSvcs(day)
            for (tid in cands) {
                val t = trips[tid] ?: continue
                if (t.svc !in act) continue
                val gap = when {
                    rel < t.t0 -> t.t0 - rel
                    rel > t.t1 -> rel - t.t1
                    else -> 0
                }
                if (gap < bestGap) { bestGap = gap; best = tid }
            }
        }
        return if (bestGap <= 2 * 3600) best else null
    }

    /** Záloha pro vozidla bez použitelného cis: spoj stejné linky, který právě jede a vede nejblíž vozidlu. */
    fun findTripByLine(line: String, lat: Double, lng: Double): Int? {
        val now = nowPrague()
        val today = now.toLocalDate()
        val rel = Duration.between(today.atStartOfDay(), now).seconds.toInt()
        val act = activeSvcs(today)
        var best: Int? = null
        var bestDist = Double.MAX_VALUE
        for ((tid, t) in trips) {
            if (t.line != line || t.svc !in act) continue
            if (rel < t.t0 - 600 || rel > t.t1 + 600) continue
            for (sid in tripStops(tid)) {
                val s = stops[sid] ?: continue
                if (s.lat == 0.0 && s.lng == 0.0) continue
                val dy = (s.lat - lat) * 110540.0
                val dx = (s.lng - lng) * 111320.0 * Math.cos(Math.toRadians(lat))
                val d = Math.hypot(dx, dy)
                if (d < bestDist) { bestDist = d; best = tid }
            }
        }
        return if (bestDist <= 400.0) best else null
    }


    fun tripStops(tid: Int): IntArray = tripTimes(tid)?.first ?: intArrayOf()

    private fun isDpmulAgency(a: String): Boolean {
        val f = foldText(a)
        return "dopravni podnik mesta usti" in f || "dpmul" in f
    }

    private val dpmulByLine: Map<String, List<Int>> by lazy {
        val dp = trips.values.filter { isDpmulAgency(it.agency) }
        (if (dp.isEmpty()) trips.values else dp).groupBy({ normLine(it.line) }, { it.id })
    }

    fun matchDpmulTrip(v: Vehicle): Int? {
        val now = nowPrague()
        val fd = foldText(v.destination)
        var best: Int? = null
        var bestSc = 800.0
        for (dday in 0L..1L) {
            val day = now.toLocalDate().minusDays(dday)
            val rel = Duration.between(day.atStartOfDay(), now).seconds - v.delay * 60.0
            val act = activeSvcs(day)
            for (tid in dpmulByLine[normLine(v.line)].orEmpty()) {
                val t = trips[tid] ?: continue
                if (t.svc !in act || rel < t.t0 - 900 || rel > t.t1 + 900) continue
                val (sIds, _, dep) = tripTimes(tid) ?: continue
                var sc = Double.MAX_VALUE
                var hd: Double? = null
                for (i in sIds.indices) {
                    val s = stops[sIds[i]] ?: continue
                    if (s.lat == 0.0 && s.lng == 0.0) continue
                    val d = geoDistM(v.lat, v.lng, s.lat, s.lng)
                    if (d > 400) continue
                    val c = d + 0.5 * abs(dep[i] - rel)
                    if (c < sc) {
                        sc = c
                        val j = if (i + 1 < sIds.size) i else i - 1
                        val a = stops[sIds[j]]
                        val b = stops[sIds[j + 1]]
                        hd = if (a != null && b != null) bearingDeg(a.lat, a.lng, b.lat, b.lng) else null
                    }
                }
                if (sc == Double.MAX_VALUE) continue
                val vh = v.heading
                if (vh != null && hd != null && angDiff(vh, hd) > 100) sc += 600
                val fh = foldText(t.head)
                if (fd.isNotBlank() && fh.isNotBlank() && (fd in fh || fh in fd)) sc -= 250
                if (v.trip.isNotBlank() && t.num == v.trip) sc -= 1000
                if (sc < bestSc) { best = tid; bestSc = sc }
            }
        }
        return best
    }


    private class TripLine(val lat: DoubleArray, val lng: DoubleArray, val cum: DoubleArray)
    private val tripLines = ConcurrentHashMap<Int, TripLine>()

    fun progressOnTrip(tid: Int, lat: Double, lng: Double): Double? {
        val tl = tripLines[tid] ?: run {
            val pts = ArrayList<Stop>()
            for (sid in tripStops(tid)) {
                val s = stops[sid] ?: continue
                if (s.lat != 0.0 || s.lng != 0.0) pts.add(s)
            }
            if (pts.size < 2) return null
            val cum = DoubleArray(pts.size)
            for (i in 1 until pts.size) {
                cum[i] = cum[i - 1] + geoDistM(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng)
            }
            val built = TripLine(
                DoubleArray(pts.size) { pts[it].lat },
                DoubleArray(pts.size) { pts[it].lng },
                cum
            )
            tripLines[tid] = built
            built
        }
        val cosLat = cos(Math.toRadians(lat))
        var bestD = Double.MAX_VALUE
        var bestS = 0.0
        for (i in 0 until tl.lat.size - 1) {
            val ax = (tl.lng[i] - lng) * 111320.0 * cosLat
            val ay = (tl.lat[i] - lat) * 110540.0
            val dx = (tl.lng[i + 1] - lng) * 111320.0 * cosLat - ax
            val dy = (tl.lat[i + 1] - lat) * 110540.0 - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestD) { bestD = d; bestS = tl.cum[i] + t * (tl.cum[i + 1] - tl.cum[i]) }
        }
        return bestS
    }


}


// ───────────────────────── RAPTOR Engine ─────────────────────────

class TripInstance(
    val de0: Int,
    val tid: Int,
    val arr: IntArray,
    val dep: IntArray
)

class Pattern(
    val stops: IntArray,
    val tids: IntArray,
    val arr: Array<IntArray>,
    val dep: Array<IntArray>,
    val cols: Array<IntArray>
)

sealed class ParentRef {
    data class Walk(val fromStop: Int, val duration: Int) : ParentRef()
    data class Ride(val patternIdx: Int, val tripIdx: Int, val boardIdx: Int, val alightIdx: Int) : ParentRef()
}

class Net(val tt: Timetable, val day: LocalDate) {

    val patterns: List<Pattern>
    val stopPats: Map<Int, List<Pair<Int, Int>>>

    init {
        tt.buildFoot()
        val active = HashMap<Int, MutableList<Int>>()

        val yesterday = day.minusDays(1)
        val svcsYest = tt.activeSvcs(yesterday)
        val svcsToday = tt.activeSvcs(day)
        val tomorrow = day.plusDays(1)
        val svcsTmrw = tt.activeSvcs(tomorrow)

        android.util.Log.i(
            "RaptorDebug",
            "day=$day " +
                    "servicesYesterday=${svcsYest.size} " +
                    "servicesToday=${svcsToday.size} " +
                    "servicesTomorrow=${svcsTmrw.size}"
        )

        for ((tid, t) in tt.trips) {
            if (t.svc in svcsYest && t.t1 >= 86400) {
                active.getOrPut(tid) { mutableListOf() }.add(-86400)
            }
            if (t.svc in svcsToday) {
                active.getOrPut(tid) { mutableListOf() }.add(0)
            }
            if (t.svc in svcsTmrw && t.t0 <= NEXT_DAY_CUTOFF) {
                active.getOrPut(tid) { mutableListOf() }.add(86400)
            }
        }

        android.util.Log.i(
            "RaptorDebug",
            "activeTrips=${active.size} " +
                    "activeInstances=${active.values.sumOf { it.size }}"
        )

        val groups = HashMap<List<Int>, MutableList<TripInstance>>()
        val db = SQLiteDatabase.openDatabase(tt.dbFile.path, null, SQLiteDatabase.OPEN_READONLY)

        try {
            db.rawQuery("SELECT trip, stops, arr, dep FROM trip_times", null).use { cursor ->
                val cTrip = cursor.getColumnIndexOrThrow("trip")
                val cStops = cursor.getColumnIndexOrThrow("stops")
                val cArr = cursor.getColumnIndexOrThrow("arr")
                val cDep = cursor.getColumnIndexOrThrow("dep")

                while (cursor.moveToNext()) {
                    val tid = cursor.getInt(cTrip)
                    val offsets = active[tid] ?: continue
                    val stops = bytesToIntArray(cursor.getBlob(cStops)).toList()
                    val arr = bytesToIntArray(cursor.getBlob(cArr))
                    val dep = bytesToIntArray(cursor.getBlob(cDep))

                    for (off in offsets) {
                        val arrOff = IntArray(arr.size) { arr[it] + off }
                        val depOff = IntArray(dep.size) { dep[it] + off }
                        groups.getOrPut(stops) { mutableListOf() }.add(
                            TripInstance(depOff[0], tid, arrOff, depOff)
                        )
                    }
                }
                android.util.Log.i(
                    "RaptorDebug",
                    "patterns=${groups.size} " +
                            "tripInstances=${groups.values.sumOf { it.size }}"
                )
            }
        } finally {
            db.close()
        }

        val patsList = ArrayList<Pattern>()
        val stopPatsMap = HashMap<Int, MutableList<Pair<Int, Int>>>()

        for ((stopsList, instances) in groups) {
            instances.sortBy { it.de0 }
            val pi = patsList.size
            val stops = stopsList.toIntArray()
            val tids = instances.map { it.tid }.toIntArray()
            val arrs = instances.map { it.arr }.toTypedArray()
            val deps = instances.map { it.dep }.toTypedArray()
            val cols = Array(stops.size) { i -> IntArray(instances.size) { j -> deps[j][i] } }

            patsList.add(Pattern(stops, tids, arrs, deps, cols))
            for ((pos, s) in stops.withIndex()) {
                stopPatsMap.getOrPut(s) { mutableListOf() }.add(pi to pos)
            }
        }

        patterns = patsList
        stopPats = stopPatsMap
    }

    private fun binarySearchLeft(arr: IntArray, target: Int): Int {
        var low = 0
        var high = arr.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (arr[mid] < target) low = mid + 1
            else high = mid
        }
        return low
    }

    fun raptor(srcs: List<Int>, dsts: Set<Int>, t0Sec: Int, kmax: Int = 4): List<Journey> {
        val foot = tt.foot ?: emptyMap()
        val INF = 1_000_000_000
        val arrs = Array(kmax + 1) { HashMap<Int, Int>() }
        val par = Array(kmax + 1) { HashMap<Int, ParentRef>() }
        val best = HashMap<Int, Int>()
        var marked = HashSet<Int>()

        for (s in srcs) {
            arrs[0][s] = t0Sec
            best[s] = t0Sec
            marked.add(s)
        }
        for (s in srcs) {
            for ((s2, d) in foot[s] ?: emptyList()) {
                if (t0Sec + d < (best[s2] ?: INF)) {
                    arrs[0][s2] = t0Sec + d
                    best[s2] = t0Sec + d
                    par[0][s2] = ParentRef.Walk(s, d)
                    marked.add(s2)
                }
            }
        }

        var target = INF
        val found = ArrayList<Journey>()

        for (k in 1..kmax) {
            val queue = HashMap<Int, Int>()
            for (s in marked) {
                for ((pi, pos) in stopPats[s] ?: emptyList()) {
                    val currentMin = queue[pi] ?: INF
                    if (pos < currentMin) queue[pi] = pos
                }
            }

            val newMarked = HashSet<Int>()
            val prevArrs = arrs[k - 1]
            val curArrs = arrs[k]
            val curPar = par[k]
            val mt = if (k == 1) 0 else MIN_TRANSFER

            for ((pi, start) in queue) {
                val pattern = patterns[pi]
                val stops = pattern.stops
                var trip = -1
                var board = -1

                for (i in start until stops.size) {
                    val s = stops[i]
                    if (trip >= 0) {
                        val a = pattern.arr[trip][i]
                        if (a < (best[s] ?: INF) && a < target) {
                            best[s] = a
                            curArrs[s] = a
                            curPar[s] = ParentRef.Ride(pi, trip, board, i)
                            newMarked.add(s)
                        }
                    }
                    val p = prevArrs[s]
                    if (p != null) {
                        val col = pattern.cols[i]
                        val j = binarySearchLeft(col, p + mt)
                        if (j < col.size && (trip < 0 || j < trip)) {
                            trip = j
                            board = i
                        }
                    }
                }
            }

            for (s in newMarked.toList()) {
                val curS = curArrs[s] ?: continue
                for ((s2, d) in foot[s] ?: emptyList()) {
                    val a = curS + d
                    if (a < (best[s2] ?: INF) && a < target) {
                        best[s2] = a
                        curArrs[s2] = a
                        curPar[s2] = ParentRef.Walk(s, d)
                        newMarked.add(s2)
                    }
                }
            }

            marked = newMarked
            val hit = dsts.mapNotNull { d -> curArrs[d]?.let { it to d } }
            if (hit.isNotEmpty()) {
                val minHit = hit.minByOrNull { it.first }!!
                target = minHit.first
                found.add(buildJourney(par, k, minHit.second))
            }
            if (marked.isEmpty()) break
        }

        return found
    }

    private fun buildJourney(par: Array<HashMap<Int, ParentRef>>, startK: Int, targetStop: Int): Journey {
        val legs = ArrayList<Leg>()
        var s = targetStop
        var k = startK

        while (true) {
            val p = par[k][s] ?: break
            when (p) {
                is ParentRef.Walk -> {
                    legs.add(Leg(kind = "walk", fromStop = p.fromStop, toStop = s, dep = 0, arr = p.duration))
                    s = p.fromStop
                }
                is ParentRef.Ride -> {
                    val pattern = patterns[p.patternIdx]
                    val stops = pattern.stops
                    val tids = pattern.tids
                    val arr = pattern.arr[p.tripIdx]
                    val dep = pattern.dep[p.tripIdx]
                    val seq = (p.boardIdx..p.alightIdx).map { i -> Triple(stops[i], arr[i], dep[i]) }

                    legs.add(
                        Leg(
                            kind = "ride",
                            fromStop = stops[p.boardIdx],
                            toStop = stops[p.alightIdx],
                            dep = dep[p.boardIdx],
                            arr = arr[p.alightIdx],
                            tid = tids[p.tripIdx],
                            stops = seq,
                            t0 = dep[0],
                            t1 = arr.last()
                        )
                    )
                    s = stops[p.boardIdx]
                    k -= 1
                }
            }
        }

        legs.reverse()

        for (i in legs.indices) {
            val l = legs[i]
            if (l.kind == "walk") {
                val dur = l.arr
                val nxt = legs.subList(i + 1, legs.size).firstOrNull { it.kind == "ride" }
                val prv = if (i > 0) legs[i - 1] else null
                if (prv != null && prv.kind == "ride") {
                    l.dep = prv.arr
                    l.arr = prv.arr + dur
                } else if (nxt != null) {
                    l.dep = nxt.dep - dur
                    l.arr = nxt.dep
                }
            }
        }

        val rides = legs.filter { it.kind == "ride" }
        val firstRide = rides.first()
        val dep = firstRide.dep
        val arr = legs.maxOf { it.arr }

        val line1 = tt.trips[firstRide.tid]?.line ?: "?"
        val line2 = if (rides.size > 1) tt.trips[rides[1].tid]?.line else null
        val dest = tt.trips[rides.last().tid]?.head ?: ""
        val agency = tt.trips[firstRide.tid]?.agency ?: ""

        val totalStops = rides.sumOf { maxOf(0, it.stops.size - 1) }
        val transferAtStop = if (rides.size > 1) tt.stops[rides[0].toStop]?.name else null
        val transferWaitMin = if (rides.size > 1) maxOf(0, (rides[1].dep - rides[0].arr) / 60) else null

        val tripStr = tt.trips[firstRide.tid]?.num?.ifBlank { firstRide.tid.toString() } ?: firstRide.tid.toString()
        val trip2Str = if (rides.size > 1) (tt.trips[rides[1].tid]?.num?.ifBlank { rides[1].tid.toString() } ?: rides[1].tid.toString()) else null

        return Journey(
            departure = hhmm(dep),
            arrival = hhmm(arr),
            line = line1,
            line2 = line2,
            destination = prettyName(dest),
            agency = agency,
            delay = 0,
            realtime = false,
            lowFloor = (tt.trips[firstRide.tid]?.wheel ?: 0) > 0,
            stopsCount = totalStops,
            transferAt = transferAtStop?.let { prettyName(it) },
            transferWait = transferWaitMin,
            trip = tripStr,
            trip2 = trip2Str,
            sortKey = dep,
            day = day,
            rawLegs = legs,
            cis = tt.trips[firstRide.tid]?.cis ?: ""
        )
    }
}

class Planner(val tt: Timetable) {
    private val nets = HashMap<LocalDate, Net>()

    @Synchronized
    fun net(day: LocalDate): Net {
        return nets.getOrPut(day) {
            if (nets.size >= 4) {
                nets.remove(nets.keys.first())
            }
            Net(tt, day)
        }
    }

    fun find(
        fromPlace: String,
        toPlace: String,
        start: LocalDateTime,
        count: Int = 8,
        end: LocalDateTime? = null,
        horizonHours: Int = 14
    ): List<Journey> {
        val day = start.toLocalDate()
        val n = net(day)
        val midnight = day.atStartOfDay()
        var tSec = maxOf(0, Duration.between(midnight, start).seconds.toInt())
        val tEndSec = if (end != null) {
            Duration.between(midnight, end).seconds.toInt()
        } else {
            tSec + horizonHours * 3600
        }

        val srcs = tt.places[fromPlace] ?: emptyList()
        val dsts = (tt.places[toPlace] ?: emptyList()).toSet()
        for (sid in srcs) {
            val stop = tt.stops[sid]
            val pats = n.stopPats[sid].orEmpty()


            android.util.Log.i(
                "RaptorDebug",
                "source sid=$sid " +
                        "name=${stop?.name} " +
                        "place=${stop?.place} " +
                        "patterns=${pats.size} " +
                        "inNet=${pats.isNotEmpty()}"
            )
        }

        val sourcePositions = srcs.flatMap { n.stopPats[it].orEmpty() }

        val sourceDepartures = sourcePositions.sumOf { (pi, pos) ->
            n.patterns[pi].cols[pos].count { it >= tSec }
        }

        val unsortedColumns = n.patterns.sumOf { pattern ->
            pattern.cols.count { col ->
                (1 until col.size).any { i -> col[i] < col[i - 1] }
            }
        }

        android.util.Log.i(
            "RaptorDebug",
            "sourcePositions=${sourcePositions.size} " +
                    "departuresAfterStart=$sourceDepartures " +
                    "unsortedColumns=$unsortedColumns"
        )

        android.util.Log.i("Search", "from=$fromPlace srcs=${srcs.size} to=$toPlace dsts=${dsts.size} trips=${tt.trips.size} patterns=${n.patterns.size}")
        if (srcs.isEmpty() || dsts.isEmpty()) return emptyList()

        val out = HashMap<String, Journey>()
        var runs = 0

        while (out.size < count && tSec <= tEndSec && runs < 40) {
            val js = n.raptor(srcs, dsts, tSec)
            android.util.Log.i("Search", "run=$runs t=$tSec end=$tEndSec js=${js.size} foot=${tt.foot?.size} deps=${js.map { it.dep }}")
            if (js.isEmpty()) break
            for (j in js) {
                if (j.dep <= tEndSec) out[j.key] = j
            }
            tSec = (js.minByOrNull { it.dep }?.dep ?: tSec) + 1
            runs++
        }
        android.util.Log.i("Search", "out=${out.size} pruned=${prune(out.values.toList()).size}")
        return prune(out.values.toList())
    }

    fun moreLater(fromPlace: String, toPlace: String, journeys: List<Journey>, count: Int = 6): List<Journey> {
        if (journeys.isEmpty()) return emptyList()
        val last = journeys.maxOf { it.depDt }
        val newJs = find(fromPlace, toPlace, last.plusSeconds(60), count)
        val existingKeys = journeys.mapTo(HashSet()) { it.key }
        return prune(journeys + newJs.filter { it.key !in existingKeys })
    }

    fun moreEarlier(fromPlace: String, toPlace: String, journeys: List<Journey>, count: Int = 6, hours: Int = 2): List<Journey> {
        if (journeys.isEmpty()) return emptyList()
        val first = journeys.minOf { it.depDt }
        val lo = maxOf(first.minusHours(hours.toLong()), first.toLocalDate().atStartOfDay())
        val newJs = find(fromPlace, toPlace, lo, 60, end = first.minusSeconds(1))
            .sortedBy { it.depDt }
            .takeLast(count)
        val existingKeys = journeys.mapTo(HashSet()) { it.key }
        return prune(journeys + newJs.filter { it.key !in existingKeys })
    }
}

fun prune(js: List<Journey>): List<Journey> {
    val keep = js.filter { j ->
        !js.any { k ->
            k !== j &&
                    k.depDt >= j.depDt &&
                    k.dep + (k.day.toEpochDay() - j.day.toEpochDay()) * 86400 <= j.dep.toLong() &&
                    k.transfers <= j.transfers &&
                    !(k.depDt == j.depDt && k.dep == j.dep && k.transfers == j.transfers)
        }
    }
    return keep.sortedWith(compareBy<Journey> { it.depDt }.thenBy { it.dep })
}

// ───────────────────────── Realtime Traffic ─────────────────────────

class RealtimeClient(
    private val urls: List<String> = listOf(FEED_URL, TRAFFIC_URL),
    private val snapshotFile: File? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun fetchBody(): String {
        var last: Exception? = null
        for (u in urls) {
            try {
                val req = Request.Builder().url(u)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code} ($u)")
                    return resp.body?.string() ?: "{}"
                }
            } catch (e: Exception) {
                last = e
                android.util.Log.w("Traffic", "zdroj $u selhal: ${e.message}")
            }
        }
        throw last ?: IllegalStateException("Žádná URL")
    }


    var vehicles: List<Vehicle> = emptyList()
        private set
    var byKey: Map<String, List<Vehicle>> = emptyMap()
        private set
    var lastFetchTime: Long = 0

    private val lastSeen = HashMap<String, Pair<Vehicle, Long>>()
    private fun keyOf(v: Vehicle) = if (v.id != 0) "id${v.id}" else "${v.cis}:${v.line}:${v.trip}"


    @Volatile var heldCount: Int = 0
        private set

    fun ageSec(v: Vehicle): Long =
        (System.currentTimeMillis() - (lastSeen[keyOf(v)]?.second ?: System.currentTimeMillis())) / 1000


    init { loadSnapshot() }


    private fun loadSnapshot() {
        val f = snapshotFile ?: return
        runCatching {
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val nowMs = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ts = o.getLong("ts")
                if (nowMs - ts > 20 * 60_000L) continue
                val v = Vehicle(
                    lat = o.getDouble("lat"), lng = o.getDouble("lng"),
                    line = o.getString("line"), trip = o.getString("trip"),
                    delay = o.getInt("delay"), train = false,
                    agency = o.optString("agency"), state = o.getInt("state"),
                    cis = o.getString("cis"), id = o.getInt("id"),
                    accessible = if (o.has("low")) o.getBoolean("low") else null,
                    airConditioned = if (o.has("ac")) o.getBoolean("ac") else null,
                )
                lastSeen[keyOf(v)] = v to ts
            }
            vehicles = lastSeen.values.map { it.first }
        }
    }

    private fun saveSnapshot() {
        val f = snapshotFile ?: return
        runCatching {
            val arr = JSONArray()
            for ((v, ts) in lastSeen.values) {
                arr.put(JSONObject().apply {
                    put("lat", v.lat); put("lng", v.lng); put("line", v.line)
                    put("trip", v.trip); put("delay", v.delay); put("agency", v.agency)
                    put("state", v.state); put("cis", v.cis); put("id", v.id); put("ts", ts)
                    put("low", v.accessible); put("ac", v.airConditioned)
                })
            }
            f.writeText(arr.toString())
        }
    }


    @Synchronized
    fun refresh(maxAgeMs: Long = 0): List<Vehicle> {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < maxAgeMs && vehicles.isNotEmpty()) {
            return vehicles
        }

        val list = parseTraffic(JSONObject(fetchBody()))
        val newKeys = list.map { keyOf(it) }.toSet()
        val nowMs = System.currentTimeMillis()
        // vozidla držená serverem mají stáří v ageSec, ať "před X min" sedí
        for (v in list) lastSeen[keyOf(v)] = v to (nowMs - v.ageSec * 1000L)

        val missing = lastSeen.keys.filter { it !in newKeys }
        val outage = missing.size >= OUTAGE_MIN
        if (!outage) missing.forEach { lastSeen.remove(it) }

        lastSeen.entries.removeAll { (_, p) ->
            val age = nowMs - p.second
            val keepMs = if (p.first.id in 300000..309999) 20 * 60_000L else 10 * 60_000L
            age > keepMs || (p.first.state !in 0..1 && age > 30_000)
        }
        heldCount = list.count { it.ageSec > 30 } +
                (if (outage) lastSeen.keys.count { it !in newKeys } else 0)
        vehicles = lastSeen.values.map { it.first }

        val keyMap = HashMap<String, MutableList<Vehicle>>()
        for (v in vehicles) v.vehicleKey?.let { keyMap.getOrPut(it) { mutableListOf() }.add(v) }
        byKey = keyMap
        lastFetchTime = now
        saveSnapshot()
        return vehicles
    }



    private fun parseTraffic(data: JSONObject): List<Vehicle> {
        val out = ArrayList<Vehicle>()
        val arr = data.optJSONArray("VehicleList") ?: return out
        val now = nowPrague()

        var dropState = 0; var dropZero = 0; var dropStale = 0

        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val lat = e.optDouble("Latitude", 0.0)
            val lon = e.optDouble("Longitude", 0.0)
            val state = e.optInt("State", 255)
            val id = e.optInt("ID", 0)
            val srvAge = e.optInt("_ageSec", 0)

            if (state == 255 || lat == 0.0 || lon == 0.0) { if (state == 255) dropState++ else dropZero++; continue }

            val actStr = e.optStringClean("LastActivityDT", "")
            val actDt = runCatching { java.time.OffsetDateTime.parse(actStr) }.getOrNull()


            val ageLimitMin = if (id in 300000..309999) 45 else 10
            if (srvAge == 0 && actDt != null && Duration.between(actDt.toInstant(), java.time.Instant.now()).abs().toMinutes() > ageLimitMin) {
                dropStale++
                continue
            }

            val cisInt = e.optInt("CISLineID", 0)
            val cisStr = String.format(Locale.ROOT, "%06d", cisInt)
            val routeId = e.optStringClean("RouteID", "")
            val lineName = e.optStringClean("qride_linename", e.optStringClean("LineID", "?"))
            val agency = e.optStringClean("agency", e.optStringClean("agency_name", ""))
            val destination = e.optStringClean("headsign", e.optStringClean("destination", e.optStringClean("last_stop", "")))
            val lastStop = e.optStringClean("last_stop_name", e.optStringClean("last_stop", e.optStringClean("stop_name", "")))
            val delay = e.optInt("Delay", 0)
            val low = if (e.has("HasLowfloor") && !e.isNull("HasLowfloor")) e.optBoolean("HasLowfloor") else null
            val ac = if (e.has("isAirConditioned") && !e.isNull("isAirConditioned")) e.optBoolean("isAirConditioned") else null

            out.add(
                Vehicle(
                    lat = lat,
                    lng = lon,
                    line = lineName,
                    trip = routeId,
                    delay = delay,
                    train = false,
                    destination = prettyName(destination),
                    agency = agency,
                    lastStop = prettyName(lastStop),
                    accessible = low,
                    airConditioned = ac,
                    state = state,
                    cis = cisStr,
                    id = id,
                    ageSec = srvAge
                )
            )
        }

        android.util.Log.d("Traffic", "raw=${arr.length()} kept=${out.size} state255=$dropState zero=$dropZero stale=$dropStale noCis=${out.count { it.cis == "000000" }}")
        return out
    }

    fun live(tt: Timetable, leg: Leg, day: LocalDate): Vehicle? {
        val t = tt.trips[leg.tid] ?: return null
        val relSec = Duration.between(day.atStartOfDay(), nowPrague()).seconds.toInt()
        val key = "${t.cis}:${t.num}"

        val matches = byKey[key] ?: return null
        for (v in matches) {
            if (v.state in 0..1 && v.delay > -30 && leg.t0 - 3600 <= relSec && relSec <= leg.t1 + 3600) {
                return v
            }
        }
        return null
    }
}

// ───────────────────────── OSRM & Geometry ─────────────────────────

class OsrmClient(private val cacheDir: File) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val fastClient = client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    fun pathFor(stops: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (stops.size < 2) return stops

        // řídké waypointy: první, poslední a body ≥ 700 m od posledního vybraného
        val way = ArrayList<Pair<Double, Double>>()
        way.add(stops.first())
        for (i in 1 until stops.size - 1) {
            if (distM(way.last(), stops[i]) >= 700.0) way.add(stops[i])
        }
        way.add(stops.last())
        if (way.size < 2) return stops

        val key = md5("v2;" + way.joinToString(";") { "${it.first},${it.second}" })
        val cacheFile = File(cacheDir, "routes/$key.json")

        if (cacheFile.exists()) {
            runCatching {
                val json = JSONArray(cacheFile.readText())
                val res = ArrayList<Pair<Double, Double>>()
                for (i in 0 until json.length()) {
                    val p = json.getJSONArray(i)
                    res.add(p.getDouble(0) to p.getDouble(1))
                }
                return res
            }
        }

        try {
            val out = ArrayList<Pair<Double, Double>>()
            for (i in 0 until way.size - 1 step 80) {
                val chunk = way.subList(i, minOf(way.size, i + 81))
                val coordsStr = chunk.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.second, it.first) }
                val radStr = List(chunk.size) { "30" }.joinToString(";")
                val url = "https://router.project-osrm.org/route/v1/driving/$coordsStr" +
                        "?overview=full&geometries=geojson&continue_straight=false&radiuses=$radStr"
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return stops
                    val body = resp.body?.string() ?: return stops
                    val routes = JSONObject(body).optJSONArray("routes") ?: return stops
                    if (routes.length() == 0) return stops
                    val coords = routes.getJSONObject(0).optJSONObject("geometry")
                        ?.optJSONArray("coordinates") ?: return stops
                    for (j in 0 until coords.length()) {
                        val pt = coords.getJSONArray(j)
                        out.add(pt.getDouble(1) to pt.getDouble(0))
                    }
                }
            }
            if (out.isNotEmpty()) {
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    val arr = JSONArray()
                    for (pt in out) arr.put(JSONArray().put(pt.first).put(pt.second))
                    cacheFile.writeText(arr.toString())
                }
                return out
            }
        } catch (e: Exception) { }
        return stops
    }

    private fun distM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dy = (b.first - a.first) * 110540.0
        val dx = (b.second - a.second) * 111320.0 * Math.cos(Math.toRadians(a.first))
        return Math.hypot(dx, dy)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Vzdálenosti po silnici z bodu `from` ke každému bodu v `to` (metry). null při chybě. */
    fun roadDistances(
        from: Pair<Double, Double>,
        to: List<Pair<Double, Double>>
    ): List<Double?>? {
        if (to.isEmpty()) return emptyList()
        return try {
            val coords = (listOf(from) + to).joinToString(";") {
                String.format(Locale.US, "%.6f,%.6f", it.second, it.first)
            }
            val dests = (1..to.size).joinToString(";")
            val url = "https://router.project-osrm.org/table/v1/driving/$coords" +
                    "?sources=0&destinations=$dests&annotations=distance"
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val row = JSONObject(body).optJSONArray("distances")?.optJSONArray(0) ?: return null
                List(to.size) { i -> if (row.isNull(i)) null else row.getDouble(i) }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun clearCache(): Int {
        val dir = File(cacheDir, "routes")
        if (!dir.exists()) return 0
        val files = dir.listFiles() ?: return 0
        var count = 0
        for (f in files) {
            if (f.delete()) count++
        }
        return count
    }

}