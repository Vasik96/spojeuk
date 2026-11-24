package com.vasik96.spojeuk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingTrackerTest {
    private fun bus(lat: Double, lng: Double, source: String = SourceIds.DPMUL, heading: Double? = null) =
        Vehicle(source = source, lat = lat, lng = lng, line = "1", delay = 0, observedAtMs = 0L, id = 451, heading = heading)

    // ~0.0003° šířky ≈ 33 m
    @Test
    fun firstFixHasNoHeading() {
        val t = HeadingTracker()
        val out = t.track(bus(50.66, 14.04))
        assertNull(out.heading)
        assertNull(out.trail)
    }

    @Test
    fun headingFollowsLatestMoveNotWholeHistory() {
        val t = HeadingTracker()
        t.track(bus(50.6600, 14.0400))
        t.track(bus(50.6603, 14.0400))      // na sever
        val north = t.track(bus(50.6606, 14.0400))
        assertEquals(0.0, north.heading!!, 1.0)
        // zatáčka na východ: směr se řídí posledním úsekem, ne tětivou od začátku
        t.track(bus(50.6606, 14.0405))
        val east = t.track(bus(50.6606, 14.0410))
        assertEquals(90.0, east.heading!!, 1.0)
        assertNotNull(east.trail)
    }

    @Test
    fun dpmulSourceHeadingIsNotTrusted() {
        val t = HeadingTracker()
        t.track(bus(50.6600, 14.0400, heading = 180.0))
        val out = t.track(bus(50.6606, 14.0400, heading = 180.0))   // jede na sever, "azimut" tvrdí jih
        assertEquals(0.0, out.heading!!, 1.0)
    }

    @Test
    fun otherSourcesKeepOwnHeading() {
        val t = HeadingTracker()
        t.track(bus(50.6600, 14.0400, SourceIds.DUK, 180.0))
        val out = t.track(bus(50.6606, 14.0400, SourceIds.DUK, 180.0))
        assertEquals(180.0, out.heading!!, 0.001)
    }
}
