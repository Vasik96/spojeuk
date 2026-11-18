package com.vasik96.spojeuk

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

// Hledání spojení (algoritmus RAPTOR). Konstanty MIN_TRANSFER a NEXT_DAY_CUTOFF jsou v Config.kt.

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

/** Jízdní řád jednoho dne (včetně přesahu z včerejška a rána zítřka), časy v s od půlnoci dne. Staví [Net.build]. */
class Net private constructor(
    val tt: Timetable,
    val day: LocalDate,
    val patterns: List<Pattern>,
    val stopPats: Map<Int, List<Pair<Int, Int>>>,
) {
    /** Klíč vzoru = pořadí zastávek jako surové bajty z DB (bez převodu na List<Int>, který stál přes sekundu). */
    private class StopsKey(val bytes: ByteArray) {
        private val h = bytes.contentHashCode()
        override fun hashCode() = h
        override fun equals(other: Any?) = other is StopsKey && other.h == h && other.bytes.contentEquals(bytes)
    }

    companion object {
        /** Souběžná čtení DB při stavbě sítě (telefony mají 6–8 jader, čtení z SQLite je vázané na CPU). */
        private val READ_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /**
         * Postaví sítě pro víc dní JEDNÍM průchodem databází (večer hledání potřebuje dnešek i zítřek; dřív se jízdní
         * řád četl pro každý den zvlášť, ~2,5 s na den), souběžně v několika vláknech.
         */
        fun build(tt: Timetable, days: List<LocalDate>): Map<LocalDate, Net> {
            // pro každý den: spoj -> posuny (-1 den = přesah z včerejška, 0, +1 den = ráno zítřka)
            val actives = days.map { day ->
                val active = HashMap<Int, IntArray>()
                val svcsYest = tt.activeSvcs(day.minusDays(1))
                val svcsToday = tt.activeSvcs(day)
                val svcsTmrw = tt.activeSvcs(day.plusDays(1))
                for ((tid, t) in tt.trips) {
                    val a = t.svc in svcsYest && t.t1 >= 86400
                    val b = t.svc in svcsToday
                    val c = t.svc in svcsTmrw && t.t0 <= NEXT_DAY_CUTOFF
                    if (!a && !b && !c) continue
                    var n = 0
                    val offs = IntArray((if (a) 1 else 0) + (if (b) 1 else 0) + (if (c) 1 else 0))
                    if (a) offs[n++] = -86400
                    if (b) offs[n++] = 0
                    if (c) offs[n] = 86400
                    active[tid] = offs
                }
                active
            }

            // Čtení dat jízd (desítky MB blobů) je hlavní cena: běží souběžně v několika spojeních nad rozsahy ID spojů
            // (trip je INTEGER PRIMARY KEY, rozsah je levný), každé vlákno si plní vlastní mapy, nakonec se spojí.
            val maxTid = tt.trips.keys.maxOrNull() ?: 0
            val parts = READ_THREADS.coerceAtMost(maxOf(1, maxTid / 1000))
            fun readPart(lo: Int, hi: Int): List<HashMap<StopsKey, MutableList<TripInstance>>> {
                val groups = days.map { HashMap<StopsKey, MutableList<TripInstance>>() }
                val db = SQLiteDatabase.openDatabase(tt.dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    // bez JOIN filtru podle služeb (měřeno: souběžné čtení s ním bylo pomalejší); neaktivní spoje se
                    // přeskočí níž ještě před převodem jejich dat
                    val sql = "SELECT trip, stops, arr, dep FROM trip_times WHERE trip BETWEEN $lo AND $hi"
                    db.rawQuery(sql, null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val tid = cursor.getInt(0)
                            var key: StopsKey? = null
                            var arr: IntArray? = null
                            var dep: IntArray? = null
                            for (di in days.indices) {
                                val offsets = actives[di][tid] ?: continue
                                if (key == null) {
                                    key = StopsKey(cursor.getBlob(1))
                                    arr = bytesToIntArray(cursor.getBlob(2))
                                    dep = bytesToIntArray(cursor.getBlob(3))
                                }
                                val g = groups[di].getOrPut(key) { ArrayList() }
                                for (off in offsets) {
                                    val a = if (off == 0) arr!! else IntArray(arr!!.size) { arr[it] + off }
                                    val d = if (off == 0) dep!! else IntArray(dep!!.size) { dep[it] + off }
                                    g.add(TripInstance(d[0], tid, a, d))
                                }
                            }
                        }
                    }
                } finally {
                    db.close()
                }
                return groups
            }

            val step = maxTid / parts + 1
            val results = if (parts <= 1) listOf(readPart(0, maxTid)) else {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(parts)
                try {
                    (0 until parts).map { i -> pool.submit<List<HashMap<StopsKey, MutableList<TripInstance>>>> { readPart(i * step, minOf(maxTid, (i + 1) * step - 1)) } }
                        .map { f ->
                            try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                        }
                } finally {
                    pool.shutdown()
                }
            }
            val groups = days.indices.map { di ->
                val merged = HashMap<StopsKey, MutableList<TripInstance>>()
                for (r in results) for ((k, v) in r[di]) {
                    val g = merged[k]
                    if (g == null) merged[k] = v else g.addAll(v)
                }
                merged
            }

            return days.indices.associate { di -> days[di] to assemble(tt, days[di], groups[di]) }
        }

        private fun assemble(tt: Timetable, day: LocalDate, groups: Map<StopsKey, MutableList<TripInstance>>): Net {
            val patsList = ArrayList<Pattern>(groups.size)
            val stopPatsMap = HashMap<Int, MutableList<Pair<Int, Int>>>()
            for ((key, instances) in groups) {
                instances.sortBy { it.de0 }
                val pi = patsList.size
                val stops = bytesToIntArray(key.bytes)
                val tids = IntArray(instances.size) { instances[it].tid }
                val arrs = Array(instances.size) { instances[it].arr }
                val deps = Array(instances.size) { instances[it].dep }
                val cols = Array(stops.size) { i -> IntArray(instances.size) { j -> deps[j][i] } }
                patsList.add(Pattern(stops, tids, arrs, deps, cols))
                for ((pos, st) in stops.withIndex()) {
                    stopPatsMap.getOrPut(st) { mutableListOf() }.add(pi to pos)
                }
            }
            return Net(tt, day, patsList, stopPatsMap)
        }
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

    fun raptor(srcs: List<Int>, dsts: Set<Int>, t0Sec: Int, kmax: Int = 4, modeFilter: ModeFilter = ModeFilter.ALL): List<Journey> {
        val foot = tt.foot
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
                var boardPrev = INF   // příjezd 1. spoje na zastávku nástupu (menší = dřívější přestup)

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
                        var j = binarySearchLeft(col, p + mt)
                        if (modeFilter != ModeFilter.ALL) {
                            while (j < col.size) {
                                val tid = pattern.tids[j]
                                val isTrain = tt.trips[tid]?.isTrain == true
                                if (modeFilter.accepts(isTrain)) break
                                j++
                            }
                        }
                        if (j < col.size && (trip < 0 || j < trip || (j == trip && p < boardPrev))) {
                            trip = j
                            board = i
                            boardPrev = p
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

        // časy pěších úseků se dopočítají podle sousedních jízd
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
        val firstWalk = legs.first().takeIf { it.kind == "walk" }
        val dep = firstWalk?.dep ?: firstRide.dep          // odchod = začátek chůze
        val arr = legs.maxOf { it.arr }

        fun walkMin(l: Leg) = (l.arr - l.dep + 59) / 60

        val line1 = tt.trips[firstRide.tid]?.line ?: "?"
        val line2 = if (rides.size > 1) tt.trips[rides[1].tid]?.line else null
        val dest = tt.trips[rides.last().tid]?.head ?: ""
        val agency = tt.trips[firstRide.tid]?.agency ?: ""

        val totalStops = rides.sumOf { maxOf(0, it.stops.size - 1) }

        // přestup mezi jízdou i a i+1 (pro každý přestup zvlášť, ne jen první)
        val transferList = (0 until rides.size - 1).map { i ->
            val r0 = rides[i]
            val r1 = rides[i + 1]
            val fromName = tt.stops[r0.toStop]?.name?.let { prettyName(it) }
            val toName = tt.stops[r1.fromStop]?.name?.let { prettyName(it) }
            val at = when {
                fromName != null && toName != null && r0.toStop != r1.fromStop -> {
                    val fromClean = stopLabel(fromName)
                    val toClean = stopLabel(toName)
                    if (fromClean != toClean) "$fromClean → $toClean" else fromClean
                }
                else -> fromName?.let { stopLabel(it) } ?: ""
            }
            val wait = maxOf(0, (r1.dep - r0.arr) / 60)

            val iA = legs.indexOfFirst { it === r0 }
            val iB = legs.indexOfFirst { it === r1 }
            val walkLeg = legs.subList(iA + 1, iB).firstOrNull { it.kind == "walk" }
            val walk = when {
                walkLeg != null -> walkMin(walkLeg)
                r0.toStop != r1.fromStop -> {
                    val a = tt.stops[r0.toStop]
                    val b = tt.stops[r1.fromStop]
                    if (a != null && b != null && a.hasCoords && b.hasCoords)
                        maxOf(1, (Geo.distanceM(a.lat, a.lng, b.lat, b.lng) / 80.0).toInt())
                    else null
                }
                else -> null
            }
            TransferInfo(at = at, waitMin = wait, walkMin = walk)
        }
        val firstTransfer = transferList.firstOrNull()
        val allLines = rides.map { tt.trips[it.tid]?.line ?: "?" }

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
            transferAt = firstTransfer?.at,
            transferWait = firstTransfer?.waitMin,
            trip = tripStr,
            trip2 = trip2Str,
            sortKey = dep,
            day = day,
            rawLegs = legs,
            cis = tt.trips[firstRide.tid]?.cis ?: "",
            boardWalkMin = firstWalk?.let { walkMin(it) },
            boardStopName = firstWalk?.let { tt.stops[it.toStop]?.name?.let { n -> prettyName(n) } },
            transferWalkMin = firstTransfer?.walkMin,
            transfers = rides.size - 1,
            lines = allLines,
            transferList = transferList
        )
    }
}

