package com.vasik96.spojeuk

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Pěší přesuny mezi blízkými zastávkami (stopId → [(stopId, sekundy)]). */
object FootGraph {
    /**
     * Zastávka → [soused, čas chůze s, soused, čas, …] (prokládané, bez zabalených Pair<Int, Int>).
     * Mřížka ~330 × 330 m jako seřazená pole (klíč buňky → zastávky), sousedé z 3 × 3 buněk. Bez map s klíči
     * Long / Int: ty při startu zabalily miliony čísel (desítky MB odpadu souběžně s prvním hledáním).
     */
    fun build(stops: Map<Int, Stop>): IntCsr {
        val n0 = stops.size
        val ids = IntArray(n0)
        val lat = DoubleArray(n0)
        val lng = DoubleArray(n0)
        val key = LongArray(n0)
        var n = 0
        var maxId = 0
        for ((sid, stop) in stops) {
            if (!stop.hasCoords) continue
            ids[n] = sid; lat[n] = stop.lat; lng[n] = stop.lng
            key[n] = cellKey((stop.lat / 0.003).toInt(), (stop.lng / 0.0045).toInt())
            if (sid > maxId) maxId = sid
            n++
        }
        // pořadí zastávek podle buňky
        val order = IntArray(n) { it }
        sortIds(order) { a, b -> key[a].compareTo(key[b]).let { if (it != 0) it else a.compareTo(b) } }
        val sortedKey = LongArray(n) { key[order[it]] }

        fun firstOf(k: Long): Int {   // první pozice v sortedKey s klíčem >= k
            var lo = 0; var hi = n
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (sortedKey[mid] < k) lo = mid + 1 else hi = mid }
            return lo
        }

        val b = IntCsr.Builder(maxId)
        // dva průchody (počet, pak zápis), sousedé ve stejném pořadí
        for (pass in 0..1) {
            if (pass == 1) b.alloc()
            for (ia in 0 until n) {
                val ka = key[ia]
                val cy = (ka shr 32).toInt()
                val cx = ka.toInt()
                var cnt = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val k = cellKey(cy + dy, cx + dx)
                    var p = firstOf(k)
                    while (p < n && sortedKey[p] == k) {
                        val ib = order[p++]
                        if (ib == ia) continue
                        val d = Geo.distanceM(lat[ia], lng[ia], lat[ib], lng[ib])
                        if (d <= FOOT_MAX_M) {
                            if (pass == 0) cnt += 2
                            else { b.put(ids[ia], ids[ib]); b.put(ids[ia], maxOf(60, (d / WALK_MS).toInt())) }
                        }
                    }
                }
                if (pass == 0 && cnt > 0) b.count(ids[ia], cnt)
            }
        }
        return b.build()
    }

    private fun cellKey(y: Int, x: Int): Long = (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)
}

/**
 * Seznamy intů podle id (CSR: začátky + jedno pole dat), náhrada Map<Int, IntArray> přes desítky tisíc zastávek:
 * místo ~3 malých objektů na zastávku (zabalené id, uzel mapy, pole) dvě velká pole, která GC nepřesouvá.
 * Seznam id je `data[from(id) until to(id)]`.
 */
class IntCsr private constructor(private val start: IntArray, val data: IntArray) {
    fun from(id: Int): Int = if (id >= 0 && id < start.size - 1) start[id] else 0
    fun to(id: Int): Int = if (id >= 0 && id < start.size - 1) start[id + 1] else 0

    /** Počet id s neprázdným seznamem. */
    val size: Int by lazy { (0 until start.size - 1).count { start[it + 1] > start[it] } }

    /** Dva průchody: [count] pro všechna id, [alloc], pak [put] ve stejném pořadí. */
    class Builder(maxId: Int) {
        private val start = IntArray(maxId + 2)
        private var fill = IntArray(0)
        private var data = IntArray(0)
        fun count(id: Int, n: Int) { start[id + 1] += n }
        fun alloc() {
            for (i in 1 until start.size) start[i] += start[i - 1]
            data = IntArray(start[start.size - 1])
            fill = start.copyOf()
        }
        fun put(id: Int, v: Int) { data[fill[id]++] = v }
        fun build() = IntCsr(start, data)
    }
}

