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
    fun testSourceLabelWhenAllSourcesStale() {
        val dpmulReport = SourceReport(
            id = "dpmul",
            name = "DPMÚL",
            priority = 0,
            segments = setOf(Segment.CITY),
            health = Health.STALE,
            staleSegments = setOf(Segment.CITY),
            vehicleCount = 20
        )
        val dukReport = SourceReport(
            id = "duk",
            name = "DÚK",
            priority = 1,
            segments = setOf(Segment.CITY, Segment.REGIONAL),
            health = Health.STALE,
            staleSegments = setOf(Segment.CITY, Segment.REGIONAL),
            vehicleCount = 50
        )
        val trainReport = SourceReport(
            id = "train",
            name = "Správa Železnic",
            priority = 2,
            segments = setOf(Segment.REGIONAL),
            health = Health.STALE,
            staleSegments = setOf(Segment.REGIONAL),
            vehicleCount = 10
        )

        val label = SourceLabel.build(listOf(dpmulReport, dukReport, trainReport))
        assertEquals("DPMÚL (zastaralé), DÚK (zastaralé), Správa Železnic (zastaralé)", label)
    }

    @Test
    fun testSourceLabelWhenSomeSourcesStale() {
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
            staleSegments = emptySet(),
            vehicleCount = 50
        )
        val trainReport = SourceReport(
            id = "train",
            name = "Správa Železnic",
            priority = 2,
            segments = setOf(Segment.REGIONAL),
            health = Health.STALE,
            staleSegments = setOf(Segment.REGIONAL),
            vehicleCount = 10
        )

        val label = SourceLabel.build(listOf(dpmulReport, dukReport, trainReport))
        assertEquals("DPMÚL, DÚK, Správa Železnic (zastaralé)", label)
    }

    @Test
    fun testSourceLabelWhenFirstSourceStaleOthersLive() {
        val dpmulReport = SourceReport(
            id = "dpmul",
            name = "DPMÚL",
            priority = 0,
            segments = setOf(Segment.CITY),
            health = Health.STALE,
            staleSegments = setOf(Segment.CITY),
            vehicleCount = 20
        )
        val dukReport = SourceReport(
            id = "duk",
            name = "DÚK",
            priority = 1,
            segments = setOf(Segment.CITY, Segment.REGIONAL),
            health = Health.LIVE,
            staleSegments = emptySet(),
            vehicleCount = 50
        )
        val trainReport = SourceReport(
            id = "train",
            name = "Správa Železnic",
            priority = 2,
            segments = setOf(Segment.REGIONAL),
            health = Health.LIVE,
            staleSegments = emptySet(),
            vehicleCount = 10
        )

        val label = SourceLabel.build(listOf(dpmulReport, dukReport, trainReport))
        assertEquals("DPMÚL (zastaralé), DÚK, Správa Železnic", label)
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
}
