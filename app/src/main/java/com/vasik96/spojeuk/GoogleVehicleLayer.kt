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
 *  - markery existují jen pro vozidla ve viditelné oblasti (+ okraj), ostatní se odebírají,
 *  - nových markerů se za jeden průchod přidá nejvýš [MAX_NEW_PER_PASS], ať se nezasekne snímek,
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

    /**
     * Synchronizuje markery s vozidly.
     * @param view oblast, ve které se markery přidávají (už rozšířená o okraj); null = vše
     * @param keepView větší oblast, mimo kterou se markery odebírají (hystereze proti blikání na okraji)
     * @param movePositions false během pohybu kamery: jen přidávat/odebírat, existující markery nehýbat
     * @param prewarm v režimu teček těsně před přepnutím: chystat štítky dopředu, ať přepnutí nic nestojí
     * @return true, pokud se kvůli limitům nestihlo vše (zavolat znovu brzy)
     */
    fun updateVehicles(
        activeVehicles: List<Pair<String, Vehicle>>,
        hub: MotionHub,
        dot: Boolean,
        targetUid: String?,
        phase: Int,
        view: ViewBox?,
        keepView: ViewBox?,
        movePositions: Boolean,
        prewarm: Boolean,
    ): Boolean {
        val map = googleMap ?: return false
        val seen = HashSet<String>(slots.size * 2 + 16)
        var added = 0
        var swaps = 0
        var descBudget = MAX_NEW_DESC_PER_PASS
        val swapBudget = if (movePositions) MAX_ICON_SWAPS_PER_PASS else MAX_ICON_SWAPS_MOVING
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

        for ((key, vehicle) in activeVehicles) {
            val p = hub.positionOf(key)
            val lat = p?.latitude ?: vehicle.lat
            val lng = p?.longitude ?: vehicle.lng
            val isHl = targetUid != null && vehicle.uid == targetUid
            val slot = slots[key]

            // culling: zvýrazněné vozidlo se drží vždy
            if (!isHl) {
                val area = if (slot == null) view else keepView
                if (area != null && !area.contains(lat, lng)) continue
            }
            seen += key

            val iconPhase = if (isHl) phase else 0
            val dotDesc = dotDescriptor(context, vehicleColor(vehicle.train, vehicle.delay, iconPhase))
            val desc: BitmapDescriptor = when {
                dot -> {
                    if (prewarm && !isHl) label(vehicle)   // jen připravit, nezobrazovat
                    dotDesc
                }
                isHl -> markerDescriptor(context, vehicle.line, vehicle.train, vehicle.delay, iconPhase)
                // štítek ještě není => zatím nechat, co je (tečku), vymění se v dalším průchodu
                else -> label(vehicle) ?: slot?.desc ?: dotDesc
            }
            val z = if (isHl) 2.0f else 1.0f

            if (slot == null) {
                if (added >= MAX_NEW_PER_PASS && !isHl) { pending = true; seen -= key; continue }
                val m = map.addMarker(
                    MarkerOptions()
                        .position(LatLng(lat, lng))
                        .icon(desc)
                        .anchor(0.5f, 0.5f)
                        .zIndex(z)
                ) ?: continue
                added++
                val s = Slot(m, m.id)
                s.lat = lat; s.lng = lng; s.desc = desc; s.z = z; s.vehicle = vehicle
                slots[key] = s
                byMarkerId[s.id] = key
            } else {
                slot.vehicle = vehicle   // jen v paměti, žádné setTag IPC
                if (movePositions && (abs(slot.lat - lat) > 1e-6 || abs(slot.lng - lng) > 1e-6)) {
                    slot.marker.position = LatLng(lat, lng)
                    slot.lat = lat; slot.lng = lng
                }
                if (slot.desc !== desc) {
                    // výměna ikony je drahá (IPC + přepočet kolizí v jádře) => po dávkách, zvýraznění vždy hned
                    if (swaps < swapBudget || isHl) {
                        slot.marker.setIcon(desc)
                        slot.desc = desc
                        swaps++
                    } else {
                        pending = true
                    }
                }
                if (slot.z != z) {
                    slot.marker.zIndex = z
                    slot.z = z
                }
            }
        }

        // úklid neaktivních nebo vzdálených vozidel
        val it = slots.entries.iterator()
        while (it.hasNext()) {
            val (key, s) = it.next()
            if (key !in seen) {
                runCatching { s.marker.remove() }
                byMarkerId.remove(s.id)
                it.remove()
            }
        }
        return pending
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
        const val MAX_NEW_PER_PASS = 80
        const val MAX_ICON_SWAPS_PER_PASS = 40
        const val MAX_ICON_SWAPS_MOVING = 12
        const val MAX_NEW_DESC_PER_PASS = 16
        const val MAX_MISSING = 256
    }
}
