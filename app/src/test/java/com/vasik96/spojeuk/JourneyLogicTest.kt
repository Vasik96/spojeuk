package com.vasik96.spojeuk

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyLogicTest {
    private fun h(hh: Int, mm: Int) = hh * 3600 + mm * 60
    private fun ride(dep: Int, arr: Int, tid: Int = 1) = Leg(kind = "ride", fromStop = 0, toStop = 1, dep = dep, arr = arr, tid = tid)
    private fun walk(dep: Int, arr: Int) = Leg(kind = "walk", fromStop = 1, toStop = 2, dep = dep, arr = arr)

    // 10:00–10:20 jízda, 10:20–10:25 chůze, 10:30–10:50 jízda, 11:00–11:10 jízda
    private val legs = listOf(
        ride(h(10, 0), h(10, 20)),
        walk(h(10, 20), h(10, 25)),
        ride(h(10, 30), h(10, 50)),
        ride(h(11, 0), h(11, 10)),
    )

    // ───── aktuální jízda pro mapu ─────

    @Test fun activeLeg_firstUntilThreeMinutesAfterDeparture() {
        assertEquals(0, activeLegIndexOf(legs, h(9, 50)) { 0 })
        assertEquals(0, activeLegIndexOf(legs, h(10, 3)) { 0 })          // přesně 3 min po odjezdu ještě první
        assertEquals(2, activeLegIndexOf(legs, h(10, 3) + 1) { 0 })
    }

    @Test fun activeLeg_delayPostponesSwitch() {
        // první jízda má 5 min zpoždění: odjela až 10:05, další se ukáže po 10:08
        val delays = mapOf(0 to 5)
        assertEquals(0, activeLegIndexOf(legs, h(10, 7)) { delays[it] ?: 0 })
        assertEquals(2, activeLegIndexOf(legs, h(10, 9)) { delays[it] ?: 0 })
    }

    @Test fun activeLeg_lastRideStaysAfterEverything() {
        assertEquals(3, activeLegIndexOf(legs, h(12, 0)) { 0 })
    }

    @Test fun activeLeg_singleRideAndWalkFirst() {
        val single = listOf(walk(h(9, 55), h(10, 0)), ride(h(10, 0), h(10, 20)))
        assertEquals(1, activeLegIndexOf(single, h(11, 0)) { 0 })
    }

    // ───── ohrožený přestup ─────

    @Test fun transfer_noLiveDataNoStatus() {
        assertNull(transferStatus(legs, 0, emptyMap(), h(10, 0)))
    }

    @Test fun transfer_onTimeIsFine() {
        // čekání 10 min, z toho 5 min chůze => rezerva 5 min
        val s = transferStatus(legs, 0, mapOf(0 to 0), h(10, 0))!!
        assertEquals(TransferRisk.NONE, s.risk)
        assertEquals(5, s.marginMin)
    }

    @Test fun transfer_tightAndAtRisk() {
        assertEquals(TransferRisk.TIGHT, transferStatus(legs, 0, mapOf(0 to 4), h(10, 0))!!.risk)     // rezerva 1 min
        assertEquals(TransferRisk.TIGHT, transferStatus(legs, 0, mapOf(0 to 5), h(10, 0))!!.risk)     // rezerva 0
        val late = transferStatus(legs, 0, mapOf(0 to 7), h(10, 0))!!
        assertEquals(TransferRisk.AT_RISK, late.risk)
        assertEquals(-2, late.marginMin)
    }

    @Test fun transfer_delayedConnectionHelps() {
        // přijíždí o 7 min později, ale navazující má taky 4 min zpoždění => rezerva 2 min, žádné upozornění
        val s = transferStatus(legs, 0, mapOf(0 to 7, 2 to 4), h(10, 0))!!
        assertEquals(TransferRisk.NONE, s.risk)
        assertEquals(2, s.marginMin)
    }

    @Test fun transfer_earlyConnectionNotTrusted() {
        // navazující hlásí -3 min (napřed): na to se nespoléhá
        val s = transferStatus(legs, 0, mapOf(0 to 4, 2 to -3), h(10, 0))!!
        assertEquals(1, s.marginMin)
    }

    @Test fun transfer_pastTransferIgnored() {
        assertNull(transferStatus(legs, 0, mapOf(0 to 10), h(10, 45)))
    }

    @Test fun transfer_secondTransferWithoutWalk() {
        // 10:50 → 11:00, bez chůze, 9 min zpoždění => rezerva 1 min
        val s = transferStatus(legs, 1, mapOf(2 to 9), h(10, 30))!!
        assertEquals(TransferRisk.TIGHT, s.risk)
        assertEquals(1, s.marginMin)
    }

    // ───── vůz u trasy ─────

    @Test fun distanceToRoute_segmentNotJustVertices() {
        // trasa po rovnoběžce, bod 100 m severně od středu úsečky (vrcholy jsou daleko)
        val route = listOf(LatLng(50.66, 14.00), LatLng(50.66, 14.05))
        val p = LatLng(50.66 + 100.0 / Geo.M_PER_DEG_LAT, 14.025)
        val d = distanceToRouteM(p, route)
        assertTrue("d=$d", d in 95.0..105.0)
    }

    @Test fun distanceToRoute_farVehicle() {
        val route = listOf(LatLng(50.66, 14.00), LatLng(50.66, 14.05))
        assertTrue(distanceToRouteM(LatLng(50.56, 14.02), route) > 10_000)
    }

    // ───── fáze sledované cesty ─────

    @Test fun phase_walkThenBeforeDeparture() {
        val withWalk = listOf(walk(h(9, 55), h(10, 0))) + legs
        assertEquals(TrackPhase.BeforeDeparture(1, h(10, 0)), trackPhase(withWalk, h(9, 50), emptyMap()))
    }

    @Test fun phase_ridingWithDelay() {
        // 2 min zpoždění: v 10:01 ještě čeká (odjezd 10:02), v 10:21 jede (příjezd 10:22)
        assertEquals(TrackPhase.BeforeDeparture(0, h(10, 2)), trackPhase(legs, h(10, 1), mapOf(0 to 2)))
        assertEquals(TrackPhase.Riding(0, h(10, 22)), trackPhase(legs, h(10, 21), mapOf(0 to 2)))
    }

    @Test fun phase_transferAndArrived() {
        assertEquals(TrackPhase.Transfer(2, h(10, 30)), trackPhase(legs, h(10, 25), emptyMap()))
        assertEquals(TrackPhase.Riding(3, h(11, 10)), trackPhase(legs, h(11, 5), emptyMap()))
        assertEquals(TrackPhase.Arrived(h(11, 10)), trackPhase(legs, h(11, 30), emptyMap()))
    }
}