private const val MAX_NETS = 2

class Planner(val tt: Timetable) {
    private val nets = HashMap<LocalDate, Net>()

    @Synchronized
    fun net(day: LocalDate): Net {
        nets[day]?.let { return it }
        prepare(listOf(day))
        return nets.getValue(day)
    }

    /** Postaví chybějící sítě pro [days] najednou (jeden průchod DB, viz [Net.build]). */
    @Synchronized
    fun prepare(days: List<LocalDate>) {
        val missing = days.distinct().filter { it !in nets }
        if (missing.isEmpty()) return
        val t0 = System.nanoTime()
        val built = Net.build(tt, missing)
        // síť dne zabírá ~100 MB (statisíce jízd s časy): drží se nejvýš dva dny (dnešek + zítřek), víc hrozí OOM / GC
        while (nets.size + built.size > MAX_NETS && nets.isNotEmpty()) nets.remove(nets.keys.first())
        nets.putAll(built)
        android.util.Log.d("SearchPerf", "síť pro $missing postavena za ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    fun find(
        fromPlace: String,
        toPlace: String,
        start: LocalDateTime,
        count: Int = 8,
        end: LocalDateTime? = null,
        horizonHours: Int = 14,
        modeFilter: ModeFilter = ModeFilter.ALL
    ): List<Journey> {
        val srcs = tt.places[fromPlace] ?: emptyList()
        val dsts = (tt.places[toPlace] ?: emptyList()).toSet()
        if (srcs.isEmpty() || dsts.isEmpty()) return emptyList()

        val endDt = end ?: start.plusHours(horizonHours.toLong())
        val lastDay = endDt.toLocalDate()
        val out = HashMap<String, Journey>()

        // Okno se prochází po dnech: každý den má vlastní jízdní řád (Net), takže hledání
        // může pokračovat přes půlnoc a nekončí u přesahu z předchozího dne.
        var day = start.toLocalDate()
        // večer hledání skoro vždy přeteče do zítřka: obě sítě jedním průchodem DB místo dvou
        if (start.hour >= 18 && lastDay.isAfter(day)) prepare(listOf(day, day.plusDays(1)))
        while (!day.isAfter(lastDay) && out.size < count) {
            val n = net(day)
            val midnight = day.atStartOfDay()
            var tSec = maxOf(0, Duration.between(midnight, start).seconds.toInt())
            var tEndSec = Duration.between(midnight, endDt).seconds.toInt()
            // pokud okno pokračuje dalším dnem, tenhle den se bere jen do půlnoci (zbytek přinese jeho vlastní řád)
            if (day.isBefore(lastDay)) tEndSec = minOf(tEndSec, 86_399)

            var runs = 0
            while (out.size < count && tSec <= tEndSec && runs < 40) {
                val js = n.raptor(srcs, dsts, tSec, modeFilter = modeFilter)
                if (js.isEmpty()) break
                for (j in js) {
                    if (j.dep <= tEndSec) out[j.key] = j
                }
                tSec = (js.minByOrNull { it.dep }?.dep ?: tSec) + 1
                runs++
            }
            day = day.plusDays(1)
        }
        val pruned = prune(out.values.toList())
        return if (modeFilter == ModeFilter.ALL) {
            pruned
        } else {
            pruned.filter { j ->
                j.rawLegs.all { leg ->
                    leg.kind != "ride" || modeFilter.accepts(tt.trips[leg.tid]?.isTrain == true)
                }
            }
        }
    }

    fun moreLater(
        fromPlace: String,
        toPlace: String,
        journeys: List<Journey>,
        count: Int = 6,
        modeFilter: ModeFilter = ModeFilter.ALL
    ): List<Journey> {
        if (journeys.isEmpty()) return emptyList()
        val last = journeys.maxOf { it.depDt }
        val newJs = find(fromPlace, toPlace, last.plusSeconds(60), count, modeFilter = modeFilter)
        val existingKeys = journeys.mapTo(HashSet()) { it.key }
        return prune(journeys + newJs.filter { it.key !in existingKeys })
    }

    fun moreEarlier(
        fromPlace: String,
        toPlace: String,
        journeys: List<Journey>,
        count: Int = 6,
        hours: Int = 2,
        modeFilter: ModeFilter = ModeFilter.ALL
    ): List<Journey> {
        if (journeys.isEmpty()) return emptyList()
        val first = journeys.minOf { it.depDt }
        val lo = maxOf(first.minusHours(hours.toLong()), first.toLocalDate().atStartOfDay())
        val newJs = find(fromPlace, toPlace, lo, 60, end = first.minusSeconds(1), modeFilter = modeFilter)
            .sortedBy { it.depDt }
            .takeLast(count)
        val existingKeys = journeys.mapTo(HashSet()) { it.key }
        return prune(journeys + newJs.filter { it.key !in existingKeys })
    }
}

/** Odstraní spojení, která jsou horší než jiné (později odjíždí, dřív nedojede, víc přestupů). */
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