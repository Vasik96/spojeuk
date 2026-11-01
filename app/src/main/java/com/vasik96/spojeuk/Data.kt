package com.vasik96.spojeuk

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

// Import statických jízdních řádů: stažený tar → gtfs.zip → SQLite (static.sqlite).
// Konstanty (BBOX_LAT, ...) jsou v Config.kt, pomocné funkce (parseTsec, intArrayToBytes, nowPrague) v Util.kt.

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

// ───────────────────────── GTFS → SQLite ─────────────────────────

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