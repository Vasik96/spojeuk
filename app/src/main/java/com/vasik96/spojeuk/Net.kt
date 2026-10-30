package com.vasik96.spojeuk

import android.database.sqlite.SQLiteDatabase
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

/** Jízdní řád jednoho dne (včetně přesahu z včerejška a rána zítřka), časy v s od půlnoci dne. */
class Net(val tt: Timetable, val day: LocalDate) {

    val patterns: List<Pattern>
    val stopPats: Map<Int, List<Pair<Int, Int>>>

    init {
        val active = HashMap<Int, MutableList<Int>>()

        val svcsYest = tt.activeSvcs(day.minusDays(1))
        val svcsToday = tt.activeSvcs(day)
        val svcsTmrw = tt.activeSvcs(day.plusDays(1))

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
        if (srcs.isEmpty() || dsts.isEmpty()) return emptyList()

        val out = HashMap<String, Journey>()
        var runs = 0

        while (out.size < count && tSec <= tEndSec && runs < 40) {
            val js = n.raptor(srcs, dsts, tSec)
            if (js.isEmpty()) break
            for (j in js) {
                if (j.dep <= tEndSec) out[j.key] = j
            }
            tSec = (js.minByOrNull { it.dep }?.dep ?: tSec) + 1
            runs++
        }
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