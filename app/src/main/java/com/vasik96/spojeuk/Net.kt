package com.vasik96.spojeuk

import android.util.Log
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

// Hledání spojení (algoritmus RAPTOR). Konstanty MIN_TRANSFER a NEXT_DAY_CUTOFF jsou v Config.kt.

/**
 * Spoje se stejným pořadím zastávek, seřazené podle odjezdu. Časy všech vzorů sítě leží ve dvou plochých polích
 * (po sloupcích: zastávka × spoj od [base]), spoje vzoru v [tidsAll] od [tidBase].
 *
 * Dřív dvě malá pole na každou zastávku každého vzoru: ~milion malých objektů, které GC při každém úklidu
 * přesouval (komprese ~200 ms) => zásek UI hned po startu, když hlavní vlákno muselo čekat na GC.
 */
class Pattern(
    val stops: IntArray,
    /** Počet jízd vzoru. */
    val m: Int,
    private val tidsAll: IntArray,
    private val tidBase: Int,
    private val arrAll: IntArray,
    private val depAll: IntArray,
    private val base: Int,
) {
    fun tid(trip: Int): Int = tidsAll[tidBase + trip]
    fun dep(trip: Int, stop: Int): Int = depAll[base + stop * m + trip]
    fun arr(trip: Int, stop: Int): Int = arrAll[base + stop * m + trip]

    /** První jízda s odjezdem ze zastávky [stop] v čase >= [t] (odjezdy jsou na každé zastávce seřazené); [m] = žádná. */
    fun firstDepAtOrAfter(stop: Int, t: Int): Int {
        val off = base + stop * m
        var low = 0
        var high = m
        while (low < high) {
            val mid = (low + high) ushr 1
            if (depAll[off + mid] < t) low = mid + 1 else high = mid
        }
        return low
    }
}

sealed class ParentRef {
    data class Walk(val fromStop: Int, val duration: Int) : ParentRef()
    data class Ride(val patternIdx: Int, val tripIdx: Int, val boardIdx: Int, val alightIdx: Int) : ParentRef()
}

