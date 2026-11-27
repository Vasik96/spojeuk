@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, com.google.maps.android.compose.MapsComposeExperimentalApi::class)

package com.vasik96.spojeuk

import com.vasik96.spojeuk.BuildConfig
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition as MapLibreCameraPosition
import org.maplibre.android.geometry.LatLng as MapLibreLatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView as MapLibreMapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Mapy.cz (api.mapy.com) nemá vektorový styl ani tmavou variantu => rastrové dlaždice jako MapLibre style JSON.
 * Tmavé dlaždice přebarvuje při stažení [MapyDarkTiles] (parametr v URL), ne vrstva mapy.
 */
private fun mapyStyleJson(dark: Boolean): String {
    val darkParam = if (dark) "&${MapyDarkTiles.DARK_PARAM}=1" else ""
    val tiles = "https://api.mapy.com/v1/maptiles/basic/256@2x/{z}/{x}/{y}?apikey=${BuildConfig.MAPYCZ_API_KEY}$darkParam"
    val paint = "{}"
    val bg = if (dark) "#1d2430" else "#f1f0e5"   // barva pozadí dlaždic, než se načtou
    return """
        {"version":8,
         "sources":{"mapy":{"type":"raster","tiles":["$tiles"],"tileSize":256,"maxzoom":19,
            "attribution":"© Seznam.cz a.s. a další, © OpenStreetMap"}},
         "layers":[{"id":"mapy-bg","type":"background","paint":{"background-color":"$bg"}},
                   {"id":"mapy-layer","type":"raster","source":"mapy","paint":$paint}]}
    """.trimIndent()
}

private fun mapStyleBuilder(provider: MapProvider, dark: Boolean): Style.Builder =
    if (provider == MapProvider.MAPYCZ && BuildConfig.MAPYCZ_API_KEY.isNotBlank())
        Style.Builder().fromJson(mapyStyleJson(dark))
    else
        Style.Builder().fromUri(cartoStyleUrl(dark))

private fun cartoStyleUrl(dark: Boolean): String {
    val name = if (dark) "dark-matter-gl-style" else "voyager-gl-style"
    return "https://basemaps.cartocdn.com/gl/$name/style.json?key=${BuildConfig.CARTO_API_KEY}"
}


/*******
 * map libre render: ******/

// TextureView místo SurfaceView (všude): mapa se skládá do okna appky ve STEJNÉM snímku jako overlay vozidel.
// SurfaceView jde na displej vlastní cestou o snímek dřív než okno appky => vozidla při tažení zaostávala za mapou.
// (Navíc řeší "BLASTBufferQueue ... Can't acquire next buffer" a vykreslování na Vivo/Funtouch.)
private const val USE_TEXTURE_MODE = true

private const val LOCATION_ANIMATION_FPS = 10              // animace modrého bodu (kompas) v MapLibre
/** Tolerance při čekání na další snímek poloh (polovina snímku při 120 Hz). */
internal const val FRAME_SLACK_NS = 4_000_000L

/** Čas snímku (System.nanoTime z withFrameNanos) v časové ose SystemClock.elapsedRealtime (ms), jak ji čte pohyb vozidel. */
internal fun frameElapsedMs(frameNanos: Long): Long =
    (frameNanos + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())) / 1_000_000L

/** Prolnutí zastávek při objevení / zmizení (ms). */
internal const val STOP_FADE_MS = 250L
/** O kolik úrovní pod hranicí zobrazení zastávek se jejich vrstva MapLibre ještě zpracovává (doznění). */
private const val STOP_LAYER_MIN_ZOOM_MARGIN = 0.6

private const val STOPS_SRC = "map-stops-src"
private const val STOPS_LAYER = "map-stops"
private const val STOP_IMG_BUS = "map-stop-bus"
private const val STOP_IMG_RAIL = "map-stop-rail"


internal const val GOOGLE_VEHICLE_INTERVAL_MS = 33L     // Google: pohyb vozidel v overlayi (~30 Hz, jako MapLibre)
internal const val GOOGLE_DOT_INTERVAL_MS = 250L         // oddálené tečky (pod DOT_SMOOTH_ZOOM) se hýbou o zlomky pixelu
internal const val DOT_SMOOTH_ZOOM = 10.5f
internal const val LABEL_PREWARM_ZOOM = 1.0f            // o kolik úrovní před přepnutím na štítky se začnou kreslit


