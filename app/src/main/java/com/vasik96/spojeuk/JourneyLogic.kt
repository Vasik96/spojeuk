package com.vasik96.spojeuk

import com.google.android.gms.maps.model.LatLng

/**
 * Čistá logika nad spojením (bez Androidu a jízdního řádu), aby šla testovat.
 * Indexy jízd jsou pozice v [Journey.rawLegs]; časy úseků v sekundách od půlnoci dne spojení.
 */

/** Indexy jízd (ne chůze) v [Journey.rawLegs]. */
internal fun rideIndices(legs: List<Leg>): List<Int> = legs.indices.filter { legs[it].kind == "ride" }

/** Po kolika sekundách od odjezdu jízdy (vč. zpoždění) ukazuje mapa už další jízdu spojení. */
internal const val LEG_SWITCH_AFTER_SEC = 180

/**
 * Která jízda spojení je "aktuální" pro mapu: první, dokud z nástupní zastávky neodjela před víc než
 * [LEG_SWITCH_AFTER_SEC] (odjezd podle JŘ + živé zpoždění v minutách z [delayMin]), pak další atd.
 */
internal fun activeLegIndexOf(legs: List<Leg>, nowSec: Int, delayMin: (Int) -> Int): Int {
    val rides = rideIndices(legs)
    if (rides.size <= 1) return rides.firstOrNull() ?: 0
    for (i in rides.dropLast(1)) {
        if (nowSec <= legs[i].dep + delayMin(i) * 60 + LEG_SWITCH_AFTER_SEC) return i
    }
    return rides.last()
}

enum class TransferRisk { NONE, TIGHT, AT_RISK }

/** Stav přestupu po započtení živých zpoždění: [marginMin] = rezerva v minutách (záporná = nestihne se). */
data class TransferStatus(val risk: TransferRisk, val marginMin: Int, val delayMin: Int)

/**
 * Přestup [k] (mezi k-tou a (k+1)-tou jízdou). Vyhodnocuje se jen se živým zpožděním přijíždějící jízdy
 * ([delays] = index jízdy → zpoždění v min) a jen dokud se nepřestoupilo. Zpoždění navazující jízdy rezervu
 * prodlouží (na předčasný odjezd se nespoléhá), chůze mezi zastávkami ji zkrátí.
 */
internal fun transferStatus(legs: List<Leg>, k: Int, delays: Map<Int, Int>, nowSec: Int): TransferStatus? {
    val rides = rideIndices(legs)
    val a = rides.getOrNull(k) ?: return null
    val b = rides.getOrNull(k + 1) ?: return null
    val d1 = delays[a] ?: return null
    val d2 = (delays[b] ?: 0).coerceAtLeast(0)
    if (nowSec > legs[b].dep + d2 * 60) return null            // navazující spoj už odjel, přestup je minulost
    val walkSec = (a + 1 until b).sumOf { i -> legs[i].takeIf { it.kind == "walk" }?.let { it.arr - it.dep } ?: 0 }
    val slackSec = legs[b].dep - legs[a].arr - walkSec
    val marginSec = slackSec - d1 * 60 + d2 * 60
    val margin = Math.floorDiv(marginSec, 60)
    val risk = when {
        d1 <= 0 -> TransferRisk.NONE                                // bez zpoždění jde přestup podle plánu
        marginSec < 0 -> TransferRisk.AT_RISK
        marginSec < 120 -> TransferRisk.TIGHT
        else -> TransferRisk.NONE
    }
    return TransferStatus(risk, margin, d1)
}

/** Nejmenší vzdálenost bodu od lomené čáry trasy v metrech (rovinná aproximace, na pár km stačí). */
internal fun distanceToRouteM(p: LatLng, route: List<LatLng>): Double {
    if (route.isEmpty()) return Double.MAX_VALUE
    val kx = Geo.M_PER_DEG_LNG * Math.cos(Math.toRadians(p.latitude))
    val ky = Geo.M_PER_DEG_LAT
    fun x(l: LatLng) = (l.longitude - p.longitude) * kx
    fun y(l: LatLng) = (l.latitude - p.latitude) * ky
    if (route.size == 1) return Math.hypot(x(route[0]), y(route[0]))
    var best = Double.MAX_VALUE
    for (i in 0 until route.size - 1) {
        val ax = x(route[i]); val ay = y(route[i])
        val bx = x(route[i + 1]); val by = y(route[i + 1])
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val tt = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val d = Math.hypot(ax + tt * dx, ay + tt * dy)
        if (d < best) best = d
    }
    return best
}

