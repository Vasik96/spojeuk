package com.vasik96.spojeuk

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin

/**
 * Vozidla (a trasa) na Google mapě.
 *
 * Perfetto: každý MARKER prochází v každém snímku labelovacím / kolizním enginem mapy (~6 µs na viditelný marker
 * na GL vlákně) => 2500 vozidel v záběru = 15–20 ms na snímek, mapa ~20 fps. Polyline tečky jsou ještě horší
 * (~400 ms na snímek) a TileOverlay mění velikost teček při zoomu a bliká. Proto vozidla NEJSOU objekty mapy:
 * tahle třída jen připraví snímek poloh / ikon a kreslí ho [GoogleDotOverlay] vlastním canvasem nad mapou
 * (pozice z kamery, pevná velikost v dp, mapové jádro o vozidlech neví). Nativní markery má jen trasa.
 */
internal class GoogleVehicleLayer(private val context: Context) {
    private var googleMap: GoogleMap? = null

    private val routeMarkers = ArrayList<Marker>()
    private var lastRoute: List<LatLng>? = null
    private var lastFull: List<LatLng>? = null
    private var lastUseFull = false
    private var lastStops: List<LatLng>? = null
    private var lastArrows: List<RouteArrow>? = null
    private var lastArrowColor = 0

    // ── snímek vozidel pro [GoogleDotOverlay]. Zapisuje i čte se jen na hlavním vlákně. ──
    var count = 0
        private set
    var wx = DoubleArray(0)          // světové souřadnice 0..1 (Web Mercator)
        private set
    var wy = DoubleArray(0)
        private set
    var color = IntArray(0)          // barva tečky
        private set
    var late = BooleanArray(0)       // tramvaj / metro se zpožděním: červený okraj tečky
        private set
    var label = arrayOfNulls<Bitmap>(0)   // štítek s číslem linky; null = tečka
        private set
    private var lat = DoubleArray(0)
    private var lng = DoubleArray(0)
    private var vehicles = arrayOfNulls<Vehicle>(0)
    var hl = -1                      // index zvýrazněného vozidla (kreslí se poslední), -1 = žádné
        private set
    /** Změna snímku => překreslení overlaye (kamera se čte zvlášť). */
    val version = mutableIntStateOf(0)

    var isDotMode = true
        private set

    /** Prolínání (objevení, tečky ↔ štítky); [fadeKick] se zvýší při startu animace => overlay kreslí každý snímek. */
    val fade = VehicleFade()
    val fadeKick = mutableIntStateOf(0)
    /** Některé štítky v záběru se ještě kreslí na pozadí (přechod na štítky na ně počká). */
    var labelsPending = false
        private set

    /**
     * Štítky od [DOT_ZOOM_GOOGLE] (s hysterezí proti blikání). Pojistka: při extrémním počtu vozidel v záběru
     * zůstanou tečky (tisíce štítků přes sebe jsou nečitelné a zbytečně drahé na kreslení).
     */
    fun updateMode(zoom: Float, visibleCount: Int): Boolean {
        val zoomOk = if (isDotMode) zoom >= DOT_ZOOM_GOOGLE + DOT_HYSTERESIS else zoom >= DOT_ZOOM_GOOGLE
        val countOk = if (isDotMode) visibleCount <= LABEL_ENTER_MAX else visibleCount <= LABEL_LEAVE_MAX
        isDotMode = !(zoomOk && countOk)
        // dokud nejsou vozidla vidět, režim se nastaví bez animace
        if (!fade.appeared) fade.initDotMode(isDotMode)
        else if (fade.setDotMode(isDotMode)) fadeKick.intValue++
        return isDotMode
    }

    /** Nové otevření mapy: vozidla se znovu plynule objeví. */
    fun restartAppear() {
        fade.resetAppear()
        version.intValue++
    }

    fun attach(map: GoogleMap) {
        if (googleMap === map) return
        detach()
        googleMap = map
    }

    fun detach() {
        clearAll()
        googleMap = null
    }

    fun clearAll() {
        for (i in 0 until count) { vehicles[i] = null; label[i] = null }
        count = 0
        hl = -1
        version.intValue++
        clearRoute()
    }

    /** Průhlednost markerů trasy (šipky, zastávky, začátek / konec) pro plynulé objevení a zmizení trasy. */
    private var routeAlpha = 1f