/** Rostoucí pole intů bez zabalování (pro velké indexy plánovače). */
class IntList(cap: Int = 4) {
    private var a = IntArray(cap)
    var size = 0
        private set
    fun add(x: Int) {
        if (size == a.size) a = a.copyOf(maxOf(4, size * 2))
        a[size++] = x
    }
    fun toArray(): IntArray = a.copyOf(size)
    operator fun get(i: Int): Int = a[i]
}

/**
 * Statický jízdní řád načtený ze static.sqlite. Jen data a jednoduché dotazy;
 * hledání spojení je v Net/Planner, párování živých vozů v TripLocator.
 */
class Timetable(val dbFile: File) {

    val stops = HashMap<Int, Stop>()
    /** Zastávky podle id jako pole: [stop] bez zabalení id (horké smyčky párování vozidel). */
    private var stopArr = arrayOfNulls<Stop>(0)

    fun stop(id: Int): Stop? = if (id >= 0 && id < stopArr.size) stopArr[id] else null

    private val foldedHeads = ConcurrentHashMap<String, String>()

    /** foldText cíle spoje, jednou pro každý text (cílů jsou tisíce, kandidátních spojů při párování statisíce). */
    fun foldedHead(head: String): String = foldedHeads.getOrPut(head) { foldText(head) }
    val places = HashMap<String, MutableList<Int>>()
    /** Spoje po sloupcích (viz [TripTable]); naplní se v [load]. */
    lateinit var trips: TripTable
        private set
    val svcs = HashMap<Int, SvcInfo>()
    val svcDates = HashMap<String, MutableList<SvcDateInfo>>()
    val meta = HashMap<String, String>()

    /** DB je ve verzi se sloupcem gid (starší static.sqlite ho nemá). */
    var hasGid = false
        private set

    // Indexy spojů podle čísla: id seřazená podle (cis, num, id) / vlaky podle (num, id), hledá se půlením.
    // Dřív HashMap s klíčem "cis:num": ~50 MB dočasných řetězců a seznamů při startu => GC pauzy na UI vlákně.
    private var tripsByKey = IntArray(0)
    private var tripsByTrain = IntArray(0)
    private val gidCache = ConcurrentHashMap<Int, String>()

