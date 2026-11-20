package com.vasik96.spojeuk

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Predikce pohybu: jen dopředu po trase, rychlost z realtime dat, směr jen z azimutu / realtime pohybu,
 * bez trasy plynulá jízda k realtime poloze po celý interval. Hodiny jsou řízené testem.
 */
class VehicleMotionTest {

    private var now = 0L          // elapsedRealtime
    private var wallNow = 0L      // čas pozorování (wall clock)

    /** 2 km rovně na sever, zastávky po cestě. */
    private fun route() = PathMeasure(
        listOf(LatLng(50.000, 14.000), LatLng(50.018, 14.000)),
        listOf(LatLng(50.005, 14.000), LatLng(50.010, 14.000))
    )

    /** Bod [m] metrů na sever od začátku trasy. */
    private fun north(m: Double) = LatLng(50.000 + m / 111_195.0, 14.000)

    private fun motion(start: LatLng, train: Boolean = false) =
        VehicleMotion(start.latitude, start.longitude, wallNow, isTrain = train, elapsed = { now }, wall = { wallNow })

    private fun advance(ms: Long) { now += ms; wallNow += ms }

    private fun fix(m: VehicleMotion, p: LatLng, heading: Double? = null) =
        m.onFix(p.latitude, p.longitude, wallNow, heading = heading, predict = true)

    /** Projede [ms] po 100 ms a vrátí zeměpisné šířky (sever = dopředu po trase). */
    private fun run(m: VehicleMotion, ms: Long): List<Double> {
        val out = ArrayList<Double>()
        var t = 0L
        while (t < ms) { advance(100); t += 100; out += m.positionAt(now).latitude }
        return out
    }

    private fun meters(a: Double, b: Double) = (b - a) * 111_195.0

    @Test
    fun movesForwardContinuouslyAtMeasuredSpeed() {
        val m = motion(north(100.0))
        m.setRoute(route())
        m.positionAt(now)
        advance(6_000)
        fix(m, north(160.0))                 // 60 m za 6 s = 10 m/s
        val lats = run(m, 6_000)
        for (i in 1 until lats.size) assertTrue("jen dopředu", lats[i] >= lats[i - 1] - 1e-9)
        val endM = meters(north(0.0).latitude, lats.last())
        // za 6 s při 10 m/s: kolem 160 + 60 m (s rozjezdem a dorovnáním), ne stání a ne ujetí
        assertTrue("jede (ujel $endM m)", endM > 190.0)
        assertTrue("nepředbíhá horizont predikce ($endM m)", endM <= 160.0 + 10.0 * 6.0 + 2.0)
    }

    @Test
    fun keepsGoingWhenNewFixConfirmsPrediction() {
        // jede stálých 10 m/s mezi zastávkami (555 m a 1 111 m), poloha po 6 s; predikce trefuje nové fixy
        val m = motion(north(600.0))
        m.setRoute(route())
        m.positionAt(now)
        var pos = 600.0
        val speeds = ArrayList<Double>()
        repeat(7) { i ->
            pos += 60.0
            var prev = m.positionAt(now).latitude
            repeat(60) {                       // 6 s jízdy, pak nový fix tam, kde vůz opravdu je
                advance(100)
                val l = m.positionAt(now).latitude
                if (i >= 3) speeds += meters(prev, l) * 10.0   // m/s, po rozjezdu
                prev = l
            }
            fix(m, north(pos))
        }
        // dřív vůz před každou aktualizací brzdil k pevnému horizontu 6 s a u fixu, který predikce trefila, stál
        val min = speeds.min()
        assertTrue("nezastaví u potvrzené predikce (nejnižší rychlost $min m/s)", min > 8.0)
        assertTrue("nejede rychleji, než jede vůz (${speeds.max()} m/s)", speeds.max() < 12.5)
    }

    @Test
    fun neverMovesBackwardWhenPredictionIsAhead() {
        val m = motion(north(100.0))
        m.setRoute(route())
        advance(6_000); fix(m, north(160.0))
        run(m, 6_000)                        // predikce je teď ~60 m před posledním fixem
        val before = m.positionAt(now).latitude
        advance(6_000); fix(m, north(175.0)) // vůz ve skutečnosti skoro stál (zastávka): fix je za vykreslenou polohou
        val lats = run(m, 6_000)
        for (l in lats) assertTrue("necouvá", l >= before - 1e-9)
    }