// vrstvy trasy vždy POD vrstvou vozidel
private fun addBelowMarkers(style: Style, layer: Layer) {
    ensureRouteAnchor(style)
    style.addLayerBelow(layer, ROUTE_ANCHOR_LAYER)
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
@Composable
internal fun MapLibreScreenContent(
    modifier: Modifier = Modifier,
    appState: SpojeAppState,
    active: Boolean,
    useDarkMap: Boolean,
    activeVehicles: List<Pair<String, Vehicle>>,
    primaryColor: Color,
    showFullRoute: Boolean,
    phase: Int,
    targetUid: String?,
    hasLocationPermission: Boolean,
    onMapReady: (MapLibreMap) -> Unit,
    onStyleLoaded: () -> Unit = {},
    tertiaryColor: Color,
    arrowColor: Color,
    hub: MotionHub,
    stopIndex: StopIndex?,
    stopIcons: StopIcons,
    onStopTap: (MapStop) -> Unit,
    onMapDisposed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = context.resources.displayMetrics.density

    val mapView = remember(context) {
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(USE_TEXTURE_MODE)
        MapLibreMapView(context, options)
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleVersion by remember { mutableIntStateOf(0) }
    var appliedStyleKey by remember { mutableStateOf<String?>(null) }

    // ── lifecycle (onDestroy se zavolá právě jednou) ──
    DisposableEffect(lifecycleOwner, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> if (!destroyed) {
                    destroyed = true
                    mapView.onDestroy()
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // uložit poslední pozici kamery, než se zahodí reference na mapu
            runCatching {
                map?.cameraPosition?.let { cp ->
                    cp.target?.let {
                        appState.mapCenter = LatLng(it.latitude, it.longitude)
                        appState.mapZoom = cp.zoom.toFloat() + 1f
                    }
                }
            }
            // nejdřív zahodit reference na mapu, ať na ni nesahají běžící efekty
            map = null
            onMapDisposed()
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!destroyed) {
                destroyed = true
                val state = lifecycleOwner.lifecycle.currentState
                if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    // ── stav vozidel ──
    val latestVehicles by rememberUpdatedState(activeVehicles)

    // zastávky: všechny ve zdroji mapy; viditelnost podle zoomu hlídá pohyb kamery (viz syncStopVisibility)
    val latestStopIndex by rememberUpdatedState(stopIndex)
    val stopVis = remember { StopVisibility() }
    fun syncStopVisibility(m: MapLibreMap, force: Boolean = false) {
        val st = m.style ?: return
        val show = latestStopIndex != null && m.cameraPosition.zoom >= STOP_MIN_ZOOM_GOOGLE - 1.0
        if (!force && show == stopVis.shown) return
        val layer = st.getLayer(STOPS_LAYER) as? SymbolLayer ?: return
        stopVis.shown = show
        layer.setProperties(PropertyFactory.iconOpacity(if (show) 1f else 0f))
    }

    // overlay vozidel (stejný jako u Google mapy) a počítadlo pohybu kamery, které ho překresluje
    val overlayLayer = remember(context) { GoogleVehicleLayer(context) }
    val camTick = remember { mutableLongStateOf(0L) }
    var cameraMoving by remember { mutableStateOf(false) }
    // Během pohybu kamery (gesto, animace) se overlay překreslí v KAŽDÉM snímku a kameru si přečte až při kreslení:
    // poloha vozidel pak odpovídá transformaci, kterou MapLibre v tom snímku vykreslí (ne o snímek starší z události).
    LaunchedEffect(cameraMoving) {
        while (cameraMoving) { withFrameNanos { }; camTick.longValue++ }
    }

    Box(modifier) {
    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { m ->
                    // kamera hned, ne až po načtení stylu
                    m.cameraPosition = MapLibreCameraPosition.Builder()
                        .target(
                            MapLibreLatLng(
                                appState.mapCenter.latitude,
                                appState.mapCenter.longitude
                            )
                        )
                        .zoom(appState.mapZoom.toDouble() - 1.0)
                        .build()
                    m.uiSettings.isAttributionEnabled = false
                    m.uiSettings.isLogoEnabled = false
                    m.uiSettings.isCompassEnabled = false

                    // overlay vozidel počítá s pohledem shora (jako Google mapa): bez náklonu
                    m.uiSettings.isTiltGesturesEnabled = false
                    m.addOnCameraMoveStartedListener { cameraMoving = true }
                    m.addOnCameraMoveListener {
                        camTick.longValue++
                        syncStopVisibility(m)   // přechod přes hranici zoomu: prolnutí hned, ne až po zastavení
                    }
                    // stav kamery se ukládá až když se mapa zastaví, ne při každém framu pohybu
                    m.addOnCameraIdleListener {
                        cameraMoving = false
                        camTick.longValue++
                        syncStopVisibility(m)
                        val cp = m.cameraPosition
                        cp.target?.let {
                            appState.mapCenter = LatLng(it.latitude, it.longitude)
                            appState.mapZoom = cp.zoom.toFloat() + 1f
                        }
                    }

                    // klik na vozidlo: výpočet vzdálenosti v Kotlinu (zcela async / neblokující, žádné queryRenderedFeatures)
                    fun pickVehicle(latLng: MapLibreLatLng): Boolean {
                        // POZOR: toScreenLocation je JNI volání do nativní mapy a při každém vozidle blokovalo hlavní vlákno
                        // (soupeří s renderem, který každých 33 ms dostává nový GeoJSON) => ~1 s lagu po kliku.
                        // Projekci proto voláme jen 3x (klik + dva posuny) a zbytek počítáme lokální lineární aproximací.
                        val proj = m.projection
                        val eps = 0.001
                        val c0 = proj.toScreenLocation(latLng)
                        val cLat = proj.toScreenLocation(MapLibreLatLng(latLng.latitude + eps, latLng.longitude))
                        val cLng = proj.toScreenLocation(MapLibreLatLng(latLng.latitude, latLng.longitude + eps))
                        val pxPerLatX = (cLat.x - c0.x) / eps
                        val pxPerLatY = (cLat.y - c0.y) / eps
                        val pxPerLngX = (cLng.x - c0.x) / eps
                        val pxPerLngY = (cLng.y - c0.y) / eps
                        val radiusPx = 28f * density
                        val radiusSq = (radiusPx * radiusPx).toDouble()
                        var clickedVehicle: Vehicle? = null
                        var minSq = Double.MAX_VALUE
                        // vozy, jejichž ikona místo ťuknutí kryje (štítky přes sebe): nabídne se výběr
                        val overlapSq = (14f * density).let { (it * it).toDouble() }
                        val covering = ArrayList<Pair<Double, Vehicle>>()

                        for ((k, v) in latestVehicles) {
                            val pos = hub.positionOf(k) ?: LatLng(v.lat, v.lng)
                            val dLat = pos.latitude - latLng.latitude
                            val dLng = pos.longitude - latLng.longitude
                            val dx = dLat * pxPerLatX + dLng * pxPerLngX
                            val dy = dLat * pxPerLatY + dLng * pxPerLngY
                            val distSq = dx * dx + dy * dy
                            if (distSq <= radiusSq && distSq < minSq) {
                                minSq = distSq
                                clickedVehicle = v
                            }
                            if (distSq <= overlapSq) covering += distSq to v
                        }

                        if (covering.size >= 2) {
                            appState.vehicleChoices = covering.sortedBy { it.first }.map { it.second }.distinctBy { it.uid }
                            return true
                        }
                        if (clickedVehicle != null) {
                            appState.selectVehicle(clickedVehicle)
                            return true
                        }
                        return false
                    }

                    // OnMapClickListener v MapLibre chodí až z onSingleTapConfirmed, tj. po vypršení timeoutu double-tapu
                    // (~300-500 ms) => sheet se zdánlivě "nic nedělá". Tap proto vyhodnocujeme sami hned při zvednutí prstu.
                    // Listener vrací false, takže mapa dál zpracovává gesta normálně.
                    val tapSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    var downX = 0f
                    var downY = 0f
                    var downAt = 0L
                    var tapValid = false
                    mapView.setOnTouchListener { _, ev ->
                        when (ev.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                downX = ev.x; downY = ev.y
                                downAt = ev.eventTime
                                tapValid = true
                            }
                            android.view.MotionEvent.ACTION_POINTER_DOWN -> tapValid = false
                            android.view.MotionEvent.ACTION_MOVE -> {
                                if (tapValid && (kotlin.math.abs(ev.x - downX) > tapSlop || kotlin.math.abs(ev.y - downY) > tapSlop)) {
                                    tapValid = false
                                }
                            }
                            android.view.MotionEvent.ACTION_CANCEL -> tapValid = false
                            android.view.MotionEvent.ACTION_UP -> {
                                if (tapValid && ev.eventTime - downAt <= android.view.ViewConfiguration.getLongPressTimeout()) {
                                    val ll = m.projection.fromScreenLocation(android.graphics.PointF(ev.x, ev.y))
                                    if (!pickVehicle(ll) && m.cameraPosition.zoom >= STOP_MIN_ZOOM_GOOGLE - 1.0) {
                                        // klikací oblast přizpůsobená aktuální velikosti ikony zastávky
                                        val iconDp = stopIconDpForZoom(m.cameraPosition.zoom)
                                        val hitRadiusDp = iconDp / 2f + 4f
                                        val r = hitRadiusDp * density * m.projection.getMetersPerPixelAtLatitude(ll.latitude)
                                        val idx = latestStopIndex
                                        val dLat = r / 111_320.0
                                        val dLng = dLat / kotlin.math.cos(Math.toRadians(ll.latitude)).coerceAtLeast(0.01)
                                        (idx?.inView(ViewBox(ll.latitude - dLat, ll.longitude - dLng, ll.latitude + dLat, ll.longitude + dLng)) ?: emptyList())
                                            .map { it to Geo.distanceM(ll.latitude, ll.longitude, it.lat, it.lng) }
                                            .filter { it.second <= r }
                                            .minByOrNull { it.second }
                                            ?.let { onStopTap(it.first) }
                                    }
                                }
                                tapValid = false
                            }
                        }
                        false
                    }

                    map = m
                    onMapReady(m)
                    if (m.style?.isFullyLoaded == true) {
                        onStyleLoaded()
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
    // vozidla: GPU overlay přímo nad mapou (pod ovládacími prvky, které kreslí MapScreen nad tímhle Boxem)
    VehicleCanvasOverlay(overlayLayer, Modifier.fillMaxSize()) {
        camTick.longValue   // odběr pohybu kamery: překreslit při každém snímku pohybu
        map?.let { overlayCamera(it) }
    }
    }


    // ── ZASTÁVKY: všechny naráz ve zdroji mapy; MapLibre si je sám rozřeže do dlaždic (na pozadí), takže při posunu
    // mapy jsou hned na místě. Dřív jen výřez obrazovky, přepočítaný až po zastavení kamery (+ smyčka po 100 ms):
    // po rychlém posunu se zastávky na okrajích objevily až za ~1 s. Pod hranicí zoomu plynule zmizí (průhlednost).
    LaunchedEffect(map, styleVersion, stopIndex, stopIcons) {
        val m = map ?: return@LaunchedEffect
        val style = m.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val idx = stopIndex
        if (idx == null) {
            // zastávky vypnuté v nastavení: vrstva plynule zmizí, ťuknutí už nic nenajde
            syncStopVisibility(m, force = true)
            return@LaunchedEffect
        }
        // barvy motivu: při změně se obrázky přepíšou
        style.addImage(STOP_IMG_BUS, stopIcons.bus)
        style.addImage(STOP_IMG_RAIL, stopIcons.rail)
        val json = withContext(Dispatchers.Default) { stopsGeoJson(idx) }
        if (m.style !== style || !style.isFullyLoaded) return@LaunchedEffect
        val src = style.getSourceAs<GeoJsonSource>(STOPS_SRC)
        if (src != null) src.setGeoJson(json)
        else {
            style.addSource(GeoJsonSource(STOPS_SRC, json))
            // zastávky pod vozidly
            ensureRouteAnchor(style)
            style.addLayerBelow(
                SymbolLayer(STOPS_LAYER, STOPS_SRC).withProperties(
                    PropertyFactory.iconImage(Expression.get("img")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconOpacity(0f),
                    PropertyFactory.iconSize(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(13.5, STOP_ICON_MIN_DP / STOP_ICON_MAX_DP),
                            Expression.stop(17.0, 1.0f)
                        )
                    ),
                ).also {
                    it.iconOpacityTransition = TransitionOptions(STOP_FADE_MS, 0, false)
                    // Pod hranicí se vrstva vůbec nezpracovává: s průhledností 0 by MapLibre při oddálení dál
                    // rozmisťoval a kreslil desítky tisíc ikon (oddálená republika: render vlákno 98 % i v klidu).
                    // Rezerva pod hranicí, ať mizení při oddalování stihne doběhnout.
                    it.minZoom = (STOP_MIN_ZOOM_GOOGLE - 1.0 - STOP_LAYER_MIN_ZOOM_MARGIN).toFloat()
                },
                ROUTE_ANCHOR_LAYER
            )
        }
        // objevení / zmizení při přechodu přes hranici zoomu: prolnutí průhlednosti vrstvy (jako u vozidel)
        syncStopVisibility(m, force = true)
    }

    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
        if (m.style?.isFullyLoaded == true) {
            onStyleLoaded()
        }
    }

    // ── styl se nastavuje jen jednou a jen při změně tématu ──
    LaunchedEffect(useDarkMap, map, appState.mapProvider) {
        val m = map ?: return@LaunchedEffect
        val provider = appState.mapProvider
        val rasterMapy = provider == MapProvider.MAPYCZ && BuildConfig.MAPYCZ_API_KEY.isNotBlank()
        val key = "${if (rasterMapy) "mapy" else "carto"}:$useDarkMap"
        if (appliedStyleKey == key) {
            if (m.style?.isFullyLoaded == true) onStyleLoaded()
            return@LaunchedEffect
        }
        appliedStyleKey = key
        m.setStyle(mapStyleBuilder(provider, useDarkMap)) { style ->
            if (useDarkMap && !rasterMapy) softenDark(style)
            ensureRouteAnchor(style)
            styleVersion++
            onStyleLoaded()
        }
    }

    // ── modrý bod polohy ──
    LaunchedEffect(map, hasLocationPermission, styleVersion) {
        val m = map ?: return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        if (!hasLocationPermission || !style.isFullyLoaded) return@LaunchedEffect
        runCatching {
            m.locationComponent.apply {
                activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, style)
                        // rychlejší aktualizace modrého bodu (výchozí je ~1 s a nižší priorita)
                        .locationEngineRequest(
                            LocationEngineRequest.Builder(500L)
                                .setFastestInterval(250L)
                                .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                                .build()
                        )
                        .locationComponentOptions(
                            org.maplibre.android.location.LocationComponentOptions.builder(context)
                                .accuracyAnimationEnabled(false)
                                .build()
                        )
                        .build()
                )
                isLocationComponentEnabled = true
                cameraMode = CameraMode.NONE
                renderMode = RenderMode.COMPASS
                // Kompas posílá směr pořád a animace modrého bodu jinak běží plnou frekvencí displeje (~100 Hz)
                // => MapLibre překresloval celou mapu (v Praze tisíce štítků) i bez dotyku, render vlákno ~80 %
                // a zoom se sekal. Pár snímků za vteřinu na otáčení šipky stačí.
                setMaxAnimationFps(LOCATION_ANIMATION_FPS)
            }
        }
    }

    val primaryColorArgb = primaryColor.toArgb()
    val tertiaryArgb = tertiaryColor.toArgb()
    val arrowArgb = arrowColor.toArgb()
    val walkList = if (appState.journeyWalks.isNotEmpty()) appState.journeyWalks
    else listOfNotNull(appState.journeyWalk)
    val tripRoute = appState.tripRoute
    val tripRouteSegments = appState.tripRouteSegments
    val tripRouteSegmentStyles = appState.tripRouteSegmentStyles
    val fullRoute = appState.fullRoute
    val stopDots = appState.stopDots(showFullRoute)

    // ── TRASY (nezávisí na zoomu, šipky a tečky kreslí MapLibre nativně) ──
    // Jiná trasa: stará plynule zmizí, nová se plynule objeví. Změna jen vzhledu téže trasy (celá trasa, tečky
    // zastávek, barvy): výměna vrstev bez animace. Vše se připraví předem, vrstvy se vyměňují naráz (bez snímku,
    // ve kterém by trasa chyběla a bliknula).
    val drawnRoute = remember { DrawnRoute() }
    LaunchedEffect(
        map, tripRoute, tripRouteSegments, tripRouteSegmentStyles, fullRoute, walkList, showFullRoute, stopDots,
        primaryColorArgb, useDarkMap, tertiaryArgb, arrowArgb, styleVersion
    ) {
        val m = map ?: return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        ensureRouteAnchor(style) // kotva musí existovat dřív než trasy (vkládají se pod ni)
        val hadRoute = style.getLayer("route-line") != null
        val newRoute = tripRoute.size >= 2
        // jiná trasa (nebo žádná) než ta nakreslená: nejdřív plynule zmizí
        val different = !hadRoute || drawnRoute.route !== tripRoute
        if (hadRoute && different) {
            setRouteOpacity(style, 0f, ROUTE_FADE_OUT_MS.toLong())
            delay(ROUTE_FADE_OUT_MS.toLong() + 40)
        }
        if (!newRoute) {
            removeRouteLayers(style)
            drawnRoute.route = null
            return@LaunchedEffect
        }

        val segments = if (showFullRoute && fullRoute.size >= 2) listOf(fullRoute)
        else if (tripRouteSegments.isNotEmpty()) tripRouteSegments
        else listOf(tripRoute)
        // spojení s přestupem: každá jízda v barvě svého druhu dopravy (vlastnost "c" úseku)
        val segStyles = tripRouteSegmentStyles.takeIf { segments === tripRouteSegments && it.size == segments.size }
        val routeLine = if (showFullRoute && fullRoute.size >= 2) fullRoute else tripRoute
        val prep = withContext(Dispatchers.Default) {
            RoutePrep(
                arrow = routeArrowBitmap(arrowArgb),
                start = routeStartBitmap(context),
                end = routeEndBitmap(context),
                stop = if (stopDots.isNotEmpty()) routeStopBitmap(context, primaryColorArgb) else null,
                lines = segments.mapIndexedNotNull { i, seg ->
                    if (seg.size < 2) return@mapIndexedNotNull null
                    val argb = segStyles?.get(i)?.let { if (useDarkMap) it.routeDark else it.route } ?: primaryColorArgb
                    Feature.fromGeometry(seg.toLineString()).apply {
                        addStringProperty("c", String.format("#%06X", argb and 0xFFFFFF))
                    }
                },
                // pěší trasa: čárkovaná linka s kulatými konci = tečky, kreslí GPU
                walks = walkList.filter { it.points.size >= 2 }.map { w ->
                    Feature.fromGeometry(LineString.fromLngLats(w.points.map { Point.fromLngLat(it.second, it.first) }))
                },
                arrowLine = Feature.fromGeometry(tripRoute.toLineString()),
                stops = stopDots.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) },
                ends = listOf(
                    Feature.fromGeometry(Point.fromLngLat(routeLine.first().longitude, routeLine.first().latitude))
                        .also { it.addStringProperty("img", "route-start") },
                    Feature.fromGeometry(Point.fromLngLat(routeLine.last().longitude, routeLine.last().latitude))
                        .also { it.addStringProperty("img", "route-end") }
                ),
            )
        }
        if (m.style !== style || !style.isFullyLoaded) return@LaunchedEffect

        // ── výměna vrstev naráz (žádné čekání mezi odebráním a přidáním) ──
        removeRouteLayers(style)
        style.addImage("route-arrow", prep.arrow)
        style.addImage("route-start", prep.start)
        style.addImage("route-end", prep.end)
        if (prep.lines.isNotEmpty()) {
            style.addSource(GeoJsonSource("route-src", FeatureCollection.fromFeatures(prep.lines)))
            addBelowMarkers(
                style,
                LineLayer("route-line", "route-src").withProperties(
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("c"))),
                    PropertyFactory.lineWidth(12f / density),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }
        if (prep.walks.isNotEmpty()) {
            style.addSource(GeoJsonSource("walk-src", FeatureCollection.fromFeatures(prep.walks)))
            addBelowMarkers(
                style,
                LineLayer("walk-dots", "walk-src").withProperties(
                    PropertyFactory.lineColor(tertiaryArgb),
                    PropertyFactory.lineWidth(14f / density),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineDasharray(arrayOf(0.01f, 2f))
                )
            )
        }
        // šipky: symbol podél linie, rozestup i natočení řeší MapLibre
        style.addSource(GeoJsonSource("arrows-src", FeatureCollection.fromFeatures(listOf(prep.arrowLine))))
        addBelowMarkers(
            style,
            SymbolLayer("route-arrows", "arrows-src").withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.symbolSpacing(110f),
                PropertyFactory.iconImage("route-arrow"),
                PropertyFactory.iconRotate(90f), // bitmapa míří nahoru, linie doprava
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(8, 0.3f / density),
                        Expression.stop(9, 0.45f / density),
                        Expression.stop(11, 0.65f / density),
                        Expression.stop(13, 0.9f / density),
                        Expression.stop(15, 1.15f / density),
                        Expression.stop(17, 1.4f / density)
                    )
                )
            )
        )
        // mezilehlé zastávky: tečky na přesné poloze zastávky (pod začátkem / koncem)
        if (prep.stop != null && prep.stops.isNotEmpty()) {
            style.addImage("route-stop", prep.stop)
            style.addSource(GeoJsonSource("stops-src", FeatureCollection.fromFeatures(prep.stops)))
            addBelowMarkers(
                style,
                SymbolLayer("route-stops", "stops-src").withProperties(
                    PropertyFactory.iconImage("route-stop"),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true)
                )
            )
        }
        // začátek a konec (pod autobusy)
        style.addSource(GeoJsonSource("ends-src", FeatureCollection.fromFeatures(prep.ends)))
        addBelowMarkers(
            style,
            SymbolLayer("route-ends", "ends-src").withProperties(
                PropertyFactory.iconImage(Expression.get("img")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
        drawnRoute.route = tripRoute
        // nová (jiná) trasa se plynule objeví; změna vzhledu téže trasy bez animace
        if (different) {
            setRouteOpacity(style, 0f, 0)
            delay(32)   // jeden snímek s průhledností 0, jinak by přechod neměl odkud začít
            setRouteOpacity(style, 1f, ROUTE_FADE_IN_MS.toLong())
        }
    }

    // ── VOZIDLA: overlay nad mapou (viz VehicleCanvasOverlay / VehicleOverlayLoop), mapa o nich neví ──
    // Dřív GeoJSON zdroj: každá dávka poloh (až 30× za sekundu) = nové rozřezání zdroje na dlaždice a rozmístění
    // všech štítků v MapLibre (render + pracovní vlákna). Overlay je stejný jako u Google mapy (sprite batch na GPU).
    VehicleOverlayLoop(
        layer = overlayLayer, hub = hub, active = active, appState = appState,
        activeVehicles = activeVehicles, targetUid = targetUid, phase = phase,
        stopIndex = null,   // zastávky kreslí MapLibre (vlastní vrstva a ťuknutí)
        stopMinZoom = Float.MAX_VALUE,
        camera = { map?.let { overlayCamera(it) } },
        visibleBounds = {
            map?.let { m ->
                runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull()?.let { b ->
                    ViewBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
                }
            }
        },
    )

}


/** Kamera MapLibre pro overlay vozidel: zoom v měřítku Google (MapLibre má dlaždice 512 => o 1 menší zoom). */
private fun overlayCamera(m: MapLibreMap): OverlayCamera? {
    val cp = runCatching { m.cameraPosition }.getOrNull() ?: return null
    val t = cp.target ?: return null
    return OverlayCamera(t.latitude, t.longitude, (cp.zoom + 1.0).toFloat(), cp.bearing.toFloat())
}

private fun softenDark(style: Style) {
    style.layers.forEach { layer ->
        val id = layer.id
        when {
            layer is BackgroundLayer ->
                layer.setProperties(PropertyFactory.backgroundColor("#2a2f3a"))
            layer is FillLayer && id.contains("water") ->
                layer.setProperties(PropertyFactory.fillColor("#1d3144"))
            layer is FillLayer && (id.contains("landcover") || id.contains("landuse")) ->
                layer.setProperties(PropertyFactory.fillColor("#323845"))
            layer is FillLayer && id.contains("building") ->
                layer.setProperties(PropertyFactory.fillColor("#3a4150"))
            layer is LineLayer && id.contains("road") && !id.contains("casing") ->
                layer.setProperties(PropertyFactory.lineColor("#5b6475"))
        }
    }
}

internal fun routeArrowBitmap(color: Int): Bitmap {
    val size = 96
    val bitmap = createBitmap(size, size)
    bitmap.density = android.util.DisplayMetrics.DENSITY_DEFAULT
    val canvas = Canvas(bitmap)
    canvas.scale(size / 72f, size / 72f)

    val path = Path().apply {
        moveTo(13f, 43f)
        lineTo(36f, 15f)
        lineTo(59f, 43f)
    }
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 11f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    })
    return bitmap
}