    /** GTFS trip_id spoje (klíč do shapes.sqlite), čte se z DB až když je potřeba; "" když DB sloupec nemá. */
    fun gidOf(tid: Int): String {
        if (!hasGid) return ""
        gidCache[tid]?.let { return it }
        val g = readDb().rawQuery("SELECT gid FROM trip WHERE id=?", arrayOf(tid.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else ""
        }
        if (gidCache.size > 5_000) gidCache.clear()
        gidCache[tid] = g
        return g
    }
    private val activeCache = ConcurrentHashMap<LocalDate, SvcSet>()
    private val timesCache = ConcurrentHashMap<Int, TripTimes>()
    private val lineIndexes = ConcurrentHashMap<List<String>, Map<String, IntArray>>()

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

    val foot: IntCsr by lazy { FootGraph.build(stops) }
    val locator: TripLocator by lazy { TripLocator(this) }
    /** Časy jízd pro stavbu sítě plánovače, namapované ze souboru (viz [NetCache]); první použití ho může vytvořit. */
    val netCache: NetCache by lazy { NetCache.open(dbFile) }

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

            // zastávky z rychlého startu (readStops) se převezmou: jinak by v paměti byly dvakrát (~75 tisíc objektů
            // a ~200 tisíc řetězců navíc, seznam drží vyhledávání zastávek)
            val stopList = sharedStops(dbFile) { readStopRows(db) }
            var maxStop = 0
            for (stop in stopList) {
                val id = stop.id.toInt()
                stops[id] = stop
                if (id > maxStop) maxStop = id
                places.getOrPut(stop.place) { mutableListOf() }.add(id)
            }
            stopArr = arrayOfNulls(maxStop + 1)
            for ((id, stop) in stops) if (id >= 0) stopArr[id] = stop

            db.forEachRow("PRAGMA table_info(trip)") { if (it.getString(1) == "gid") hasGid = true }

            loadTrips()

            db.forEachRow("SELECT id, dow, d0, d1 FROM svc") { c ->
                val id = c.getInt(0)
                svcs[id] = SvcInfo(id, c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "")
            }

            val datePool = CursorStringPool()   // ~100 tisíc řádků, ale jen pár set různých dat
            db.forEachRow("SELECT svc, d, type FROM svc_date") { c ->
                val d = datePool.get(c, 1) ?: ""
                svcDates.getOrPut(d) { mutableListOf() }.add(SvcDateInfo(c.getInt(0), d, c.getInt(2)))
            }
        } finally {
            db.close()
        }
    }

    /**
     * Tabulka spojů (statisíce řádků, hlavně textové sloupce) je nejdražší část načítání (~2,6 s v jednom vlákně):
     * čte se souběžně v několika spojeních nad rozsahy id, indexy podle čísla spoje / vlaku se postaví po spojení
     * ve stejném pořadí jako dřív (podle id).
     */
    private fun loadTrips() {
        // místo celého gid jen příznak vlaku (řetězce gid se nenačítají, viz DbTrip.isTrain)
        val gidCol = if (hasGid) ", gid LIKE 'czptt:%'" else ""
        val maxId = readDb().rawQuery("SELECT max(id) FROM trip", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val parts = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val step = maxId / parts + 1

        val table = TripTable(maxId)

        /** Zapisuje rovnou do sloupců (každá část má vlastní rozsah id, takže se vlákna nepřekrývají). */
        fun readPart(lo: Int, hi: Int): Int {
            var count = 0
            // linky, dopravci, cíle a čísla ČIS se opakují u statisíců spojů; sdílená instance šetří paměť i GC
            val pool = CursorStringPool()
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.forEachRow("SELECT id, line, agency, cis, num, head, svc, wheel, t0, t1$gidCol FROM trip WHERE id BETWEEN $lo AND $hi") { c ->
                    val id = c.getInt(0)
                    if (id < 0 || id > maxId) return@forEachRow
                    table.set(
                        id = id,
                        line = pool.get(c, 1) ?: "?",
                        agency = pool.get(c, 2) ?: "",
                        cis = pool.get(c, 3) ?: "",
                        num = pool.get(c, 4) ?: "",
                        head = pool.get(c, 5) ?: "",
                        svc = c.getInt(6),
                        wheel = c.getInt(7),
                        t0 = c.getInt(8),
                        t1 = c.getInt(9),
                        isTrain = hasGid && c.getInt(10) == 1,
                    )
                    count++
                }
            } finally {
                db.close()
            }
            return count
        }

        // Future.get() zaručí, že zápisy z vláken jsou tady vidět
        table.size = if (parts <= 1) readPart(0, maxId) else {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(parts)
            try {
                (0 until parts).map { i -> pool.submit<Int> { readPart(i * step, minOf(maxId, (i + 1) * step - 1)) } }
                    .sumOf { f -> try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e } }
            } finally {
                pool.shutdown()
            }
        }
        trips = table

        var nKey = 0
        var nTrain = 0
        table.forEachId { id ->
            if (table.cis[id]!!.isNotBlank()) nKey++
            if (table.train[id] && table.num[id]!!.isNotBlank()) nTrain++
        }
        val keyed = IntArray(nKey)
        val trains = IntArray(nTrain)
        var k = 0
        var tr = 0
        table.forEachId { id ->
            if (table.cis[id]!!.isNotBlank()) keyed[k++] = id
            if (table.train[id] && table.num[id]!!.isNotBlank()) trains[tr++] = id
        }
        val cisCol = table.cis
        val numCol = table.num
        sortIds(keyed) { a, b ->
            var r = cisCol[a]!!.compareTo(cisCol[b]!!)
            if (r == 0) r = numCol[a]!!.compareTo(numCol[b]!!)
            if (r == 0) r = a.compareTo(b)
            r
        }
        sortIds(trains) { a, b ->
            val r = numCol[a]!!.compareTo(numCol[b]!!)
            if (r != 0) r else a.compareTo(b)
        }
        tripsByKey = keyed
        tripsByTrain = trains
    }

    /** Spoje se stejným (cis, num) z [tripsByKey] (v pořadí id), nebo null. */
    private fun keyRange(cis: String, num: String): IntArray? =
        equalRange(tripsByKey) { id -> trips.cis[id]!!.compareTo(cis).let { if (it != 0) it else trips.num[id]!!.compareTo(num) } }

    /** Vlaky s číslem [num] z [tripsByTrain] (v pořadí id), nebo null. */
    private fun trainRange(num: String): IntArray? = equalRange(tripsByTrain) { id -> trips.num[id]!!.compareTo(num) }

    /** Souvislý úsek seřazeného [ids], kde [cmp] (spoj vs. hledaný klíč) vrací 0. */
    private inline fun equalRange(ids: IntArray, cmp: (Int) -> Int): IntArray? {
        var lo = 0
        var hi = ids.size
        while (lo < hi) {   // první >= klíč
            val mid = (lo + hi) ushr 1
            if (cmp(ids[mid]) < 0) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < ids.size && cmp(ids[end]) == 0) end++
        return if (end > lo) ids.copyOfRange(lo, end) else null
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
                val target = if (trips.isTrain(c.getInt(0))) rail else bus
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

    fun activeSvcs(d: LocalDate): SvcSet = activeCache.getOrPut(d) {
        val ds = d.format(DateTimeFormatter.BASIC_ISO_DATE)
        val weekday = d.dayOfWeek.value - 1 // po = 0 … ne = 6
        var max = 0
        for (sid in svcs.keys) if (sid > max) max = sid
        for (e in svcDates[ds].orEmpty()) if (e.svc > max) max = e.svc
        val active = BooleanArray(max + 1)
        for ((sid, svc) in svcs) {
            if (sid >= 0 && svc.d0 <= ds && ds <= svc.d1 && svc.dow.getOrNull(weekday) == '1') active[sid] = true
        }
        for (e in svcDates[ds].orEmpty()) {
            if (e.svc < 0) continue
            if (e.type == 1) active[e.svc] = true else if (e.type == 2) active[e.svc] = false
        }
        SvcSet(active)
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
        return nearestInTime(keyRange(c, num) ?: return null)
    }

    /** Vlak podle čísla (tn ze SŽ, např. "1193"), preferuje instanci nejbližší v čase (dnes / včera). */
    fun findTrainTripId(trainNumber: String): Int? {
        val n = trainNumber.trim().trimStart('0')
        if (n.isEmpty()) return null
        return nearestInTime(trainRange(n) ?: return null)
    }

    /**
     * Vlak podle čísla bez ohledu na kalendář: když dnes v okolí ±2 h nejede (souprava stojí po oběhu, číslo z jiného
     * dne), spoj se stejným číslem, jehož jízda je denní dobou nejblíž teď. Trasa vlaku s jedním číslem bývá stejná.
     */
    fun findTrainTripIdAnyDay(trainNumber: String): Int? {
        findTrainTripId(trainNumber)?.let { return it }
        val n = trainNumber.trim().trimStart('0')
        val candidates = trainRange(n) ?: return null
        val now = nowPrague()
        val rel = secondsSinceMidnight(now.toLocalDate(), now)
        return candidates.minByOrNull { tid ->
            if (!trips.has(tid)) return@minByOrNull Int.MAX_VALUE
            val t0 = trips.t0[tid]; val t1 = trips.t1[tid]
            val gap = when { rel < t0 -> t0 - rel; rel > t1 -> rel - t1; else -> 0 }.toInt()
            minOf(gap, 86_400 - gap)
        }
    }

    /** Linka vlaku podle čísla (S88, U5, …), i když spoj teď nejede. */
    fun trainLine(trainNumber: String): String? {
        val n = trainNumber.trim().trimStart('0')
        if (n.isEmpty()) return null
        return trainRange(n)?.asList()?.firstNotNullOfOrNull { trips.lineOf(it)?.takeIf { l -> l.isNotBlank() && l != "?" } }
    }

    /**
     * Číslo vlaku → linka (U32, Ex6, …), pro malou tabulku na disku, díky které mají vlaky linku hned po startu,
     * ještě než se dočte celý jízdní řád. Linka se u čísla vlaku prakticky nemění; bere se první platná.
     */
    fun trainLineTable(): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val ids = tripsByTrain
        while (i < ids.size) {   // skupiny se stejným číslem vlaku leží za sebou
            val num = trips.num[ids[i]]!!
            var j = i
            var found = false
            while (j < ids.size) {
                val id = ids[j]
                if (trips.num[id] != num) break
                val line = trips.line[id]!!
                if (!found && line.isNotBlank() && line != "?") { out[num.trimStart('0')] = line; found = true }
                j++
            }
            i = j
        }
        return out
    }

    private fun nearestInTime(candidates: IntArray): Int? {
        val now = nowPrague()
        var best: Int? = null
        var bestGap = Int.MAX_VALUE
        for (daysBack in 0L..1L) {
            val day = now.toLocalDate().minusDays(daysBack)
            val rel = secondsSinceMidnight(day, now)
            val active = activeSvcs(day)
            for (tid in candidates) {
                if (!trips.has(tid) || trips.svc[tid] !in active) continue
                val t0 = trips.t0[tid]; val t1 = trips.t1[tid]
                val gap = when {
                    rel < t0 -> t0 - rel
                    rel > t1 -> rel - t1
                    else -> 0
                }
                if (gap < bestGap) { bestGap = gap; best = tid }
            }
        }
        return if (bestGap <= 2 * 3600) best else null
    }

    /** Spoje linky [line] (volitelně jen dopravce, jehož název obsahuje některé z [agencyKeywords]). */
    fun tripsOfLine(line: String, agencyKeywords: List<String>): IntArray =
        // computeIfAbsent: index se postaví jen jednou. Kotlinské getOrPut na ConcurrentHashMap není atomické
        // a při desítkách souběžných dotazů (každé vozidlo zvlášť) stavělo desítky indexů najednou -> OutOfMemoryError.
        lineIndexes.computeIfAbsent(agencyKeywords) { buildLineIndex(it) }[normLine(line)] ?: IntArray(0)

    private fun buildLineIndex(keywords: List<String>): Map<String, IntArray> {
        val t = trips
        // dopravce vyhovuje? (počítá se jednou pro každý název dopravce, ne pro každý spoj)
        val agencyOk = HashMap<String, Boolean>()
        fun accepted(id: Int) = keywords.isEmpty() ||
            agencyOk.getOrPut(t.agency[id]!!) { foldText(t.agency[id]!!).let { a -> keywords.any { it in a } } }
        var any = false
        t.forEachId { if (!any && accepted(it)) any = true }
        // když jízdní řád dopravce vůbec neobsahuje, raději hledat mezi všemi než nic
        val all = !any
        val normCache = HashMap<String, String>()   // stejných linek je pár set, spojů statisíce
        val out = HashMap<String, IntList>()
        t.forEachId { id ->
            if (all || accepted(id)) {
                val l = t.line[id]!!
                out.getOrPut(normCache.getOrPut(l) { normLine(l) }) { IntList() }.add(id)
            }
        }
        val res = HashMap<String, IntArray>(out.size * 2)
        for ((k, v) in out) res[k] = v.toArray()
        return res
    }

    companion object {
        private const val MAX_TIMES_CACHE = 20_000

        /**
         * Jen zastávky (desítky ms), bez načítání statisíců spojů. Pro rychlý start: vyhledávání zastávek
         * a nejbližší zastávka podle polohy nemusí čekat, než se načte celý jízdní řád.
         */
        fun readStops(dbFile: File): List<Stop> = sharedStops(dbFile) {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                readStopRows(db)
            } finally {
                db.close()
            }
        }

        private class StopsCache(val stamp: String, val stops: List<Stop>)
        private val stopsLock = Any()
        private var lastStops: java.lang.ref.WeakReference<StopsCache>? = null

        private fun stamp(f: File) = "${f.path}:${f.length()}:${f.lastModified()}"

        /**
         * Zastávky souboru [dbFile] jen jednou: rychlé čtení pro vyhledávání ([readStops]) a načítání jízdního řádu
         * běží při startu souběžně a každé by mělo vlastní kopii (~75 tisíc objektů a ~200 tisíc řetězců navíc).
         * Druhé počká na první a dostane stejný seznam (drží se slabě, dokud ho někdo používá).
         */
        private fun sharedStops(dbFile: File, read: () -> List<Stop>): List<Stop> = synchronized(stopsLock) {
            val st = stamp(dbFile)
            lastStops?.get()?.takeIf { it.stamp == st }?.let { return it.stops }
            read().also { lastStops = java.lang.ref.WeakReference(StopsCache(st, it)) }
        }

        private fun readStopRows(db: SQLiteDatabase): List<Stop> =
            db.rawQuery("SELECT id, name, place, lat, lon, inreg FROM stop", null).use { c ->
                val out = ArrayList<Stop>(c.count)
                val pool = CursorStringPool()
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: ""
                    val rawPlace = pool.get(c, 2)
                    out += Stop(
                        id = c.getInt(0).toString(),
                        name = name,
                        lat = if (c.isNull(3)) 0.0 else c.getDouble(3),
                        lng = if (c.isNull(4)) 0.0 else c.getDouble(4),
                        // místo bývá stejné jako název: sdílený řetězec místo kopie
                        place = if (rawPlace == null || rawPlace == name) name else rawPlace,
                        inreg = c.getInt(5) == 1,
                    )
                }
                out
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

