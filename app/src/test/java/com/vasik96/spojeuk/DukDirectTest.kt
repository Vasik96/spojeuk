package com.vasik96.spojeuk

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DukDirectTest {

    @Test
    fun testTextUtilities() {
        assertEquals("usti nad labem", foldText("Ústí nad Labem"))
        assertEquals("teplice, hlavni nadrazi", foldText(prettyName("Teplice,hlavní nádraží")))
        assertEquals("08:05", hhmm(8 * 3600 + 5 * 60))
        assertEquals(14 * 3600 + 30 * 60, parseTsec("14:30:00"))
    }

    @Test
    fun testPruneJourneys() {
        val today = LocalDate.of(2026, 10, 23)
        val j1 = Journey(
            departure = "10:00",
            arrival = "10:30",
            line = "101",
            destination = "Cil",
            agency = "DPMUL",
            trip = "1",
            sortKey = 10 * 3600,
            day = today
        )
        val j2 = Journey(
            departure = "10:05",
            arrival = "10:25",
            line = "102",
            destination = "Cil",
            agency = "DPMUL",
            trip = "2",
            sortKey = 10 * 3600 + 300,
            day = today
        )

        val pruned = prune(listOf(j1, j2))
        assertEquals(2, pruned.size)
    }

    @Test
    fun testSourceLabelWhenDukRegionalStale() {
        val dpmulReport = SourceReport(
            id = "dpmul",
            name = "DPMÚL",
            priority = 0,
            segments = setOf(Segment.CITY),
            health = Health.LIVE,
            staleSegments = emptySet(),
            vehicleCount = 20
        )
        val dukReport = SourceReport(
            id = "duk",
            name = "DÚK",
            priority = 1,
            segments = setOf(Segment.CITY, Segment.REGIONAL),
            health = Health.LIVE,
            staleSegments = setOf(Segment.CITY, Segment.REGIONAL),
            vehicleCount = 50
        )

        val label = SourceLabel.build(listOf(dpmulReport, dukReport))
        assertEquals("DPMÚL, DÚK (zastaralé)", label)
    }

    @Test
    fun testSourceLabelWhenDukDeliversNothing() {
        val dpmulReport = SourceReport(
            id = "dpmul",
            name = "DPMÚL",
            priority = 0,
            segments = setOf(Segment.CITY),
            health = Health.LIVE,
            staleSegments = emptySet(),
            vehicleCount = 20
        )
        val dukReport = SourceReport(
            id = "duk",
            name = "DÚK",
            priority = 1,
            segments = setOf(Segment.CITY, Segment.REGIONAL),
            health = Health.LIVE,
            staleSegments = setOf(Segment.CITY, Segment.REGIONAL),
            vehicleCount = 0
        )

        val label = SourceLabel.build(listOf(dpmulReport, dukReport))
        assertEquals("DPMÚL", label)
    }

    @Test
    fun testVehicleFusionPrefersFresherDpmulOverOutdatedDuk() {
        val nowMs = 1_000_000_000L
        val fusion = VehicleFusion(freshnessSlackSec = 5)

        val dpmulReport = SourceReport("dpmul", "DPMÚL", priority = 0, segments = setOf(Segment.CITY), health = Health.LIVE)
        val dukReport = SourceReport("duk", "DÚK", priority = 1, segments = setOf(Segment.CITY), health = Health.LIVE)

        val vDpmul = Vehicle(
            source = "dpmul",
            lat = 50.6600,
            lng = 14.0400,
            line = "11",
            delay = 1,
            observedAtMs = nowMs - 10_000L, // 10s čerstvé
            tid = 100,
            tripMatch = TripMatch.HEURISTIC,
            segment = Segment.CITY
        )

        val vDuk = Vehicle(
            source = "duk",
            lat = 50.6610, // o kousek dál na trase
            lng = 14.0410,
            line = "11",
            delay = 5,
            observedAtMs = nowMs - 180_000L, // 3 minuty zastaralé
            tid = 100,
            tripMatch = TripMatch.EXACT,
            segment = Segment.CITY
        )

        val inputs = listOf(
            SourceInput(dpmulReport, staleVehicleSec = 90, vehicles = listOf(vDpmul)),
            SourceInput(dukReport, staleVehicleSec = 40, vehicles = listOf(vDuk))
        )

        val fused = fusion.fuse(inputs, progress = { _, _, _ -> 2000.0 }, nowMs = nowMs)
        assertEquals(1, fused.size)
        assertEquals("dpmul", fused[0].source)
        assertEquals(1, fused[0].delay)
    }

    @Test
    fun testVehicleFusionRemovesOutdatedDukAsGhost() {
        val nowMs = 1_000_000_000L
        val fusion = VehicleFusion()

        val dpmulReport = SourceReport("dpmul", "DPMÚL", priority = 0, segments = setOf(Segment.CITY), health = Health.LIVE, staleSegments = emptySet())
        val dukReport = SourceReport("duk", "DÚK", priority = 1, segments = setOf(Segment.CITY), health = Health.LIVE, staleSegments = emptySet())

        val vDpmul = Vehicle(
            source = "dpmul",
            lat = 50.6600,
            lng = 14.0400,
            line = "11",
            delay = 1,
            observedAtMs = nowMs - 10_000L,
            tid = 100,
            tripMatch = TripMatch.HEURISTIC,
            segment = Segment.CITY
        )

        // DUK je daleko (mimo sameSpotM) a staré (3 minuty)
        val vDukStale = Vehicle(
            source = "duk",
            lat = 50.7000,
            lng = 14.1000,
            line = "11",
            delay = 5,
            observedAtMs = nowMs - 180_000L,
            tid = 200,
            tripMatch = TripMatch.EXACT,
            segment = Segment.CITY
        )

        val inputs = listOf(
            SourceInput(dpmulReport, staleVehicleSec = 90, vehicles = listOf(vDpmul)),
            SourceInput(dukReport, staleVehicleSec = 40, vehicles = listOf(vDukStale))
        )

        val fused = fusion.fuse(inputs, progress = null, nowMs = nowMs)
        assertEquals(1, fused.size)
        assertEquals("dpmul", fused[0].source)
    }

    @Test
    fun testFleetLiveOnRejectsStaleExactAndReturnsFreshHeuristic() {
        val testDay = LocalDate.of(2026, 10, 23)
        val nowMs = testDay.atTime(10, 5).atZone(PRAGUE_ZONE).toInstant().toEpochMilli()
        val leg = Leg(kind = "ride", fromStop = 1, toStop = 2, dep = 36000, arr = 36600, tid = 100, t0 = 36000, t1 = 36600)

        val vDukStale = Vehicle(
            source = "duk",
            lat = 50.66, lng = 14.04, line = "11", delay = 10,
            observedAtMs = nowMs - 300_000L, // 5 minut zastaralé
            tid = 100, tripMatch = TripMatch.EXACT, state = 0
        )

        val vDpmulFresh = Vehicle(
            source = "dpmul",
            lat = 50.66, lng = 14.04, line = "11", delay = 2,
            observedAtMs = nowMs - 15_000L, // 15 sekund čerstvé
            tid = 100, tripMatch = TripMatch.HEURISTIC, state = 0
        )

        val fleet = Fleet(vehicles = listOf(vDukStale, vDpmulFresh), reports = emptyList(), label = "", warningCount = 0)

        val live = fleet.liveOn(leg, day = testDay, nowMs = nowMs)
        assertEquals("dpmul", live?.source)
        assertEquals(2, live?.delay)
    }

    @Test
    fun testVehicleIdNormalizationAndMatching() {
        assertEquals(451, baseVehicleId(300451))
        assertEquals(1212, baseVehicleId(301212))
        assertEquals(1212, baseVehicleId(3001212))
        assertEquals(400123, baseVehicleId(400123)) // Teplice se nesahá
        assertEquals(451, baseVehicleId(451))
        assertEquals(1212, baseVehicleId(1212))
        assertEquals(300, baseVehicleId(300))
        assertEquals(300, baseVehicleId(300300))

        assertEquals(true, sameVehicleId(300451, 451))
        assertEquals(true, sameVehicleId(301212, 1212))
        assertEquals(false, sameVehicleId(400123, 123)) // Teplice se nepáruje s 123
        assertEquals(true, sameVehicleId(300300, 300))
        assertEquals(false, sameVehicleId(300451, 300452))
        assertEquals(false, sameVehicleId(451, 452))
        assertEquals(false, sameVehicleId(0, 451))
    }

    @Test
    fun testVehicleFusionFusesByVehicleIdAcrossDukAndDpmul() {
        val nowMs = 1_000_000_000L
        val fusion = VehicleFusion()

        val dpmulReport = SourceReport("dpmul", "DPMÚL", priority = 0, segments = setOf(Segment.CITY), health = Health.LIVE)
        val dukReport = SourceReport("duk", "DÚK", priority = 1, segments = setOf(Segment.CITY), health = Health.LIVE)

        val vDpmul = Vehicle(
            source = "dpmul",
            lat = 50.6600,
            lng = 14.0400,
            line = "11",
            delay = 1,
            observedAtMs = nowMs - 10_000L,
            id = 451,
            segment = Segment.CITY
        )

        val vDuk = Vehicle(
            source = "duk",
            lat = 50.6900, // třeba i geograficky dál kvůli zpoždění
            lng = 14.0900,
            line = "11",
            delay = 1,
            observedAtMs = nowMs - 10_000L,
            id = 300451,
            segment = Segment.CITY
        )

        val inputs = listOf(
            SourceInput(dpmulReport, staleVehicleSec = 90, vehicles = listOf(vDpmul)),
            SourceInput(dukReport, staleVehicleSec = 40, vehicles = listOf(vDuk))
        )

        val fused = fusion.fuse(inputs, progress = null, nowMs = nowMs)
        assertEquals(1, fused.size)
    }

    @Test
    fun testVehicleFusionDoesNotFuseDifferentVehicleIdsEvenIfClose() {
        val nowMs = 1_000_000_000L
        val fusion = VehicleFusion()

        val dpmulReport = SourceReport("dpmul", "DPMÚL", priority = 0, segments = setOf(Segment.CITY), health = Health.LIVE)
        val dukReport = SourceReport("duk", "DÚK", priority = 1, segments = setOf(Segment.CITY), health = Health.LIVE)

        val vDpmul = Vehicle(
            source = "dpmul",
            lat = 50.6600,
            lng = 14.0400,
            line = "11",
            delay = 1,
            observedAtMs = nowMs - 10_000L,
            id = 451,
            segment = Segment.CITY
        )

        val vDukDifferent = Vehicle(
            source = "duk",
            lat = 50.6601, // velmi blízko (10m)
            lng = 14.0401,
            line = "11",
            delay = 1,
            observedAtMs = nowMs - 10_000L,
            id = 300452, // jiné vozidlo
            segment = Segment.CITY
        )

        val inputs = listOf(
            SourceInput(dpmulReport, staleVehicleSec = 90, vehicles = listOf(vDpmul)),
            SourceInput(dukReport, staleVehicleSec = 40, vehicles = listOf(vDukDifferent))
        )

        val fused = fusion.fuse(inputs, progress = null, nowMs = nowMs)
        assertEquals(2, fused.size)
    }
}