// ───────────────────────── sledování cesty (notifikace) ─────────────────────────

/** Kde v cestě uživatel je; časy už se živým zpožděním, v sekundách od půlnoci dne spojení. */
sealed interface TrackPhase {
    /** Ještě nenastoupil do první jízdy [ride] (index v rawLegs), odjíždí v [depSec]. */
    data class BeforeDeparture(val ride: Int, val depSec: Int) : TrackPhase
    /** Jede jízdou [ride], vystupuje v [arrSec]. */
    data class Riding(val ride: Int, val arrSec: Int) : TrackPhase
    /** Přestupuje: další jízda [ride] odjíždí v [depSec]. */
    data class Transfer(val ride: Int, val depSec: Int) : TrackPhase
    /** Dojel do cíle v [arrSec]. */
    data class Arrived(val arrSec: Int) : TrackPhase
}

/**
 * Fáze cesty v čase [nowSec] se zpožděními [delays] (index jízdy → min). Zpoždění posouvá odjezd i příjezd
 * dané jízdy; jízdy bez živých dat jedou podle jízdního řádu.
 */
internal fun trackPhase(legs: List<Leg>, nowSec: Int, delays: Map<Int, Int>): TrackPhase {
    val rides = rideIndices(legs)
    if (rides.isEmpty()) return TrackPhase.Arrived(legs.lastOrNull()?.arr ?: 0)
    for ((n, i) in rides.withIndex()) {
        val d = (delays[i] ?: 0) * 60
        val dep = legs[i].dep + d
        val arr = legs[i].arr + d
        if (nowSec < dep) return if (n == 0) TrackPhase.BeforeDeparture(i, dep) else TrackPhase.Transfer(i, dep)
        if (nowSec < arr) return TrackPhase.Riding(i, arr)
    }
    val last = rides.last()
    return TrackPhase.Arrived(legs[last].arr + (delays[last] ?: 0) * 60)
}

/** Do jaké vzdálenosti od zastávky výstupu se teprve hlásí "vystupuješ" (ne hned po předchozí zastávce). */
internal const val ALIGHT_ANNOUNCE_M = 350.0

/** Rezerva, o kolik smí být zastávka "mezi" vedle přímky poloha → výstup, aby se pořád brala jako ležící mezi nimi. */
private const val BETWEEN_SLACK_M = 120.0

/**
 * Leží mezi [from] a cílovou zastávkou [to] nějaká jiná zastávka z [between]? Zastávka je "mezi", když se cesta
 * from → ona → to prodlouží oproti přímé jen o málo ([BETWEEN_SLACK_M]) a ona sama není u cíle (to je ta samá zastávka).
 */
internal fun hasStopBetween(from: Pair<Double, Double>, to: Pair<Double, Double>, between: List<Pair<Double, Double>>): Boolean {
    val direct = Geo.distanceM(from.first, from.second, to.first, to.second)
    return between.any { s ->
        val toEnd = Geo.distanceM(s.first, s.second, to.first, to.second)
        if (toEnd < 40.0) return@any false     // další nástupiště téže zastávky
        val viaIt = Geo.distanceM(from.first, from.second, s.first, s.second) + toEnd
        viaIt <= direct + BETWEEN_SLACK_M
    }
}

/**
 * Je výstup "na dosah", tedy má se psát / hlásit "vystupuješ"? Až když je to příští zastávka (žádná mezi) a zbývá
 * nejvýš [ALIGHT_ANNOUNCE_M] m; na dlouhém úseku se tedy nehlásí hned po odjezdu z předchozí zastávky. Krátký úsek
 * (zastávky blíž než [ALIGHT_ANNOUNCE_M]) splní podmínku hned, takže se hlásí tak jako dřív.
 * Bez polohy: jen příští zastávka a zbývá nejvýš 90 s.
 */
