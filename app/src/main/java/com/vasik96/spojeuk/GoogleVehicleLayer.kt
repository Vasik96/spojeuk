package com.vasik96.spojeuk

import android.content.Context
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/** Vzhled štítku vozidla (klíč do cache ikon). */
private data class BadgeKey(val line: String, val train: Boolean, val late: Boolean, val phase: Int)

private class LayerItem(val key: String, val v: Vehicle, val badge: BadgeKey)

/** Marker z poolu a to, co má právě zobrazené (ať se zbytečně nepřenastavuje ikona / pozice). */
private class PooledMarker(val marker: Marker) {
    var key: String? = null
    var dot = false
    var badge: BadgeKey? = null
    var color = 0
    var pos: LatLng? = null
    var stale = false
}

/**
 * Vozidla na Google mapě jako nativní [Marker]y z recyklovaného poolu.
 *
 *  - Markery mají na obrazovce pořád stejnou velikost (nezvětšují se ani nezmenšují se zoomem), jsou ukotvené na mapě
 *    a při tažení / zoomu / otáčení se hýbou s ní.
 *  - Kreslí se vozidla, která jsou vidět na obrazovce (ta, co by splynula do jednoho místa, jednou); mimo obrazovku
 *    se nevytváří nic, takže cena nezávisí na počtu vozidel v zemi. Drahé operace se rozkládají do více průchodů.
 *  - Markery se nevytvářejí a neruší pořád dokola: nepotřebné se schovají a vrátí do poolu, a použijí se znovu
 *    pro jiné vozidlo (změní se jen pozice / ikona).
 *  - Aktualizuje se jen když kamera stojí, při gestech se s markery nedělá nic.
 *  - Zvýrazněné vozidlo spoje je samostatný nativní [Marker], aby se plynule hýbal a pulzoval.
 */
internal class GoogleVehicleLayer {
    private var map: GoogleMap? = null

    private var items: List<LayerItem> = emptyList()
    private var itemsSrc: List<Pair<String, Vehicle>>? = null

    private val pool = ArrayList<PooledMarker>()            // všechny existující markery
    private val free = ArrayList<PooledMarker>()            // schované, k dalšímu použití
    private val used = HashMap<String, PooledMarker>()      // klíč vozidla -> jeho marker

    private var hlMarker: Marker? = null
    private var hlSrc: List<Pair<String, Vehicle>>? = null
    private var hlUid: String? = null
    private var hlEntry: Pair<String, Vehicle>? = null
    private var hlBadge: BadgeKey? = null
    private var hlPos: LatLng? = null

    fun attach(m: GoogleMap) {
        if (map === m) return
        pool.clear(); free.clear(); used.clear()
        hlMarker = null
        map = m
    }

    fun detach() {
        for (p in pool) runCatching { p.marker.remove() }
        runCatching { hlMarker?.remove() }
        pool.clear(); free.clear(); used.clear()
        hlMarker = null
        map = null
    }

    /** Zvýrazněné vozidlo spoje: nativní marker (levný, jediný), pozice se aktualizuje často. */
    fun updateHighlight(
        ctx: Context, hub: MotionHub, now: Long,
        vehicles: List<Pair<String, Vehicle>>, targetUid: String?, phase: Int
    ) {
        val m = map ?: return
        if (vehicles !== hlSrc || targetUid != hlUid) {
            hlSrc = vehicles
            hlUid = targetUid
            hlEntry = if (targetUid == null) null else vehicles.firstOrNull { it.second.uid == targetUid }
        }
        val e = hlEntry
        if (e == null || phase == 0) {
            if (hlMarker != null) { runCatching { hlMarker?.remove() }; hlMarker = null; hlBadge = null; hlPos = null }
            return
        }
        val (key, v) = e
        hub.tickKey(key, now)
        val pos = hub.positionOf(key) ?: LatLng(v.lat, v.lng)
        val badge = BadgeKey(v.line, v.train, v.delay > 3, phase)
        val mk = hlMarker
        if (mk == null) {
            hlMarker = m.addMarker(
                MarkerOptions().position(pos)
                    .icon(markerDescriptor(ctx, v.line, v.train, v.delay, phase))
                    .anchor(0.5f, 0.5f).zIndex(10f)
            )
            hlBadge = badge
            hlPos = pos
        } else {
            if (pos != hlPos) { mk.position = pos; hlPos = pos }
            if (badge != hlBadge) {
                runCatching { mk.setIcon(markerDescriptor(ctx, v.line, v.train, v.delay, phase)) }
                hlBadge = badge
            }
        }
    }