/** Porovnání dvou id bez zabalování čísel (obecné (Int, Int) -> Int lambdy čísla zabalují). */
fun interface IdComparator { fun compare(a: Int, b: Int): Int }

/** Stabilní řazení pole id (merge sort) s [cmp]; bez zabalených čísel, jedno pomocné pole. */
fun sortIds(ids: IntArray, cmp: IdComparator) {
    val n = ids.size
    if (n < 2) return
    var src = ids
    var dst = IntArray(n)
    var width = 1
    while (width < n) {
        var lo = 0
        while (lo < n) {
            val mid = minOf(lo + width, n)
            val hi = minOf(lo + 2 * width, n)
            var i = lo; var j = mid; var k = lo
            while (i < mid && j < hi) dst[k++] = if (cmp.compare(src[j], src[i]) < 0) src[j++] else src[i++]
            while (i < mid) dst[k++] = src[i++]
            while (j < hi) dst[k++] = src[j++]
            lo = hi
        }
        val t = src; src = dst; dst = t
        width *= 2
    }
    if (src !== ids) src.copyInto(ids)
}

/**
 * Aktivní kalendáře (svc) jednoho dne jako pole příznaků. `svc in set` nad Set<Int> zabalí číslo do objektu,
 * a to pro každý ze statisíců spojů při stavbě sítě (desítky MB odpadu => GC zasekávající UI).
 */
class SvcSet(private val mask: BooleanArray) {
    operator fun contains(svc: Int): Boolean = svc >= 0 && svc < mask.size && mask[svc]
}
