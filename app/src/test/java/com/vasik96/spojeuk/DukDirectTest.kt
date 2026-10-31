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
        assertEquals("DPMÚL, DÚK (regionální doprava zastaralé)", label)
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
}
