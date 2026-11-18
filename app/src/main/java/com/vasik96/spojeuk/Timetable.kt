package com.vasik96.spojeuk

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Pěší přesuny mezi blízkými zastávkami (stopId → [(stopId, sekundy)]). */
object FootGraph {
    private fun cellKey(y: Int, x: Int): Long = (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)

    fun build(stops: Map<Int, Stop>): Map<Int, List<Pair<Int, Int>>> {
        val cells = HashMap<Long, MutableList<Int>>()
        for ((sid, stop) in stops) {
            if (!stop.hasCoords) continue
            cells.getOrPut(cellKey((stop.lat / 0.003).toInt(), (stop.lng / 0.0045).toInt())) { ArrayList(4) }.add(sid)
        }
        val foot = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for ((cell, ids) in cells) {
            val cy = (cell shr 32).toInt()
            val cx = cell.toInt()
            for (a in ids) {
                val sa = stops[a] ?: continue
                for (dy in -1..1) for (dx in -1..1) {
                    val neighbours = cells[cellKey(cy + dy, cx + dx)] ?: continue
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
    val trips = HashMap<Int, DbTrip>(1 shl 19)
    val svcs = HashMap<Int, SvcInfo>()
    val svcDates = HashMap<String, MutableList<SvcDateInfo>>()
    val meta = HashMap<String, String>()

    /** DB je ve verzi se sloupcem gid (starší static.sqlite ho nemá). */
    var hasGid = false
        private set

    private val tripsByKey = HashMap<String, MutableList<Int>>()          // "cis:num" → spoje
    private val tripsByTrain = HashMap<String, MutableList<Int>>()        // číslo vlaku → spoje
    private val activeCache = ConcurrentHashMap<LocalDate, Set<Int>>()
    private val timesCache = ConcurrentHashMap<Int, TripTimes>()
    private val lineIndexes = ConcurrentHashMap<List<String>, Map<String, List<Int>>>()

    // Jedno sdílené spojení pro čtení časů spojů. Dřív se DB otevírala a zavírala při každém dotazu (a ty se dělají
    // pro stovky spojů najednou při párování vozidel), což bylo hlavně v debug buildu extrémně pomalé.
    private val dbLock = Any()
    private var sharedDb: SQLiteDatabase? = null

    private fun readDb(): SQLiteDatabase = synchronized(dbLock) {
        sharedDb?.takeIf { it.isOpen } ?: SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).also { sharedDb = it }
    }

    /** Zavře sdílené spojení (volat při zahazování tohoto jízdního řádu). */
    fun close() = synchronized(dbLock) {
        runCatching { sharedDb?.close() }
        sharedDb = null
    }

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
            // linky, dopravci, cíle a čísla ČIS se opakují u statisíců spojů; sdílená instance šetří paměť i GC
            val pool = HashMap<String, String>(8192)
            fun intern(x: String?): String? = if (x == null) null else pool.getOrPut(x) { x }
            db.forEachRow("SELECT id, line, agency, cis, num, head, svc, wheel, t0, t1$gidCol FROM trip") { c ->
                val id = c.getInt(0)
                val trip = DbTrip(
                    id = id,
                    line = intern(c.getString(1)) ?: "?",
                    agency = intern(c.getString(2)) ?: "",
                    cis = intern(c.getString(3)) ?: "",
                    num = intern(c.getString(4)) ?: "",
                    head = intern(c.getString(5)) ?: "",
                    svc = c.getInt(6),
                    wheel = c.getInt(7),
                    t0 = c.getInt(8),
                    t1 = c.getInt(9),
                    gid = if (hasGid) c.getString(10) ?: "" else "",
                )
                trips[id] = trip
                if (trip.cis.isNotBlank()) tripsByKey.getOrPut("${trip.cis}:${trip.num}") { mutableListOf() }.add(id)
                if (trip.isTrain && trip.num.isNotBlank()) tripsByTrain.getOrPut(trip.num) { mutableListOf() }.add(id)
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

    // ───────── druhy dopravy na zastávkách ─────────

    /**
     * Názvy zastávek, kde staví vlak / autobus (jedním průchodem přes trip_times).
     * Párované nádraží + autobusové stanoviště se stejným názvem je v obou množinách.
     * Drahé (čte všechny spoje), proto volat jen mimo hlavní vlákno, jednou.
     */
    fun computeStopModes(): StopModes {
        val cache = File(dbFile.parentFile, "stopmodes.cache")
        val stamp = "${dbFile.length()}:${dbFile.lastModified()}:${meta["tag"]}:${meta["trips"]}"
        readStopModesCache(cache, stamp)?.let { return it }

        val rail = HashSet<Int>()
        val bus = HashSet<Int>()
        val db = readDb()
        // jen sloupec stops (arr/dep se nečtou), druh spoje se zjišťuje z paměti
        db.rawQuery("SELECT trip, stops FROM trip_times", null).use { c ->
            while (c.moveToNext()) {
                val target = if (trips[c.getInt(0)]?.isTrain == true) rail else bus
                for (sid in bytesToIntArray(c.getBlob(1))) target.add(sid)
            }
        }
        fun names(ids: Set<Int>): Set<String> =
            ids.mapNotNullTo(HashSet()) { stops[it]?.name }
        val modes = StopModes(railNames = names(rail), busNames = names(bus))
        runCatching { writeStopModesCache(cache, stamp, modes) }
        return modes
    }

    // výsledek se uloží vedle DB; po dalším startu se jen načte (stovky MB trip_times se znovu neprocházejí)
    private fun writeStopModesCache(f: File, stamp: String, m: StopModes) {
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(stamp); w.newLine()
            w.write("\u0001R"); w.newLine()
            for (n in m.railNames) { w.write(n.replace('\n', ' ')); w.newLine() }
            w.write("\u0001B"); w.newLine()
            for (n in m.busNames) { w.write(n.replace('\n', ' ')); w.newLine() }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun readStopModesCache(f: File, stamp: String): StopModes? = runCatching {
        if (!f.exists()) return null
        val rail = HashSet<String>()
        val bus = HashSet<String>()
        f.bufferedReader(Charsets.UTF_8).use { r ->
            if (r.readLine() != stamp) return null
            var target: HashSet<String>? = null
            while (true) {
                val line = r.readLine() ?: break
                when (line) {
                    "\u0001R" -> target = rail
                    "\u0001B" -> target = bus
                    else -> target?.add(line)
                }
            }
        }
        if (rail.isEmpty() && bus.isEmpty()) null else StopModes(rail, bus)
    }.getOrNull()

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
        val db = readDb()
        db.rawQuery("SELECT stops, arr, dep FROM trip_times WHERE trip=?", arrayOf(tid.toString())).use { c ->
            if (!c.moveToFirst()) return null
            val t = TripTimes(bytesToIntArray(c.getBlob(0)), bytesToIntArray(c.getBlob(1)), bytesToIntArray(c.getBlob(2)))
            if (timesCache.size > MAX_TIMES_CACHE) timesCache.clear()   // pojistka proti nekonečnému růstu
            timesCache[tid] = t
            return t
        }
    }

    fun tripStops(tid: Int): IntArray = tripTimes(tid)?.stops ?: IntArray(0)

    // ───────── dohledání spoje ─────────

    /** Spoj podle čísla z ČIS (cis + číslo spoje), preferuje instanci nejbližší v čase (dnes / včera). */
    fun findTripId(cis: String, num: String): Int? {
        val c = cis.trim().padStart(6, '0')
        if (c == "000000" || num.isBlank()) return null
        return nearestInTime(tripsByKey["$c:$num"] ?: return null)
    }

    /** Vlak podle čísla (tn ze SŽ, např. "1193"), preferuje instanci nejbližší v čase (dnes / včera). */
    fun findTrainTripId(trainNumber: String): Int? {
        val n = trainNumber.trim().trimStart('0')
        if (n.isEmpty()) return null
        return nearestInTime(tripsByTrain[n] ?: return null)
    }

    /**
     * Vlak podle čísla bez ohledu na kalendář: když dnes v okolí ±2 h nejede (souprava stojí po oběhu, číslo z jiného
     * dne), spoj se stejným číslem, jehož jízda je denní dobou nejblíž teď. Trasa vlaku s jedním číslem bývá stejná.
     */
    fun findTrainTripIdAnyDay(trainNumber: String): Int? {
        findTrainTripId(trainNumber)?.let { return it }
        val n = trainNumber.trim().trimStart('0')
        val candidates = tripsByTrain[n] ?: return null
        val now = nowPrague()
        val rel = secondsSinceMidnight(now.toLocalDate(), now)
        return candidates.minByOrNull { tid ->
            val t = trips[tid] ?: return@minByOrNull Int.MAX_VALUE
            val gap = when { rel < t.t0 -> t.t0 - rel; rel > t.t1 -> rel - t.t1; else -> 0 }.toInt()
            minOf(gap, 86_400 - gap)
        }
    }

    /** Linka vlaku podle čísla (S88, U5, …), i když spoj teď nejede. */
    fun trainLine(trainNumber: String): String? {
        val n = trainNumber.trim().trimStart('0')
        if (n.isEmpty()) return null
        return tripsByTrain[n]?.firstNotNullOfOrNull { trips[it]?.line?.takeIf { l -> l.isNotBlank() && l != "?" } }
    }

    /**
     * Číslo vlaku → linka (U32, Ex6, …), pro malou tabulku na disku, díky které mají vlaky linku hned po startu,
     * ještě než se dočte celý jízdní řád. Linka se u čísla vlaku prakticky nemění; bere se první platná.
     */
    fun trainLineTable(): Map<String, String> {
        val out = HashMap<String, String>(tripsByTrain.size * 2)
        for ((num, tids) in tripsByTrain) {
            for (tid in tids) {
                val line = trips[tid]?.line ?: continue
                if (line.isNotBlank() && line != "?") { out[num.trimStart('0')] = line; break }
            }
        }
        return out
    }

    private fun nearestInTime(candidates: List<Int>): Int? {
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
        // computeIfAbsent: index se postaví jen jednou. Kotlinské getOrPut na ConcurrentHashMap není atomické
        // a při desítkách souběžných dotazů (každé vozidlo zvlášť) stavělo desítky indexů najednou -> OutOfMemoryError.
        lineIndexes.computeIfAbsent(agencyKeywords) { buildLineIndex(it) }[normLine(line)].orEmpty()

    private fun buildLineIndex(keywords: List<String>): Map<String, List<Int>> {
        val all = trips.values
        val matching: Collection<DbTrip> = if (keywords.isEmpty()) all
        else all.filter { t -> foldText(t.agency).let { a -> keywords.any { it in a } } }
        // když jízdní řád dopravce vůbec neobsahuje, raději hledat mezi všemi než nic
        val pool = matching.ifEmpty { all }
        val normCache = HashMap<String, String>()   // stejných linek je pár set, spojů statisíce
        val out = HashMap<String, MutableList<Int>>()
        for (t in pool) out.getOrPut(normCache.getOrPut(t.line) { normLine(t.line) }) { ArrayList() }.add(t.id)
        return out
    }

    companion object {
        private const val MAX_TIMES_CACHE = 20_000

        /**
         * Jen zastávky (desítky ms), bez načítání statisíců spojů. Pro rychlý start: vyhledávání zastávek
         * a nejbližší zastávka podle polohy nemusí čekat, než se načte celý jízdní řád.
         */
        fun readStops(dbFile: File): List<Stop> {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("SELECT id, name, place, lat, lon, inreg FROM stop", null).use { c ->
                    val out = ArrayList<Stop>(c.count)
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: ""
                        out += Stop(
                            id = c.getInt(0).toString(),
                            name = name,
                            lat = if (c.isNull(3)) 0.0 else c.getDouble(3),
                            lng = if (c.isNull(4)) 0.0 else c.getDouble(4),
                            place = c.getString(2) ?: name,
                            inreg = c.getInt(5) == 1,
                        )
                    }
                    return out
                }
            } finally {
                db.close()
            }
        }

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

/** Názvy zastávek podle druhu dopravy (pro filtr Vše / Autobusy / Vlaky). */
class StopModes(val railNames: Set<String>, val busNames: Set<String>)
