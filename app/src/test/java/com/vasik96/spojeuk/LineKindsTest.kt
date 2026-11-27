package com.vasik96.spojeuk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Druh linky ze živých dat přebije kategorii z jízdního řádu (trolejbus, mimopražská tramvaj). */
class LineKindsTest {
    private val dpmul = "Dopravní podnik města Ústí nad Labem a.s."

    @After fun tearDown() = LineKinds.clearForTest()

    @Test fun unknownLineKeepsTimetableCategory() {
        assertEquals(VehicleStyle.BUS, tripStyle(MapCategory.BUS, "72", dpmul))
        assertEquals(VehicleStyle.TRAM, tripStyle(MapCategory.TRAM, "9", "Dopravní podnik hl. m. Prahy"))
    }

    @Test fun learnedTrolleybusAndTram() {
        LineKinds.learn(dpmul, "72", "trolleybus")
        LineKinds.learn("Dopravní podnik města Brna", "1", "tram")
        assertEquals(VehicleStyle.TROLLEYBUS, tripStyle(MapCategory.BUS, " 72", dpmul))
        assertEquals(VehicleStyle.TRAM, tripStyle(MapCategory.BUS, "1", "Dopravní podnik města Brna"))
        // stejné číslo u jiného dopravce zůstává autobus
        assertEquals(VehicleStyle.BUS, tripStyle(MapCategory.BUS, "72", "Arriva Teplice"))
    }

    @Test fun pragueTrolleybusInTramRangeAndBusNeverUnlearns() {
        LineKinds.learn("Dopravní podnik hl. m. Prahy", "58", "trolleybus")
        LineKinds.learn("Dopravní podnik hl. m. Prahy", "58", "bus")   // výluka: autobusy na trolejbusové lince
        assertEquals(VehicleStyle.TROLLEYBUS, tripStyle(MapCategory.TRAM, "58", "Dopravní podnik hl. m. Prahy"))
        assertEquals(VehicleStyle.METRO_A, tripStyle(MapCategory.METRO, "A", "Dopravní podnik hl. m. Prahy"))
    }
}