    fun setRouteAlpha(a: Float) {
        if (a == routeAlpha) return
        routeAlpha = a
        for (m in routeMarkers) runCatching { m.alpha = a }
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
     * Připraví snímek všech vozidel pro overlay (polohy z hubu, barva, štítek). Levné: žádné volání mapy.
     *
     * @param view viditelná oblast (s okrajem); štítky se dopředu chystají jen pro ni
     * @param prewarm v režimu teček těsně před přepnutím: chystat štítky dopředu, ať se při přepnutí jen vymění
     * @return true, pokud se čeká na štítky kreslené na pozadí (zavolat znovu v dalším snímku)
     */
    fun updateVehicles(
        activeVehicles: List<Pair<String, Vehicle>>,
        hub: MotionHub,
        targetUid: String?,
        phase: Int,
        view: ViewBox?,
        prewarm: Boolean,
    ): Boolean {
        // během mizení štítků (přepnutí na tečky) se štítky pořád chystají, overlay je prolíná
        val dot = isDotMode && !fade.switching()
        var pending = false

        /** Bitmapa štítku, pokud je hotová; jinak se objedná kreslení na pozadí. */
        fun labelOf(v: Vehicle): Bitmap? {
            val key = labelKey(v.line, vehicleStyle(v), 0, lateMinutes(v))
            cachedMarkerBitmap(key)?.let { return it }
            if (missingLabels.size < MAX_MISSING) missingLabels[key] = v
            pending = true
            return null
        }

        val cap = activeVehicles.size
        if (wx.size < cap) {
            val sz = cap + cap / 4 + 16
            wx = DoubleArray(sz); wy = DoubleArray(sz); color = IntArray(sz); late = BooleanArray(sz); label = arrayOfNulls(sz)
            lat = DoubleArray(sz); lng = DoubleArray(sz); vehicles = arrayOfNulls(sz)
        }
        var n = 0
        var h = -1
        for ((key, v) in activeVehicles) {
            val p = hub.positionOf(key)
            val la = p?.latitude ?: v.lat
            val lo = p?.longitude ?: v.lng
            val isHl = targetUid != null && v.uid == targetUid
            wx[n] = (lo + 180.0) / 360.0
            val sn = sin(Math.toRadians(la)).coerceIn(-0.9999, 0.9999)
            wy[n] = 0.5 - ln((1 + sn) / (1 - sn)) / (4 * PI)
            val style = vehicleStyle(v)
            color[n] = vehicleColor(style, if (isHl) phase else 0)
            val lateMin = lateMinutes(v)
            late[n] = lateMin > 0
            lat[n] = la; lng[n] = lo; vehicles[n] = v
            val inView = view == null || view.contains(la, lo)
            label[n] = when {
                // zvýrazněné: pulzující štítek (jen pár variant, kreslí se hned)
                isHl && !dot -> markerBitmap(context, v.line, style, phase, lateMin)
                isHl -> null
                !dot -> if (inView) labelOf(v) else cachedMarkerBitmap(labelKey(v.line, style, 0, lateMin))
                else -> { if (prewarm && inView) labelOf(v); null }
            }
            if (isHl) h = n
            n++
        }
        for (i in n until count) { vehicles[i] = null; label[i] = null }
        count = n
        hl = h
        if (n > 0 && fade.startAppear()) fadeKick.intValue++
        labelsPending = pending
        version.intValue++
        return pending
    }

    /**
     * Okamžité vyhodnocení ťuknutí. Počítá se jen z pozic v paměti, žádné volání mapy.
     * [lat]/[lng] = místo ťuknutí, [degPerDpLng] = stupně délky na dp.
     */
    fun hitTest(lat: Double, lng: Double, degPerDpLng: Double): List<Vehicle> {
        val degPerDpLat = degPerDpLng * kotlin.math.cos(Math.toRadians(lat))
        val density = context.resources.displayMetrics.density
        var best: Vehicle? = null
        var bestD = Double.MAX_VALUE
        // vozy, jejichž štítek / tečka místo ťuknutí skutečně kryje (bez rezervy na prst), s pořadím podle vzdálenosti
        val covering = ArrayList<Pair<Double, Vehicle>>()
        for (i in 0 until count) {
            val b = label[i]
            val w = if (b != null) b.width / density / 2.0 else 6.0
            val h = if (b != null) b.height / density / 2.0 else 6.0
            val dx = (this.lng[i] - lng) / degPerDpLng
            val dy = (this.lat[i] - lat) / degPerDpLat
            // dp, se slušnou rezervou na prst
            if (abs(dx) > w + (if (b != null) 4.0 else 6.0) || abs(dy) > h + (if (b != null) 3.0 else 6.0)) continue
            val v = vehicles[i] ?: continue
            val d = dx * dx + dy * dy - (if (i == hl) 1e6 else 0.0)   // zvýrazněné má přednost
            if (d < bestD) { bestD = d; best = v }
            if (abs(dx) <= w && abs(dy) <= h) covering += d to v
        }
        // víc vozů přes sebe (např. dva vlaky v jedné stanici): výběr, jinak nejbližší
        if (covering.size >= 2) return covering.sortedBy { it.first }.map { it.second }.distinctBy { it.uid }
        return listOfNotNull(best)
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
            map.addMarker(o.alpha(routeAlpha))?.let { routeMarkers.add(it) }
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
        const val MAX_MISSING = 256
        const val LABEL_ENTER_MAX = 1500    // pojistka: štítky, když je v záběru nejvýš tolik vozidel…
        const val LABEL_LEAVE_MAX = 1800    // …a zpět na tečky až nad tímto počtem
    }
}
