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

/**
 * Vyhodnocení, zda se má vyvolat jednorázové upozornění na výstup ("Příští zastávka... vystupuješ").
 * Upozornění se spustí pouze tehdy, když se uživatel/vozidlo reálně blíží k cílové zastávce
 * (do ~500 m podle GPS), aby se nehlásilo hned po opuštění předchozí zastávky, pokud je úsek dlouhý (např. 10 minut).
 */
internal fun shouldTriggerAlightAlert(
    nowSec: Int,
    arrSec: Int,
    depSec: Int,
    userLat: Double? = null,
    userLng: Double? = null,
    alightLat: Double? = null,
    alightLng: Double? = null,
): Boolean {
    val remainingSec = arrSec - nowSec
    if (remainingSec < 0) return false

    if (userLat != null && userLng != null && alightLat != null && alightLng != null) {
        val distM = Geo.distanceM(userLat, userLng, alightLat, alightLng)
        if (distM > 600.0) return false // Na dlouhých úsecích ještě daleko -> neupozorňovat
        return distM <= 500.0 && remainingSec <= 180
    }

    // Bez souřadnic: upozornit jen v závěrečných 40 % úseku a nejvýše 90 sekund před příjezdem
    val legDuration = maxOf(60, arrSec - depSec)
    val elapsed = nowSec - depSec
    val progressFrac = elapsed.toDouble() / legDuration
    return remainingSec <= 90 && progressFrac >= 0.6
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
): Boolean {
    if (!hasLiveB) return false // Bez živých dat na mapě nepovažujeme spoj za zmeškaný (může mít zpoždění)

    val rides = rideIndices(legs)
    val b = rides.getOrNull(k + 1) ?: return false
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
): Boolean {
    if (!hasLiveFirst) return false // Bez živých dat nehodnotit odjezd jako zmeškaný

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

