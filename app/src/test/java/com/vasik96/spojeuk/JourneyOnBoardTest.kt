package com.vasik96.spojeuk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyOnBoardTest {
    private fun h(hh: Int, mm: Int) = hh * 3600 + mm * 60
    private fun ride(dep: Int, arr: Int, tid: Int = 1) = Leg(kind = "ride", fromStop = 0, toStop = 1, dep = dep, arr = arr, tid = tid)
    private val legs = listOf(ride(h(10, 0), h(10, 20)), ride(h(10, 30), h(10, 50), tid = 2))

    private val lat0 = 50.66
    private val lng0 = 14.04
    /** Bod [m] metrů severně od základního. */
    private fun north(m: Double) = lat0 + m / Geo.M_PER_DEG_LAT

    // ───── zmeškaný přestup / odjezd ─────

    @Test fun transfer_connectionStillAtStopIsNotMissedEvenIfScheduleSaysDeparted() {
        // A přijede pozdě (10:21 místo 10:20), navazující B má odjet v 10:30; v 10:33 je B podle polohy ještě u zastávky
        // a uživatel je v A 180 m před ní. Podle JŘ "odjel", ale vůz ještě stojí => nezmeškáno.
        val delays = mapOf(0 to 13, 1 to 0)
        val now = h(10, 33)
        val u = north(180.0)
        assertTrue(isTransferMissed(legs, 0, delays, now, u, lng0, lat0, lng0))                              // dřív: falešně zmeškáno
        assertFalse(isTransferMissed(legs, 0, delays, now, u, lng0, lat0, lng0, vehicleBNotLeftStop = true))
    }

    @Test fun transfer_reallyMissedStillDetected() {
        // B už podle polohy odjel, uživatel stojí u přestupní zastávky a ve voze B není
        assertTrue(isTransferMissed(legs, 0, mapOf(0 to 0, 1 to 0), h(10, 33), lat0, lng0, lat0, lng0, vehicleBNotLeftStop = false, onBoardRide = -1))
    }

    @Test fun transfer_notMissedWhenOnBoardConnection() {
        assertFalse(isTransferMissed(legs, 0, mapOf(0 to 0, 1 to 0), h(10, 40), lat0, lng0, lat0, lng0, onBoardRide = 1))
    }

    @Test fun initial_notMissedWhenOnBoardOrVehicleStillThere() {
        val now = h(10, 4)
        assertFalse(isInitialDepartureMissed(legs, mapOf(0 to 0), now, north(180.0), lng0, lat0, lng0, userOnBoard = true))
        assertFalse(isInitialDepartureMissed(legs, mapOf(0 to 0), now, north(180.0), lng0, lat0, lng0, vehicleNotLeftStop = true))
    }

    // ───── poznání, že jsem ve voze ─────

    @Test fun onBoard_movingWithVehicleIsDetectedAfterTwoSamples() {
        val t = OnBoardTracker()
        // 10 s, 80 m = 8 m/s; vůz se pohybuje s uživatelem (poloha vozu 20 m od uživatele)
        t.update(0L, north(0.0), lng0, 10f, mapOf(0 to VehiclePos(north(20.0), lng0, 2)))
        assertEquals(-1, t.ride)   // jedno měření nestačí
        t.update(10_000L, north(80.0), lng0, 10f, mapOf(0 to VehiclePos(north(100.0), lng0, 2)))
        assertEquals(0, t.ride)
    }

    @Test fun onBoard_walkerNearStoppedVehicleIsNot() {
        val t = OnBoardTracker()
        repeat(4) { i ->
            // chodec (1,4 m/s) 60 m od vozu
            t.update(i * 10_000L, north(i * 14.0), lng0, 10f, mapOf(0 to VehiclePos(north(60.0 + i * 14.0), lng0, 2)))
        }
        assertEquals(-1, t.ride)
    }

    @Test fun onBoard_staysWhenBusStopsAtLights_andLeavesWhenFarTwice() {
        val t = OnBoardTracker()
        t.update(0L, north(0.0), lng0, 10f, mapOf(0 to VehiclePos(north(10.0), lng0, 1)))
        t.update(10_000L, north(80.0), lng0, 10f, mapOf(0 to VehiclePos(north(90.0), lng0, 1)))
        t.update(20_000L, north(160.0), lng0, 10f, mapOf(0 to VehiclePos(north(170.0), lng0, 1)))
        assertEquals(0, t.ride)
        // semafor: stojí
        t.update(30_000L, north(160.0), lng0, 10f, mapOf(0 to VehiclePos(north(168.0), lng0, 1)))
        assertEquals(0, t.ride)
        // vystoupil a odešel daleko od vozu, dvakrát po sobě
        t.update(40_000L, north(160.0), lng0, 10f, mapOf(0 to VehiclePos(north(900.0), lng0, 1)))
        assertEquals(0, t.ride)
        t.update(50_000L, north(160.0), lng0, 10f, mapOf(0 to VehiclePos(north(1500.0), lng0, 1)))
        assertEquals(-1, t.ride)
    }

    @Test fun phase_onBoardOverridesWaitingPhase() {
        // podle JŘ čeká (10:01 před odjezdem 10:02 se zpožděním 2), ale sedí ve voze: jede
        val p = trackPhaseOnBoard(legs, h(10, 1), mapOf(0 to 2), onBoardRide = 0)
        assertTrue(p is TrackPhase.Riding && p.ride == 0)
        assertEquals(trackPhase(legs, h(10, 1), mapOf(0 to 2)), trackPhaseOnBoard(legs, h(10, 1), mapOf(0 to 2), onBoardRide = -1))
    }

    // ───── výstup až blízko zastávky ─────

    @Test fun alight_notAnnouncedRightAfterPreviousStopOnLongHop() {
        // 1,5 km k zastávce výstupu, žádná zastávka mezi: ještě ne
        assertFalse(alightImminent(remainingSec = 150, nextStopIsAlight = true, distToAlightM = 1500.0))
        // 300 m: ano
        assertTrue(alightImminent(remainingSec = 40, nextStopIsAlight = true, distToAlightM = 300.0))
        // 300 m, ale mezi je ještě jiná zastávka: ne
        assertFalse(alightImminent(remainingSec = 40, nextStopIsAlight = false, distToAlightM = 300.0))
    }

    @Test fun alight_shortHopAnnouncedAsBefore() {
        // krátký úsek (zastávky 250 m od sebe): hned po odjezdu z předchozí je výstup už do 350 m
        assertTrue(alightImminent(remainingSec = 60, nextStopIsAlight = true, distToAlightM = 250.0))
    }

    @Test fun alight_alertWaitsForNextStopAndDistance() {
        val aLat = lat0; val aLng = lng0
        assertFalse(shouldTriggerAlightAlert(h(10, 16), h(10, 18), h(10, 0), north(480.0), lng0, aLat, aLng))   // dřív 500 m stačilo
        assertTrue(shouldTriggerAlightAlert(h(10, 16), h(10, 18), h(10, 0), north(300.0), lng0, aLat, aLng))
        assertFalse(shouldTriggerAlightAlert(h(10, 16), h(10, 18), h(10, 0), north(300.0), lng0, aLat, aLng, nextStopIsAlight = false))
    }

    @Test fun stopBetween_detectedOnlyWhenLyingOnTheWay() {
        val ref = north(1000.0) to lng0
        val alight = north(0.0) to lng0
        assertTrue(hasStopBetween(ref, alight, listOf(north(500.0) to lng0)))
        assertFalse(hasStopBetween(ref, alight, listOf(north(1300.0) to lng0)))                 // za vozem
        assertFalse(hasStopBetween(ref, alight, listOf(north(10.0) to (lng0 + 0.0002))))        // další nástupiště cíle
        assertFalse(hasStopBetween(ref, alight, listOf(north(500.0) to (lng0 + 0.01))))         // stranou, jiná ulice
    }
}
