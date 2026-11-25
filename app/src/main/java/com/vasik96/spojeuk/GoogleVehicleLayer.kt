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
    private var lastStopColor = 0

    // ── snímek vozidel pro [GoogleDotOverlay]. Zapisuje i čte se jen na hlavním vlákně. ──
    var count = 0
        private set
    var wx = DoubleArray(0)          // světové souřadnice 0..1 (Web Mercator)
        private set
    var wy = DoubleArray(0)
        private set
    var vwx = DoubleArray(0)         // rychlost ve světových souřadnicích za ms (dopočet polohy v každém snímku)
        private set
    var vwy = DoubleArray(0)
        private set
    /** Čas snímku (System.nanoTime), ke kterému platí [wx]/[wy]. */
    var stampNanos = 0L
    /** Čas posledního snímku (System.nanoTime), ke kterému overlay kreslí. */
    var frameNanos = 0L
    /** Nějaké vozidlo se hýbe => overlay se překresluje každý snímek (s dopočtem polohy). */
    val moving = androidx.compose.runtime.mutableStateOf(false)
    var color = IntArray(0)          // barva tečky
        private set
    var late = BooleanArray(0)       // tramvaj / metro se zpožděním: červený okraj tečky
        private set
    var christmas = BooleanArray(0)  // vánoční ozdoba
        private set
    var label = arrayOfNulls<Bitmap>(0)   // štítek s číslem linky; null = tečka
        private set
    private var lat = DoubleArray(0)
    private var lng = DoubleArray(0)
    private var vehicles = arrayOfNulls<Vehicle>(0)
    var hl = -1                      // index zvýrazněného vozidla (kreslí se poslední), -1 = žádné
        private set
    // ── zastávky v záběru (kreslí je overlay pod vozidly) ──
    var stops: List<MapStop> = emptyList()
        private set
    var stopWx = DoubleArray(0)
        private set
    var stopWy = DoubleArray(0)
        private set

    /** Bitmapy zastávek v barvách motivu (nastaví mapa). */
    var stopIcons: StopIcons? = null
        set(v) { if (field !== v) { field = v; version.intValue++ } }

    // ── objevení / zmizení zastávek (prolnutí) ──
    private var stopsShown = false
    private var stopFadeFrom = 0f
    private var stopFadeT0 = 0L
    /** Zvýší se při startu prolínání => overlay kreslí každý snímek, dokud doznívá. */
    val stopFadeKick = mutableIntStateOf(0)

    fun setStopsVisible(show: Boolean, now: Long) {
        if (show == stopsShown) return
        stopFadeFrom = stopAlpha(now)
        stopsShown = show
        stopFadeT0 = now
        stopFadeKick.intValue++
    }

    /** Průhlednost zastávek v čase [now] (elapsedRealtime). */
    fun stopAlpha(now: Long): Float {
        val f = ((now - stopFadeT0).toFloat() / STOP_FADE_MS).coerceIn(0f, 1f)
        val target = if (stopsShown) 1f else 0f
        return stopFadeFrom + (target - stopFadeFrom) * f
    }

    fun stopsFading(now: Long): Boolean = now - stopFadeT0 < STOP_FADE_MS

    fun setStops(list: List<MapStop>) {
        if (list == stops) return
        val wx = DoubleArray(list.size)
        val wy = DoubleArray(list.size)
        for ((i, st) in list.withIndex()) {
            wx[i] = (st.lng + 180.0) / 360.0
            val sn = sin(Math.toRadians(st.lat)).coerceIn(-0.9999, 0.9999)
            wy[i] = 0.5 - ln((1 + sn) / (1 - sn)) / (4 * PI)
        }
        stopWx = wx; stopWy = wy; stops = list
        version.intValue++
    }

    /** Zastávka pod ťuknutím (ikona ~16 dp, s rezervou na prst), nejbližší. */
    fun hitTestStop(lat: Double, lng: Double, degPerDpLng: Double): MapStop? {
        val degPerDpLat = degPerDpLng * kotlin.math.cos(Math.toRadians(lat))
        var best: MapStop? = null
        var bestD = Double.MAX_VALUE
        for (st in stops) {
            val dx = (st.lng - lng) / degPerDpLng
            val dy = (st.lat - lat) / degPerDpLat
            if (abs(dx) > 18.0 || abs(dy) > 18.0) continue
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = st }
        }
        return best
    }

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

    // ── údaje vozidel, které se mění jen s novou dávkou dat (~6 s), ne s každým snímkem ──
    // Dřív se pro všech ~2 300 vozidel každých 33 ms znovu počítal styl (řetězcové operace), barva, zpoždění a skládal
    // se klíč štítku (nový řetězec) + hledal v cache: většina CPU hlavního vlákna na otevřené mapě.
    private var metaSrc: List<Pair<String, Vehicle>>? = null
    private var mSorted: List<Pair<String, Vehicle>> = emptyList()
    private var mStyle = arrayOfNulls<VehicleStyle>(0)
    private var mColor = IntArray(0)
    private var mLate = IntArray(0)
    private var mKey = arrayOfNulls<String>(0)
    private var mLabel = arrayOfNulls<Bitmap>(0)         // nalezená bitmapa štítku (fáze 0), null = ještě není

    private fun ensureMeta(list: List<Pair<String, Vehicle>>) {
        if (list === metaSrc) return
        metaSrc = list
        mSorted = if (list.any { it.second.christmas }) list.sortedBy { (_, v) -> if (v.christmas) 1 else 0 } else list
        val n = mSorted.size
        if (mStyle.size < n) {
            val sz = n + n / 4 + 16
            mStyle = arrayOfNulls(sz); mColor = IntArray(sz); mLate = IntArray(sz); mKey = arrayOfNulls(sz); mLabel = arrayOfNulls(sz)
        }
        for (i in 0 until n) {
            val v = mSorted[i].second
            val style = vehicleStyle(v)
            val late = lateMinutes(v)
            mStyle[i] = style
            mColor[i] = vehicleColor(style, 0)
            mLate[i] = late
            mKey[i] = labelKey(v.line, style, 0, late, v.christmas)
            mLabel[i] = null
        }
        for (i in n until mStyle.size) { mStyle[i] = null; mKey[i] = null; mLabel[i] = null }
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
        /**
         * Plynulý pohyb po snímcích (overlay dopočítává polohu v každém snímku). false u oddálených teček: posouvají se
         * o zlomky pixelu a stačí jim snímek poloh po 250 ms; překreslování každý snímek (60–120×/s tisíců teček) bylo
         * zbytečné.
         */
        smooth: Boolean = true,
    ): Boolean {
        // během mizení štítků (přepnutí na tečky) se štítky pořád chystají, overlay je prolíná
        val dot = isDotMode && !fade.switching()
        var pending = false

        ensureMeta(activeVehicles)

        /** Bitmapa štítku vozidla [i], pokud je hotová (jednou nalezená se pamatuje); jinak se objedná kreslení na pozadí. */
        fun labelOf(i: Int, v: Vehicle, order: Boolean): Bitmap? {
            mLabel[i]?.let { return it }
            val key = mKey[i]!!
            cachedMarkerBitmap(key)?.let { mLabel[i] = it; return it }
            if (order) {
                if (missingLabels.size < MAX_MISSING) missingLabels[key] = v
                pending = true
            }
            return null
        }

        val cap = activeVehicles.size
        if (wx.size < cap) {
            val sz = cap + cap / 4 + 16
            wx = DoubleArray(sz); wy = DoubleArray(sz); vwx = DoubleArray(sz); vwy = DoubleArray(sz); color = IntArray(sz); late = BooleanArray(sz); christmas = BooleanArray(sz); label = arrayOfNulls(sz)
            lat = DoubleArray(sz); lng = DoubleArray(sz); vehicles = arrayOfNulls(sz)
        }
        var n = 0
        var h = -1
        var anyMoving = false
        val sortedVehicles = mSorted
        for (idx in sortedVehicles.indices) {
            val (key, v) = sortedVehicles[idx]
            val e = hub.entryOf(key)
            val p = e?.pos
            val la = p?.latitude ?: v.lat
            val lo = p?.longitude ?: v.lng
            val isHl = targetUid != null && v.uid == targetUid
            // Web Mercator (sin + ln) jen při změně polohy: vozidla mimo záběr se posouvají jen jednou za vteřinu
            // a stojící vůbec, přepočet pro všechna každých 33 ms byl zbytečný (pořadí je pro dávku pevné: n == idx)
            if (lat[n] != la || lng[n] != lo || vehicles[n] !== v) {
                wx[n] = (lo + 180.0) / 360.0
                val sn = sin(Math.toRadians(la)).coerceIn(-0.9999, 0.9999)
                wy[n] = 0.5 - ln((1 + sn) / (1 - sn)) / (4 * PI)
            }
            // d(wx)/d(lng) = 1/360, d(wy)/d(lat) = -1/(360·cos φ)
            val vLat = e?.velLat ?: 0.0
            val vx = (e?.velLng ?: 0.0) / 360.0
            val vy = if (vLat == 0.0) 0.0 else -vLat / (360.0 * kotlin.math.cos(Math.toRadians(la)).coerceAtLeast(0.01))
            vwx[n] = vx; vwy[n] = vy
            if (vx != 0.0 || vy != 0.0) anyMoving = true
            val style = mStyle[idx]!!
            color[n] = if (isHl) vehicleColor(style, phase) else mColor[idx]
            val lateMin = mLate[idx]
            late[n] = lateMin > 0
            christmas[n] = v.christmas
            lat[n] = la; lng[n] = lo; vehicles[n] = v
            val inView = view == null || view.contains(la, lo)
            label[n] = when {
                // zvýrazněné: pulzující štítek (jen pár variant, kreslí se hned)
                isHl && !dot -> markerBitmap(context, v.line, style, phase, lateMin, v.christmas)
                isHl -> null
                !dot -> labelOf(idx, v, order = inView)
                else -> { if (prewarm && inView) labelOf(idx, v, order = true); null }
            }
            if (isHl) h = n
            n++
        }
        for (i in n until count) { vehicles[i] = null; label[i] = null }
        count = n
        hl = h
        val mv = anyMoving && smooth
        if (moving.value != mv) moving.value = mv
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
        arrowColor: Int,
        stopColor: Int,
    ) {
        val map = googleMap ?: return
        if (route === lastRoute && full === lastFull && useFull == lastUseFull &&
            stopDots == lastStops && arrows === lastArrows && arrowColor == lastArrowColor &&
            stopColor == lastStopColor
        ) return

        clearRoute()
        lastRoute = route; lastFull = full; lastUseFull = useFull
        lastStops = stopDots; lastArrows = arrows; lastArrowColor = arrowColor; lastStopColor = stopColor

        if (route.size < 2) return

        fun add(o: MarkerOptions) {
            map.addMarker(o.alpha(routeAlpha))?.let { routeMarkers.add(it) }
        }

        val start = if (useFull && full.isNotEmpty()) full.first() else route.first()
        val end = if (useFull && full.isNotEmpty()) full.last() else route.last()

        add(MarkerOptions().position(start).icon(routeStartDescriptor(context)).anchor(0.5f, 0.5f).zIndex(1.1f))
        add(MarkerOptions().position(end).icon(routeEndDescriptor(context)).anchor(0.5f, 0.5f).zIndex(1.1f))

        val stopDesc = routeStopDescriptor(context, stopColor)
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