private val ROUTE_LAYER_IDS = listOf("route-line", "walk-dots", "route-arrows", "route-stops", "route-ends")

/** Průhlednost vrstev trasy MapLibre s přechodem [ms] (plynulé objevení / zmizení). */
private fun setRouteOpacity(style: Style, a: Float, ms: Long) {
    val t = TransitionOptions(ms, 0)
    for (id in ROUTE_LAYER_IDS) {
        when (val l = style.getLayer(id)) {
            is LineLayer -> { l.lineOpacityTransition = t; l.setProperties(PropertyFactory.lineOpacity(a)) }
            is SymbolLayer -> { l.iconOpacityTransition = t; l.setProperties(PropertyFactory.iconOpacity(a)) }
        }
    }
}

private fun removeRouteLayers(style: Style) {
    listOf("route-ends", "route-stops", "route-arrows", "walk-dots", "route-line").forEach { style.removeLayer(it) }
    listOf("ends-src", "stops-src", "arrows-src", "walk-src", "route-src").forEach { style.removeSource(it) }
    listOf("route-arrow", "route-start", "route-end", "route-stop").forEach { style.removeImage(it) }
}

private fun List<LatLng>.toLineString() =
    LineString.fromLngLats(map { Point.fromLngLat(it.longitude, it.latitude) })

