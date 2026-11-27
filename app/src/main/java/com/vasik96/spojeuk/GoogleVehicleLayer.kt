package com.vasik96.spojeuk

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
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
    /** Šipky trasy zvlášť: při změně rozestupu (zoom) se prolínají jen ony, ostatní markery trasy zůstanou. */
    private var arrowMarkers = ArrayList<Marker>()
    private var fadingArrows = ArrayList<Marker>()
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
    private var keys = arrayOfNulls<String>(0)
    /** Čas objevení vozidla (elapsedRealtime), 0 = je vidět odjakživa: overlay ho plynule zviditelní. */
    var appearAt = LongArray(0)
        private set
    /** Čas, kdy vozidlu přibyl štítek (dokreslený na pozadí) místo tečky, 0 = žádný přechod: overlay je prolne. */
    var labelAt = LongArray(0)
        private set

    // ── prolínání jednotlivých vozidel (nové / zmizelé v dávce dat, změna filtru, štítek dokreslený na pozadí) ──
    private val appearByKey = HashMap<String, Long>()
    private val labelByKey = HashMap<String, Long>()
    /** Vozidla, která z dat zmizela: doznívají na posledním místě. */
    val ghosts = ArrayList<VehicleGhost>()
    private var lastDot = true
    /** Zvýší se při startu prolínání vozidel => overlay kreslí každý snímek, dokud doznívá ([itemsFading]). */
    val itemFadeKick = mutableIntStateOf(0)
    private var itemFadeUntil = 0L

    fun itemsFading(now: Long): Boolean = now < itemFadeUntil

    private fun kickItemFade(now: Long) {
        itemFadeUntil = now + ITEM_FADE_MS
        itemFadeKick.intValue++
    }
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
        ghosts.clear(); appearByKey.clear(); labelByKey.clear()
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
        for (i in 0 until count) { vehicles[i] = null; label[i] = null; keys[i] = null }
        count = 0
        hl = -1
        ghosts.clear(); appearByKey.clear(); labelByKey.clear()
        version.intValue++
        clearRoute()
    }

    /** Průhlednost markerů trasy (šipky, zastávky, začátek / konec) pro plynulé objevení a zmizení trasy. */
    private var routeAlpha = 1f

    fun setRouteAlpha(a: Float) {
        if (a == routeAlpha) return
        routeAlpha = a
        for (m in routeMarkers) runCatching { m.alpha = a }
        for (m in arrowMarkers) runCatching { m.alpha = a }
    }

    private fun clearRoute() {
        for (m in routeMarkers) runCatching { m.remove() }
        routeMarkers.clear()
        for (m in arrowMarkers) runCatching { m.remove() }
        arrowMarkers.clear()
        for (m in fadingArrows) runCatching { m.remove() }
        fadingArrows.clear()
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
        trackAppearances(list)
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
     * Nová dávka vozidel: nová vozidla se objeví plynule, zmizelá doznívají (duchové). Při prvním zobrazení
     * (vozidla se objevují všechna najednou přes [fade]) a po znovuotevření mapy se jednotlivě neprolínají.
     */
    private fun trackAppearances(list: List<Pair<String, Vehicle>>) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (count == 0 || !fade.appeared) return
        appearByKey.entries.removeAll { now - it.value >= ITEM_FADE_MS }
        labelByKey.entries.removeAll { now - it.value >= ITEM_FADE_MS }
        ghosts.removeAll { now - it.at >= ITEM_FADE_MS }
        val newKeys = HashSet<String>(list.size * 2)
        for ((k, _) in list) newKeys += k
        val oldKeys = HashSet<String>(count * 2)
        var changed = false
        for (i in 0 until count) {
            val k = keys[i] ?: continue
            oldKeys += k
            if (k !in newKeys && i != hl && ghosts.size < MAX_GHOSTS) {
                ghosts += VehicleGhost(wx[i], wy[i], color[i], late[i], christmas[i], label[i], now)
                changed = true
            }
        }
        for ((k, _) in list) if (k !in oldKeys) { appearByKey[k] = now; changed = true }
        if (changed) kickItemFade(now)
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
            wx = wx.copyOf(sz); wy = wy.copyOf(sz); vwx = DoubleArray(sz); vwy = DoubleArray(sz); color = color.copyOf(sz)
            late = late.copyOf(sz); christmas = christmas.copyOf(sz); label = label.copyOf(sz)
            lat = DoubleArray(sz); lng = DoubleArray(sz); vehicles = arrayOfNulls(sz); keys = keys.copyOf(sz)
            appearAt = LongArray(sz); labelAt = LongArray(sz)
        }
        var n = 0
        var h = -1
        var anyMoving = false
        val sortedVehicles = mSorted
        val now = android.os.SystemClock.elapsedRealtime()
        var labelKick = false
        if (ghosts.isNotEmpty() && !itemsFading(now)) ghosts.clear()   // doznělí duchové
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
            val prevLabel = if (keys[n] == key) label[n] else null
            val sameSlot = keys[n] == key
            val lb = when {
                // zvýrazněné: pulzující štítek (jen pár variant, kreslí se hned)
                isHl && !dot -> markerBitmap(context, v.line, style, phase, lateMin, v.christmas)
                isHl -> null
                !dot -> labelOf(idx, v, order = inView)
                else -> { if (prewarm && inView) labelOf(idx, v, order = true); null }
            }
            // štítek dokreslený na pozadí, zatímco vozidlo už bylo vidět jako tečka (ne přepnutí režimu): prolnout
            if (lb != null && prevLabel == null && sameSlot && !dot && !lastDot && !isHl && fade.appeared) {
                labelByKey[key] = now; labelKick = true
            }
            label[n] = lb
            keys[n] = key
            appearAt[n] = appearByKey[key] ?: 0L
            labelAt[n] = labelByKey[key] ?: 0L
            if (isHl) h = n
            n++
        }
        for (i in n until count) { vehicles[i] = null; label[i] = null; keys[i] = null }
        count = n
        hl = h
        lastDot = dot
        if (labelKick) kickItemFade(now)
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

    suspend fun updateRoute(
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

        // změnily se jen šipky (rozestup podle zoomu): ostatní markery zůstanou, šipky se prolnou
        val onlyArrows = route === lastRoute && full === lastFull && useFull == lastUseFull && stopDots == lastStops &&
            stopColor == lastStopColor && arrowColor == lastArrowColor && arrows !== lastArrows && routeMarkers.isNotEmpty()
        if (onlyArrows) {
            lastArrows = arrows
            crossfadeArrows(map, arrows, arrowColor)
            return
        }

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

        arrowMarkers = addArrows(map, arrows, arrowColor, routeAlpha)
    }

    private fun addArrows(map: GoogleMap, arrows: List<RouteArrow>, arrowColor: Int, alpha: Float): ArrayList<Marker> {
        val out = ArrayList<Marker>(arrows.size)
        val arrowDesc = routeArrowDescriptor(arrowColor, 1.0f)   // jedna sdílená ikona místo bitmapy pro každou šipku
        for (arrow in arrows) {
            map.addMarker(
                MarkerOptions()
                    .position(arrow.position)
                    .icon(arrowDesc)
                    .anchor(0.5f, 0.5f)
                    .rotation(arrow.bearing)
                    .flat(true)
                    .zIndex(1.2f)
                    .alpha(alpha)
            )?.let { out.add(it) }
        }
        return out
    }

    /** Nové šipky se objeví, staré zmizí (prolnutí [ARROW_FADE_MS]); přerušené prolnutí se dokončí skokem. */
    private suspend fun crossfadeArrows(map: GoogleMap, arrows: List<RouteArrow>, arrowColor: Int) {
        // předchozí (přerušené) prolnutí: zbylé staré šipky pryč
        for (m in fadingArrows) runCatching { m.remove() }
        fadingArrows = arrowMarkers
        arrowMarkers = addArrows(map, arrows, arrowColor, 0f)
        try {
            val t0 = withFrameNanos { it }
            while (true) {
                val t = ((withFrameNanos { it } - t0) / 1_000_000f / ARROW_FADE_MS).coerceIn(0f, 1f)
                val a = routeAlpha
                for (m in arrowMarkers) runCatching { m.alpha = a * t }
                for (m in fadingArrows) runCatching { m.alpha = a * (1f - t) }
                if (t >= 1f) break
            }
        } finally {
            for (m in fadingArrows) runCatching { m.remove() }
            fadingArrows = ArrayList()
            for (m in arrowMarkers) runCatching { m.alpha = routeAlpha }
        }
    }

    private companion object {
        const val ARROW_FADE_MS = 250f
        const val MAX_GHOSTS = 6000
        const val MAX_MISSING = 256
        const val LABEL_ENTER_MAX = 1500    // pojistka: štítky, když je v záběru nejvýš tolik vozidel…
        const val LABEL_LEAVE_MAX = 1800    // …a zpět na tečky až nad tímto počtem
    }
}

/** Prolnutí jednoho vozidla (objevení, zmizení, tečka -> štítek), ms. */
internal const val ITEM_FADE_MS = 300L

/** Vozidlo, které z dat zmizelo: doznívá na posledním místě (poloha ve světových souřadnicích, vzhled). */
internal class VehicleGhost(
    val wx: Double, val wy: Double, val color: Int, val late: Boolean, val christmas: Boolean, val label: Bitmap?,
    val at: Long,
)