    @Test
    fun stopsWhenRealtimeShowsItStands() {
        val m = motion(north(100.0))
        m.setRoute(route())
        advance(6_000); fix(m, north(160.0))
        run(m, 3_000)
        advance(3_000); fix(m, north(162.0)) // nový fix (jiný čas pozorování), skoro stejné místo = stojí
        run(m, 4_000)
        val a = m.positionAt(now).latitude
        val lats = run(m, 6_000)
        assertEquals("stojí", a, lats.last(), 1e-7)
    }

    @Test
    fun repeatedHeldFixIsNotTreatedAsStanding() {
        val m = motion(north(100.0))
        m.setRoute(route())
        advance(6_000); fix(m, north(160.0))
        run(m, 2_000)
        // server poslal stejnou polohu se stejným časem pozorování (jen ji drží): žádná nová informace
        val held = m.onFix(north(160.0).latitude, north(160.0).longitude, wallNow - 2_000, predict = true)
        assertFalse(held)
        val a = m.positionAt(now).latitude
        val lats = run(m, 2_000)
        assertTrue("dál jede", lats.last() > a)
    }

    @Test
    fun headingAgainstRouteDirectionIgnoresRoute() {
        val m = motion(north(500.0))
        m.setRoute(route())
        advance(6_000)
        fix(m, north(440.0), heading = 180.0)     // jede na jih, trasa vede na sever: k vozu nepatří
        val lats = run(m, 3_000)
        for (i in 1 until lats.size) assertTrue("jede k realtime poloze (na jih), ne po trase na sever", lats[i] <= lats[i - 1] + 1e-9)
        assertTrue("nepředbíhá realtime polohu", lats.last() >= north(440.0).latitude - 1e-7)
    }

    @Test
    fun withoutRouteMovesSmoothlyOverTheWholeInterval() {
        val m = motion(north(100.0))
        advance(6_000)
        fix(m, north(160.0))
        run(m, 1_000)
        val after1s = meters(north(100.0).latitude, m.positionAt(now).latitude)
        assertTrue("po 1 s ještě nedojel (plynulá jízda, ne skok): $after1s m", after1s in 3.0..20.0)
        run(m, 6_000)
        assertEquals(north(160.0).latitude, m.positionAt(now).latitude, 1e-6)
    }

    @Test
    fun stopsAtNextStopUntilRealtimeShowsDeparture() {
        // zastávka ~555 m od začátku trasy
        val m = motion(north(470.0))
        m.setRoute(route())
        advance(6_000); fix(m, north(530.0))     // 10 m/s, příští zastávka je 25 m před ním
        val lats = run(m, 8_000)
        val stopLat = north(556.0).latitude
        for (l in lats) assertTrue("nepřejede zastávku, dokud data neukážou odjezd", l <= stopLat)
        assertEquals("dobrzdil u zastávky", lats.last(), lats[lats.size - 10], 1e-7)
        advance(2_000); fix(m, north(600.0))     // odjel
        val after = run(m, 4_000)
        assertTrue("po odjezdu pokračuje", after.last() > stopLat)
    }

    @Test
    fun stationaryAtTerminus() {
        val end = north(2_001.0)
        val m = motion(north(1_990.0))
        m.setRoute(route())
        advance(6_000); fix(m, end)
        run(m, 2_000)
        val a = m.positionAt(now).latitude
        val lats = run(m, 6_000)
        assertEquals("na konečné stojí", a, lats.last(), 1e-7)
    }

    @Test
    fun joiningRouteDoesNotTeleport() {
        val raw = LatLng(50.003, 14.0004)      // ~30 m vedle osy trasy
        val m = motion(raw)
        m.positionAt(now)
        m.setRoute(route())
        advance(6_000)
        fix(m, LatLng(50.0035, 14.0004))
        advance(16)
        val p = m.positionAt(now)
        assertTrue("žádný skok při napojení na trasu", haversineMeters(p, raw) < 3.0)
        run(m, 2_000)
        assertEquals("po přechodu je na ose trasy", 14.000, m.positionAt(now).longitude, 0.0001)
    }

    @Test
    fun hugeJumpIsNotAnimated() {
        val m = motion(north(100.0))
        advance(6_000)
        fix(m, LatLng(50.2, 14.3))              // jiný vůz / chyba zdroje
        assertEquals(50.2, m.positionAt(now).latitude, 1e-9)
    }
}
