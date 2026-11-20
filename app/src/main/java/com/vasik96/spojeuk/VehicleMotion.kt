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

    /** Nejbližší zastávka na trase za [s] (m od začátku), nebo null. */
    fun nextStopAfter(s: Double): Double? {
        var i = java.util.Arrays.binarySearch(stopDistances, s)
        i = if (i < 0) -i - 1 else i + 1
        return if (i < stopDistances.size) stopDistances[i] else null
    }

    /** Jednotkový směr trasy (x = východ, y = sever) v místě [s]. */
    fun tangentAt(s: Double): Pair<Double, Double> {
        val idx = segmentStart(s.coerceIn(0.0, totalMeters))
        val dx = xs[idx + 1] - xs[idx]
        val dy = ys[idx + 1] - ys[idx]
        val len = hypot(dx, dy)
        return if (len <= 1e-9) 0.0 to 0.0 else (dx / len) to (dy / len)
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

/** Plynulý rozjezd i dojezd (smoothstep) místo konstantní rychlosti s trhnutím na začátku a konci. */
private fun ease(f: Double) = f * f * (3 - 2 * f)

/** Skok delší než tohle není jízda (výměna vozu za jiný, chyba zdroje): bez animace. */
private const val TELEPORT_M = 3_000.0

/**
 * O kolik sekund jízdy smí predikce předběhnout poslední ověřenou polohu (pak vůz počká na další fix): nejméně
 * tolik, a jinak podle rytmu dat (obvyklý odstup fixů + jejich stáří + brzdná doba), ať vůz dojede k další poloze
 * bez brzdění. Dál než k neprojeté zastávce se nepředpovídá nikdy.
 */
private const val LEAD_HORIZON_S = 6.0
private const val MAX_LEAD_HORIZON_S = 30.0

/** Poloha starší než tohle (s) se už nepředpovídá, vůz zůstává tam, kde ho server naposledy viděl. */
private const val MAX_PREDICT_DATA_AGE_S = 45.0

/** Za jak dlouho (s) dorovná vykreslená poloha rozdíl oproti predikci. */
private const val RESPONSE_S = 3.0

/** Přesun vykreslené polohy na realtime polohu (vstup na trasu, velká oprava dopředu i zpět): vždy přesně 1 s. */
private const val GLIDE_MS = 1_000.0

/**
 * Predikce se rozešla s realtime polohou víc než o tohle (a víc než [RESYNC_S] jízdy): místo dohánění /
 * čekání (u velkého rozdílu klidně desítky sekund) se vůz za [GLIDE_MS] přenese na správné místo, dopředu i zpět.
 */
private const val RESYNC_M = 50.0
private const val RESYNC_S = 4.0

/**
 * Zastávka je projetá, až je poslední realtime poloha víc než tohle ZA ní. Vůz stojící u zastávky (fix kousek
 * před i za sloupkem) ji drží, dokud data neukážou odjezd; dřív se zastávka do 15 m PŘED fixem brala jako projetá
 * a predikce z ní odjela hned po příjezdu, a pak zastavila kdesi za ní.
 */
private const val STOP_PASSED_M = 20.0

/** Predikce napřed o tolik sekund jízdy jede na ~37 % naměřené rychlosti (plynulé zpomalení, ne zastavení). */
private const val AHEAD_SOFT_S = 4.0

/**
 * Stejné souřadnice jako minulý fix s jiným časem pozorování (zdroj / server polohu drží a jen ji znovu orazítkuje)
 * se za stání vozu berou, až když se nezměnily aspoň tak dlouho; jinak by každá držená kopie vůz zastavila.
 */
private const val DUP_FIX_STANDING_MS = 20_000L

/** Pohodlné brzdění k zastávce / konci predikce (m/s²). */
private const val BRAKE_MS2 = 1.1

/** Odchylka směru jízdy od směru trasy, od které trasa k vozu nepatří (cos 100°). */
private const val DIR_MISMATCH_DOT = -0.17

/** Poloha se stejným místem a časem pozorování do tolika ms je stále tentýž fix (server ji jen drží). */
private const val SAME_FIX_MS = 2_500L

/**
 * Pohyb jednoho vozidla.
 *
 * Bez predikce: rychlá (1 s) animace A -> B na každý nový fix.
 *
 * S predikcí:
 *  - **Po trase** spoje (když trasa sedí se směrem jízdy): vůz jede jen dopředu rychlostí naměřenou ze skutečného
 *    postupu mezi fixy a předpovídá nejvýš [LEAD_HORIZON_S] jízdy před poslední ověřenou polohou, a ne dál než
 *    k příští zastávce, kterou realtime poloha ještě neprojela (u ní plynule zastaví a čeká na data o odjezdu).
 *    Když je predikce napřed, zpomalí / počká (necouvá). Zpět se posune jen podle realtime dat (> [BEHIND_RESYNC_M]).
 *  - **Bez trasy** (není načtená / nesedí se směrem): plynulá jízda k poslední realtime poloze po celý interval
 *    mezi aktualizacemi (ne 1 s skok a pak stání).
 *  - **Směr** určuje jen azimut ze zdroje, a kde chybí nebo není spolehlivý (DPMÚL), pohyb mezi realtime polohami.
 *    Predikce směr nikdy neurčuje.
 *
 * Hodiny jsou parametr kvůli testům ([elapsed] = SystemClock.elapsedRealtime, [wall] = čas pozorování fixů).
 */
internal class VehicleMotion(
    lat: Double,
    lng: Double,
    obsMs: Long,
    val isTrain: Boolean = false,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
    private val wall: () -> Long = { System.currentTimeMillis() },
) {
    private var shown = LatLng(lat, lng)

    // poslední NOVÝ fix (ne držená kopie)
    private var lastFix = shown
    private var lastObsMs = obsMs
    private var lastFixRecvMs = elapsed()
    private var intervalMs = 6_000.0         // obvyklý odstup nových fixů (pro plynulou jízdu bez trasy)
    private var maxIntervalMs = 6_000.0      // delší z nedávných odstupů (horizont predikce)
    private var lastAgeS = 0.0               // stáří posledního fixu při příjmu
    private var lastDir: Pair<Double, Double>? = null

    // lineární animace (bez predikce, bez trasy)
    private var from = shown
    private var to = shown
    private var t0 = lastFixRecvMs
    private var durMs = 1.0
    private var eased = false

    // po trase
    private var route: PathMeasure? = null
    private var onRoute = false
    private var sDisp = Double.NaN            // vykreslená poloha (m od začátku trasy), jen roste
    private var vDisp = 0.0                   // vykreslená rychlost (m/s)
    private var sFix = Double.NaN             // poslední ověřená poloha na trase
    private var sFixAtMs = 0L                 // kdy (elapsed) byl vůz na sFix
    private var vEst = 0.0                    // naměřená rychlost (m/s)
    private var lastTickMs = 0L
    private var mismatches = 0
    private var missed = 0

    // plynulé přenesení na trasu
    private var gliding = false
    private var glideFrom = shown
    private var glideT0 = 0L

    private val vMaxAbs = if (isTrain) 45.0 else 22.0
    private val accel = if (isTrain) 0.8 else 1.5
    private val decel = if (isTrain) 1.2 else 2.5

    private fun planLinear(a: LatLng, b: LatLng, now: Long, ms: Double, ease: Boolean = false) {
        from = a; to = b; t0 = now; durMs = ms.coerceAtLeast(1.0); eased = ease
    }

    private fun leaveRoute(now: Long) {
        onRoute = false
        gliding = false
        sDisp = Double.NaN
        sFix = Double.NaN
        vDisp = 0.0
        planLinear(shown, shown, now, 1.0)
    }

    private fun startGlide(now: Long) {
        glideFrom = shown
        glideT0 = now
        gliding = true
    }

    fun setRoute(r: PathMeasure?) {
        if (r === route) return
        route = r
        mismatches = 0
        missed = 0
        // stará poloha po trase k nové trase nepatří; napojí se při dalším fixu (plynule)
        if (onRoute) leaveRoute(elapsed())
    }

    /** Jednotkový směr jízdy: azimut ze zdroje, jinak pohyb mezi realtime polohami. Nikdy z predikce. */
    private fun direction(heading: Double?, moveVec: Pair<Double, Double>?): Pair<Double, Double>? {
        if (heading != null && heading.isFinite()) {
            val rad = Math.toRadians(heading)
            return sin(rad) to cos(rad)
        }
        if (moveVec != null) {
            val len = hypot(moveVec.first, moveVec.second)
            if (len >= 8.0) return (moveVec.first / len) to (moveVec.second / len)
        }
        return null
    }

    /** Zaregistruje fix. [heading] = azimut ze zdroje, jen když je spolehlivý (DPMÚL ho nedává). */
    fun onFix(
        lat: Double,
        lng: Double,
        obsMs: Long,
        heading: Double? = null,
        predict: Boolean = false
    ): Boolean {
        val np = LatLng(lat, lng)
        val now = elapsed()

        // server polohu drží, dokud zdroj nepošle novou: stejné místo a čas pozorování = žádná nová informace
        // (dřív se to bralo jako "vůz stojí" a predikce ho zastavila)
        if (np == lastFix && kotlin.math.abs(obsMs - lastObsMs) < SAME_FIX_MS) return false
        // stejné místo s novým časem: skoro jistě držená kopie (GPS stojícího vozu se aspoň trochu hýbe);
        // za stání se bere, až se poloha nezměnila delší dobu
        if (haversineMeters(np, lastFix) < 0.5 &&
            elapsed() - lastFixRecvMs < maxOf(DUP_FIX_STANDING_MS, (intervalMs * 2.5).toLong())
        ) return false

        val moveDist = haversineMeters(lastFix, np)
        val moveVec: Pair<Double, Double>? = if (moveDist >= 8.0) {
            val k = cos(Math.toRadians(lat))
            ((lng - lastFix.longitude) * Math.toRadians(1.0) * k * EARTH_R) to
                    ((lat - lastFix.latitude) * Math.toRadians(1.0) * EARTH_R)
        } else null
        // čas mezi pozorováními (přesnější než čas příjmu), jinak mezi příjmy
        val dtObsS = if (obsMs > 0 && lastObsMs > 0 && obsMs > lastObsMs) (obsMs - lastObsMs) / 1000.0
        else (now - lastFixRecvMs) / 1000.0
        val prevFix = lastFix
        val dtRecv = now - lastFixRecvMs
        if (dtRecv in 1_500L..20_000L) {
            intervalMs = intervalMs * 0.7 + dtRecv * 0.3
            // pro horizont predikce raději delší z nedávných odstupů: průměr z výchozích 6 s dobíhá ke skutečným
            // 10 s pomalu a vůz pak před každou aktualizací brzdil ke konci příliš krátkého horizontu
            maxIntervalMs = maxOf(dtRecv.toDouble(), maxIntervalMs * 0.8 + dtRecv * 0.2)
        }
        lastFix = np
        lastObsMs = obsMs
        lastFixRecvMs = now
        direction(heading, moveVec)?.let { lastDir = it }

        if (haversineMeters(shown, np) > TELEPORT_M) {
            leaveRoute(now)
            shown = np
            planLinear(np, np, now, 1.0)
            return true
        }

        val r = route
        if (!predict) {
            if (onRoute) leaveRoute(now)
            planLinear(shown, np, now, FAST_ANIM_DURATION_MS, ease = true)
            return true
        }

        val ageS = when {
            obsMs <= 0 -> 0.0
            else -> ((wall() - obsMs) / 1000.0).coerceAtLeast(0.0)
        }
        lastAgeS = ageS

        val proj = r?.project(
            p = np,
            nearS = if (onRoute) sFix else null,
            heading = heading,
            moveVec = moveVec,
            isTrain = isTrain,
            dtSec = dtObsS.coerceIn(1.0, 30.0),
            expectedSpeed = if (vEst > 0.5) vEst else if (isTrain) 15.0 else 7.0,
        )

        // trasa musí sedět se směrem jízdy (azimut / realtime pohyb), jinak k vozu nepatří
        val dir = lastDir
        val dirOk = proj == null || dir == null || r == null || run {
            val t = r.tangentAt(proj.s)
            t.first * dir.first + t.second * dir.second >= DIR_MISMATCH_DOT
        }
        if (proj != null && !dirOk) mismatches++ else if (proj != null) mismatches = 0

        // parkující vůz na konečné mimo osu trasy zůstává na své GPS poloze (ať se stojící vozy neslijí do bodu)
        val parkedAtTerminus = proj != null && r != null && moveDist < 12.0 && proj.offsetMeters > 20.0 &&
                (proj.s <= 40.0 || proj.s >= r.totalMeters - 40.0)

        if (proj == null || !dirOk || parkedAtTerminus) {
            if (onRoute && proj == null && missed < 2) {
                // jeden dva fixy mimo trasu (šum GPS): zůstává se na trase, predikce dojede na horizont a počká
                missed++
                return true
            }
            if (onRoute && !dirOk && mismatches < 2) return true   // jednorázově nesedí směr: ignorovat
            if (onRoute) leaveRoute(now)
            // plynulá jízda k realtime poloze po celý interval mezi aktualizacemi; velký skok (rychlost, kterou by vůz
            // nejel) nebo parkování na konečné za 1 s
            val dur = intervalMs.coerceIn(2_500.0, 9_000.0)
            val jump = parkedAtTerminus || haversineMeters(shown, np) / (dur / 1000.0) > vMaxAbs
            if (jump) planLinear(shown, np, now, FAST_ANIM_DURATION_MS, ease = true)
            else planLinear(shown, np, now, dur)
            return true
        }
        missed = 0

        // rychlost ze skutečného postupu po trase
        val newS = proj.s
        val fresh = ageS <= MAX_PREDICT_DATA_AGE_S
        if (onRoute && !sFix.isNaN()) {
            val ds = newS - sFix
            val vInst = when {
                moveDist < 6.0 && moveDist / dtObsS.coerceAtLeast(1.0) < 1.5 -> 0.0   // stojí
                ds <= 0.0 -> 0.0
                else -> (ds / dtObsS.coerceIn(1.0, 60.0)).coerceAtMost(vMaxAbs)
            }
            vEst = if (vInst == 0.0) 0.0 else if (vEst < 0.5) vInst else vEst * 0.4 + vInst * 0.6
        } else {
            // první fix na trase: rychlost z realtime pohybu před napojením, jinak zatím stojí
            vEst = if (moveVec != null && prevFix != np) (moveDist / dtObsS.coerceIn(1.0, 60.0)).coerceAtMost(vMaxAbs) else 0.0
        }
        if (!fresh || newS >= (r!!.totalMeters - 30.0)) vEst = 0.0

        sFix = newS
        sFixAtMs = now - (ageS.coerceAtMost(30.0) * 1000).toLong()

        if (!onRoute) {
            // napojení na trasu: plynule z dosavadní vykreslené polohy
            onRoute = true
            sDisp = newS
            vDisp = vEst
            lastTickMs = now
            startGlide(now)
            return true
        }

        // kde má vůz být teď: realtime poloha + jízda za dobu od pozorování (ne dál než k neprojeté zastávce)
        var sNow = (newS + vEst * ageS.coerceAtMost(MAX_LEAD_HORIZON_S)).coerceAtMost(r!!.totalMeters)
        r.nextStopAfter(newS - STOP_PASSED_M)?.let { if (sNow > it) sNow = maxOf(it, newS) }
        if (kotlin.math.abs(sNow - sDisp) > maxOf(RESYNC_M, vEst * RESYNC_S)) {
            // predikce se rozešla s realitou (výpadek dat / dřívější špatné napojení): za 1 s na správné místo
            sDisp = sNow
            vDisp = vEst
            startGlide(now)
        }
        return true
    }

    /** Pozice v čase [now] (elapsedRealtime). */
    fun positionAt(now: Long): LatLng {
        val r = route
        if (!onRoute || r == null || sDisp.isNaN()) {
            val f = ((now - t0) / durMs).coerceIn(0.0, 1.0)
            val pos = lerpLatLng(from, to, if (eased) ease(f) else f)
            shown = pos
            return pos
        }

        val dt = if (lastTickMs <= 0L) 0.0 else ((now - lastTickMs) / 1000.0).coerceIn(0.0, 2.0)
        lastTickMs = now

        // hranice predikce: nejvýš LEAD_HORIZON_S jízdy za poslední ověřenou polohou a ne dál než k příští zastávce,
        // kterou realtime poloha ještě neprojela (vůz u ní zastaví a pokračuje, až data ukážou, že odjel)
        // příští fix přijde zhruba za obvyklý odstup + stáří dat od pozorování sFix; do té doby (a ještě brzdnou
        // dráhu) má být kam jet, jinak vůz před každou aktualizací zbytečně brzdí
        // brzdná rezerva z vyšší rychlosti: vykreslený vůz může zrovna dohánět (vDisp > vEst) a brzdí déle
        val vBrakeRef = maxOf(vEst, vDisp)
        val horizonS = (maxIntervalMs / 1000.0 + lastAgeS.coerceAtMost(30.0) + vBrakeRef / (2.0 * BRAKE_MS2) + 1.0)
            .coerceIn(LEAD_HORIZON_S, MAX_LEAD_HORIZON_S)
        var sLimit = (sFix + vEst * horizonS).coerceAtMost(r.totalMeters)
        // zastávku, kterou už vykreslený vůz přejel, nedržet (prudké zastavení za ní by vypadalo hůř)
        r.nextStopAfter(maxOf(sFix - STOP_PASSED_M, sDisp - 1.0))?.let { if (it < sLimit) sLimit = it }
        // cíl predikce: poslední ověřená poloha + naměřená rychlost × čas od ní (pohybuje se rychlostí vEst)
        val leadS = ((now - sFixAtMs) / 1000.0).coerceIn(0.0, horizonS)
        val sTarget = (sFix + vEst * leadS).coerceAtMost(sLimit)
        val err = sTarget - sDisp
        val vMax = maxOf(vEst * 1.5, vEst + 3.0).coerceAtMost(vMaxAbs)
        // Rychlost = naměřená + dorovnání odchylky od cíle. Predikce kousek napřed (nová poloha potvrdila, že sedí)
        // jen trochu zpomalí, nezastaví (dřív err ≤ 0 → stát). Brzdí se jen k pevné hranici (zastávka / konec
        // horizontu): v ≤ √(2·a·dráha k hranici); dřív se brzdilo k pohyblivému cíli a vůz po každé aktualizaci,
        // kdy byl blízko cíle, prudce zpomalil.
        val vBrake = sqrt(2.0 * BRAKE_MS2 * (sLimit - sDisp).coerceAtLeast(0.0))
        // Napřed před cílem: plynule zpomalit podle toho, o kolik sekund jízdy je napřed (dřív vEst + err/3 →
        // už ~3 s napřed = stát, takže vůz po každé aktualizaci, kdy byl kousek před realtime polohou, zastavil).
        val vFollow = if (err >= 0.0) (vEst + err / RESPONSE_S).coerceIn(0.0, vMax)
        else if (vEst > 0.1) vEst * kotlin.math.exp(err / (vEst * AHEAD_SOFT_S))
        else 0.0
        val vCmd = minOf(vFollow, vBrake)
        vDisp = if (vDisp < vCmd) (vDisp + accel * dt).coerceAtMost(vCmd) else (vDisp - decel * dt).coerceAtLeast(vCmd)
        // jen dopředu a nikdy za hranici predikce
        sDisp = (sDisp + vDisp * dt).coerceAtMost(maxOf(sDisp, sLimit)).coerceIn(0.0, r.totalMeters)
        if (sDisp >= sLimit && vCmd == 0.0) vDisp = 0.0

        var pos = r.pointAt(sDisp)
        if (gliding) {
            val f = ((now - glideT0) / GLIDE_MS).coerceIn(0.0, 1.0)
            pos = lerpLatLng(glideFrom, pos, ease(f))
            if (f >= 1.0) gliding = false
        }
        shown = pos
        return pos
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
        val uid: String,
        val source: String,
        val id: Int,
        val cis: String,
        val trip: String,
        val tid: Int,
        val line: String,
        val pos: LatLng,
        val heading: Double?,
        /** uid, který nezávisí na poloze (od serveru, nebo zdroj + číslo vozu). */
        val stable: Boolean,
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

    /** uid, podle kterého jde vůz poznat i v příští odpovědi (serverové "p<lat>,<lng>" se mění s polohou). */
    private fun hasStableUid(v: Vehicle): Boolean =
        if (v.serverUid.isNotEmpty()) !v.serverUid.startsWith("p") else v.id != 0

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

        // 1. Identita vozu. Server dává stabilní uid napříč zdroji ("v451" = tentýž vůz z DÚK i DPMÚL, "d834" Děčín,
        //    "g3:1234" tramvaj PID, "t1193" vlak). Samotná čísla vozů se mezi zdroji a druhy dopravy opakují
        //    (Ústí 834 i Děčín 834, PID tramvaj i autobus 1234, vlak 665 i autobus 665; běžně desítky kolizí naráz),
        //    proto se podle čísla nikdy nepáruje napříč zdroji: klíč by přeskakoval mezi dvěma vozy a animace by
        //    "letěla" přes město. Bez stabilního uid jen v rámci stejného zdroje.
        val prevByUid = HashMap<String, Int>(prev.size * 2)
        for ((p, old) in prev.withIndex()) if (old.stable) prevByUid.putIfAbsent(old.uid, p)
        for ((i, v) in list.withIndex()) {
            val pi = if (hasStableUid(v)) {
                prevByUid[v.uid]?.takeIf { it !in usedPrev }
            } else {
                prev.indices.firstOrNull { p ->
                    if (p in usedPrev) return@firstOrNull false
                    val old = prev[p]
                    !old.stable && old.source == v.source && when {
                        v.id != 0 -> old.id == v.id
                        v.cis.isNotBlank() && v.trip.isNotBlank() -> old.cis == v.cis && old.trip == v.trip
                        else -> false
                    }
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
                uid = v.uid,
                source = v.source,
                id = v.id,
                cis = v.cis,
                trip = v.trip,
                tid = v.tid,
                line = normLine(v.line),
                pos = LatLng(v.lat, v.lng),
                heading = v.heading,
                stable = hasStableUid(v),
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