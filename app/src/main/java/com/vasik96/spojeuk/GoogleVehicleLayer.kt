package com.vasik96.spojeuk

import android.content.Context
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.math.abs

/**
 * Nativní správce markerů pro Google Maps.
 *
 * Každé volání na [Marker] (position, icon, zIndex, tag, id…) je Binder IPC do mapového jádra a běží na hlavním vlákně.
 * Proto:
 *  - markery existují pro všechna vozidla; mimo záběr se jen občas dorovnají (jádro je nekreslí),
 *  - práce s markery má časový rozpočet na snímek, záběr má přednost, zbytek se dodělá v dalších snímcích,
 *  - stav (pozice, ikona, zIndex, vozidlo) se drží v Kotlinu a na marker se zapisuje jen skutečná změna,
 *  - během pohybu kamery se existujícím markerům pozice nemění (jádro je posouvá s mapou samo).
 */
internal class GoogleVehicleLayer(private val context: Context) {
    private class Slot(val marker: Marker, val id: String) {
        var lat = 0.0
        var lng = 0.0
        var desc: BitmapDescriptor? = null
        var z = 0f
        lateinit var vehicle: Vehicle
    }

    private var googleMap: GoogleMap? = null
    private val slots = HashMap<String, Slot>()          // klíč vozidla -> marker
    private val byMarkerId = HashMap<String, String>()   // id markeru -> klíč vozidla (bez IPC v click listeneru)

    private val routeMarkers = ArrayList<Marker>()
    private var lastRoute: List<LatLng>? = null
    private var lastFull: List<LatLng>? = null
    private var lastUseFull = false
    private var lastStops: List<LatLng>? = null
    private var lastArrows: List<RouteArrow>? = null
    private var lastArrowColor = 0

    var isDotMode = false
        private set

    fun updateMode(zoom: Float): Boolean {
        isDotMode = if (isDotMode) zoom < DOT_ZOOM_GOOGLE + DOT_HYSTERESIS else zoom < DOT_ZOOM_GOOGLE
        return isDotMode
    }

    fun attach(map: GoogleMap, onVehicleSelected: (Vehicle) -> Unit) {
        if (googleMap === map) return
        detach()
        googleMap = map
        map.setOnMarkerClickListener { marker ->
            val key = byMarkerId[marker.id]
            val v = key?.let { slots[it]?.vehicle }
            if (v != null) {
                onVehicleSelected(v)
                true
            } else {
                false
            }
        }
    }

    fun detach() {
        clearAll()
        googleMap?.setOnMarkerClickListener(null)
        googleMap = null
    }

    fun clearAll() {
        for (s in slots.values) runCatching { s.marker.remove() }
        slots.clear()
        byMarkerId.clear()
        clearRoute()
    }

    private fun clearRoute() {
        for (m in routeMarkers) runCatching { m.remove() }
        routeMarkers.clear()
        lastRoute = null
        lastFull = null
        lastStops = null
        lastArrows = null
    }

    /** Vozidla, jejichž štítek ještě není nakreslený (vyzvednout přes [takeMissingLabels] a nakreslit na pozadí). */
    private val missingLabels = LinkedHashMap<String, Vehicle>()

    fun takeMissingLabels(): List<Vehicle> {
        if (missingLabels.isEmpty()) return emptyList()
        val out = missingLabels.values.toList()
        missingLabels.clear()
        return out
    }

    private class Cand(
        val key: String,
        val vehicle: Vehicle,
        val lat: Double,
        val lng: Double,
        val slot: Slot?,
        val isHl: Boolean,
    ) {
        var dist = 0.0
    }

    private var offCursor = 0   // round-robin přes vozidla mimo záběr

