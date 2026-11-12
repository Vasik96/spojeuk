package com.vasik96.spojeuk

import android.os.SystemClock
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_R = 6_371_008.8
private const val MAX_ON_ROUTE_OFFSET_M = 150.0

/** Kolik metrů zpět po trase se ještě toleruje (šum GPS, dvě zastávky těsně u sebe); víc je už „jiné rameno“. */
private const val BACK_TOLERANCE_M = 25.0

/** Odchylka fixu od trasy, od které je shoda „špatná“ (GPS šum bývá do ~15 m). Dva takové fixy za sebou spustí přesynchronizaci. */
private const val POOR_FIT_M = 45.0

/** Kolik po sobě jdoucích fixů mimo trasu vydrží dead reckoning po trase, než se vůz prohlásí za mimo trasu. */
private const val MAX_MISSED_FIXES = 3

internal fun haversineMeters(a: LatLng, b: LatLng): Double {
    val p1 = Math.toRadians(a.latitude)
    val p2 = Math.toRadians(b.latitude)
    val dp = p2 - p1
    val dl = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_R * atan2(sqrt(h), sqrt(1 - h))
}

private fun lerpLatLng(a: LatLng, b: LatLng, t: Double) = LatLng(
    a.latitude + (b.latitude - a.latitude) * t,
    a.longitude + (b.longitude - a.longitude) * t
)

/** Stejné vozidlo i když se mezi polly změnil objekt (a u id == 0 i uid). */
internal fun Vehicle.sameAs(o: Vehicle): Boolean =
    this === o || uid == o.uid ||
            (id != 0 && o.id != 0 && sameVehicleId(id, o.id)) ||
            (cis.isNotBlank() && trip.isNotBlank() && source == o.source && cis == o.cis && trip == o.trip)


// ───────────────────────── trasa jako polyline ─────────────────────────

internal class PathProjection(val s: Double, val offsetMeters: Double)

