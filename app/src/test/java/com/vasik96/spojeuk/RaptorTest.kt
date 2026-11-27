package com.vasik96.spojeuk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jádro plánovače ([Raptor]) na malé ručně sestavené síti. Zastávky jsou čísla, časy v s od půlnoci.
 *
 *   linka 1 (tid 1x):  A(0) → B(1) → C(2)
 *   linka 2 (tid 2x):  B(1) → D(3)
 *   linka 3 (tid 3x):  E(4) → D(3)        E je pěšky od B
 *   linka 4 (tid 40):  A(0) → D(3)        přímý, ale pomalý
 */
class RaptorTest {

    private val A = 0; private val B = 1; private val C = 2; private val D = 3; private val E = 4; private val F = 5

    private fun t(h: Int, m: Int) = h * 3600 + m * 60

    /** Jízda vzoru: id spoje a čas na každé zastávce (příjezd = odjezd). */
    private class Trip(val tid: Int, vararg val times: Int)

    private fun pattern(stops: IntArray, vararg trips: Trip): Pattern {
        val sorted = trips.sortedBy { it.times[0] }
        val m = sorted.size
        val arr = IntArray(stops.size * m)
        for (i in stops.indices) for ((j, tr) in sorted.withIndex()) arr[i * m + j] = tr.times[i]
        return Pattern(stops, m, IntArray(m) { sorted[it].tid }, 0, arr, arr.copyOf(), 0)
    }

    private fun csr(maxId: Int, pairs: Map<Int, List<Pair<Int, Int>>>): IntCsr {
        val b = IntCsr.Builder(maxId)
        for (id in 0..maxId) b.count(id, 2 * (pairs[id]?.size ?: 0))
        b.alloc()
        for (id in 0..maxId) pairs[id]?.forEach { (x, y) -> b.put(id, x); b.put(id, y) }
        return b.build()
    }

    private fun raptor(
        patterns: List<Pattern>,
        walks: List<Triple<Int, Int, Int>> = emptyList(),
        category: (Int) -> MapCategory = { MapCategory.BUS },
    ): Raptor {
        val stopPats = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for ((pi, p) in patterns.withIndex()) for ((pos, s) in p.stops.withIndex()) stopPats.getOrPut(s) { ArrayList() } += pi to pos
        // pěší přesuny oběma směry, jako FootGraph
        val foot = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for ((a, b, sec) in walks) {
            foot.getOrPut(a) { ArrayList() } += b to sec
            foot.getOrPut(b) { ArrayList() } += a to sec
        }
        return Raptor(patterns, csr(F, stopPats), csr(F, foot), category)
    }

    private val line1 = pattern(intArrayOf(A, B, C), Trip(10, t(10, 0), t(10, 10), t(10, 20)), Trip(11, t(10, 30), t(10, 40), t(10, 50)))

    private fun List<Leg>.kinds() = joinToString(",") { it.kind }
    private fun List<Leg>.rides() = filter { it.kind == "ride" }

    @Test
    fun directRideTakesFirstDepartureAfterStart() {
        val found = raptor(listOf(line1)).search(listOf(A), setOf(C), t(10, 5))
        assertEquals(1, found.size)
        val ride = found[0].single()
        assertEquals("ride", ride.kind)
        assertEquals(11, ride.tid)                  // 10:00 už ujel, jede se tím v 10:30
        assertEquals(t(10, 30), ride.dep)
        assertEquals(t(10, 50), ride.arr)
        assertEquals(listOf(A, B, C), ride.stops.map { it.first })
    }

    @Test
    fun departureExactlyAtStartTimeIsReachable() {
        val found = raptor(listOf(line1)).search(listOf(A), setOf(C), t(10, 0))
        assertEquals(10, found.single().single().tid)
    }

    @Test
    fun noConnectionAfterLastDeparture() {
        assertTrue(raptor(listOf(line1)).search(listOf(A), setOf(C), t(11, 0)).isEmpty())
    }

