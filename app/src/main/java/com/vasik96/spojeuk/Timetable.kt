package com.vasik96.spojeuk

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Pěší přesuny mezi blízkými zastávkami (stopId → [(stopId, sekundy)]). */
object FootGraph {
    fun build(stops: Map<Int, Stop>): Map<Int, List<Pair<Int, Int>>> {
        val cells = HashMap<Pair<Int, Int>, MutableList<Int>>()
        for ((sid, stop) in stops) {
            if (!stop.hasCoords) continue
            cells.getOrPut((stop.lat / 0.003).toInt() to (stop.lng / 0.0045).toInt()) { mutableListOf() }.add(sid)
        }
        val foot = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for ((cell, ids) in cells) {
            for (a in ids) {
                val sa = stops[a] ?: continue
                for (dy in -1..1) for (dx in -1..1) {
                    val neighbours = cells[(cell.first + dy) to (cell.second + dx)] ?: continue
                    for (b in neighbours) {
                        if (b == a) continue
                        val sb = stops[b] ?: continue
                        val d = Geo.distanceM(sa.lat, sa.lng, sb.lat, sb.lng)
                        if (d <= FOOT_MAX_M) {
                            foot.getOrPut(a) { mutableListOf() }.add(b to maxOf(60, (d / WALK_MS).toInt()))
                        }
                    }
                }
            }
        }
        return foot
    }
}

/**
 * Statický jízdní řád načtený ze static.sqlite. Jen data a jednoduché dotazy;
 * hledání spojení je v Net/Planner, párování živých vozů v TripLocator.
 */
class Timetable(val dbFile: File) {

    val stops = HashMap<Int, Stop>()
    val places = HashMap<String, MutableList<Int>>()
    val trips = HashMap<Int, DbTrip>()
    val svcs = HashMap<Int, SvcInfo>()
    val svcDates = HashMap<String, MutableList<SvcDateInfo>>()
    val meta = HashMap<String, String>()

    /** DB je ve verzi se sloupcem gid (starší static.sqlite ho nemá). */
    var hasGid = false
        private set

    private val tripsByKey = HashMap<String, MutableList<Int>>()          // "cis:num" → spoje
    private val activeCache = ConcurrentHashMap<LocalDate, Set<Int>>()
    private val timesCache = ConcurrentHashMap<Int, TripTimes>()
    private val lineIndexes = ConcurrentHashMap<List<String>, Map<String, List<Int>>>()

    val foot: Map<Int, List<Pair<Int, Int>>> by lazy { FootGraph.build(stops) }
    val locator: TripLocator by lazy { TripLocator(this) }

    init {
        load()
    }

    // ───────── načítání ─────────

    private inline fun SQLiteDatabase.forEachRow(sql: String, row: (Cursor) -> Unit) {
        rawQuery(sql, null).use { c -> while (c.moveToNext()) row(c) }
    }