/** Trasa jako polyline s kumulativními vzdálenostmi (v metrech). Body musí být ve směru jízdy. */
internal class PathMeasure(
    private val points: List<LatLng>,
    stopPoints: List<LatLng> = emptyList()
) {
    private val n = points.size
    private val xs = DoubleArray(n)
    private val ys = DoubleArray(n)
    private val cum = DoubleArray(n)
    val totalMeters: Double
    val stopDistances: DoubleArray

    init {
        require(n >= 2)
        val k = cos(Math.toRadians(points[0].latitude))
        for (i in 0 until n) {
            xs[i] = Math.toRadians(points[i].longitude) * k * EARTH_R
            ys[i] = Math.toRadians(points[i].latitude) * EARTH_R
            if (i > 0) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        totalMeters = cum[n - 1]

        if (stopPoints.isNotEmpty()) {
            val list = ArrayList<Double>()
            var lastS = 0.0
            for (sp in stopPoints) {
                val px = Math.toRadians(sp.longitude) * k * EARTH_R
                val py = Math.toRadians(sp.latitude) * EARTH_R
                val proj = scanWindow(
                    px = px,
                    py = py,
                    lo = (lastS - 10.0).coerceAtLeast(0.0),
                    hi = Double.MAX_VALUE,
                    nearS = lastS,
                    hasDir = false,
                    vx = 0.0,
                    vy = 0.0,
                    maxOffset = 150.0,
                    stopMode = true
                )
                if (proj != null) {
                    list.add(proj.s)
                    lastS = proj.s
                }
            }
            list.sort()
            stopDistances = list.toDoubleArray()
        } else {
            stopDistances = DoubleArray(0)
        }
    }

    /** Index úseku, ve kterém (nebo těsně před kterým) leží vzdálenost d. */
    private fun segmentStart(d: Double): Int {
        if (d <= 0.0) return 0
        var idx = java.util.Arrays.binarySearch(cum, d)
        if (idx < 0) idx = -idx - 2
        return idx.coerceIn(0, n - 2)
    }

    /**
     * Najde nejlepší úsek trasy pro bod (px, py) v okně [lo, hi] metrů od začátku.
     *
     * Cena kandidáta = odchylka od trasy + postup + směr:
     *  - **postup** ([nearS] = poslední známá pozice): zpět po trase se téměř nepouští, vpřed se čeká
     *    [expected] metrů. Díky tomu se nelze „přepnout“ na protější rameno smyčky / protisměrnou
     *    vozovku, protože ta leží na trase o stovky metrů jinde.
     *  - **směr** je jen slabá nápověda ([hasDir]). Heading z DPMÚL je ve skutečnosti směr k nejbližší zastávce
     *    vzdušnou čarou a v zatáčce / na kruháči bývá opačný než silnice, proto nikdy nesmí rozhodovat sám
     *    ani vyřadit správný úsek.
     * [stopMode] zachovává původní hledání zastávek (vpřed téměř zdarma, zpět draho).
     */
    private fun scanWindow(
        px: Double, py: Double,
        lo: Double, hi: Double,
        nearS: Double?,
        hasDir: Boolean, vx: Double, vy: Double,
        maxOffset: Double,
        expected: Double = 0.0,
        stopMode: Boolean = false,
        wideMode: Boolean = false
    ): PathProjection? {
        var bestProj: PathProjection? = null
        var bestCost = Double.MAX_VALUE

        val startIdx = segmentStart(lo)
        for (i in startIdx until n - 1) {
            if (cum[i] > hi) break
            if (cum[i + 1] < lo) continue

            val ax = xs[i]
            val ay = ys[i]
            val dx = xs[i + 1] - ax
            val dy = ys[i + 1] - ay
            val len2 = dx * dx + dy * dy
            if (len2 <= 1e-9) continue

            val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val projX = ax + dx * t
            val projY = ay + dy * t
            val d = hypot(px - projX, py - projY)
            if (d > maxOffset) continue

            val s = cum[i] + (cum[i + 1] - cum[i]) * t
            if (s < lo || s > hi) continue

            val len = sqrt(len2)
            val ux = dx / len
            val uy = dy / len

            var dirPenalty = 0.0
            if (hasDir) {
                val dot = ux * vx + uy * vy
                // jen slabá nápověda (max. pár metrů odchylky), nikdy ne vyřazení úseku
                dirPenalty = when {
                    dot < -0.5 -> 12.0
                    dot < 0.0 -> 4.0
                    else -> 0.0
                }
            }

            var progPenalty = 0.0
            if (nearS != null) {
                val delta = s - nearS
                progPenalty = if (wideMode) {
                    // přesynchronizace: pozice se může opravit i směrem zpět, rozhoduje hlavně odchylka od trasy
                    kotlin.math.abs(delta - expected) * 0.05
                } else if (stopMode) {
                    if (delta < 0.0) -delta * 0.8 else delta * 0.08
                } else when {
                    delta < -BACK_TOLERANCE_M -> BACK_TOLERANCE_M * 0.3 + (-delta - BACK_TOLERANCE_M) * 2.0
                    delta < 0.0 -> -delta * 0.3
                    else -> {
                        // věrohodný rozsah postupu: do ~1,8× očekávané dráhy je to lehká cena, dál (jiné rameno
                        // smyčky, protější jízdní pás) rychle prudce roste, takže jeden odlehlý fix nepřeskočí
                        val plausible = expected * 1.8 + 40.0
                        if (delta <= plausible) kotlin.math.abs(delta - expected) * 0.08
                        else (plausible - expected) * 0.08 + (delta - plausible) * 0.5
                    }
                }
            }

            val totalCost = d + dirPenalty + progPenalty
            if (totalCost < bestCost) {
                bestCost = totalCost
                bestProj = PathProjection(s, d)
            }
        }
        return bestProj
    }

    /**
     * Průmět bodu na trasu.
     * Při známé pozici [nearS] prohledává jen reálné dopředné okno (ne celou trasu!),
     * čímž brání přeskokům na protější ramena smyček a kruhových objezdů.
     * Zohledňuje směr jízdy vozidla [heading] nebo [moveVec].
     */
    fun project(
        p: LatLng,
        nearS: Double? = null,
        heading: Double? = null,
        moveVec: Pair<Double, Double>? = null,
        maxOffset: Double = MAX_ON_ROUTE_OFFSET_M,
        isTrain: Boolean = false,
        dtSec: Double = 6.0,
        expectedSpeed: Double = if (isTrain) 18.0 else 8.8,
        hintS: Double? = null,
        wide: Boolean = false
    ): PathProjection? {
        val px = Math.toRadians(p.longitude) * cos(Math.toRadians(points[0].latitude)) * EARTH_R
        val py = Math.toRadians(p.latitude) * EARTH_R

        var vx = 0.0
        var vy = 0.0
        var hasDir = false
        if (heading != null) {
            val rad = Math.toRadians(heading)
            vx = sin(rad)
            vy = cos(rad)
            hasDir = true
        } else if (moveVec != null) {
            val mLen = hypot(moveVec.first, moveVec.second)
            if (mLen >= 6.0) {
                vx = moveVec.first / mLen
                vy = moveVec.second / mLen
                hasDir = true
            }
        }

        if (nearS != null && wide) {
            // opravná přesynchronizace: okolí poslední známé pozice na obě strany, bez zákazu couvání
            val maxSpeed = if (isTrain) 35.0 else 17.0
            val fwd = (maxSpeed * dtSec * 1.5 + 60.0).coerceIn(150.0, 600.0)
            return scanWindow(
                px, py, (nearS - 450.0).coerceAtLeast(0.0), (nearS + fwd).coerceAtMost(totalMeters),
                nearS, hasDir, vx, vy, maxOffset, expectedSpeed * dtSec, wideMode = true
            )
        }

        if (nearS != null) {
            val backWindow = 40.0
            val maxSpeed = if (isTrain) 35.0 else 17.0
            val forwardWindow = (maxSpeed * dtSec * 1.5 + 40.0).coerceIn(120.0, if (isTrain) 600.0 else 320.0)
            val lo = (nearS - backWindow).coerceAtLeast(0.0)
            val hi = (nearS + forwardWindow).coerceAtMost(totalMeters)

            val expected = kotlin.math.min(expectedSpeed * dtSec, kotlin.math.max(0.0, hi - nearS))
            val proj = scanWindow(px, py, lo, hi, nearS, hasDir, vx, vy, maxOffset, expected)
            if (proj != null) return proj
            // Když už vůz na trase je, v žádném případě neskenujeme celou trasu, aby se nepřeskočilo na smyčku!
            return null
        }

        // Znovu nalezení trasy po výpadku: nejdřív okolí poslední známé pozice, celá trasa až nakonec
        if (hintS != null) {
            val maxSpeed = if (isTrain) 35.0 else 17.0
            val fwd = (maxSpeed * dtSec * 1.5 + 150.0).coerceIn(300.0, 2500.0)
            val near = scanWindow(
                px, py, (hintS - 150.0).coerceAtLeast(0.0), (hintS + fwd).coerceAtMost(totalMeters),
                hintS, hasDir, vx, vy, maxOffset, expectedSpeed * dtSec
            )
            if (near != null) return near
        }

        // První promítnutí vozu na trasu (nearS == null): nejbližší úsek, směr jen jako slabá nápověda
        return scanWindow(px, py, -1.0, Double.MAX_VALUE, null, hasDir, vx, vy, maxOffset)
    }

    fun pointAt(s: Double): LatLng {
        val d = s.coerceIn(0.0, totalMeters)
        var idx = java.util.Arrays.binarySearch(cum, d)
        if (idx < 0) idx = -idx - 2
        idx = idx.coerceIn(0, n - 2)
        val seg = cum[idx + 1] - cum[idx]
        val t = if (seg <= 1e-9) 0.0 else ((d - cum[idx]) / seg).coerceIn(0.0, 1.0)
        return lerpLatLng(points[idx], points[idx + 1], t)
    }
}

// ───────────────────────── pohyb jednoho vozidla ─────────────────────────

/** Délka animace bez predikce z bodu A do bodu B (1 s). */
private const val FAST_ANIM_DURATION_MS = 1_000.0

/** Skok delší než tohle není jízda (výměna vozu za jiný, chyba zdroje): bez animace. */
private const val TELEPORT_M = 3_000.0

/**
 * Pohyb jednoho vozidla. Podporuje:
 * 1) Běžnou lineární animaci A -> B pro ne-predikční režim nebo vozy mimo trasu.
 * 2) Spojitou predikci po trase spoje: interpolaci polohy po trase silnice bez teleportů,
 *    plynulý nájezd na trasu po jejím načtení, respektování reálné rychlosti a zastávek.
 */
internal class VehicleMotion(
    lat: Double,
    lng: Double,
    obsMs: Long,
    val isTrain: Boolean = false
) {
    private var fixObsMs = obsMs
    private var fixRecvMs = SystemClock.elapsedRealtime()

    // Lineární animace pro fallback / bez predikce / plynulý nájezd na trasu
    private var from = LatLng(lat, lng)
    private var to = from
    private var t0 = fixRecvMs
    private var durMs = 1.0

    // Spojitý pohyb po trase
    private var route: PathMeasure? = null
    private var sCurr = Double.NaN         // aktuálně vykreslená pozice (m od začátku trasy)
    private var sTarget = Double.NaN       // cílová extrapolovaná pozice (m od začátku trasy)
    private var sLastFix = Double.NaN      // pozice z posledního serverového fixu
    private var lastFixRecvMs = 0L

    // Cestovní rychlost v m/s: vlak ~18 m/s (~65 km/h), autobus ~8.8 m/s (~32 km/h)
    private var vCruise = if (isTrain) 18.0 else 8.8
    private var vCurrent = 0.0

    private var lastTickMs = 0L

    // Pobyt na zastávce
    private var isDwelling = false
    private var dwellStartMs = 0L
    private var dwellDurationMs = 10_000L
    private var dwellStopDist = -1.0

    // Detekce stání na konečné / na místě
    private var isStationary = false
    private var stationaryFixCount = 0
    private var lastFixPos: LatLng? = null

    // Poslední jistá pozice na trase (pro znovunalezení po výpadku) a počet fixů v řadě, které na trasu nesedly
    private var lastGoodS = Double.NaN
    private var lastGoodMs = 0L
    private var missedFixes = 0
    private var poorFits = 0
    private var catchUpDurMs = FAST_ANIM_DURATION_MS

    // Plynulý přechod ze surové polohy na trasu (žádný instantní teleport při načtení trasy)
    private var isOffRoute = true
    private var transitioningToRoute = false
    private var needRouteInit = false

    // 1-sekundová animace k nejnovější realtime pozici, když je realtime fix napřed
    private var isCatchingUp = false
    private var catchUpStartS = Double.NaN
    private var catchUpEndS = Double.NaN
    private var catchUpStartPos = from
    private var catchUpT0 = fixRecvMs

    private var shown = from

    private fun planFallback(a: LatLng, b: LatLng, now: Long, ms: Double) {
        from = a
        to = b
        t0 = now
        durMs = ms.coerceAtLeast(1.0)
    }

    fun setRoute(r: PathMeasure?) {
        if (r === route) return
        route = r
        lastGoodS = Double.NaN
        missedFixes = 0
        poorFits = 0
        if (r == null) {
            sCurr = Double.NaN
            sTarget = Double.NaN
            sLastFix = Double.NaN
            isOffRoute = true
            transitioningToRoute = false
            needRouteInit = false
            isCatchingUp = false
        } else {
            needRouteInit = true
            isOffRoute = false
        }
    }

    /** Zaregistruje nový fix. */
    fun onFix(
        lat: Double,
        lng: Double,
        obsMs: Long,
        heading: Double? = null,
        predict: Boolean = false
    ): Boolean {
        val np = LatLng(lat, lng)
        val now = SystemClock.elapsedRealtime()

        if (np == to && !predict) {
            fixObsMs = obsMs
            return false
        }

        fixObsMs = obsMs
        val curPos = shown
        val moveDist = if (lastFixPos != null) haversineMeters(np, lastFixPos!!) else 0.0

        // Směrový vektor pohybu mezi serverovými fixy (x=East, y=North v metrech)
        val moveVec: Pair<Double, Double>? = if (lastFixPos != null && moveDist >= 6.0) {
            val k = cos(Math.toRadians(lat))
            val dx = (lng - lastFixPos!!.longitude) * Math.toRadians(1.0) * k * EARTH_R
            val dy = (lat - lastFixPos!!.latitude) * Math.toRadians(1.0) * EARTH_R
            dx to dy
        } else null

        if (haversineMeters(curPos, np) > TELEPORT_M) {
            from = np; to = np; t0 = now; durMs = 1.0
            shown = np
            sCurr = Double.NaN; sTarget = Double.NaN; sLastFix = Double.NaN
            lastGoodS = Double.NaN; missedFixes = 0
            lastFixPos = np; lastFixRecvMs = now
            isOffRoute = true; transitioningToRoute = false
            return true
        }

        val r = route
        if (!predict || r == null) {
            planFallback(curPos, np, now, FAST_ANIM_DURATION_MS)
            to = np
            lastFixPos = np
            lastFixRecvMs = now
            isOffRoute = true
            transitioningToRoute = false
            return true
        }

        val dtFixSec = if (lastFixRecvMs > 0L) ((now - lastFixRecvMs) / 1000.0).coerceIn(1.0, 30.0) else 6.0
        // Oporou pro hledání je poslední FIX na trase (ne vykreslená, předsazená poloha): ta se pohybuje
        // jen podle skutečných dat a nepředbíhá, takže okno nikdy neujede od reality.
        val trackS = if (!sLastFix.isNaN()) sLastFix else sCurr
        val tracked = !(sCurr.isNaN() || isOffRoute)
        var proj = r.project(
            p = np,
            nearS = if (tracked) trackS else null,
            heading = heading,
            moveVec = moveVec,
            isTrain = isTrain,
            dtSec = dtFixSec,
            expectedSpeed = vCruise,
            hintS = if (!tracked && !lastGoodS.isNaN()) {
                lastGoodS + vCruise * ((now - lastGoodMs) / 1000.0).coerceIn(0.0, 120.0) * 0.5
            } else null
        )

        // Kontrola shody: když dva fixy za sebou sedí na trasu špatně (> POOR_FIT_M), asi jsme uvízli na špatném
        // místě (např. po odlehlém fixu uskočila poloha o stovky metrů vpřed). Zkusíme okolí na obě strany.
        var resynced = false
        if (tracked && proj != null && proj.offsetMeters > POOR_FIT_M) {
            poorFits++
            if (poorFits >= 2) {
                val alt = r.project(
                    p = np, nearS = trackS, heading = heading, moveVec = moveVec, isTrain = isTrain,
                    dtSec = dtFixSec, expectedSpeed = vCruise, wide = true
                )
                if (alt != null && alt.offsetMeters < POOR_FIT_M * 0.6 && alt.offsetMeters < proj.offsetMeters - 20.0) {
                    proj = alt
                    resynced = true
                }
                poorFits = 0
            }
        } else if (proj != null) {
            poorFits = 0
        }

        if (proj == null && tracked && missedFixes < MAX_MISSED_FIXES) {
            // Fix nesedí na trasu (šum GPS, krátký výpadek): zůstáváme na trase a pokračujeme predikcí,
            // místo abychom po jednom špatném bodu zahodili trasu a pak hledali na celé délce (skok na jiné rameno).
            missedFixes++
            lastFixPos = np
            lastFixRecvMs = now
            return true
        }

        if (proj == null) {
            // Vůz je mimo trasu (objížďka apod.) -> plynulá lineární animace na nový fix
            if (!sCurr.isNaN() && !isOffRoute) { lastGoodS = sLastFix.takeUnless { it.isNaN() } ?: sCurr; lastGoodMs = now }
            planFallback(curPos, np, now, FAST_ANIM_DURATION_MS)
            to = np
            lastFixPos = np
            lastFixRecvMs = now
            isOffRoute = true
            transitioningToRoute = false
            return true
        }

        missedFixes = 0
        val sFix = proj.s
        if (resynced) {
            // oprava polohy po trase (ne přímkou): vykreslená poloha se přesadí na ověřené místo
            sCurr = sFix
            sLastFix = sFix
            isCatchingUp = false
            isDwelling = false
            vCurrent = vCruise * 0.8
        }
        lastGoodS = sFix
        lastGoodMs = now
        val targetPoint = r.pointAt(sFix)

        val isNearTerminus = sFix >= r.totalMeters - 40.0
        if (moveDist < 15.0 || isNearTerminus) {
            stationaryFixCount++
        } else {
            stationaryFixCount = 0
        }
        val isStat = stationaryFixCount >= 2 || isNearTerminus
        isStationary = isStat

        if (sCurr.isNaN() || isOffRoute) {
            sCurr = sFix
            sTarget = sFix
            sLastFix = sFix
            lastFixRecvMs = now
            lastFixPos = np
            isOffRoute = false
            isDwelling = false
            if (!isStat) vCurrent = vCruise * 0.8

            // Plynulý nájezd z dosavadní vizuální polohy na trasu (žádný skok/teleport!)
            val dist = haversineMeters(curPos, targetPoint)
            if (dist >= 1.0) {
                planFallback(curPos, targetPoint, now, FAST_ANIM_DURATION_MS)
                to = targetPoint
                transitioningToRoute = true
            } else {
                transitioningToRoute = false
            }
            isCatchingUp = false
            return true
        }

        // Výpočet reálné rychlosti z po sobě jdoucích fixů
        if (lastFixRecvMs > 0 && !sLastFix.isNaN()) {
            val dtSec = ((now - lastFixRecvMs) / 1000.0).coerceIn(2.0, 45.0)
            val dsM = sFix - sLastFix
            if (dsM > 10.0 && !isStat) {
                val vCalc = dsM / dtSec
                val minV = if (isTrain) 4.0 else 2.5
                val maxV = if (isTrain) 35.0 else 14.5
                if (vCalc in minV..maxV) {
                    val defaultV = if (isTrain) 18.0 else 8.8
                    vCruise = (if (vCruise <= 0.0) defaultV else vCruise) * 0.7 + vCalc * 0.3
                }
            }
        }
        if (vCruise <= 0.0) vCruise = if (isTrain) 18.0 else 8.8
        if (isStat) vCurrent = 0.0

        if (sFix > sCurr + 1.0 && !isStat) {
            // Realtime pozice je napřed oproti predikci -> 1s animace k realtime pozici,
            // od které pak predikce plynule pokračuje s tipováním, kde vůz je.
            planFallback(curPos, targetPoint, now, FAST_ANIM_DURATION_MS)
            to = targetPoint
            catchUpStartS = sCurr
            catchUpEndS = sFix
            catchUpStartPos = curPos
            catchUpT0 = 0L
            // do 100 m za 1 s; delší doběh trvá úměrně déle (max. 3 s), jede se ale pořád PO TRASE
            catchUpDurMs = ((sFix - sCurr) / 100.0 * 1000.0).coerceIn(FAST_ANIM_DURATION_MS, 3_000.0)
            isCatchingUp = true
            transitioningToRoute = false
            isDwelling = false
        } else if (isStat) {
            isCatchingUp = false
            transitioningToRoute = false
        }

        sLastFix = sFix
        lastFixRecvMs = now
        lastFixPos = np

        val ageSec = if (obsMs > 0 && obsMs <= System.currentTimeMillis()) {
            ((System.currentTimeMillis() - obsMs) / 1000.0).coerceIn(0.0, 4.0)
        } else 0.0

        val extrapolatedS = (sFix + vCruise * ageSec).coerceAtMost(r.totalMeters)
        sTarget = extrapolatedS

        // Pokud server nahlásil, že vůz už je za zastávkou, zrušíme čekání
        if (isDwelling && sFix > dwellStopDist + 25.0) {
            isDwelling = false
        }

        to = targetPoint
        return true
    }

    /** Pozice v čase [now] (SystemClock.elapsedRealtime). Levné, bez alokací kromě LatLng. */
    fun positionAt(now: Long): LatLng {
        val r = route
        if (r != null && needRouteInit) {
            needRouteInit = false
            val curPos = shown
            val proj = r.project(curPos, nearS = null, isTrain = isTrain)
            if (proj != null) {
                sCurr = proj.s
                sTarget = proj.s
                sLastFix = proj.s
                lastFixRecvMs = now
                if (!isStationary) vCurrent = vCruise * 0.8
                val targetPoint = r.pointAt(proj.s)
                val dist = haversineMeters(curPos, targetPoint)
                if (dist >= 1.0) {
                    planFallback(curPos, targetPoint, now, FAST_ANIM_DURATION_MS)
                    to = targetPoint
                    transitioningToRoute = true
                } else {
                    transitioningToRoute = false
                }
                isOffRoute = false
            } else {
                sCurr = Double.NaN
                sTarget = Double.NaN
                sLastFix = Double.NaN
                isOffRoute = true
                transitioningToRoute = false
            }
        }

        if (r == null || sCurr.isNaN() || isOffRoute) {
            val f = ((now - t0) / durMs).coerceIn(0.0, 1.0)
            val pos = lerpLatLng(from, to, f)
            shown = pos
            return pos
        }

        if (isCatchingUp) {
            if (catchUpT0 <= 0L) catchUpT0 = now
            val elapsedMs = now - catchUpT0
            if (elapsedMs < catchUpDurMs) {
                val f = (elapsedMs / catchUpDurMs).coerceIn(0.0, 1.0)
                sCurr = catchUpStartS + (catchUpEndS - catchUpStartS) * f
                // vždy po trase (ne po přímce, ta by u smyčky / kruháče řezala přes budovy);
                // případný počáteční posun od osy trasy se plynule vytrácí
                val routePos = r.pointAt(sCurr)
                val axis0 = r.pointAt(catchUpStartS)
                val pos = LatLng(
                    routePos.latitude + (catchUpStartPos.latitude - axis0.latitude) * (1.0 - f),
                    routePos.longitude + (catchUpStartPos.longitude - axis0.longitude) * (1.0 - f)
                )
                shown = pos
                return pos
            } else {
                // 1s animace k realtime pozici dokončena, sCurr je na sFix a predikce odtud pokračuje
                isCatchingUp = false
                sCurr = catchUpEndS
                if (!isStationary) vCurrent = vCruise * 0.8
                shown = r.pointAt(sCurr)
            }
        }

        if (transitioningToRoute) {
            val f = ((now - t0) / durMs).coerceIn(0.0, 1.0)
            if (f >= 1.0) {
                transitioningToRoute = false
            }
            val routePos = r.pointAt(sCurr)
            val pos = lerpLatLng(from, routePos, f)
            shown = pos
            return pos
        }

        if (lastTickMs <= 0L) lastTickMs = now
        val dtSec = ((now - lastTickMs) / 1000.0).coerceIn(0.001, 0.2)
        lastTickMs = now

        val totalMeters = r.totalMeters
        if (isStationary || sCurr >= totalMeters) {
            val goalS = if (sCurr >= totalMeters) totalMeters else sLastFix
            if (kotlin.math.abs(goalS - sCurr) > 0.5) {
                val step = (vCruise * dtSec * 0.8).coerceAtMost(kotlin.math.abs(goalS - sCurr))
                sCurr = if (sCurr < goalS) sCurr + step else sCurr - step
            }
            val p = r.pointAt(sCurr)
            shown = p
            return p
        }

        // Predikce dopředu po trase: kompenzace latence serverových dat (~3.0 s pro busy, ~5.0 s pro vlaky)
        val leadSec = if (isTrain) 5.0 else 3.0
        val timeSinceFixSec = ((now - lastFixRecvMs) / 1000.0).coerceAtLeast(0.0)
        val currentTargetS = (sLastFix + vCruise * (timeSinceFixSec + leadSec)).coerceAtMost(totalMeters)

        if (isDwelling) {
            if (now - dwellStartMs >= dwellDurationMs || (!sLastFix.isNaN() && sLastFix > dwellStopDist + 40.0)) {
                isDwelling = false
            } else {
                val p = r.pointAt(dwellStopDist)
                shown = p
                return p
            }
        }

        val err = currentTargetS - sCurr
        val catchUpFactor = when {
            err > 0 -> 1.0 + (err / 200.0).coerceAtMost(0.20)
            err < 0 -> (1.0 + err / 200.0).coerceAtLeast(0.4)
            else -> 1.0
        }
        var targetV = vCruise * catchUpFactor

        val stops = r.stopDistances
        var nextStopDist = -1.0
        var prevStopDist = -1.0
        for (sd in stops) {
            if (sd > sCurr) {
                if (nextStopDist < 0.0) nextStopDist = sd
            } else {
                prevStopDist = sd
            }
        }

        if (nextStopDist > 0.0) {
            val distToStop = nextStopDist - sCurr
            if (distToStop <= 120.0 && sLastFix <= nextStopDist + 40.0) {
                if (distToStop <= 3.0) {
                    isDwelling = true
                    dwellStartMs = now
                    dwellDurationMs = if (isTrain) 25_000L else 12_000L
                    dwellStopDist = nextStopDist
                    sCurr = nextStopDist
                    vCurrent = 0.0
                    val p = r.pointAt(nextStopDist)
                    shown = p
                    return p
                } else {
                    val decelRatio = distToStop / 120.0
                    targetV = (targetV * decelRatio * decelRatio).coerceAtLeast(0.8)
                }
            }
        }

        if (prevStopDist > 0.0) {
            val distFromStop = sCurr - prevStopDist
            if (distFromStop in 0.0..60.0) {
                val accelFactor = distFromStop / 60.0
                targetV *= (0.25 + 0.75 * accelFactor)
            }
        }

        val maxAccel = (if (isTrain) 0.8 else 1.5) * dtSec
        if (vCurrent < targetV) {
            vCurrent = (vCurrent + maxAccel).coerceAtMost(targetV)
        } else {
            vCurrent = (vCurrent - maxAccel * 1.5).coerceAtLeast(targetV)
        }

        sCurr = (sCurr + vCurrent * dtSec).coerceIn(0.0, totalMeters)
        val p = r.pointAt(sCurr)
        shown = p
        return p
    }
}

// ───────────────────────── stabilní klíče vozidel ─────────────────────────

/**
 * Stabilní klíč pro Compose `key(...)`. uid u vozidel s id == 0 obsahuje souřadnice,
 * takže se při každém pohybu mění a marker by se pokaždé zahodil a vytvořil jinde.
 */
internal class VehicleKeyer {

    private data class Prev(
        val key: String,
        val source: String,
        val id: Int,
        val cis: String,
        val trip: String,
        val tid: Int,
        val line: String,
        val pos: LatLng,
        val heading: Double?
    )

    private data class Match(
        val distance: Float,
        val currentIndex: Int,
        val previousIndex: Int
    )

    private var prev: List<Prev> = emptyList()
    private var seq = 0

    private companion object {
        const val CROSS_SOURCE_MATCH_M = 500f
        const val LOOSE_MATCH_M = 100f
        const val AMBIGUITY_MARGIN_M = 60f
        const val MAX_DIRECTION_DIFF = 55.0
    }

    private fun normLine(line: String): String =
        line.trim().uppercase().filterNot { it.isWhitespace() }

    private fun directionCompatible(a: Double?, b: Double?): Boolean {
        if (a == null || b == null) return true

        val diff = kotlin.math.abs((a - b + 540.0) % 360.0 - 180.0)
        return diff <= MAX_DIRECTION_DIFF
    }

    @Synchronized
    fun assign(list: List<Vehicle>): List<Pair<String, Vehicle>> {
        val usedKeys = HashSet<String>()
        val usedPrev = HashSet<Int>()
        val out = arrayOfNulls<String>(list.size)

        fun unique(base: String): String {
            var key = base
            var n = 2
            while (!usedKeys.add(key)) {
                key = "$base~${n++}"
            }
            return key
        }

        // 1. Zachování identity v rámci stejného zdroje nebo přes stejné ID vozidla.
        for ((i, v) in list.withIndex()) {
            val pi = prev.indices.firstOrNull { p ->
                if (p in usedPrev) return@firstOrNull false

                val old = prev[p]

                when {
                    v.id != 0 && old.id != 0 && sameVehicleId(v.id, old.id) -> true
                    old.source == v.source && v.id != 0 -> old.id == v.id
                    old.source == v.source && v.cis.isNotBlank() && v.trip.isNotBlank() ->
                        old.cis == v.cis && old.trip == v.trip
                    else -> false
                }
            }

            if (pi != null) {
                val key = prev[pi].key
                if (usedKeys.add(key)) {
                    out[i] = key
                    usedPrev.add(pi)
                }
            }
        }

        // 2. Kandidáti pro spárování mezi DÚK a DPMÚL.
        val candidates = ArrayList<Match>()

        for (i in list.indices) {
            if (out[i] != null) continue

            val v = list[i]
            val line = normLine(v.line)

            if (line.isBlank()) continue

            for (pi in prev.indices) {
                if (pi in usedPrev) continue

                val old = prev[pi]

                if (old.source == v.source) continue
                if (line != old.line) continue

                val distance = distanceMeters(
                    LatLng(v.lat, v.lng),
                    old.pos
                )

                val sameTid =
                    v.tid != 0 &&
                            old.tid != 0 &&
                            v.tid == old.tid

                val sameTrip =
                    v.cis.isNotBlank() &&
                            v.trip.isNotBlank() &&
                            old.cis == v.cis &&
                            old.trip == v.trip

                val strongIdentity = sameTid || sameTrip

                val maxDistance = if (strongIdentity) {
                    CROSS_SOURCE_MATCH_M
                } else {
                    LOOSE_MATCH_M
                }

                if (distance > maxDistance) continue

                if (!directionCompatible(v.heading, old.heading)) {
                    continue
                }

                candidates.add(
                    Match(distance, i, pi)
                )
            }
        }

        // 3. Najdeme jednoznačně nejlepší shody z obou stran.
        val bestCurrent = candidates
            .groupBy { it.currentIndex }
            .mapValues { (_, matches) ->
                val sorted = matches.sortedBy { it.distance }

                if (
                    sorted.size == 1 ||
                    sorted[1].distance - sorted[0].distance >= AMBIGUITY_MARGIN_M
                ) {
                    sorted[0]
                } else {
                    null
                }
            }

        val bestPrevious = candidates
            .groupBy { it.previousIndex }
            .mapValues { (_, matches) ->
                val sorted = matches.sortedBy { it.distance }

                if (
                    sorted.size == 1 ||
                    sorted[1].distance - sorted[0].distance >= AMBIGUITY_MARGIN_M
                ) {
                    sorted[0]
                } else {
                    null
                }
            }

        // 4. Přijmeme pouze vzájemně nejlepší shody.
        for (match in candidates.sortedBy { it.distance }) {
            val i = match.currentIndex
            val pi = match.previousIndex

            if (out[i] != null || pi in usedPrev) continue

            if (bestCurrent[i] != match) continue
            if (bestPrevious[pi] != match) continue

            val key = prev[pi].key
            if (!usedKeys.add(key)) continue

            out[i] = key
            usedPrev.add(pi)
        }

        // 5. Nová vozidla dostanou vlastní klíč.
        for (i in list.indices) {
            if (out[i] != null) continue

            val v = list[i]

            val base = when {
                v.id != 0 -> v.uid

                v.cis.isNotBlank() && v.trip.isNotBlank() ->
                    "${v.source}#${v.cis}/${v.trip}"

                else -> "${v.source}#h${++seq}"
            }

            out[i] = unique(base)
        }

        // 6. Uložíme aktuální vozidla pro další aktualizaci.
        prev = list.indices.map { i ->
            val v = list[i]

            Prev(
                key = out[i]!!,
                source = v.source,
                id = v.id,
                cis = v.cis,
                trip = v.trip,
                tid = v.tid,
                line = normLine(v.line),
                pos = LatLng(v.lat, v.lng),
                heading = v.heading
            )
        }

        return list.indices.map { i ->
            out[i]!! to list[i]
        }
    }
}


// ───────────────────────── trasy všech vozidel ─────────────────────────

/** Trasa spoje pro každé vozidlo (shapes, jinak OSRM), cache podle tid. Nezávisí na tom, co je vykreslené. */
internal class VehicleRoutes(private val api: TransitRepository) {
    private val byTid = object : LinkedHashMap<Int, PathMeasure?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PathMeasure?>?) = size > 150
    }
    private val gate = Semaphore(3)   // OSRM / SQLite nezahltíme sty souběžných dotazů

    suspend fun get(v: Vehicle): PathMeasure? {
        val tid = api.resolveTripId(v) ?: return null
        if (byTid.containsKey(tid)) return byTid[tid]
        val pts = gate.withPermit { api.routeForTrip(tid) }
        val stops = gate.withPermit { api.stopCoordsForTrip(tid) }
        // stavba PathMeasure (trigonometrie přes celou trasu) mimo main vlákno
        val pm = withContext(Dispatchers.Default) {
            if (pts.size >= 2) {
                PathMeasure(
                    pts.map { LatLng(it.first, it.second) },
                    stops.map { LatLng(it.first, it.second) }
                )
            } else null
        }
        byTid[tid] = pm
        return pm
    }
}