internal fun alightImminent(
    remainingSec: Int,
    nextStopIsAlight: Boolean,
    distToAlightM: Double?,
): Boolean {
    if (remainingSec < 0 || !nextStopIsAlight) return false
    return if (distToAlightM != null) distToAlightM <= ALIGHT_ANNOUNCE_M else remainingSec <= 90
}

/**
 * Vyhodnocení, zda se má vyvolat jednorázové upozornění na výstup ("Příští zastávka... vystupuješ").
 * Spustí se, až když je zastávka výstupu příští (mezi tím žádná, [nextStopIsAlight]) a je do [ALIGHT_ANNOUNCE_M] m
 * podle GPS, ne hned po opuštění předchozí zastávky, pokud je úsek dlouhý (např. 10 minut).
 */
internal fun shouldTriggerAlightAlert(
    nowSec: Int,
    arrSec: Int,
    depSec: Int,
    userLat: Double? = null,
    userLng: Double? = null,
    alightLat: Double? = null,
    alightLng: Double? = null,
    nextStopIsAlight: Boolean = true,
): Boolean {
    val remainingSec = arrSec - nowSec
    if (remainingSec < 0 || !nextStopIsAlight) return false

    if (userLat != null && userLng != null && alightLat != null && alightLng != null) {
        val distM = Geo.distanceM(userLat, userLng, alightLat, alightLng)
        return distM <= ALIGHT_ANNOUNCE_M && remainingSec <= 240
    }

    // Bez souřadnic: upozornit jen v závěrečných 40 % úseku a nejvýše 90 sekund před příjezdem
    val legDuration = maxOf(60, arrSec - depSec)
    val elapsed = nowSec - depSec
    val progressFrac = elapsed.toDouble() / legDuration
    return remainingSec <= 90 && progressFrac >= 0.6
}

// ───────────────────────── je uživatel ve voze? ─────────────────────────

/** Poloha živého vozu jízdy a stáří údaje (s). */
internal data class VehiclePos(val lat: Double, val lng: Double, val ageSec: Long)

/**
 * Je uživatel s největší pravděpodobností v tomto voze? Tolerance roste s nepřesností GPS a stářím polohy vozu
 * (vůz mezitím ujede, polohy chodí po několika vteřinách), ať se člověk v jedoucím autobuse pozná i s trochu starým údajem.
 */
internal fun isNearVehicle(userLat: Double, userLng: Double, accuracyM: Float?, v: VehiclePos): Boolean =
    Geo.distanceM(userLat, userLng, v.lat, v.lng) <= vehicleTolerance(accuracyM, v.ageSec)

private fun vehicleTolerance(accuracyM: Float?, ageSec: Long): Double =
    70.0 + (accuracyM ?: 25f).coerceAtMost(60f) * 0.5 + ageSec.coerceIn(0L, 30L) * 6.0

/**
 * Pozná, že uživatel sedí v konkrétní jízdě sledovaného spojení: poloha uživatele sedí na polohu živého vozu téže jízdy
 * ve dvou po sobě jdoucích měřeních a uživatel se přitom pohybuje rychlostí vozidla (nad chůzi) nebo je od vozu
 * skoro na dosah ruky. Když je jednou ve voze, drží se to, dokud se od vozu dvakrát po sobě nevzdálí.
 */
internal class OnBoardTracker {
    /** Index jízdy v rawLegs, ve které uživatel jede; -1 = neznámo / ne ve voze. */
    var ride: Int = -1
        private set
    private var cand = -1
    private var hits = 0
    private var misses = 0
    private var prev: Triple<Double, Double, Long>? = null

    /** Poslední změřená rychlost uživatele (m/s), NaN = neznámá. */
    var lastSpeedMs = Double.NaN
        private set

    /** Pohybuje se rychlostí vozidla (nad chůzi): jede v něčem. */
    val movingLikeVehicle: Boolean get() = !lastSpeedMs.isNaN() && lastSpeedMs >= MOVING_MS

