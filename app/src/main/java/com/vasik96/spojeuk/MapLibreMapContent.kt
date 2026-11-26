@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, com.google.maps.android.compose.MapsComposeExperimentalApi::class)

package com.vasik96.spojeuk

import com.vasik96.spojeuk.BuildConfig
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.awaitAll
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
import java.util.concurrent.atomic.AtomicBoolean

/** Mapy.cz (api.mapy.com) nemá vektorový styl ani tmavou variantu => rastrové dlaždice jako MapLibre style JSON. */
private fun mapyStyleJson(dark: Boolean): String {
    val tiles = "https://api.mapy.com/v1/maptiles/basic/256@2x/{z}/{x}/{y}?apikey=${BuildConfig.MAPYCZ_API_KEY}"
    // tmavý režim: inverze jasu + otočení odstínu zpět (aproximace tmavé mapy z rastru)
    val paint = if (dark)
        """{"raster-brightness-min":1,"raster-brightness-max":0,"raster-hue-rotate":180,"raster-saturation":-0.35,"raster-contrast":-0.1}"""
    else "{}"
    return """
        {"version":8,
         "sources":{"mapy":{"type":"raster","tiles":["$tiles"],"tileSize":256,"maxzoom":19,
            "attribution":"© Seznam.cz a.s. a další, © OpenStreetMap"}},
         "layers":[{"id":"mapy-layer","type":"raster","source":"mapy","paint":$paint}]}
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

internal const val VEH_SRC = "vehicles-src"
internal const val VEH_LAYER = "vehicles-layer"

// TextureView místo SurfaceView: řeší "BLASTBufferQueue ... Can't acquire next buffer"
// a rozbité vykreslování na Vivo/Funtouch v kombinaci s Compose AndroidView.
private val USE_TEXTURE_MODE = android.os.Build.MANUFACTURER.lowercase() in setOf("vivo", "iqoo")

// max. frekvence předávání pozic vozidel do MapLibre (~30 Hz), animace A -> B je počítaná z času
private const val VEHICLE_UPDATE_INTERVAL_MS = 33L
private const val VEHICLE_UPDATE_INTERVAL_MID_MS = 66L      // štítky, 300–800 vozidel v záběru
private const val VEHICLE_UPDATE_INTERVAL_MANY_MS = 100L    // štítky, víc než 800 vozidel (Praha)
// okraj kolem záběru, jehož vozidla jsou v GeoJSON (podíl rozměru záběru na každou stranu; 0,3 = 2,6× plocha).
// Větší = víc štítků k rozmístění při každé dávce, menší = při rychlém posunu víc vozů naskočí až další dávkou.
private const val VEH_VIEW_MARGIN = 0.3
private const val LOCATION_ANIMATION_FPS = 10              // animace modrého bodu (kompas) v MapLibre
// oddáleno (tečky): pohyb o pixel trvá dlouho, stačí řídké aktualizace
private const val DOT_UPDATE_INTERVAL_MS = 250L
private const val DOT_UPDATE_INTERVAL_FAR_MS = 500L
/** Tolerance při čekání na další snímek poloh (polovina snímku při 120 Hz). */
internal const val FRAME_SLACK_NS = 4_000_000L

/** Čas snímku (System.nanoTime z withFrameNanos) v časové ose SystemClock.elapsedRealtime (ms), jak ji čte pohyb vozidel. */
internal fun frameElapsedMs(frameNanos: Long): Long =
    (frameNanos + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())) / 1_000_000L

/** Prolnutí zastávek při objevení / zmizení (ms). */
internal const val STOP_FADE_MS = 250L

private const val STOPS_SRC = "map-stops-src"
private const val STOPS_LAYER = "map-stops"
private const val STOP_IMG_BUS = "map-stop-bus"
private const val STOP_IMG_RAIL = "map-stop-rail"


internal const val GOOGLE_VEHICLE_INTERVAL_MS = 33L     // Google: pohyb vozidel v overlayi (~30 Hz, jako MapLibre)
internal const val GOOGLE_DOT_INTERVAL_MS = 250L         // oddálené tečky (pod DOT_SMOOTH_ZOOM) se hýbou o zlomky pixelu
internal const val DOT_SMOOTH_ZOOM = 10.5f
internal const val LABEL_PREWARM_ZOOM = 1.0f            // o kolik úrovní před přepnutím na štítky se začnou kreslit
private const val MAX_NEW_IMAGES_PER_PUSH = 600   // Praha: ~300 různých štítků v záběru


// vrstvy trasy vždy POD vrstvou vozidel
private fun addBelowMarkers(style: Style, layer: Layer) {
    ensureVehicleLayer(style)
    style.addLayerBelow(layer, VEH_DOT_LAYER)
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
    val latestPhase by rememberUpdatedState(phase)
    val latestTarget by rememberUpdatedState(targetUid)

    val dirty = remember { AtomicBoolean(true) }
    // kamera se pohnula => přepočítat, která vozidla jsou ve viditelné oblasti
    val cameraDirty = remember { AtomicBoolean(true) }
    // zastávky: přepočet výřezu po zastavení kamery; ťuknutí hledá v tom, co je nakreslené
    val stopsDirty = remember { AtomicBoolean(true) }
    var shownStops by remember { mutableStateOf<List<MapStop>>(emptyList()) }
    // obrázky přidané do aktuálního stylu (při změně stylu se začíná od nuly)
    val addedImages = remember(styleVersion) { mutableSetOf<String>() }

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

                    m.addOnCameraMoveListener { cameraDirty.set(true) }
                    // stav kamery se ukládá až když se mapa zastaví, ne při každém framu pohybu
                    m.addOnCameraIdleListener {
                        cameraDirty.set(true)
                        stopsDirty.set(true)
                        val cp = m.cameraPosition
                        cp.target?.let {
                            appState.mapCenter = LatLng(it.latitude, it.longitude)
                            appState.mapZoom = cp.zoom.toFloat() + 1f
                        }
                    }

                    // klik na vozidlo: výpočet vzdálenosti v Kotlinu (zcela async / neblokující, žádné queryRenderedFeatures)
                    fun pickVehicle(latLng: MapLibreLatLng): Boolean {
                        if (m.style?.getLayer(VEH_LAYER) == null) return false
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
                                        shownStops
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
        modifier = modifier
    )

    // změna vozidel / zvýraznění => překreslit zdroj
    LaunchedEffect(activeVehicles, phase) { dirty.set(true) }

    // ── ZASTÁVKY: ikony v záběru (jen přiblížená mapa), přepočet po zastavení kamery ──
    LaunchedEffect(map, styleVersion, stopIndex, stopIcons) {
        val m = map ?: return@LaunchedEffect
        val idx = stopIndex
        if (idx == null) {
            // zastávky vypnuté v nastavení: vrstva plynule zmizí, ťuknutí už nic nenajde
            (m.style?.getLayer(STOPS_LAYER) as? SymbolLayer)?.setProperties(PropertyFactory.iconOpacity(0f))
            shownStops = emptyList()
            return@LaunchedEffect
        }
        stopsDirty.set(true)
        var iconsAdded = false
        var stopsShown: Boolean? = null
        while (true) {
            delay(100)
            val style = m.style
            if (style == null || !style.isFullyLoaded) continue
            if (!iconsAdded || style.getImage(STOP_IMG_BUS) == null) {
                // barvy motivu: při změně se obrázky přepíšou
                style.addImage(STOP_IMG_BUS, stopIcons.bus)
                style.addImage(STOP_IMG_RAIL, stopIcons.rail)
                iconsAdded = true
            }
            if (style.getSource(STOPS_SRC) == null) {
                style.addSource(GeoJsonSource(STOPS_SRC, FeatureCollection.fromFeatures(emptyList<Feature>())))
                // zastávky pod vozidly
                ensureVehicleLayer(style)
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
                    ).also { it.iconOpacityTransition = TransitionOptions(STOP_FADE_MS, 0, false) },
                    VEH_DOT_LAYER
                )
                stopsShown = null
                stopsDirty.set(true)
            }
            // objevení / zmizení při přechodu přes hranici zoomu: prolnutí průhlednosti vrstvy (jako u vozidel)
            val zoom = m.cameraPosition.zoom
            val show = zoom >= STOP_MIN_ZOOM_GOOGLE - 1.0
            if (show != stopsShown) {
                stopsShown = show
                (style.getLayer(STOPS_LAYER) as? SymbolLayer)?.setProperties(PropertyFactory.iconOpacity(if (show) 1f else 0f))
                if (show) stopsDirty.set(true)
            }
            // skryté zastávky zůstanou ve zdroji (doznívají), seznam se obnoví až po objevení
            if (!show || !stopsDirty.getAndSet(false)) continue
            val b = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull() ?: continue
            val list = withContext(Dispatchers.Default) {
                idx.inView(ViewBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast).expanded(0.1))
            }
            if (list == shownStops && list.isNotEmpty()) continue
            shownStops = list
            style.getSourceAs<GeoJsonSource>(STOPS_SRC)?.setGeoJson(
                FeatureCollection.fromFeatures(list.map { st ->
                    Feature.fromGeometry(Point.fromLngLat(st.lng, st.lat)).also { f ->
                        f.addStringProperty("img", if (st.kind == StopKind.RAIL) STOP_IMG_RAIL else STOP_IMG_BUS)
                    }
                })
            )
        }
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
            ensureVehicleLayer(style)
            dirty.set(true)
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
    val fullRoute = appState.fullRoute
    val stopDots = appState.stopDots(showFullRoute)

    // ── TRASY (nezávisí na zoomu, šipky a tečky kreslí MapLibre nativně) ──
    LaunchedEffect(
        map, tripRoute, tripRouteSegments, fullRoute, walkList, showFullRoute, stopDots,
        primaryColorArgb, tertiaryArgb, arrowArgb, styleVersion
    ) {
        val m = map ?: return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        ensureVehicleLayer(style) // vrstva vozidel musí existovat dřív než trasy a být nad nimi
        val hadRoute = style.getLayer("route-line") != null
        // zrušená trasa nejdřív plynule zmizí, teprve pak se vrstvy odeberou
        if (tripRoute.size < 2 && hadRoute) {
            setRouteOpacity(style, 0f, ROUTE_FADE_OUT_MS.toLong())
            delay(ROUTE_FADE_OUT_MS.toLong() + 40)
        }
        removeRouteLayers(style)

        if (tripRoute.size >= 2) {
            val routeBitmap = withContext(Dispatchers.Default) {
                Triple(
                    routeArrowBitmap(arrowArgb),
                    routeStartBitmap(context),
                    routeEndBitmap(context)
                )
            }
            style.addImage("route-arrow", routeBitmap.first)
            style.addImage("route-start", routeBitmap.second)
            style.addImage("route-end", routeBitmap.third)

            val segments = if (showFullRoute && fullRoute.size >= 2) listOf(fullRoute)
            else if (tripRouteSegments.isNotEmpty()) tripRouteSegments
            else listOf(tripRoute)

            val routeFeatures = withContext(Dispatchers.Default) {
                segments.mapNotNull { seg ->
                    if (seg.size >= 2) Feature.fromGeometry(seg.toLineString()) else null
                }
            }

            if (routeFeatures.isNotEmpty()) {
                style.addSource(
                    GeoJsonSource(
                        "route-src",
                        FeatureCollection.fromFeatures(routeFeatures)
                    )
                )
                addBelowMarkers(
                    style,
                    LineLayer("route-line", "route-src").withProperties(
                        PropertyFactory.lineColor(primaryColorArgb),
                        PropertyFactory.lineWidth(12f / density),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )
            }

            // pěší trasa: čárkovaná linka s kulatými konci = tečky, kreslí GPU
            val walkLines = withContext(Dispatchers.Default) {
                walkList.filter { it.points.size >= 2 }.map { w ->
                    Feature.fromGeometry(
                        LineString.fromLngLats(w.points.map { Point.fromLngLat(it.second, it.first) })
                    )
                }
            }
            if (walkLines.isNotEmpty()) {
                style.addSource(
                    GeoJsonSource("walk-src", FeatureCollection.fromFeatures(walkLines))
                )
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
            style.addSource(
                GeoJsonSource(
                    "arrows-src",
                    FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(tripRoute.toLineString())))
                )
            )
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
            if (stopDots.isNotEmpty()) {
                style.addImage("route-stop", routeStopBitmap(context, primaryColorArgb))
                val stopFeatures = withContext(Dispatchers.Default) {
                    FeatureCollection.fromFeatures(
                        stopDots.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
                    )
                }
                style.addSource(GeoJsonSource("stops-src", stopFeatures))
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
            val routeLine = if (showFullRoute && fullRoute.size >= 2) fullRoute else tripRoute
            val s = routeLine.first()
            val e = routeLine.last()
            val ends = withContext(Dispatchers.Default) {
                FeatureCollection.fromFeatures(
                    listOf(
                        Feature.fromGeometry(Point.fromLngLat(s.longitude, s.latitude))
                            .also { it.addStringProperty("img", "route-start") },
                        Feature.fromGeometry(Point.fromLngLat(e.longitude, e.latitude))
                            .also { it.addStringProperty("img", "route-end") }
                    )
                )
            }
            style.addSource(GeoJsonSource("ends-src", ends))
            addBelowMarkers(
                style,
                SymbolLayer("route-ends", "ends-src").withProperties(
                    PropertyFactory.iconImage(Expression.get("img")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true)
                )
            )
            // nová trasa se plynule objeví (výměna trasy za jinou bez animace, ať nebliká)
            if (!hadRoute) {
                setRouteOpacity(style, 0f, 0)
                delay(32)   // jeden snímek s průhledností 0, jinak by přechod neměl odkud začít
                setRouteOpacity(style, 1f, ROUTE_FADE_IN_MS.toLong())
            }
        }
    }

    // ── VOZIDLA: jedna smyčka synchronizovaná s vykreslováním, nikdy se nezastavuje (ani při pohybu kamery) ──
    LaunchedEffect(map, styleVersion, active) {
        val m = map ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect

        var draws: List<VehicleDraw> = emptyList()
        var lastPush = 0L
        var view: ViewBox? = null    // oblast, jejíž vozidla jdou do GeoJSON (záběr + okraj)
        var viewAt = 0L
        var dotMode = false          // oddáleno: kreslí se jen tečky (vrstva přepíná GPU, tady jen šetříme práci)
        var needImages = true        // štítky se budou kreslit (nebo jsou těsně před tím)
        var interval = VEHICLE_UPDATE_INTERVAL_MS
        var pending = false          // některému vozidlu ještě chybí obrázek štítku
        val fade = VehicleFade()     // objevení po otevření mapy a prolínání tečky ↔ štítky
        var modeKnown = false
        var alphaStyle: Style? = null
        var lastDotA = -1f; var lastLabelA = -1f; var lastHlA = -1f
        var zoomNow = 11.0           // zoom a azimut z posledního čtení kamery (pro přepočet posunu ikony na pixely)
        var bearingNow = 0.0
        // naposledy zobrazený štítek každého vozu: při změně obrázku (zpoždění tramvaje / metra zapne / vypne červený
        // okraj, zvýraznění) se do vygenerování nového ukazuje starý, jinak vůz na chvíli zmizí
        val shownImg = HashMap<String, String>()
        // Po přepnutí na štítky musí MapLibre nové štítky nejdřív rozmístit (v Praze přes tisíc, pracovní vlákna);
        // prolínání do té doby počká, jinak by proběhlo dřív, než jsou štítky vidět (zpožděné / přeskočené snímky).
        // 0 = nic, 1 = čeká se na zpracování dat zdroje, 2 = na první plně vykreslený snímek (štítky rozmístěné)
        val labelsLoading = java.util.concurrent.atomic.AtomicInteger(0)
        val sourceListener = MapLibreMapView.OnSourceChangedListener { id ->
            if (id == VEH_SRC) labelsLoading.compareAndSet(1, 2)
        }
        val frameListener = MapLibreMapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            if (fully) labelsLoading.compareAndSet(2, 0)
        }
        mapView.addOnSourceChangedListener(sourceListener)
        mapView.addOnDidFinishRenderingFrameListener(frameListener)
        var lastCount = 0            // počet vozidel v posledním GeoJSON (řídí interval)

        fun currentView(): ViewBox? = runCatching {
            val w = mapView.width.toFloat()
            val h = mapView.height.toFloat()
            if (w <= 0f || h <= 0f) null
            else {
                val proj = m.projection
                val corners = listOf(
                    proj.fromScreenLocation(android.graphics.PointF(0f, 0f)),
                    proj.fromScreenLocation(android.graphics.PointF(w, 0f)),
                    proj.fromScreenLocation(android.graphics.PointF(0f, h)),
                    proj.fromScreenLocation(android.graphics.PointF(w, h))
                )
                ViewBox(
                    corners.minOf { it.latitude }, corners.minOf { it.longitude },
                    corners.maxOf { it.latitude }, corners.maxOf { it.longitude }
                )
            }
        }.getOrNull()

        try {
            while (true) {
                val ft = withFrameNanos { it }
                // čas snímku, ne okamžik probuzení: polohy v GeoJSON odpovídají pravidelným okamžikům
                val now = frameElapsedMs(ft)

                // průhlednost vrstev každý snímek (jen při změně), i mimo interval aktualizace poloh
                fade.tick(now, pending || labelsLoading.get() != 0)
                m.style?.takeIf { it.isFullyLoaded && it.getLayer(VEH_LAYER) != null }?.let { st ->
                    val ap = fade.appearAlpha()
                    val da = ap * fade.dotAlpha()
                    val la = ap * fade.labelAlpha()
                    if (st !== alphaStyle) { lastDotA = Float.NaN; lastLabelA = Float.NaN; lastHlA = Float.NaN }
                    if (da != lastDotA) applyLayerAlpha(st, VEH_DOT_LAYER, da)
                    if (la != lastLabelA) {
                        for (g in 0 until Z_LAYERS) {
                            applyLayerAlpha(st, freshLayerId(g), la)
                            applyLayerAlpha(st, oldLayerId(g), la)
                        }
                    }
                    if (ap != lastHlA) applyLayerAlpha(st, VEH_HL_LAYER, ap)
                    alphaStyle = st; lastDotA = da; lastLabelA = la; lastHlA = ap
                }

                if (now - appState.vehicleSelectedAtMs < SHEET_OPEN_GRACE_MS) continue
                // tolerance půl snímku: jinak se (33 ms vs. 33,3 ms snímku) občas přeskočí snímek a rytmus poskakuje
                if (now - lastPush < interval - FRAME_SLACK_NS / 1_000_000L) continue

                val camChanged = cameraDirty.getAndSet(false)
                var modeFlipped = false
                // viditelná oblast a zoom (JNI volání) jen při pohybu kamery, jinak max. 1x za vteřinu
                if (camChanged || view == null || now - viewAt >= 1000L) {
                    view = currentView()?.expanded(VEH_VIEW_MARGIN)
                    viewAt = now
                    runCatching { m.cameraPosition }.getOrNull()?.let { cp -> zoomNow = cp.zoom; bearingNow = cp.bearing; cp.zoom }?.let { z ->
                        val nowDot = z < DOT_ZOOM_ML
                        val nowImages = z >= DOT_ZOOM_ML - 0.5     // obrázky se chystají o půl úrovně dřív, ať nic neprobliká
                        if (nowImages && !needImages) modeFlipped = true
                        dotMode = nowDot
                        needImages = nowImages
                        if (!modeKnown || !fade.appeared) fade.initDotMode(nowDot)
                        else if (fade.setDotMode(nowDot)) { modeFlipped = true; labelsLoading.set(0) }
                        modeKnown = true
                        interval = when {
                            // štítky: každý GeoJSON = nové rozřezání zdroje a rozmístění všech štítků v MapLibre
                            // (render + 4 pracovní vlákna); v Praze přes tisíc vozidel => 30 Hz vytížilo GPU vlákno na 90 %
                            !nowDot && lastCount > 800 -> VEHICLE_UPDATE_INTERVAL_MANY_MS
                            !nowDot && lastCount > 300 -> VEHICLE_UPDATE_INTERVAL_MID_MS
                            !nowDot -> VEHICLE_UPDATE_INTERVAL_MS
                            z >= 10.0 -> DOT_UPDATE_INTERVAL_MS
                            else -> DOT_UPDATE_INTERVAL_FAR_MS
                        }
                    }
                }

                hub.ensureRoutes(view, enabled = !dotMode)
                val moved = hub.tick(now, view)
                val metaChanged = dirty.getAndSet(false) || modeFlipped
                // Polohy se posílají i během gesta (zoom, posun): posun ikony od kotvy je v pixelech pro zoom poslední
                // dávky, takže bez nových dávek by vozy při zoomu „ujížděly“, stály a po puštění prstu skočily na místo.
                if (!moved && !metaChanged && !camChanged && !pending) continue

                val style = m.style
                if (style == null || !style.isFullyLoaded) {
                    dirty.set(true); continue
                }
                ensureVehicleLayer(style)

                if (metaChanged || draws.isEmpty()) {
                    val tgt = latestTarget
                    val ph = latestPhase

                    val list = ArrayList<VehicleDraw>(latestVehicles.size)

                    for ((k, v) in latestVehicles) {
                        list += VehicleDraw(
                            k,
                            v,
                            if (tgt != null && v.uid == tgt) ph else 0
                        )
                    }

                    // Stabilní pořadí features (= pořadí kreslení uvnitř vrstvy): podle klíče vozidla.
                    // Vánoční vozy se od běžných oddělují vrstvami, ne řazením.
                    list.sortBy { it.key }

                    draws = list
                    val live = HashSet<String>(list.size * 2)
                    for (d in list) live += d.key
                    shownImg.keys.retainAll(live)
                }

                // obrázky štítků jen pro vozidla ve viditelné oblasti a jen když se štítky kreslí
                // (zvýrazněné vozidlo je štítek vždy).
                val missing = LinkedHashMap<String, VehicleDraw>()
                for (d in draws) {
                    if (!needImages && d.iconPhase == 0) continue
                    if (d.imgId in addedImages || d.imgId in missing) continue
                    val p = hub.positionOf(d.key) ?: continue
                    if (view != null && !view.contains(p.latitude, p.longitude)) continue
                    missing[d.imgId] = d
                    if (missing.size >= MAX_NEW_IMAGES_PER_PUSH) break
                }
                if (missing.isNotEmpty()) {
                    // paralelně na všech jádrech a do MapLibre jedním voláním (každé addImage zvlášť = JNI
                    // a nové rozmístění štítků, které na obrázek čekají)
                    val dpi = context.resources.displayMetrics.densityDpi
                    val newBitmaps = withContext(Dispatchers.Default) {
                        missing.entries.map { (imgId, d) ->
                            async {
                                val bmp = markerBitmap(context, d.v.line, d.style, d.iconPhase, d.late, d.v.christmas)
                                    .copy(Bitmap.Config.ARGB_8888, false).apply { setDensity(dpi) }
                                imgId to bmp
                            }
                        }.awaitAll()
                    }
                    val batch = HashMap<String, Bitmap>(newBitmaps.size * 2)
                    for ((imgId, bmp) in newBitmaps) if (addedImages.add(imgId)) batch[imgId] = bmp
                    if (batch.isNotEmpty()) style.addImages(batch)
                }
                pending = false

                // polohy se čtou na hlavním vlákně (MotionHub není thread-safe), samotný GeoJSON se staví na pozadí
                val nDraws = draws.size
                val lats = DoubleArray(nDraws)
                val lngs = DoubleArray(nDraws)
                for (i in 0 until nDraws) {
                    val p = hub.positionOf(draws[i].key)
                    if (p == null) { lats[i] = Double.NaN } else { lats[i] = p.latitude; lngs[i] = p.longitude }
                }
                val labelsOn = !fade.dotMode || fade.switching()
                val drawsSnap = draws
                val zoomSnap = zoomNow
                val bearingSnap = bearingNow
                val built = withContext(Dispatchers.Default) {
                    buildVehicleJson(drawsSnap, lats, lngs, view, labelsOn, addedImages, shownImg, zoomSnap, bearingSnap)
                }
                if (built.pending) pending = true

                // první dávka se štítky po přepnutí: prolínání počká, až je MapLibre zpracuje (viz labelsLoading)
                if (modeFlipped && !fade.dotMode && fade.switching()) labelsLoading.set(1)
                style.getSourceAs<GeoJsonSource>(VEH_SRC)?.setGeoJson(built.json)
                if (built.count > 0) fade.startAppear()
                lastCount = built.count
                lastPush = now
            }
        } finally {
            mapView.removeOnSourceChangedListener(sourceListener)
            mapView.removeOnDidFinishRenderingFrameListener(frameListener)
        }
    }
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
