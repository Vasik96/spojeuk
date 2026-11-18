package com.vasik96.spojeuk

import android.content.Context
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.math.abs

/**
 * Nativní správce markerů pro Google Maps.
 * Všechny značky (vozidla, zastávky, šipky trasy) jsou nativní objekty [Marker] přímo v mapovém jádře.
 * Při posunu/zoomu/otáčení mapy je nativní C++ engine Google Maps posouvá v uzamčení s dlaždicemi (0 lag, 0 plavání).
 * Veškeré čtení/zápis IPC vlastností markerů je zavedeno v paměťové cache v Kotlinu pro nula-IPC zátěž.
 */
internal class GoogleVehicleLayer(private val context: Context) {
    private var googleMap: GoogleMap? = null
    private val vehicleMarkers = HashMap<String, Marker>()
    private val knownPositions = HashMap<String, LatLng>()
    private val knownDescriptors = HashMap<String, BitmapDescriptor>()
    private val knownZIndices = HashMap<String, Float>()

    private val routeMarkers = ArrayList<Marker>()
    private var lastRouteSignature: String? = null

    private var lastDotMode = false
    private var lastTargetUid: String? = null
    private var lastPhase = 0

    val highlightKey: String? get() = lastTargetUid

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
            val v = marker.tag as? Vehicle
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
        for (m in vehicleMarkers.values) runCatching { m.remove() }
        vehicleMarkers.clear()
        knownPositions.clear()
        knownDescriptors.clear()
        knownZIndices.clear()

        for (m in routeMarkers) runCatching { m.remove() }
        routeMarkers.clear()
        lastRouteSignature = null

        lastTargetUid = null
        lastPhase = 0
    }

    @Suppress("UNUSED_PARAMETER")
    fun calibrate(cam: CameraPosition, proj: com.google.android.gms.maps.Projection, density: Float) {
        // Není potřeba pro nativní markery
    }

    @Suppress("UNUSED_PARAMETER")
    fun hitTest(x: Float, y: Float, radiusPx: Float): Vehicle? {
        // Nativní OnMarkerClickListener vyhodnocuje klepnutí přímo v mapovém jádře
        return null
    }

    fun updateVehicles(
        activeVehicles: List<Pair<String, Vehicle>>,
        hub: MotionHub,
        zoom: Float,
        targetUid: String?,
        phase: Int
    ) {
        val map = googleMap ?: return
        val dot = updateMode(zoom)
        lastDotMode = dot
        lastTargetUid = targetUid
        lastPhase = phase

        val activeKeys = HashSet<String>(activeVehicles.size)

        for ((key, vehicle) in activeVehicles) {
            activeKeys.add(key)
            val p = hub.positionOf(key)
            val lat = p?.latitude ?: vehicle.lat
            val lng = p?.longitude ?: vehicle.lng
            val latLng = LatLng(lat, lng)

            val isHl = targetUid != null && vehicle.uid == targetUid
            val iconPhase = if (isHl) phase else 0
            val desc = if (dot) {
                dotDescriptor(context, vehicleColor(vehicle.train, vehicle.delay, iconPhase))
            } else {
                markerDescriptor(context, vehicle.line, vehicle.train, vehicle.delay, iconPhase)
            }
            val z = if (isHl) 2.0f else 1.0f

            val marker = vehicleMarkers[key]
            if (marker == null) {
                val m = map.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .icon(desc)
                        .anchor(0.5f, 0.5f)
                        .zIndex(z)
                )
                if (m != null) {
                    m.tag = vehicle
                    vehicleMarkers[key] = m
                    knownPositions[key] = latLng
                    knownDescriptors[key] = desc
                    knownZIndices[key] = z
                }
            } else {
                marker.tag = vehicle

                // Kontrola pozice výhradně zpaměti (0 Binder IPC čtení)
                val prevPos = knownPositions[key]
                if (prevPos == null || abs(prevPos.latitude - lat) > 1e-6 || abs(prevPos.longitude - lng) > 1e-6) {
                    marker.position = latLng
                    knownPositions[key] = latLng
                }

                // Kontrola ikony výhradně zpaměti (0 zbytečných setIcon IPC zápisů)
                if (knownDescriptors[key] !== desc) {
                    marker.setIcon(desc)
                    knownDescriptors[key] = desc
                }

                // Kontrola zIndex výhradně zpaměti
                if (knownZIndices[key] != z) {
                    marker.zIndex = z
                    knownZIndices[key] = z
                }
            }
        }

        // Úklid vozidel, která již nejsou aktivní
        val iterator = vehicleMarkers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!activeKeys.contains(entry.key)) {
                runCatching { entry.value.remove() }
                knownPositions.remove(entry.key)
                knownDescriptors.remove(entry.key)
                knownZIndices.remove(entry.key)
                iterator.remove()
            }
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
        val signature = "${route.size}:${full.size}:${useFull}:${stopDots.size}:${arrows.size}:${arrowColor}"
        if (signature == lastRouteSignature) return
        lastRouteSignature = signature

        for (m in routeMarkers) runCatching { m.remove() }
        routeMarkers.clear()

        if (route.size < 2) return

        val start = if (useFull && full.isNotEmpty()) full.first() else route.first()
        val end = if (useFull && full.isNotEmpty()) full.last() else route.last()

        map.addMarker(
            MarkerOptions()
                .position(start)
                .icon(routeStartDescriptor(context))
                .anchor(0.5f, 0.5f)
                .zIndex(1.1f)
        )?.let { routeMarkers.add(it) }

        map.addMarker(
            MarkerOptions()
                .position(end)
                .icon(routeEndDescriptor(context))
                .anchor(0.5f, 0.5f)
                .zIndex(1.1f)
        )?.let { routeMarkers.add(it) }

        val stopDesc = routeStopDescriptor(context)
        for (stop in stopDots) {
            map.addMarker(
                MarkerOptions()
                    .position(stop)
                    .icon(stopDesc)
                    .anchor(0.5f, 0.5f)
                    .zIndex(1.05f)
            )?.let { routeMarkers.add(it) }
        }

        for (arrow in arrows) {
            val arrowBmp = routeArrowBitmap(arrowColor, scale = 1.0f)
            map.addMarker(
                MarkerOptions()
                    .position(arrow.position)
                    .icon(BitmapDescriptorFactory.fromBitmap(arrowBmp))
                    .anchor(0.5f, 0.5f)
                    .rotation(arrow.bearing)
                    .flat(true)
                    .zIndex(1.2f)
            )?.let { routeMarkers.add(it) }
        }
    }
}