    private fun load() {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.forEachRow("SELECT k, v FROM meta") { meta[it.getString(0)] = it.getString(1) }

            db.forEachRow("SELECT id, name, place, lat, lon, inreg FROM stop") { c ->
                val id = c.getInt(0)
                val name = c.getString(1) ?: ""
                val place = c.getString(2) ?: name
                stops[id] = Stop(
                    id = id.toString(),
                    name = name,
                    lat = if (c.isNull(3)) 0.0 else c.getDouble(3),
                    lng = if (c.isNull(4)) 0.0 else c.getDouble(4),
                    place = place,
                    inreg = c.getInt(5) == 1,
                )
                places.getOrPut(place) { mutableListOf() }.add(id)
            }

            db.forEachRow("PRAGMA table_info(trip)") { if (it.getString(1) == "gid") hasGid = true }

            val gidCol = if (hasGid) ", gid" else ""
            db.forEachRow("SELECT id, line, agency, cis, num, head, svc, wheel, t0, t1$gidCol FROM trip") { c ->
                val id = c.getInt(0)
                val trip = DbTrip(
                    id = id,
                    line = c.getString(1) ?: "?",
                    agency = c.getString(2) ?: "",
                    cis = c.getString(3) ?: "",
                    num = c.getString(4) ?: "",
                    head = c.getString(5) ?: "",
                    svc = c.getInt(6),
                    wheel = c.getInt(7),
                    t0 = c.getInt(8),
                    t1 = c.getInt(9),
                    gid = if (hasGid) c.getString(10) ?: "" else "",
                )
                trips[id] = trip
                if (trip.cis.isNotBlank()) tripsByKey.getOrPut("${trip.cis}:${trip.num}") { mutableListOf() }.add(id)
            }

            db.forEachRow("SELECT id, dow, d0, d1 FROM svc") { c ->
                val id = c.getInt(0)
                svcs[id] = SvcInfo(id, c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "")
            }

            db.forEachRow("SELECT svc, d, type FROM svc_date") { c ->
                val d = c.getString(1) ?: ""
                svcDates.getOrPut(d) { mutableListOf() }.add(SvcDateInfo(c.getInt(0), d, c.getInt(2)))
            }
        } finally {
            db.close()
        }
    }

    // ───────── kalendář ─────────

    fun activeSvcs(d: LocalDate): Set<Int> = activeCache.getOrPut(d) {
        val ds = d.format(DateTimeFormatter.BASIC_ISO_DATE)
        val weekday = d.dayOfWeek.value - 1 // po = 0 … ne = 6
        val active = HashSet<Int>()
        for ((sid, svc) in svcs) {
            if (svc.d0 <= ds && ds <= svc.d1 && svc.dow.getOrNull(weekday) == '1') active.add(sid)
        }
        for (e in svcDates[ds].orEmpty()) {
            if (e.type == 1) active.add(e.svc) else if (e.type == 2) active.remove(e.svc)
        }
        active
    }

    // ───────── časy spoje ─────────

    fun tripTimes(tid: Int): TripTimes? {
        timesCache[tid]?.let { return it }
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT stops, arr, dep FROM trip_times WHERE trip=?", arrayOf(tid.toString())).use { c ->
                if (!c.moveToFirst()) return null
                val t = TripTimes(bytesToIntArray(c.getBlob(0)), bytesToIntArray(c.getBlob(1)), bytesToIntArray(c.getBlob(2)))
                timesCache[tid] = t
                return t
            }
        } finally {
            db.close()
        }
    }

    fun tripStops(tid: Int): IntArray = tripTimes(tid)?.stops ?: IntArray(0)

    // ───────── dohledání spoje ─────────

    /** Spoj podle čísla z ČIS (cis + číslo spoje), preferuje instanci nejbližší v čase (dnes / včera). */
    fun findTripId(cis: String, num: String): Int? {
        val c = cis.trim().padStart(6, '0')
        if (c == "000000" || num.isBlank()) return null
        val candidates = tripsByKey["$c:$num"] ?: return null
        val now = nowPrague()
        var best: Int? = null
        var bestGap = Int.MAX_VALUE
        for (daysBack in 0L..1L) {
            val day = now.toLocalDate().minusDays(daysBack)
            val rel = secondsSinceMidnight(day, now)
            val active = activeSvcs(day)
            for (tid in candidates) {
                val t = trips[tid] ?: continue
                if (t.svc !in active) continue
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

    /** Spoje linky [line] (volitelně jen dopravce, jehož název obsahuje některé z [agencyKeywords]). */
    fun tripsOfLine(line: String, agencyKeywords: List<String>): List<Int> =
        lineIndexes.getOrPut(agencyKeywords) { buildLineIndex(agencyKeywords) }[normLine(line)].orEmpty()

    private fun buildLineIndex(keywords: List<String>): Map<String, List<Int>> {
        val ofAgency = if (keywords.isEmpty()) trips.values.toList()
        else trips.values.filter { t -> foldText(t.agency).let { a -> keywords.any { it in a } } }
        // když jízdní řád dopravce vůbec neobsahuje, raději hledat mezi všemi než nic
        val pool = ofAgency.ifEmpty { trips.values.toList() }
        return pool.groupBy({ normLine(it.line) }, { it.id })
    }

    companion object {
        /** Jen metadata (verze, datum sestavení), bez načítání celé databáze. */
        fun readMeta(dbFile: File): Map<String, String> {
            val out = HashMap<String, String>()
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("SELECT k, v FROM meta", null).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
            } finally {
                db.close()
            }
            return out
        }
    }
}