    fun reset() { ride = -1; cand = -1; hits = 0; misses = 0; prev = null; lastSpeedMs = Double.NaN }

    fun update(nowMs: Long, userLat: Double?, userLng: Double?, accuracyM: Float?, live: Map<Int, VehiclePos>) {
        if (userLat == null || userLng == null) return
        val p = prev
        val speed = if (p != null && nowMs - p.third in 3_000L..60_000L)
            Geo.distanceM(p.first, p.second, userLat, userLng) / ((nowMs - p.third) / 1000.0) else Double.NaN
        prev = Triple(userLat, userLng, nowMs)
        if (!speed.isNaN()) lastSpeedMs = speed

        if (ride >= 0) {
            val v = live[ride]
            val far = v == null || Geo.distanceM(userLat, userLng, v.lat, v.lng) > STAY_FACTOR * vehicleTolerance(accuracyM, v.ageSec)
            if (far) { if (++misses >= 2) { ride = -1; misses = 0; cand = -1; hits = 0 } } else misses = 0
            if (ride >= 0) return
        }
        var best = -1
        var bestD = Double.MAX_VALUE
        for ((i, v) in live) {
            if (!isNearVehicle(userLat, userLng, accuracyM, v)) continue
            val d = Geo.distanceM(userLat, userLng, v.lat, v.lng)
            val moving = !speed.isNaN() && speed >= MOVING_MS
            if (!(moving || d <= TOUCHING_M)) continue
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0) { cand = -1; hits = 0; return }
        if (best == cand) hits++ else { cand = best; hits = 1 }
        if (hits >= 2) { ride = best; misses = 0 }
    }

    private companion object {
        const val MOVING_MS = 2.5      // nad rychlost chůze (1,4 m/s)
        const val TOUCHING_M = 30.0
        const val STAY_FACTOR = 2.5    // jednou ve voze: vzdálení až po výrazně větší vzdálenosti než pro nástup
    }
}

/**
 * [trackPhase] s vědomím, že uživatel už sedí v jízdě [onBoardRide]: podle jízdního řádu (a zpoždění) by ještě čekal na
 * nástupu / přestupu, ale je ve voze, takže jede (výstup podle zpoždění vozu).
 */
internal fun trackPhaseOnBoard(legs: List<Leg>, nowSec: Int, delays: Map<Int, Int>, onBoardRide: Int): TrackPhase {
    val base = trackPhase(legs, nowSec, delays)
    if (onBoardRide < 0 || legs.getOrNull(onBoardRide)?.kind != "ride") return base
    val ride = when (base) {
        is TrackPhase.BeforeDeparture -> base.ride
        is TrackPhase.Transfer -> base.ride
        else -> return base
    }
    if (ride != onBoardRide) return base
    val arr = legs[onBoardRide].arr + (delays[onBoardRide] ?: 0) * 60
    return TrackPhase.Riding(onBoardRide, maxOf(arr, nowSec + 60))
}

/**
 * Zjištění, zda byl přestup zmeškán (navazující spoj odjel a cestující se na něj nedostal).
 * Vyhodnocuje se POUZE tehdy, pokud navazující spoj má živá data na mapě (hasLiveB == true),
 * aby se nepřeplánovávalo při pouhém zpoždění spoje bez GPS.
 */
