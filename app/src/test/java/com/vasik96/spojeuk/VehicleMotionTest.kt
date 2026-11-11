package com.vasik96.spojeuk

import android.os.SystemClock
import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleMotionTest {

    private fun createStraightRoute(): PathMeasure {
        // Trasa 2 km rovně na sever
        val p1 = LatLng(50.000, 14.000)
        val p2 = LatLng(50.018, 14.000)
        val stop1 = LatLng(50.005, 14.000) // ~555m
        val stop2 = LatLng(50.010, 14.000) // ~1100m
        return PathMeasure(listOf(p1, p2), listOf(stop1, stop2))
    }

    @Test
    fun testContinuousMotionWithoutStopping() {
        val route = createStraightRoute()
        val startPos = LatLng(50.000, 14.000)
        var tMs = SystemClock.elapsedRealtime()

        val motion = VehicleMotion(startPos.latitude, startPos.longitude, tMs, isTrain = false)
        motion.setRoute(route)
        motion.onFix(startPos.latitude, startPos.longitude, tMs, predict = true)

        val pos0 = motion.positionAt(tMs)
        for (step in 1..50) { tMs += 100; motion.positionAt(tMs) }
        val pos5 = motion.positionAt(tMs)

        for (step in 1..50) { tMs += 100; motion.positionAt(tMs) }
        val pos10 = motion.positionAt(tMs)

        for (step in 1..50) { tMs += 100; motion.positionAt(tMs) }
        val pos15 = motion.positionAt(tMs)

        // Vozidlo se musí neustále pohybovat dopředu i po 5.8 sekundách
        assertTrue("Pos po 5s musí pokročilt od startu", pos5.latitude > pos0.latitude)
        assertTrue("Pos po 10s musí pokročilt od 5s", pos10.latitude > pos5.latitude)
        assertTrue("Pos po 15s musí pokročilt od 10s", pos15.latitude > pos10.latitude)
    }

    @Test
    fun testDwellAtStop() {
        val route = createStraightRoute()
        val startPos = LatLng(50.000, 14.000)
        var tMs = SystemClock.elapsedRealtime()

        val motion = VehicleMotion(startPos.latitude, startPos.longitude, tMs, isTrain = false)
        motion.setRoute(route)
        motion.onFix(startPos.latitude, startPos.longitude, tMs, predict = true)

        val stopDist = route.stopDistances.first()
        val stopLatLng = route.pointAt(stopDist)

        var arrivedPos: LatLng? = null
        var dwellTime = -1L

        // Simulujeme průchod k první zastávce (až 2000 kroků po 100 ms = 200 s)
        for (step in 1..2000) {
            tMs += 100
            val p = motion.positionAt(tMs)
            if (kotlin.math.abs(p.latitude - stopLatLng.latitude) < 0.000001) {
                dwellTime = tMs
                arrivedPos = p
                break
            }
        }

        assertTrue("Vozidlo by mělo dorazit přesně k první zastávce", dwellTime > 0)

        // Během pobytu na zastávce (+4s po příjezdu) se pozice nesmí měnit
        val posAtArrival = arrivedPos!!
        for (step in 1..40) { tMs += 100; motion.positionAt(tMs) }
        val posDuringDwell = motion.positionAt(tMs)
        assertEquals("Během pobytu na zastávce se poloha nemění", posAtArrival.latitude, posDuringDwell.latitude, 0.000001)

        // Po skončení pobytu (+15s po příjezdu = celkově > 12s dwell) se vozidlo znova rozjede
        for (step in 1..150) { tMs += 100; motion.positionAt(tMs) }
        val posAfterDwell = motion.positionAt(tMs)
        assertTrue("Po skončení pobytu vozidlo pokračuje v jízdě", posAfterDwell.latitude > posAtArrival.latitude)
    }

    @Test
    fun testStationaryAtTerminus() {
        val route = createStraightRoute()
        val endPos = LatLng(50.0178, 14.000) // Blízko konečné (2 km)
        var tMs = SystemClock.elapsedRealtime()

        val motion = VehicleMotion(endPos.latitude, endPos.longitude, tMs, isTrain = false)
        motion.setRoute(route)
        motion.onFix(endPos.latitude, endPos.longitude, tMs, predict = true)

        val pos1 = motion.positionAt(tMs)
        // Posuneme čas o 10 sekund – vozidlo stojí na konečné a nesmí odjíždět z trasy
        for (step in 1..100) { tMs += 100; motion.positionAt(tMs) }
        val pos2 = motion.positionAt(tMs)

        assertEquals("Vozidlo stojící na konečné se nepohne", pos1.latitude, pos2.latitude, 0.000001)
    }

    @Test
    fun testPredictionLeadDistance() {
        val route = createStraightRoute()
        val fixPos = LatLng(50.000, 14.000)
        var tMs = SystemClock.elapsedRealtime()

        val motion = VehicleMotion(fixPos.latitude, fixPos.longitude, tMs, isTrain = false)
        motion.setRoute(route)
        motion.onFix(fixPos.latitude, fixPos.longitude, tMs, predict = true)

        val renderedPos = motion.positionAt(tMs)

        // Predikovaná poloha na mapě je předsazená dopředu (lead distance) před samotným raw GPS fixem
        assertTrue("Predikovaná poloha je napřed před raw fixem", renderedPos.latitude > fixPos.latitude)
    }

    @Test
    fun testComplicatedRoundaboutLoopDoesNotJumpDirection() {
        // Trasa simulující sjíždění z mostu, kruhový objezd a návrat těsně vedle původní silnice
        // Leg 1: z mostu na jih: (50.6650, 14.0450) -> (50.6600, 14.0500) (~650 m)
        // Leg 2: kruhový objezd / smyčka: (50.6600, 14.0500) -> (50.6590, 14.0530) -> (50.6580, 14.0510) -> (50.6590, 14.0480) -> (50.6600, 14.0500)
        // Leg 3: návrat na sever těsně vedle Leg 1 (jen 12 m posunuté): (50.6600, 14.0502) -> (50.6650, 14.0452)
        val routePoints = listOf(
            LatLng(50.6650, 14.0450), // 0: začátek mostu
            LatLng(50.6625, 14.0475), // 1: sjezd z mostu
            LatLng(50.6600, 14.0500), // 2: vjezd na kruháč
            LatLng(50.6590, 14.0530), // 3: kruháč východ
            LatLng(50.6580, 14.0510), // 4: kruháč jih
            LatLng(50.6590, 14.0480), // 5: kruháč západ
            LatLng(50.6600, 14.0500), // 6: výjezd z kruháče
            LatLng(50.6625, 14.0477), // 7: návrat podél mostu (směr sever)
            LatLng(50.6650, 14.0452), // 8: cíl sever
        )
        val route = PathMeasure(routePoints)
        var tMs = SystemClock.elapsedRealtime()

        val startPos = routePoints[0]
        val motion = VehicleMotion(startPos.latitude, startPos.longitude, tMs, isTrain = false)
        motion.setRoute(route)
        // První fix na začátku mostu se směrem na jihovýchod (~140°)
        motion.onFix(startPos.latitude, startPos.longitude, tMs, heading = 140.0, predict = true)

        // Druhý fix o 6 sekund později – vůz jede po mostu dolů (Leg 1)
        // GPS fix má šum a je fyzicky blíže k Leg 3 (která vede na sever!), ale vůz jede na jih
        tMs += 6000
        val fix2 = LatLng(50.6625, 14.0476) // mezi Leg 1 a Leg 3, mírně blíž k Leg 3
        motion.onFix(fix2.latitude, fix2.longitude, tMs, heading = 140.0, predict = true)

        val posAfterFix2 = motion.positionAt(tMs)
        // Vůz MUSÍ být na Leg 1 (před kruháčem), nikoliv přeskočený na návratovou větev (Leg 3)!
        // Na Leg 1 je zeměpisná šířka menší než 50.6650 a postupuje na jih (< 50.6650)
        assertTrue("Vůz zůstal na Leg 1 a nepřeskočil do protisměru na návrat", posAfterFix2.latitude < 50.6650 && posAfterFix2.latitude > 50.6600)

        // Třetí fix o dalších 6 sekund později – před vjezdem na kruháč
        tMs += 6000
        val fix3 = LatLng(50.6610, 14.0490)
        motion.onFix(fix3.latitude, fix3.longitude, tMs, heading = 140.0, predict = true)
        val posAfterFix3 = motion.positionAt(tMs)

        // Pozice musí monotónně postupovat směrem ke kruháči (zeměpisná šířka klesá)
        assertTrue("Pozice postupuje dopředu ke kruháči", posAfterFix3.latitude <= posAfterFix2.latitude)
    }

    @Test
    fun testSmoothTransitionWhenRouteIsSetWithoutTeleport() {
        val route = createStraightRoute()
        var tMs = SystemClock.elapsedRealtime()

        // Vůz je zobrazen mírně mimo osu trasy (např. surová GPS poloha vzdálená 25 m)
        val rawGpsPos = LatLng(50.002, 14.0003) // ~25 m na východ od trasy
        val motion = VehicleMotion(rawGpsPos.latitude, rawGpsPos.longitude, tMs, isTrain = false)

        val posBeforeRoute = motion.positionAt(tMs)
        assertEquals("Před trasou odpovídá surové GPS", rawGpsPos.latitude, posBeforeRoute.latitude, 0.00001)

        // Načte se trasa v pozadí
        motion.setRoute(route)

        // Bezprostředně po setRoute nesmí nastat instantní skok (teleport)
        val posRightAfter = motion.positionAt(tMs)
        val jumpDist = haversineMeters(posBeforeRoute, posRightAfter)
        assertTrue("Bezprostředně po setRoute nenastal skok (vzdálenost < 2 m)", jumpDist < 2.0)

        // Po 500 ms je vůz plynule v půlce cesty na trasu
        tMs += 500
        val posMid = motion.positionAt(tMs)
        val distToRaw = haversineMeters(rawGpsPos, posMid)
        assertTrue("Vůz se plynule posouvá směrem k ose trasy", distToRaw > 5.0)

        // Po 1200 ms je přechod dokončen a vůz je přesně na ose trasy
        tMs += 700
        val posEnd = motion.positionAt(tMs)
        assertEquals("Po dokončení animace je vůz na ose trasy (lng = 14.000)", 14.000, posEnd.longitude, 0.0001)
    }

    @Test
    fun testRealtimeAheadOneSecondAnimationAndContinuePrediction() {
        val route = createStraightRoute()
        var tMs = SystemClock.elapsedRealtime()

        // 1. Vůz začíná na trase
        val startPos = LatLng(50.000, 14.000)
        val motion = VehicleMotion(startPos.latitude, startPos.longitude, tMs, isTrain = false)
        motion.setRoute(route)
        motion.onFix(startPos.latitude, startPos.longitude, tMs, predict = true)
        motion.positionAt(tMs)

        // 2. Simulujeme 3 sekundy plynulého pohybu predikce
        repeat(30) {
            tMs += 100
            motion.positionAt(tMs)
        }
        val posBeforeFix = motion.positionAt(tMs)

        // 3. Přijde nový realtime serverový fix, který je napřed oproti predikci
        // (např. o ~70 m severněji od aktuální predikované polohy)
        val aheadFix = LatLng(posBeforeFix.latitude + 0.0007, 14.000)
        motion.onFix(aheadFix.latitude, aheadFix.longitude, tMs, predict = true)

        // Bezprostředně po onFix nesmí nastat teleport k aheadFix
        val posImmediately = motion.positionAt(tMs)
        val jumpDist = haversineMeters(posBeforeFix, posImmediately)
        assertTrue("Bezprostředně po fixu nenastane teleport (< 2 m)", jumpDist < 2.0)

        // Během 1 sekundy (po 500 ms) se vůz plynule posunuje směrem k aheadFix
        tMs += 500
        val posHalfway = motion.positionAt(tMs)
        assertTrue("Vůz animuje k aheadFix (v půlce cesty)", posHalfway.latitude > posBeforeFix.latitude)
        assertTrue("Vůz ještě nedorazil na konec", posHalfway.latitude < aheadFix.latitude)

        // Po 1000 ms je na pozici aheadFix
        tMs += 500
        val posAt1s = motion.positionAt(tMs)
        assertEquals("Po 1 s dosáhl realtime pozice", aheadFix.latitude, posAt1s.latitude, 0.00005)

        // Po 1s predikce plynule pokračuje dál dopředu po trase
        tMs += 1000
        val posAfterPrediction = motion.positionAt(tMs)
        assertTrue("Predikce pokračuje dál za realtime pozici", posAfterPrediction.latitude > posAt1s.latitude)
    }

    @Test
    fun testBusPredictionSpeedIsRealisticAndNotExcessive() {
        // Rovná trasa bez zastávek v testovaném úseku (2 km)
        val p1 = LatLng(50.000, 14.000)
        val p2 = LatLng(50.020, 14.000)
        val route = PathMeasure(listOf(p1, p2))
        var tMs = SystemClock.elapsedRealtime()

        val startPos = LatLng(50.000, 14.000)
        val motion = VehicleMotion(startPos.latitude, startPos.longitude, tMs, isTrain = false)
        motion.setRoute(route)
        motion.onFix(startPos.latitude, startPos.longitude, tMs, predict = true)

        // Rozjezd (2 sekundy)
        repeat(20) {
            tMs += 100
            motion.positionAt(tMs)
        }
        val posAfterWarmup = motion.positionAt(tMs)

        // Měříme pohyb po dobu 5 sekund
        repeat(50) {
            tMs += 100
            motion.positionAt(tMs)
        }
        val posAfter5s = motion.positionAt(tMs)

        val distMeters = haversineMeters(posAfterWarmup, posAfter5s)
        val speedKmh = (distMeters / 5.0) * 3.6

        // Rychlost autobusu v predikci: nesmí být extrémně pomalá (< 20 km/h), ale ani příliš rychlá (> 45 km/h)
        assertTrue("Rychlost autobusu ($speedKmh km/h) nesmí být extrémně pomalá (> 20 km/h)", speedKmh >= 20.0)
        assertTrue("Rychlost autobusu ($speedKmh km/h) nesmí být příliš rychlá (<= 45 km/h)", speedKmh <= 45.0)
    }
}