/** Jízdní řád jednoho dne (včetně přesahu z včerejška a rána zítřka), časy v s od půlnoci dne. Staví [Net.build]. */
class Net private constructor(
    val tt: Timetable,
    val day: LocalDate,
    val patterns: List<Pattern>,
    /** Zastávka → [vzor, pozice, vzor, pozice, …] (prokládané, bez zabalených Pair). */
    val stopPats: IntCsr,
) {
    companion object {
        /**
         * Postaví sítě pro víc dní z namapované mezipaměti jízd ([NetCache]): alokují se jen výsledná pole, takže
         * stavba neprovokuje GC, který by zastavil UI.
         */
        fun build(tt: Timetable, days: List<LocalDate>): Map<LocalDate, Net> {
            val cache = tt.netCache
            val trips = tt.trips
            val maxTid = minOf(cache.maxTid, trips.maxId)
            return days.associateWith { day ->
                // spoj -> bity posunů (bit 0 = přesah z včerejška −1 den, bit 1 = dnes, bit 2 = ráno zítřka +1 den);
                // jedno pole bajtů místo malého pole pro každý spoj (statisíce objektů navíc pro GC)
                val active = ByteArray(maxTid + 1)
                val svcsYest = tt.activeSvcs(day.minusDays(1))
                val svcsToday = tt.activeSvcs(day)
                val svcsTmrw = tt.activeSvcs(day.plusDays(1))
                for (tid in 0..maxTid) {
                    if (!trips.has(tid)) continue
                    val svc = trips.svc[tid]
                    val a = svc in svcsYest && trips.t1[tid] >= 86400
                    val b = svc in svcsToday
                    val c = svc in svcsTmrw && trips.t0[tid] <= NEXT_DAY_CUTOFF
                    active[tid] = ((if (a) 1 else 0) or (if (b) 2 else 0) or (if (c) 4 else 0)).toByte()
                }
                assemble(tt, day, cache, active)
            }
        }

        /** Posun jízdy podle bitu v masce aktivních spojů (viz [build]). */
        private fun dayShift(bit: Int): Int = (bit - 1) * 86400

        private fun assemble(tt: Timetable, day: LocalDate, cache: NetCache, active: ByteArray): Net {
            val nPat = cache.nPat
            val count = IntArray(nPat)
            for (tid in active.indices) {
                val o = active[tid].toInt()
                if (o == 0) continue
                val p = cache.patOf(tid)
                if (p >= 0) count[p] += Integer.bitCount(o)
            }
            // jízdy vzoru jako long: odjezd z 1. zastávky v horních 32 bitech (řazení), spoj a index posunu v dolních
            val keys = arrayOfNulls<LongArray>(nPat)
            val fill = IntArray(nPat)
            for (p in 0 until nPat) if (count[p] > 0) keys[p] = LongArray(count[p])
            for (tid in active.indices) {
                val o = active[tid].toInt()
                if (o == 0) continue
                val p = cache.patOf(tid)
                if (p < 0) continue
                val dep0 = cache[cache.depBase(tid, cache.patLen(p))]
                val k = keys[p]!!
                for (bit in 0..2) {
                    if (o and (1 shl bit) == 0) continue
                    k[fill[p]++] = ((dep0 + dayShift(bit)).toLong() shl 32) or ((tid.toLong() shl 2) or bit.toLong())
                }
            }

            // velikost plochých polí: jízdy celkem a (zastávky × jízdy) celkem
            var totalTrips = 0
            var totalCells = 0L
            for (p in 0 until nPat) {
                val c = count[p]
                if (c == 0) continue
                totalTrips += c
                totalCells += c.toLong() * cache.patLen(p)
            }
            val tidsAll = IntArray(totalTrips)
            val arrAll = IntArray(totalCells.toInt())
            val depAll = IntArray(totalCells.toInt())
            var tidBase = 0
            var base = 0

            val patsList = ArrayList<Pattern>()
            for (p in 0 until nPat) {
                val k = keys[p] ?: continue
                k.sort()
                val stops = cache.patStops(p)
                val n = stops.size
                val m = k.size
                for (j in 0 until m) {
                    val low = k[j] and 0xFFFFFFFFL
                    val tid = (low shr 2).toInt()
                    val off = dayShift((low and 3L).toInt())
                    tidsAll[tidBase + j] = tid
                    val ab = cache.arrBase(tid)
                    val db = cache.depBase(tid, n)
                    for (i in 0 until n) {
                        arrAll[base + i * m + j] = cache[ab + i] + off
                        depAll[base + i * m + j] = cache[db + i] + off
                    }
                }
                val pi = patsList.size
                patsList.add(Pattern(stops, m, tidsAll, tidBase, arrAll, depAll, base))
                tidBase += m
                base += n * m
            }
            // zastávka → (vzor, pozice) dvěma průchody, bez seznamů pro každou zastávku
            var maxStop = 0
            for (pat in patsList) for (st in pat.stops) if (st > maxStop) maxStop = st
            val sp = IntCsr.Builder(maxStop)
            for (pat in patsList) for (st in pat.stops) if (st >= 0) sp.count(st, 2)
            sp.alloc()
            for ((pi, pat) in patsList.withIndex()) {
                val stops = pat.stops
                for (pos in stops.indices) {
                    val st = stops[pos]
                    if (st >= 0) { sp.put(st, pi); sp.put(st, pos) }
                }
            }
            return Net(tt, day, patsList, sp.build())
        }
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
        val fs = foot.data
        for (s in srcs) {
            for (q in foot.from(s) until foot.to(s) step 2) {
                val s2 = fs[q]; val d = fs[q + 1]
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
                val sp = stopPats.data
                for (q in stopPats.from(s) until stopPats.to(s) step 2) {
                    val pi = sp[q]; val pos = sp[q + 1]
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
                        val a = pattern.arr(trip, i)
                        if (a < (best[s] ?: INF) && a < target) {
                            best[s] = a
                            curArrs[s] = a
                            curPar[s] = ParentRef.Ride(pi, trip, board, i)
                            newMarked.add(s)
                        }
                    }
                    val p = prevArrs[s]
                    if (p != null) {
                        val m = pattern.m
                        var j = pattern.firstDepAtOrAfter(i, p + mt)
                        if (modeFilter != ModeFilter.ALL) {
                            while (j < m) {
                                val tid = pattern.tid(j)
                                val isTrain = tt.trips.isTrain(tid)
                                if (modeFilter.accepts(isTrain)) break
                                j++
                            }
                        }
                        if (j < m && (trip < 0 || j < trip || (j == trip && p < boardPrev))) {
                            trip = j
                            board = i
                            boardPrev = p
                        }
                    }
                }
            }

            for (s in newMarked.toList()) {
                val curS = curArrs[s] ?: continue
                for (q in foot.from(s) until foot.to(s) step 2) {
                    val s2 = fs[q]; val d = fs[q + 1]
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
                    val arr = IntArray(stops.size) { i -> pattern.arr(p.tripIdx, i) }
                    val dep = IntArray(stops.size) { i -> pattern.dep(p.tripIdx, i) }
                    val seq = (p.boardIdx..p.alightIdx).map { i -> Triple(stops[i], arr[i], dep[i]) }

                    legs.add(
                        Leg(
                            kind = "ride",
                            fromStop = stops[p.boardIdx],
                            toStop = stops[p.alightIdx],
                            dep = dep[p.boardIdx],
                            arr = arr[p.alightIdx],
                            tid = pattern.tid(p.tripIdx),
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
                    leg.kind != "ride" || modeFilter.accepts(tt.trips.isTrain(leg.tid))
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