    /**
     * Synchronizuje markery s vozidly.
     *
     * Markery existují pro VŠECHNA vozidla (žádné proředění, žádné odebírání mimo záběr). Mimo záběr je mapové jádro
     * nekreslí, takže skoro nic nestojí, a najetí na Prahu / oddálení pak nevyžaduje žádné vytváření markerů.
     * Práce má časový rozpočet na snímek a jde v pořadí:
     *  1. vozidla v záběru od středu (vytvoření, ikona, pozice),
     *  2. odebrání vozidel, která zmizela z dat,
     *  3. ve zbylém čase dopředu vozidla mimo záběr (vytvoření, ikona, občas pozice).
     *
     * @param view viditelná oblast (s malým okrajem); null = vše
     * @param movePositions false během pohybu kamery: existující markery se nehýbou (jádro je posouvá s mapou samo)
     * @param prewarm v režimu teček těsně před přepnutím: chystat štítky dopředu, ať přepnutí nic nestojí
     * @return true, pokud zbývá práce (zavolat znovu v dalším snímku)
     */
    fun updateVehicles(
        activeVehicles: List<Pair<String, Vehicle>>,
        hub: MotionHub,
        dot: Boolean,
        targetUid: String?,
        phase: Int,
        view: ViewBox?,
        movePositions: Boolean,
        prewarm: Boolean,
    ): Boolean {
        val map = googleMap ?: return false
        val deadline = System.nanoTime() + (if (movePositions) BUDGET_IDLE_MS else BUDGET_MOVING_MS) * 1_000_000L
        fun outOfTime() = System.nanoTime() > deadline
        var descBudget = MAX_NEW_DESC_PER_PASS
        var pending = false

        /** Descriptor štítku, pokud je hotový (případně ho vytvoří z hotové bitmapy v rámci rozpočtu). */
        fun label(v: Vehicle): BitmapDescriptor? {
            val key = labelKey(v.line, v.train, v.delay, 0)
            cachedMarkerDescriptor(key)?.let { return it }
            if (hasMarkerBitmap(key)) {
                if (descBudget > 0) { descBudget--; return descriptorFromCachedBitmap(key) }
            } else if (missingLabels.size < MAX_MISSING) {
                missingLabels[key] = v
            }
            pending = true
            return null
        }

        fun create(c: Cand) {
            val desc = iconFor(c.vehicle, c.isHl, phase, dot, false, null, ::label)
            val z = if (c.isHl) 2.0f else 1.0f
            val m = map.addMarker(
                MarkerOptions()
                    .position(LatLng(c.lat, c.lng))
                    .icon(desc)
                    .anchor(0.5f, 0.5f)
                    .zIndex(z)
            ) ?: return
            val s = Slot(m, m.id)
            s.lat = c.lat; s.lng = c.lng; s.desc = desc; s.z = z; s.vehicle = c.vehicle
            slots[c.key] = s
            byMarkerId[s.id] = c.key
        }

        /** Ikona a zIndex existujícího markeru; false = nestihlo se. */
        fun refreshIcon(c: Cand, slot: Slot, pre: Boolean): Boolean {
            val desc = iconFor(c.vehicle, c.isHl, phase, dot, pre, slot.desc, ::label)
            if (slot.desc !== desc) {
                if (!c.isHl && outOfTime()) return false
                slot.marker.setIcon(desc)
                slot.desc = desc
            }
            val z = if (c.isHl) 2.0f else 1.0f
            if (slot.z != z) { slot.marker.zIndex = z; slot.z = z }
            return true
        }

        // ── rozdělení na záběr / mimo záběr ──
        val inView = ArrayList<Cand>()
        val offView = ArrayList<Cand>(activeVehicles.size)
        val seen = HashSet<String>(activeVehicles.size * 2 + 16)
        for ((key, vehicle) in activeVehicles) {
            seen += key
            val slot = slots[key]
            slot?.vehicle = vehicle   // jen v paměti (klik), žádné setTag IPC
            val p = hub.positionOf(key)
            val lat = p?.latitude ?: vehicle.lat
            val lng = p?.longitude ?: vehicle.lng
            val isHl = targetUid != null && vehicle.uid == targetUid
            val c = Cand(key, vehicle, lat, lng, slot, isHl)
            if (isHl || view == null || view.contains(lat, lng)) inView += c else offView += c
        }

        // ── 1. záběr, od středu ──
        val cy = view?.let { (it.s + it.n) / 2 } ?: 0.0
        val cx = view?.let { (it.w + it.e) / 2 } ?: 0.0
        for (c in inView) { val dy = c.lat - cy; val dx = c.lng - cx; c.dist = dy * dy + dx * dx }
        inView.sortWith(compareBy<Cand>({ !it.isHl }, { it.dist }))
        for (c in inView) {
            val slot = c.slot
            if (slot == null) {
                if (!c.isHl && outOfTime()) { pending = true; continue }
                create(c)
                continue
            }
            if (movePositions && (abs(slot.lat - c.lat) > 1e-6 || abs(slot.lng - c.lng) > 1e-6)) {
                slot.marker.position = LatLng(c.lat, c.lng)
                slot.lat = c.lat; slot.lng = c.lng
            }
            if (!refreshIcon(c, slot, prewarm && dot)) pending = true
        }

        // ── 2. vozidla, která zmizela z dat ──
        if (slots.size > seen.size || slots.keys.any { it !in seen }) {
            var removed = 0
            val it = slots.entries.iterator()
            while (it.hasNext()) {
                val (key, s) = it.next()
                if (key in seen) continue
                if (removed >= MIN_REMOVALS && outOfTime()) { pending = true; break }
                runCatching { s.marker.remove() }
                byMarkerId.remove(s.id)
                it.remove()
                removed++
            }
        }

        // ── 3. mimo záběr ve zbylém čase (round-robin, ať se dostane na všechny) ──
        val n = offView.size
        if (n > 0) {
            var i = 0
            var cursor = offCursor % n
            while (i < n) {
                if (outOfTime()) {
                    // zbývá vytvořit / přebarvit? (pouhé dorovnání pozice nespěchá a další snímek nevynucuje)
                    for (k in 0 until n - i) {
                        val c = offView[(cursor + k) % n]
                        val sl = c.slot
                        if (sl == null || (dot && sl.desc !== dotDescriptor(context, vehicleColor(c.vehicle.train, c.vehicle.delay, 0))) ||
                            (!dot && sl.desc !== cachedMarkerDescriptor(labelKey(c.vehicle.line, c.vehicle.train, c.vehicle.delay, 0)))
                        ) { pending = true; break }
                    }
                    break
                }
                val c = offView[cursor]
                val slot = c.slot
                if (slot == null) {
                    create(c)
                } else {
                    if (movePositions && (abs(slot.lat - c.lat) > OFF_MOVE_DEG || abs(slot.lng - c.lng) > OFF_MOVE_DEG)) {
                        slot.marker.position = LatLng(c.lat, c.lng)
                        slot.lat = c.lat; slot.lng = c.lng
                    }
                    if (!refreshIcon(c, slot, false)) { pending = true; break }
                }
                cursor = (cursor + 1) % n
                i++
            }
            offCursor = cursor
        }
        return pending
    }