internal fun isTransferMissed(
    legs: List<Leg>,
    k: Int,
    delays: Map<Int, Int>,
    nowSec: Int,
    userLat: Double? = null,
    userLng: Double? = null,
    transferStopLat: Double? = null,
    transferStopLng: Double? = null,
    hasLiveB: Boolean = true,
    /** Jízda, ve které uživatel podle [OnBoardTracker] právě jede (-1 = neznámo). */
    onBoardRide: Int = -1,
    /** Navazující vůz podle své polohy na trase ještě z přestupní zastávky neodjel (nedojel na ni, nebo na ní ještě stojí). */
    vehicleBNotLeftStop: Boolean = false,
    /**
     * Uživatel je teď u živého navazujícího vozu ([isNearVehicle]) nebo se pohybuje rychlostí vozidla. [OnBoardTracker]
     * potřebuje dvě shodná měření, a s horším GPS se nechytne: bez téhle pojistky by "jsem pořád do 400 m od zastávky"
     * po odjezdu vozu B znamenalo "zmeškáno" i pro člověka, který v autobuse sedí, a přeplánovalo by ho z něj.
     */
    userWithVehicleB: Boolean = false,
): Boolean {
    if (!hasLiveB) return false // Bez živých dat na mapě nepovažujeme spoj za zmeškaný (může mít zpoždění)
    if (userWithVehicleB) return false

    val rides = rideIndices(legs)
    val b = rides.getOrNull(k + 1) ?: return false
    if (onBoardRide >= b) return false          // uživatel už v navazujícím voze (nebo dál) sedí
    if (vehicleBNotLeftStop) return false      // navazující vůz podle polohy ještě neodjel (čeká / dojíždí), ač jízdní řád říká jinak
    val depSec = legs[b].dep + (delays[b] ?: 0) * 60
    if (nowSec <= depSec + 60) return false // Spoj ještě neodjel nebo odjel teprve před minutou

    // Pokud máme GPS polohu uživatele a ten je po odjezdu spoje pořád u přestupní zastávky -> zmeškáno
    if (userLat != null && userLng != null && transferStopLat != null && transferStopLng != null) {
        val distM = Geo.distanceM(userLat, userLng, transferStopLat, transferStopLng)
        if (distM < 400.0) return true
    }

    // Příjezdový spoj přijel až po odjezdu navazujícího
    val a = rides.getOrNull(k) ?: return false
    val arrSecA = legs[a].arr + (delays[a] ?: 0) * 60
    if (arrSecA > depSec) return true

    val currentPhase = trackPhase(legs, nowSec, delays)
    if (currentPhase is TrackPhase.Riding && currentPhase.ride >= b) return false // Uživatel už podle JŘ jede v navazujícím nebo dalším spoji
    if (currentPhase is TrackPhase.Arrived) return false

    return nowSec > depSec + 120
}

/**
 * Zjištění, zda byl zmeškán úplně první odjezd cesty.
 */
internal fun isInitialDepartureMissed(
    legs: List<Leg>,
    delays: Map<Int, Int>,
    nowSec: Int,
    userLat: Double? = null,
    userLng: Double? = null,
    boardLat: Double? = null,
    boardLng: Double? = null,
    hasLiveFirst: Boolean = true,
    /** Uživatel podle [OnBoardTracker] sedí v první jízdě. */
    userOnBoard: Boolean = false,
    /** Vůz první jízdy podle své polohy na trase ještě z nástupní zastávky neodjel (nedojel na ni, nebo na ní stojí). */
    vehicleNotLeftStop: Boolean = false,
    /** Uživatel je teď u živého vozu první jízdy nebo se pohybuje rychlostí vozidla (viz [isTransferMissed]). */
    userWithVehicle: Boolean = false,
): Boolean {
    if (!hasLiveFirst) return false // Bez živých dat nehodnotit odjezd jako zmeškaný
    if (userOnBoard || vehicleNotLeftStop) return false   // v autobuse už jsem / autobus ještě nedojel (zpoždění, semafor před zastávkou)
    if (userWithVehicle) return false   // daleko od zastávky, protože jede s vozem (OnBoardTracker se ještě nechytil)

    val rides = rideIndices(legs)
    val firstRide = rides.firstOrNull() ?: return false
    val depSec = legs[firstRide].dep + (delays[firstRide] ?: 0) * 60
    if (nowSec <= depSec + 90) return false

    val currentPhase = trackPhase(legs, nowSec, delays)
    if (currentPhase !is TrackPhase.BeforeDeparture) return false

    if (userLat != null && userLng != null && boardLat != null && boardLng != null) {
        val distM = Geo.distanceM(userLat, userLng, boardLat, boardLng)
        if (distM > 300.0) return true
    }

    return nowSec > depSec + 180
}