/** Stav viditelnosti vrstvy zastávek (null = ještě nenastaveno). */
private class StopVisibility { var shown: Boolean? = null }

/** Všechny zastávky jako GeoJSON (řetězec: MapLibre ho parsuje a řeže do dlaždic na pracovním vlákně). */
private fun stopsGeoJson(idx: StopIndex): String {
    val sb = StringBuilder(idx.stops.size * 110 + 64)
    sb.append("""{"type":"FeatureCollection","features":[""")
    for ((i, st) in idx.stops.withIndex()) {
        if (i > 0) sb.append(',')
        sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
            .append(st.lng).append(',').append(st.lat)
            .append("""]},"properties":{"img":"""")
            .append(if (st.kind == StopKind.RAIL) STOP_IMG_RAIL else STOP_IMG_BUS)
            .append("\"}}")
    }
    sb.append("]}")
    return sb.toString()
}

/** Trasa, jejíž vrstvy jsou na mapě (podle ní se pozná výměna za jinou trasu). */
private class DrawnRoute { var route: List<LatLng>? = null }

/** Vše pro vrstvy trasy, připravené na pozadí (vrstvy se pak vyměňují naráz). */
private class RoutePrep(
    val arrow: Bitmap, val start: Bitmap, val end: Bitmap, val stop: Bitmap?,
    val lines: List<Feature>, val walks: List<Feature>, val arrowLine: Feature, val stops: List<Feature>,
    val ends: List<Feature>,
)