    /**
     * Okamžité vyhodnocení ťuknutí (bez čekání ~300 ms na vyloučení dvojkliku, které dělá OnMarkerClickListener).
     * Počítá se jen z pozic v paměti, žádné volání mapy. [lat]/[lng] = místo ťuknutí, [degPerDpLng] = stupně délky na dp.
     */
    fun hitTest(lat: Double, lng: Double, degPerDpLng: Double): Vehicle? {
        val degPerDpLat = degPerDpLng * kotlin.math.cos(Math.toRadians(lat))
        val (halfW, halfH) = if (isDotMode) 12.0 to 12.0 else 26.0 to 16.0   // dp, se slušnou rezervou na prst
        var best: Slot? = null
        var bestD = Double.MAX_VALUE
        for (s in slots.values) {
            val dx = (s.lng - lng) / degPerDpLng
            val dy = (s.lat - lat) / degPerDpLat
            if (abs(dx) > halfW || abs(dy) > halfH) continue
            val d = dx * dx + dy * dy - (if (s.z > 1f) 1e6 else 0.0)   // zvýrazněné má přednost
            if (d < bestD) { bestD = d; best = s }
        }
        return best?.vehicle
    }

    private inline fun iconFor(
        vehicle: Vehicle,
        isHl: Boolean,
        phase: Int,
        dot: Boolean,
        prewarm: Boolean,
        current: BitmapDescriptor?,
        label: (Vehicle) -> BitmapDescriptor?,
    ): BitmapDescriptor {
        val iconPhase = if (isHl) phase else 0
        val dotDesc = dotDescriptor(context, vehicleColor(vehicle.train, vehicle.delay, iconPhase))
        return when {
            dot -> {
                if (prewarm && !isHl) label(vehicle)   // jen připravit, nezobrazovat
                dotDesc
            }
            isHl -> markerDescriptor(context, vehicle.line, vehicle.train, vehicle.delay, iconPhase)
            // štítek ještě není => zatím nechat, co je (tečku), vymění se v dalším průchodu
            else -> label(vehicle) ?: current ?: dotDesc
        }
    }

    fun updateRoute(
        route: List<LatLng>,
        full: List<LatLng>,
        useFull: Boolean,
        stopDots: List<LatLng>,
        arrows: List<RouteArrow>,
        arrowColor: Int
    ) {
        val map = googleMap ?: return
        if (route === lastRoute && full === lastFull && useFull == lastUseFull &&
            stopDots == lastStops && arrows === lastArrows && arrowColor == lastArrowColor
        ) return

        clearRoute()
        lastRoute = route; lastFull = full; lastUseFull = useFull
        lastStops = stopDots; lastArrows = arrows; lastArrowColor = arrowColor

        if (route.size < 2) return

        fun add(o: MarkerOptions) {
            map.addMarker(o)?.let { routeMarkers.add(it) }
        }

        val start = if (useFull && full.isNotEmpty()) full.first() else route.first()
        val end = if (useFull && full.isNotEmpty()) full.last() else route.last()

        add(MarkerOptions().position(start).icon(routeStartDescriptor(context)).anchor(0.5f, 0.5f).zIndex(1.1f))
        add(MarkerOptions().position(end).icon(routeEndDescriptor(context)).anchor(0.5f, 0.5f).zIndex(1.1f))

        val stopDesc = routeStopDescriptor(context)
        for (stop in stopDots) {
            add(MarkerOptions().position(stop).icon(stopDesc).anchor(0.5f, 0.5f).zIndex(1.05f))
        }

        val arrowDesc = routeArrowDescriptor(arrowColor, 1.0f)   // jedna sdílená ikona místo bitmapy pro každou šipku
        for (arrow in arrows) {
            add(
                MarkerOptions()
                    .position(arrow.position)
                    .icon(arrowDesc)
                    .anchor(0.5f, 0.5f)
                    .rotation(arrow.bearing)
                    .flat(true)
                    .zIndex(1.2f)
            )
        }
    }

    private companion object {
        const val BUDGET_IDLE_MS = 8L       // práce s markery za jeden snímek (snímek má 16 ms)
        const val BUDGET_MOVING_MS = 4L     // během gesta méně, ať se mapa hýbe plynule
        const val MIN_REMOVALS = 20
        const val MAX_NEW_DESC_PER_PASS = 24
        const val OFF_MOVE_DEG = 2e-4       // mimo záběr se pozice dorovnává jen při posunu > ~20 m
        const val MAX_MISSING = 256
    }
}