    @Test
    fun transferRespectsMinimumTransferTime() {
        // na B se přijede v 10:10; spoj v 10:11 je pod MIN_TRANSFER (2 min), stihne se až 10:12
        val line2 = pattern(intArrayOf(B, D), Trip(20, t(10, 11), t(10, 25)), Trip(21, t(10, 12), t(10, 27)))
        val legs = raptor(listOf(line1, line2)).search(listOf(A), setOf(D), t(9, 55)).single()
        assertEquals("ride,ride", legs.kinds())
        assertEquals(listOf(10, 21), legs.rides().map { it.tid })
        assertEquals(t(10, 27), legs.last().arr)
        assertTrue(legs[1].dep - legs[0].arr >= MIN_TRANSFER)
    }

    @Test
    fun walkingTransferBetweenNearbyStops() {
        // B → E pěšky 3 min, pak linka 3; pěší úsek začíná příjezdem první jízdy
        val line3 = pattern(intArrayOf(E, D), Trip(30, t(10, 14), t(10, 30)), Trip(31, t(10, 20), t(10, 35)))
        val legs = raptor(listOf(line1, line3), walks = listOf(Triple(B, E, 180))).search(listOf(A), setOf(D), t(9, 55)).single()
        assertEquals("ride,walk,ride", legs.kinds())
        val walk = legs[1]
        assertEquals(B, walk.fromStop)
        assertEquals(E, walk.toStop)
        assertEquals(t(10, 10), walk.dep)
        assertEquals(t(10, 13), walk.arr)
        // 10:14 by po chůzi nestihl (chůze + MIN_TRANSFER), jede se 10:20
        assertEquals(31, legs[2].tid)
    }

    @Test
    fun walkToFirstStopEndsAtDeparture() {
        // výchozí zastávka F je jen pěšky od A
        val legs = raptor(listOf(line1), walks = listOf(Triple(F, A, 240))).search(listOf(F), setOf(C), t(9, 50)).single()
        assertEquals("walk,ride", legs.kinds())
        assertEquals(t(10, 0) - 240, legs[0].dep)
        assertEquals(t(10, 0), legs[0].arr)
    }

    @Test
    fun fasterConnectionWithTransferIsOfferedAfterDirectOne() {
        val slowDirect = pattern(intArrayOf(A, D), Trip(40, t(10, 0), t(11, 0)))
        val line2 = pattern(intArrayOf(B, D), Trip(20, t(10, 15), t(10, 30)))
        val found = raptor(listOf(line1, slowDirect, line2)).search(listOf(A), setOf(D), t(9, 55))
        // méně přestupů první, pak každé další spojení musí přijet dřív
        assertEquals(2, found.size)
        assertEquals(listOf(40), found[0].rides().map { it.tid })
        assertEquals(t(11, 0), found[0].last().arr)
        assertEquals(listOf(10, 20), found[1].rides().map { it.tid })
        assertEquals(t(10, 30), found[1].last().arr)
    }

    @Test
    fun slowerConnectionWithMoreTransfersIsNotOffered() {
        val fastDirect = pattern(intArrayOf(A, D), Trip(40, t(10, 0), t(10, 20)))
        val line2 = pattern(intArrayOf(B, D), Trip(20, t(10, 15), t(10, 30)))
        val found = raptor(listOf(line1, fastDirect, line2)).search(listOf(A), setOf(D), t(9, 55))
        assertEquals(listOf(listOf(40)), found.map { j -> j.rides().map { it.tid } })
    }

    @Test
    fun modeFilterSkipsTripsOfOtherCategories() {
        // stejný vzor: v 10:00 autobus, v 10:30 vlak; hledají se jen vlaky
        val r = raptor(listOf(line1), category = { if (it == 11) MapCategory.TRAIN else MapCategory.BUS })
        val legs = r.search(listOf(A), setOf(C), t(9, 55), modeFilter = ModeFilter(setOf(MapCategory.TRAIN))).single()
        assertEquals(11, legs.single().tid)
    }

    @Test
    fun anyOfSeveralTargetStopsCounts() {
        // cíl jako skupina zastávek (např. obě strany silnice): stačí dojet na kteroukoli
        val legs = raptor(listOf(line1)).search(listOf(A), setOf(B, C), t(9, 55)).single()
        assertEquals(B, legs.single().toStop)
    }
}