    /**
     * Aktualizuje markery vozidel pro aktuální pohled. Volá se na hlavním vlákně, jen když kamera stojí.
     * Vybere vozidla na obrazovce (identické překryvy se kreslí jednou) a přiřadí jim markery z poolu.
     * Vytváření markerů a výměna ikon jsou drahé, proto se jich za jeden průchod udělá omezený počet
     * (od středu obrazovky). Vrací true, když je hotovo; false = zbývá práce, zavolat znovu hned.
     */
    fun render(
        ctx: Context, hub: MotionHub, cam: CameraPosition, wPx: Int, hPx: Int, density: Float,
        dotMode: Boolean, vehicles: List<Pair<String, Vehicle>>, targetUid: String?
    ): Boolean {
        val m = map ?: return true
        if (wPx <= 0 || hPx <= 0) return true

        if (vehicles !== itemsSrc) {
            // pevné pořadí podle klíče = výběr je stabilní a vozidla nepřeskakují
            items = vehicles.map { (k, v) -> LayerItem(k, v, BadgeKey(v.line, v.train, v.delay > 3, 0)) }.sortedBy { it.key }
            itemsSrc = vehicles
        }

        val zoom = cam.zoom.toDouble()
        val worldPx = 256.0 * density * 2.0.pow(zoom)
        val cx = worldX(cam.target.longitude)
        val cy = worldY(cam.target.latitude)
        val rr = hypot(wPx * COVER / 2.0, hPx * COVER / 2.0) / worldPx
        val b = Math.toRadians(cam.bearing.toDouble())
        val cosB = cos(b)
        val sinB = sin(b)

        // vykreslí se vše, co je na obrazovce; vozidla, která by splynula do jednoho místa, se kreslí jen jednou
        // (u teček cca velikost tečky, u štítků jen téměř identická poloha)
        val marginPx = OFFSCREEN_MARGIN_DP * density
        val cellW = (if (dotMode) DOT_CELL_DP else LABEL_CELL_W_DP) * density
        val cellH = (if (dotMode) DOT_CELL_DP else LABEL_CELL_H_DP) * density
        val maxMarkers = if (dotMode) MAX_DOTS else MAX_LABELS
        val taken = HashSet<Long>()
        val chosen = ArrayList<LayerItem>()
        val chosenPos = ArrayList<LatLng>()
        val chosenDist = ArrayList<Double>()

        for (it in items) {
            if (targetUid != null && it.v.uid == targetUid) continue      // to kreslí zvýrazněný marker
            val p = hub.positionOf(it.key)
            val lat = p?.latitude ?: it.v.lat
            val lng = p?.longitude ?: it.v.lng
            val wx = worldX(lng)
            if (abs(wx - cx) > rr) continue
            val wy = worldY(lat)
            if (abs(wy - cy) > rr) continue
            val dx = (wx - cx) * worldPx
            val dy = (wy - cy) * worldPx
            val sx = wPx / 2.0 + (dx * cosB + dy * sinB)
            val sy = hPx / 2.0 + (-dx * sinB + dy * cosB)
            if (sx < -marginPx || sy < -marginPx || sx > wPx + marginPx || sy > hPx + marginPx) continue
            val cell = (floor(sx / cellW).toLong() shl 32) xor (floor(sy / cellH).toLong() and 0xFFFFFFFFL)
            if (!taken.add(cell)) continue
            chosen.add(it)
            chosenPos.add(p ?: LatLng(lat, lng))
            chosenDist.add((sx - wPx / 2.0) * (sx - wPx / 2.0) + (sy - hPx / 2.0) * (sy - hPx / 2.0))
        }

        // při přebytku (pojistka) i při rozložené práci mají přednost vozidla u středu obrazovky
        var order: List<Int> = chosen.indices.sortedBy { chosenDist[it] }
        if (order.size > maxMarkers) order = order.subList(0, maxMarkers)

        // markery, jejichž vozidlo už není vybrané, se schovají a vrátí do poolu
        val chosenKeys = HashSet<String>(order.size * 2)
        for (i in order) chosenKeys.add(chosen[i].key)
        val iter = used.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key !in chosenKeys) {
                val pm = e.value
                pm.marker.isVisible = false
                pm.key = null
                free.add(pm)
                iter.remove()
            }
        }

        // posun o méně než ~1,5 dp se nepřenastavuje (marker by se stejně nepohnul)
        val moveEps = 360.0 / worldPx * 1.5 * density
        var budget = OPS_PER_PASS
        var done = true

        for (i in order) {
            val li = chosen[i]
            val v = li.v
            val pos = chosenPos[i]
            val color = if (dotMode) vehicleColor(v.train, v.delay) else 0
            val needIcon: (PooledMarker) -> Boolean = { pm ->
                if (dotMode) !pm.dot || pm.color != color else pm.dot || pm.badge != li.badge
            }
            val icon = { if (dotMode) dotDescriptor(ctx, color) else markerDescriptor(ctx, v.line, v.train, v.delay, 0) }

            var found = used[li.key]
            if (found == null) {
                if (budget <= 0) { done = false; continue }
                budget--
                // ikona se nastaví hned při vytvoření, jinak by marker krátce ukázal výchozí červenou špendlík
                val np = acquire(m, pos, icon())
                np.dot = dotMode; np.color = color; np.badge = if (dotMode) null else li.badge
                np.pos = pos; np.stale = false
                np.key = li.key
                used[li.key] = np
                found = np
            } else if (needIcon(found)) {
                if (budget <= 0) { done = false } else {
                    budget--
                    runCatching { found.marker.setIcon(icon()) }
                    found.dot = dotMode; found.color = color; found.badge = if (dotMode) null else li.badge
                }
            }
            val pm: PooledMarker = found

            val old = pm.pos
            if (old == null || abs(old.latitude - pos.latitude) > moveEps || abs(old.longitude - pos.longitude) > moveEps) {
                pm.marker.position = pos; pm.pos = pos
            }
            val stale = v.ageSec() >= 60
            if (pm.stale != stale) { pm.marker.alpha = if (stale) 0.65f else 1f; pm.stale = stale }
            if (!pm.marker.isVisible) pm.marker.isVisible = true
        }

        // pool se nenafukuje: nadbytečné schované markery se zruší (po částech)
        var removals = 0
        while (free.size > MAX_FREE && removals < MAX_REMOVALS_PER_PASS) {
            val pm = free.removeAt(free.size - 1)
            pool.remove(pm)
            runCatching { pm.marker.remove() }
            removals++
        }
        if (free.size > MAX_FREE) done = false
        return done
    }

    /** Vezme volný marker z poolu (a přepíše mu ikonu / pozici), nebo vytvoří nový rovnou s ikonou. */
    private fun acquire(m: GoogleMap, pos: LatLng, icon: BitmapDescriptor): PooledMarker {
        if (free.isNotEmpty()) {
            val pm = free.removeAt(free.size - 1)
            runCatching { pm.marker.setIcon(icon) }
            pm.marker.position = pos
            pm.marker.alpha = 1f
            return pm
        }
        val mk = m.addMarker(
            MarkerOptions().position(pos).icon(icon).anchor(0.5f, 0.5f).zIndex(5f).visible(false)
        )!!
        return PooledMarker(mk).also { pool.add(it) }
    }

    private companion object {
        const val COVER = 1.1                // předvýběr kolem obrazovky (kruh přes otočenou mapu)
        const val OFFSCREEN_MARGIN_DP = 40f  // přesah, aby marker na kraji obrazovky nechyběl
        const val DOT_CELL_DP = 9f           // tečky blíž než ~velikost tečky splynou, kreslí se jedna
        const val LABEL_CELL_W_DP = 12f      // štítky se vynechávají jen když leží skoro přesně na sobě
        const val LABEL_CELL_H_DP = 8f
        const val MAX_DOTS = 1800            // pojistka proti extrémnímu počtu
        const val MAX_LABELS = 900
        const val OPS_PER_PASS = 60          // nových markerů / výměn ikon za jeden průchod (každých ~40 ms)
        const val MAX_FREE = 300
        const val MAX_REMOVALS_PER_PASS = 80
    }
}

private fun worldX(lng: Double) = (lng + 180.0) / 360.0

private fun worldY(lat: Double): Double {
    val s = sin(Math.toRadians(lat.coerceIn(-85.05, 85.05)))
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}

private fun lngOf(x: Double) = x * 360.0 - 180.0

private fun latOf(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y.coerceIn(0.0, 1.0)))))

/** Viditelná oblast spočtená z kamery (bez volání do mapového jádra, které na hlavním vlákně zdržuje). */
internal fun googleViewBox(cam: CameraPosition, wPx: Int, hPx: Int, density: Float): ViewBox? {
    if (wPx <= 0 || hPx <= 0) return null
    val worldPx = 256.0 * density * 2.0.pow(cam.zoom.toDouble())
    val r = hypot(wPx / 2.0, hPx / 2.0) / worldPx      // čtverec pokrytí i při otočení mapy
    val cx = worldX(cam.target.longitude)
    val cy = worldY(cam.target.latitude)
    return ViewBox(latOf(cy + r), lngOf(cx - r), latOf(cy - r), lngOf(cx + r))
}
