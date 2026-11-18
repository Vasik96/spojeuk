package com.vasik96.spojeuk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Čísla vozů se mezi zdroji a druhy dopravy opakují (PID tramvaj i autobus 1234, Ústí i Děčín 834, vlak i bus 665).
 * Klíč (a tím animace) musí zůstat u vozu podle serverového uid, jinak vůz "letí" přes město k jinému vozu.
 */
class VehicleKeyerTest {

    private fun v(uid: String, source: String, id: Int, lat: Double, lng: Double, line: String) =
        Vehicle(source = source, lat = lat, lng = lng, line = line, delay = 0, observedAtMs = 0L, id = id, serverUid = uid)

    @Test
    fun sameNumberDifferentVehiclesKeepTheirKeys() {
        val keyer = VehicleKeyer()
        val tram = v("g0:1234", "pid", 1234, 50.08, 14.42, "22")
        val bus = v("g3:1234", "pid", 1234, 50.03, 14.50, "136")
        val first = keyer.assign(listOf(tram, bus)).associate { it.second.serverUid to it.first }
        assertNotEquals(first["g0:1234"], first["g3:1234"])

        // další odpověď: opačné pořadí, vozy se trochu posunuly
        val second = keyer.assign(listOf(bus.copy(lat = 50.031), tram.copy(lat = 50.081)))
            .associate { it.second.serverUid to it.first }
        assertEquals(first["g0:1234"], second["g0:1234"])
        assertEquals(first["g3:1234"], second["g3:1234"])
    }

    @Test
    fun crossSourceSameNumberIsNotMerged() {
        val keyer = VehicleKeyer()
        val usti = v("v834", "dpmul", 834, 50.66, 14.03, "13")
        val decin = v("d834", "dpmd", 834, 50.77, 14.21, "5")
        val first = keyer.assign(listOf(usti, decin)).associate { it.second.serverUid to it.first }
        // Ústecký vůz chvíli chybí: děčínský nesmí převzít jeho klíč
        val second = keyer.assign(listOf(decin)).associate { it.second.serverUid to it.first }
        assertEquals(first["d834"], second["d834"])
        val third = keyer.assign(listOf(usti, decin)).associate { it.second.serverUid to it.first }
        assertEquals(first["v834"], third["v834"])
        assertEquals(first["d834"], third["d834"])
    }
}
