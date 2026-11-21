@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.Stable
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Tram
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.vasik96.spojeuk.BuildConfig

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.cos
import kotlin.math.pow
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition as MapLibreCameraPosition
import org.maplibre.android.camera.CameraUpdateFactory as MapLibreCameraUpdateFactory
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
import org.maplibre.android.style.layers.CircleLayer
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
import kotlin.time.Duration.Companion.milliseconds


// Ústecký kraj + trochu Prahy a okolních krajů (filtr se používá jen pro Správu železnic)
private val USTECKY_LAT = 49.90..51.10
private val USTECKY_LNG = 12.80..14.80

private fun Vehicle.inUsteckyKraj() = lat in USTECKY_LAT && lng in USTECKY_LNG

// Při oddálení (pod touto úrovní, v měřítku Google Maps) se místo štítků s číslem linky kreslí malé tečky
// (tisíce štítků přes sebe jsou nečitelné; u MapLibre navíc tisíce ikon a GeoJSON prvků při každém snímku).
internal const val DOT_ZOOM_GOOGLE = 10.8f
internal const val DOT_HYSTERESIS = 0.15f             // jen Google: proti blikání na hranici
private const val DOT_ZOOM_ML = 11f                   // MapLibre (má zoom o 1 menší, tj. Google 12); stop výrazu potřebuje celé číslo

/** Jak dlouho po kliku na vozidlo se pozastaví pohyb vozidel na mapě (otevírání bottom sheetu). */
private const val SHEET_OPEN_GRACE_MS = 900L

@SuppressLint("MissingPermission")
@Composable
internal fun MapScreen(modifier: Modifier = Modifier, appState: SpojeAppState, api: TransitRepository, active: Boolean) {
    val context = LocalContext.current
    // barvy zvýraznění hledaného vozu podle Material You motivu
    val cs = MaterialTheme.colorScheme
    SideEffect {
        HighlightPalette.update(cs.primary.toArgb(), cs.onPrimary.toArgb(), cs.tertiary.toArgb(), cs.onTertiary.toArgb(), cs.surface.toArgb())
    }
    val scope = rememberCoroutineScope()
    var showFullRoute by remember { mutableStateOf(false) }
    var isMapReady by remember { mutableStateOf(false) }
    // Google: všechny dlaždice načtené (onMapLoaded). Zatím nic nezdržuje, jen se podle toho zapíná modrá tečka polohy.
    var googleTilesLoaded by remember { mutableStateOf(false) }
    val googleLayer = remember(context) { GoogleVehicleLayer(context) }

    // Mapa se zobrazí hned (dřív tu byla 140ms pauza s "Načítání mapy…"). Vytváří se předem na pozadí (viz SpojeApp, mapVisited),
    // takže po přepnutí na záložku už jen odkryje hotovou mapu.

    LaunchedEffect(appState.mapProvider) {
        isMapReady = false
        googleTilesLoaded = false
    }

    // polling poloh
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // Pokud již máme vozidla z paměti/cache repozitáře, ihned je zobrazit, ať banner neukazuje spinner
        val curr = api.currentVehicles()
        if (curr.isNotEmpty()) {
            appState.updateVehicles(curr)
            appState.heldCount = api.heldCount()
            appState.vehicleSource = api.sourceLabel()
            appState.vehiclesLoaded = true
        } else if (appState.vehicles.isNotEmpty()) {
            appState.vehiclesLoaded = true
        } else {
            appState.vehiclesLoaded = false
        }
        while (true) {
            runCatching { api.vehiclePositions(maxAgeMs = 4_000) }
                .onSuccess {
                    appState.updateVehicles(it)
                    appState.heldCount = api.heldCount()
                    appState.vehicleSource = api.sourceLabel()
                    appState.mapError = if (api.vehicleFetchFailing()) tr(R.string.map_positions_failed) else null
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    appState.mapError = tr(R.string.map_positions_failed)
                }
            appState.vehiclesLoaded = true
            // při výpadku zkoušet častěji, ať se polohy vrátí hned, jak server / síť zase jede
            delay((if (api.vehicleFetchFailing()) 2_500 else 6_000).milliseconds)
        }
    }

    // Aktualizace poloh ihned, jakmile dorazí nové z hubu (na pozadí z launch prewarmu nebo refreshu)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        api.vehicleUpdates.collect {
            val curr = api.currentVehicles()
            appState.updateVehicles(curr)
            appState.heldCount = api.heldCount()
            appState.vehicleSource = api.sourceLabel()
            appState.mapError = null
            appState.vehiclesLoaded = true
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(appState.mapCenter, appState.mapZoom)
    }
    DisposableEffect(Unit) {
        onDispose {
            if (appState.mapProvider == MapProvider.GOOGLE) {
                appState.mapCenter = cameraPositionState.position.target
                appState.mapZoom = cameraPositionState.position.zoom
            }
        }
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { p ->
        hasLocationPermission = p[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                p[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // zvýraznění markeru
    LaunchedEffect(appState.highlightedTrip, appState.vehiclesLoaded, appState.routeLoading) {
        if (appState.highlightedTrip == null) { appState.pulsePhase = 0; return@LaunchedEffect }
        if (!appState.vehiclesLoaded || appState.routeLoading) return@LaunchedEffect
        try {
            repeat(6) { i -> appState.pulsePhase = if (i % 2 == 0) 1 else 2; delay(400.milliseconds) }
        } finally {
            appState.pulsePhase = 0   // i při zrušení efektu (rychlé další kliknutí)
        }
        appState.highlightedTrip = null
    }

    var maplibreMapRef by remember { mutableStateOf<MapLibreMap?>(null) }

    // spodní okraj karty "Mapa vozidel" nahoře (px v rámci mapy): co je pod ní, není vidět
    var bannerBottomPx by remember { mutableFloatStateOf(0f) }
    var mapSizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    /**
     * Cíl kamery posunutý tak, aby [p] při zoomu [zoom] vyšel do středu viditelné části mapy pod kartou nahoře
     * (ne do středu celé mapy, kde by byl napůl schovaný). Metry na dp: dlaždice 256 dp.
     */
    fun belowBanner(p: LatLng, zoom: Float): LatLng {
        val shiftDp = bannerBottomPx / 2.0 / context.resources.displayMetrics.density
        val mPerDp = 156543.03 * Math.cos(Math.toRadians(p.latitude)) / Math.pow(2.0, zoom.toDouble())
        return LatLng(p.latitude + shiftDp * mPerDp / Geo.M_PER_DEG_LAT, p.longitude)
    }

    val animateCameraTo: suspend (LatLng, Float) -> Unit = { target, targetZoom ->
        val targetLatLng = belowBanner(target, targetZoom)
        if (appState.mapProvider == MapProvider.GOOGLE) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(targetLatLng, targetZoom), 700
            )
        } else {
            maplibreMapRef?.animateCamera(
                MapLibreCameraUpdateFactory.newLatLngZoom(
                    MapLibreLatLng(targetLatLng.latitude, targetLatLng.longitude),
                    targetZoom.toDouble() - 1.0
                ),
                700
            )
        }
    }

    /**
     * Výřez, ve kterém jsou vidět všechny [points], ve viditelné části mapy pod kartou nahoře. Zoom se spočítá
     * přímo (Mercator, dlaždice 256 dp) pro plochu pod kartou s okrajem; posun středu pod kartu dělá [animateCameraTo].
     */
    val animateCameraToBounds: suspend (List<LatLng>) -> Unit = { points ->
        val w = mapSizePx.width
        val h = mapSizePx.height
        if (points.size >= 2 && w > 0 && h > 0) {
            val density = context.resources.displayMetrics.density
            val padDp = 48.0
            val wDp = (w / density - 2 * padDp).coerceAtLeast(64.0)
            val hDp = ((h - bannerBottomPx) / density - 2 * padDp).coerceAtLeast(64.0)
            fun mercY(lat: Double) = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2))
            val minLat = points.minOf { it.latitude }; val maxLat = points.maxOf { it.latitude }
            val minLng = points.minOf { it.longitude }; val maxLng = points.maxOf { it.longitude }
            val xFrac = ((maxLng - minLng) / 360.0).coerceAtLeast(1e-9)
            val yFrac = ((mercY(maxLat) - mercY(minLat)) / (2 * Math.PI)).coerceAtLeast(1e-9)
            val zoom = minOf(
                Math.log(wDp / (256 * xFrac)) / Math.log(2.0),
                Math.log(hDp / (256 * yFrac)) / Math.log(2.0)
            ).coerceIn(3.0, 17.0).toFloat()
            val midLat = Math.toDegrees(Math.atan(Math.sinh((mercY(minLat) + mercY(maxLat)) / 2)))
            animateCameraTo(LatLng(midLat, (minLng + maxLng) / 2), zoom)
        }
    }

    // zastávka otevřená z hledání ("Na mapě" u nejbližší zastávky): mapa na ni přiblíží
    LaunchedEffect(appState.focusPoint, isMapReady, maplibreMapRef) {
        val p = appState.focusPoint ?: return@LaunchedEffect
        if (appState.mapProvider == MapProvider.GOOGLE && !isMapReady) return@LaunchedEffect
        if (appState.mapProvider != MapProvider.GOOGLE && maplibreMapRef == null) return@LaunchedEffect
        // Panel s odjezdy zakryje spodní ~64 % mapy: zastávka má vyjít doprostřed pruhu mezi kartou nahoře
        // a panelem. animateCameraTo ji dá doprostřed plochy pod kartou, odsud se posune o rozdíl výš.
        val h = mapSizePx.height.toFloat().takeIf { it > 0f } ?: (context.resources.displayMetrics.heightPixels * 0.76f)
        val sheetTop = h * STOP_SHEET_TOP_FRACTION
        val shiftPx = (bannerBottomPx + h) / 2f - (bannerBottomPx + sheetTop) / 2f
        val mPerDp = 156543.03 * Math.cos(Math.toRadians(p.latitude)) / Math.pow(2.0, 17.0)
        val shiftM = shiftPx / context.resources.displayMetrics.density * mPerDp
        animateCameraTo(LatLng(p.latitude - shiftM / Geo.M_PER_DEG_LAT, p.longitude), 17f)
        appState.focusPoint = null
    }

    // přiblížení po kliknutí na "Mapa"
    LaunchedEffect(appState.focusTrip, appState.vehiclesLoaded, appState.routeLoading) {
        val ref = appState.focusTrip ?: return@LaunchedEffect

        if (!appState.vehiclesLoaded || appState.routeLoading) return@LaunchedEffect

        // čistý začátek pro nové kliknutí – zahodí zbytky po předchozím
        appState.pulsePhase = 0
        appState.focusedTripVehicle = null

        val route = appState.tripRoute
        val searchRoute = appState.fullRoute.ifEmpty { route }
        val match = findVehicle(appState.vehicles, ref, searchRoute)
        val v = match?.vehicle
        if (v != null) {
            appState.focusedTripVehicle = v
            appState.focusedTripRef = ref
            if (match.approximate) {
                android.widget.Toast.makeText(
                    context,
                    tr(R.string.map_trip_id_not_found, ref.line),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            // vůz na trase (jede ji) => přiblížit na něj; ještě k trase přijíždí (spoj nevyjel) => výřez
            // s trasou i vozem, jinak by trasa nebyla vidět a vůz by se ukazoval někde mimo
            val onRoute = route.isEmpty() || distanceToRouteM(LatLng(v.lat, v.lng), route) < VEHICLE_ON_ROUTE_M
            if (onRoute) animateCameraTo(LatLng(v.lat, v.lng), 16f)
            else animateCameraToBounds(route + LatLng(v.lat, v.lng))
        } else {
            appState.focusedTripVehicle = null
            appState.focusedTripRef = null
            appState.highlightedTrip = null
            appState.pulsePhase = 0
            val notYetDeparted = ref.departureTimeMillis?.let { it > System.currentTimeMillis() } == true
            val msg = when {
                appState.mapError != null -> tr(R.string.map_positions_failed)
                isMetroLine(ref.line) -> tr(R.string.map_metro_no_live)
                notYetDeparted -> tr(R.string.map_not_departed)
                else -> tr(R.string.map_vehicle_not_found)
            }
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            // vůz nenalezen: celá trasa ve viditelné části pod kartou
            if (route.size >= 2) animateCameraToBounds(route)
        }
        appState.focusTrip = null
    }

    val phase = appState.pulsePhase
    val allVehicles = appState.vehicles

    val locked: Vehicle? = appState.focusedTripVehicle

    // Sledování běží, dokud existuje ref spoje NEBO se právě něco zvýrazňuje
    val strictMode = appState.focusedTripRef != null
            || appState.highlightedTrip != null
            || appState.focusTrip != null
            || phase != 0

    // uid zvýrazněného vozidla se čte JEN když se opravdu něco zvýrazňuje (phase != 0).
    // Jinak by každý klik na vozidlo (a každé rematch při aktualizaci dat) rekomponoval celou mapu
    // se všemi markery => ~1 s zpoždění, než se vůbec objeví bottom sheet.
    val targetUid: String? = if (phase != 0) (locked ?: appState.selectedVehicle)?.uid else null

    val keyer = remember { VehicleKeyer() }
    val routes = remember(api) { VehicleRoutes(api) }
    val keyed by produceState(emptyList<Pair<String, Vehicle>>(), allVehicles) {
        value = withContext(Dispatchers.Default) { keyer.assign(allVehicles) }
    }

    val hideOthers = appState.hideOtherVehiclesOnMap
    val mapCats = appState.mapCategories
    val allCats = mapCats.size == MapCategory.entries.size
    val activeVehicles: List<Pair<String, Vehicle>> = remember(
        keyed, strictMode, hideOthers, locked, mapCats
    ) {
        if (strictMode && hideOthers && locked != null) {
            val match = keyed.firstOrNull { it.second.uid == locked.uid }
            listOf(match ?: ("locked_${locked.uid}" to locked))
        } else {
            keyed.filter { (_, x) ->
                (allCats || mapCategory(x) in mapCats) && (
                        x.source != SourceIds.TRAIN ||
                                x.inUsteckyKraj()
                        )
            }
        }
    }

    // ── zastávky (index se postaví, jakmile je načtený jízdní řád) ──
    val loadedStops by produceState<StopIndex?>(null, api) {
        while (value == null) {
            value = runCatching { api.mapStops() }.getOrNull()
            if (value == null) delay(3_000)
        }
    }
    // zastávky jdou vypnout v nastavení: bez indexu je obě mapy plynule skryjí a ťuknutí na ně nic neotevře
    val stopIndex = loadedStops.takeIf { appState.showMapStops }

    val stopIcons = rememberStopIcons()
    androidx.compose.runtime.SideEffect { googleLayer.stopIcons = stopIcons }

    // ── pohyb všech vozidel na jednom místě ──
    val hub = remember(routes, scope) { MotionHub(routes, scope) }
    DisposableEffect(hub) { onDispose { hub.clear() } }
    val predict = appState.predictVehiclePositions
    LaunchedEffect(activeVehicles, predict) { hub.update(activeVehicles, predict) }

    val systemDark = isSystemInDarkTheme()
    val appDark = when (appState.appTheme) {
        AppTheme.SYSTEM -> systemDark
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    val useDarkMap = when (appState.mapTheme) {
        MapTheme.APP -> appDark
        MapTheme.LIGHT -> false
        MapTheme.DARK -> true
    }

    val scheme = if (useDarkMap) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT

    Box(modifier = modifier.fillMaxSize()) {
        if (appState.mapProvider == MapProvider.GOOGLE) {
            val shownRoute = rememberShownRoute(appState, showFullRoute)
            GoogleMap(
                // Okamžitý výběr vozidla při zvednutí prstu. Nativní OnMarkerClickListener čeká ~300 ms, jestli nejde
                // o dvojklik (zoom), proto se bottom sheet otevíral se zpožděním. Událost se jen sleduje (Initial pass,
                // nic se nespotřebuje), takže mapa dostane všechna gesta beze změny.
                modifier = Modifier.fillMaxSize().pointerInput(googleLayer) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val start = down.position
                        val downAt = down.uptimeMillis
                        val slop = viewConfiguration.touchSlop
                        var upAt = -1L
                        var upPos = start
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } > 1) return@awaitEachGesture   // pinch
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if ((ch.position - start).getDistance() > slop) return@awaitEachGesture   // tažení
                            if (ch.changedToUp()) { upAt = ch.uptimeMillis; upPos = ch.position; break }
                        }
                        if (upAt - downAt > 500) return@awaitEachGesture   // dlouhý stisk
                        val proj = cameraPositionState.projection ?: return@awaitEachGesture
                        val ll = runCatching {
                            proj.fromScreenLocation(android.graphics.Point(upPos.x.toInt(), upPos.y.toInt()))
                        }.getOrNull() ?: return@awaitEachGesture
                        val degPerDp = 360.0 / (256.0 * Math.pow(2.0, cameraPositionState.position.zoom.toDouble()))
                        val hits = googleLayer.hitTest(ll.latitude, ll.longitude, degPerDp)
                        if (hits.size == 1) appState.selectVehicle(hits[0])
                        else if (hits.size > 1) appState.vehicleChoices = hits
                        else if (appState.showMapStops && cameraPositionState.position.zoom >= STOP_MIN_ZOOM_GOOGLE) {
                            googleLayer.hitTestStop(ll.latitude, ll.longitude, degPerDp)?.let { appState.openStop(api, it, scope) }
                        }
                    }
                },
                cameraPositionState = cameraPositionState,
                mapColorScheme = scheme,
                onMapLoaded = { googleTilesLoaded = true },
                properties = MapProperties(isMyLocationEnabled = googleTilesLoaded && hasLocationPermission),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false,
                    tiltGesturesEnabled = false   // výběr vozidel do mřížky se počítá pro pohled shora
                )
            ) {
                // mapa už existuje, překryvná "Načítání mapy" může pryč; nečeká se na stažení všech dlaždic (onMapLoaded)
                MapEffect(Unit) { gMap ->
                    isMapReady = true
                    // vozidla nejsou markery (kreslí je GoogleDotOverlay), výběr řeší jen okamžité ťuknutí (hitTest)
                    googleLayer.attach(gMap)
                }
                DisposableEffect(googleLayer) {
                    onDispose { googleLayer.detach() }
                }
                if (!isMapReady) return@GoogleMap
                // barva trasy podle druhu dopravy spoje (na světlé mapě tmavší, na tmavé světlejší odstín barvy vozu)
                val primaryColor = rememberRouteColor(appState, useDarkMap)

                // trasa se plynule objeví / zmizí (shownRoute drží zrušenou trasu, dokud nezmizí)
                val routeAlpha = shownRoute.alpha.value
                val walkPattern = remember { listOf(Dot(), Gap(14f)) }
                for (walk in shownRoute.walks) {
                    if (walk.points.size >= 2 && shownRoute.route.isNotEmpty()) {
                        val walkPoints = remember(walk) { walk.points.map { LatLng(it.first, it.second) } }
                        Polyline(
                            points = walkPoints,
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = routeAlpha),
                            width = 14f,
                            zIndex = 0.2f,
                            pattern = walkPattern,
                            startCap = RoundCap(),
                            endCap = RoundCap(),
                            jointType = JointType.ROUND
                        )
                    }
                }

                if (shownRoute.route.isNotEmpty()) {
                    val full = shownRoute.full
                    val segments = if (showFullRoute && full.size >= 2) listOf(full)
                    else if (shownRoute.segments.isNotEmpty()) shownRoute.segments
                    else listOf(shownRoute.route)

                    for (line in segments) {
                        if (line.size >= 2) {
                            Polyline(
                                points = line,
                                color = primaryColor.copy(alpha = routeAlpha),
                                width = 12f,
                                zIndex = 0f,
                                startCap = RoundCap(),
                                endCap = RoundCap(),
                                jointType = JointType.ROUND
                            )
                        }
                    }
                }
            }
            if (isMapReady) {
                // vozidla (tečky i štítky) vlastním canvasem nad mapou (viz GoogleDotOverlay); nepřijímá dotyky
                GoogleDotOverlay(googleLayer, cameraPositionState, Modifier.fillMaxSize())
                GoogleVehicleOverlay(
                    layer = googleLayer,
                    hub = hub,
                    cameraPositionState = cameraPositionState,
                    activeVehicles = activeVehicles,
                    targetUid = targetUid,
                    phase = phase,
                    appState = appState,
                    showFullRoute = showFullRoute,
                    active = active,
                    shown = shownRoute,
                    stopIndex = stopIndex,
                    routeColor = rememberRouteColor(appState, useDarkMap).toArgb(),
                )
            }
        } else {
            MapLibreScreenContent(
                modifier = Modifier.fillMaxSize(),
                appState = appState,
                active = active,
                useDarkMap = useDarkMap,
                activeVehicles = activeVehicles,
                primaryColor = rememberRouteColor(appState, useDarkMap),
                showFullRoute = showFullRoute,
                phase = phase,
                targetUid = targetUid,
                onMapReady = { maplibreMapRef = it },
                onStopTap = { appState.openStop(api, it, scope) },
                onStyleLoaded = { isMapReady = true },
                onMapDisposed = {
                    maplibreMapRef = null
                    isMapReady = false
                },
                hasLocationPermission = hasLocationPermission,
                tertiaryColor = MaterialTheme.colorScheme.tertiary,
                arrowColor = MaterialTheme.colorScheme.onBackground,
                hub = hub,
                stopIndex = stopIndex,
                stopIcons = stopIcons,
            )
        }

        // Mapy.cz vyžaduje uvedení zdroje dat (logo Mapy.cz viz poznámka v README)
        if (appState.mapProvider == MapProvider.MAPYCZ && BuildConfig.MAPYCZ_API_KEY.isNotBlank()) {
            Text(
                stringResource(R.string.map_mapy_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        var showHeldInfo by remember { mutableStateOf(false) }
        var showCategoryFilter by remember { mutableStateOf(false) }

        if (active && showCategoryFilter) {
            CategoryFilterDialog(
                selected = appState.mapCategories,
                onChange = { appState.updateMapCategories(it) },
                onDismiss = { showCategoryFilter = false }
            )
        }

        ElevatedCard(
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth()
                .onGloballyPositioned {
                    bannerBottomPx = it.boundsInParent().bottom
                    it.parentLayoutCoordinates?.size?.let { s -> mapSizePx = s }
                },
            shape = RoundedCornerShape(24.dp)
        ) {
            Column {
                if (!appState.showOnlyMapFilters) {
                    Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.map_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (!appState.vehiclesLoaded && appState.vehicles.isEmpty()) stringResource(R.string.map_loading_positions)
                                else if (allCats) pluralStringResource(R.plurals.map_positions_count, appState.vehicles.size, appState.vehicles.size)
                                else pluralStringResource(R.plurals.map_positions_filtered, appState.vehicles.size, activeVehicles.size, appState.vehicles.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (appState.vehicleSource.isNotBlank()) {
                                Text(
                                    sourceLabelText(
                                        appState.vehicleSource,
                                        normal = MaterialTheme.colorScheme.onSurfaceVariant,
                                        bad = MaterialTheme.colorScheme.error
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (appState.routeLoading || (!appState.vehiclesLoaded && appState.vehicles.isEmpty())) { Wavy(24.dp); Spacer(Modifier.width(8.dp)) }
                        if (appState.mapError != null) {
                            Text(stringResource(R.string.map_offline), color = MaterialTheme.colorScheme.error)
                        }
                        //if (appState.heldCount > OUTAGE_MIN) {
                        IconButton(onClick = { showHeldInfo = true }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = stringResource(R.string.map_source_info),
                            )
                        }
                        //}
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(
                            start = 14.dp,
                            end = 14.dp,
                            top = if (appState.showOnlyMapFilters) 12.dp else 0.dp,
                            bottom = 12.dp
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // filtr druhů dopravy: jedno tlačítko se shrnutím, výběr v dialogu
                    FilterChip(
                        selected = !allCats,
                        onClick = { showCategoryFilter = true },
                        label = {
                            Text(
                                when {
                                    allCats -> stringResource(R.string.map_filter_all)
                                    mapCats.isEmpty() -> stringResource(R.string.map_filter_none)
                                    else -> MapCategory.entries.filter { it in mapCats }.joinToString(", ") { tr(it.labelRes) }
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(18.dp)) },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) }
                    )
                    // barevné tečky: co je na mapě vidět
                    Row(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (c in MapCategory.entries) if (c in mapCats) {
                            Icon(
                                categoryIcon(c), null,
                                tint = Color(c.style.color),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // víc vozů pod jedním ťuknutím: výběr
        if (active && appState.vehicleChoices.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { appState.vehicleChoices = emptyList() },
                title = { Text(stringResource(R.string.map_pick_vehicle)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for (v in appState.vehicleChoices) {
                            val vs = vehicleStyle(v)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        appState.vehicleChoices = emptyList()
                                        appState.selectVehicle(v)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // stejně jako na webu: ikona druhu v barvě vozidla, „Autobus 9“, „směr Divadlo“
                                val (kindRes, kindIcon) = vehicleKind(v)
                                Box(
                                    Modifier.size(40.dp).background(Color(vehicleColor(vs)), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(kindIcon, null, tint = if (vs.darkText) Color.Black else Color.White, modifier = Modifier.size(22.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${stringResource(kindRes)} ${v.line}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // cíl ze zdroje; DPMÚL ho neposílá, pak konečná ze spoje v jízdním řádu (jako v detailu)
                                    val dest by produceState(v.destination.takeIf { it.isNotBlank() }, v.uid) {
                                        if (value == null) value = runCatching { api.vehicleTripInfo(v).finalStop }.getOrNull()
                                    }
                                    Text(
                                        dest?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.vehicle_towards, vehicleStopName(prettyName(it), v.train)) }
                                            ?: cleanAgencyName(v.agency),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { appState.vehicleChoices = emptyList() }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }

        if (active && showHeldInfo) {
            AlertDialog(
                onDismissRequest = { showHeldInfo = false },
                icon = { Icon(Icons.Default.Info, null) },
                title = { Text(stringResource(R.string.map_sources_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            stringResource(R.string.map_sources_text)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.map_diagnostics), style = MaterialTheme.typography.labelLarge)
                        Text(
                            FeedDiag.text(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHeldInfo = false }) { Text(stringResource(R.string.common_ok_understood)) }
                }
            )
        }

        // logo Google je vlevo dole, proto odsazení
        // tlacitko X (vykrizkovani)
        AnimatedVisibility(
            visible = appState.tripRoute.isNotEmpty(),
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(tween(200)),
            exit = scaleOut(tween(150)) + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 18.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    appState.tripRoute = emptyList()
                    appState.routeStyle = null
                    appState.tripRouteSegments = emptyList()
                    appState.fullRoute = emptyList()
                    appState.tripStops = emptyList()
                    appState.journeyStops = null
                    appState.focusedTripVehicle = null
                    appState.focusedTripRef = null
                    appState.highlightedTrip = null
                    appState.focusTrip = null
                    appState.walkRoute = null
                    appState.journeyWalks = emptyList()
                }
            ) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.map_hide_route)) }
        }

        FloatingActionButton(
            onClick = {
                if (!hasLocationPermission) {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                } else {
                    // cache + čerstvá poloha současně, první odpověď vyhrává (dřív jen cache => často stará poloha)
                    requestFix(context, 60_000L) { loc ->
                        if (loc != null) scope.launch {
                            animateCameraTo(LatLng(loc.latitude, loc.longitude), 14f)
                        }
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
        ) { Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.my_location)) }

        AnimatedVisibility(
            visible = !isMapReady,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(300, easing = FastOutLinearInEasing))
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Wavy(size = 48.dp, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = stringResource(R.string.map_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}


/**
 * Šipky na trase, začátek / konec a zastávky jako nativní markery; vozidla jako snímek pro [GoogleDotOverlay]
 * (viz [GoogleVehicleLayer]). Nic tady nečte pozici kamery během kompozice => pohyb / zoom mapy nevyvolá rekompozici.
 * Vozidla obsluhuje jedna smyčka: pohyb (hub), režim tečky / štítky a příprava snímku.
 */
@Composable
private fun GoogleVehicleOverlay(
    layer: GoogleVehicleLayer,
    hub: MotionHub,
    cameraPositionState: CameraPositionState,
    activeVehicles: List<Pair<String, Vehicle>>,
    targetUid: String?,
    phase: Int,
    appState: SpojeAppState,
    showFullRoute: Boolean,
    active: Boolean,
    shown: ShownRoute,
    stopIndex: StopIndex?,
    /** Barva trasy (druh dopravy): v ní jsou i tečky zastávek na trase. */
    routeColor: Int,
) {
    val arrowArgb = MaterialTheme.colorScheme.onBackground.toArgb()
    val context = LocalContext.current

    // rozestup šipek se mění jen na hranicích zoomu => rekompozice jen tehdy, ne při každém snímku zoomu
    val arrowSpacing by remember(cameraPositionState) {
        derivedStateOf { arrowSpacingForZoom(cameraPositionState.position.zoom) }
    }

    // trasa, která je vidět (i zrušená, dokud plynule nezmizí), a průhlednost jejích markerů
    val route = shown.route
    val full = shown.full
    val useFull = showFullRoute && full.size >= 2
    val stopDots = shown.stopDots
    LaunchedEffect(layer, shown) {
        snapshotFlow { shown.alpha.value }.collect { layer.setRouteAlpha(it) }
    }

    val arrows by produceState(emptyList<RouteArrow>(), route, arrowSpacing) {
        value = if (route.size < 2) emptyList()
        else withContext(Dispatchers.Default) { createRouteArrows(route = route, spacingMeters = arrowSpacing) }
    }

    LaunchedEffect(route, full, useFull, stopDots, arrows, arrowArgb, routeColor) {
        layer.updateRoute(route, full, useFull, stopDots, arrows, arrowArgb, routeColor)
    }

    val latestVehicles by rememberUpdatedState(activeVehicles)
    val latestTarget by rememberUpdatedState(targetUid)
    val latestPhase by rememberUpdatedState(phase)
    val latestStops by rememberUpdatedState(stopIndex)

    LaunchedEffect(layer, hub, active) {
        if (!active) return@LaunchedEffect
        layer.restartAppear()     // po otevření mapy se vozidla plynule objeví
        var lastCam: CameraPosition? = null
        var view: ViewBox? = null
        var baseView: ViewBox? = null
        var lastVehicles: List<Pair<String, Vehicle>>? = null
        var lastTarget: String? = null
        var lastPhase = -1
        var pending = false
        var lastTickFt = 0L
        var lastStopIdx: StopIndex? = null

        while (true) {
            // Polohy se počítají k času snímku (ne k náhodnému probuzení po delay), v pravidelném rytmu celých
            // snímků; overlay mezi nimi dopočítává polohu z rychlosti v každém snímku => plynulý pohyb.
            val ft = withFrameNanos { it }
            // čeká se na štítky kreslené na pozadí => hned další snímek, jinak běžný interval
            val interval = if (layer.isDotMode && cameraPositionState.position.zoom < DOT_SMOOTH_ZOOM) GOOGLE_DOT_INTERVAL_MS
            else GOOGLE_VEHICLE_INTERVAL_MS   // oddálené tečky se hýbou o zlomky pixelu, stačí řidčeji
            if (!pending && ft - lastTickFt < interval * 1_000_000L - FRAME_SLACK_NS) continue
            lastTickFt = ft
            val now = frameElapsedMs(ft)
            if (now - appState.vehicleSelectedAtMs < SHEET_OPEN_GRACE_MS) continue

            val cam = cameraPositionState.position
            val camChanged = cam != lastCam || view == null
            if (camChanged) {
                lastCam = cam
                val b = runCatching { cameraPositionState.projection?.visibleRegion?.latLngBounds }.getOrNull()
                if (b != null) {
                    val base = ViewBox(b.southwest.latitude, b.southwest.longitude, b.northeast.latitude, b.northeast.longitude)
                    baseView = base
                    view = base.expanded(0.15)
                }
            }

            // zastávky v záběru (jen přiblížená mapa); pod hranicí zoomu plynule zmizí (seznam zůstane, ať je co prolínat)
            val sIdx = latestStops
            if (camChanged || sIdx !== lastStopIdx) {
                lastStopIdx = sIdx
                val bv = baseView
                val show = sIdx != null && bv != null && cam.zoom >= STOP_MIN_ZOOM_GOOGLE
                if (show) layer.setStops(sIdx.inView(bv.expanded(0.1)))
                layer.setStopsVisible(show, now)
            }

            val vehicles = latestVehicles
            val tgt = latestTarget
            val ph = latestPhase
            val metaChanged = vehicles !== lastVehicles || tgt != lastTarget || ph != lastPhase
            lastVehicles = vehicles; lastTarget = tgt; lastPhase = ph

            val wasDot = layer.isDotMode
            if (camChanged || metaChanged) {
                val bv = baseView
                var visible = 0
                if (bv == null) visible = vehicles.size
                else for ((_, v) in vehicles) if (bv.contains(v.lat, v.lng)) visible++
                layer.updateMode(cam.zoom, visible)
            }
            val modeChanged = wasDot != layer.isDotMode

            // vozidla kreslí overlay => můžou se hýbat i během pohybu kamery (žádné volání mapy)
            hub.ensureRoutes(view, enabled = !layer.isDotMode)
            val moved = hub.tick(now, view)
            // samotný pohyb kamery snímek nemění (overlay si kameru čte sám), jen u štítků přibývají nové v záběru
            // (stojící vozidla: jednou ještě poslat nulové rychlosti, jinak by je overlay dál dopočítával)
            if (!moved && !metaChanged && !modeChanged && !pending && !(camChanged && !layer.isDotMode) &&
                !layer.moving.value
            ) continue
            layer.stampNanos = ft

            // štítky se chystají už kousek před přepnutím z teček, ať se při přepnutí jen vymění
            val prewarm = layer.isDotMode && cam.zoom >= DOT_ZOOM_GOOGLE - LABEL_PREWARM_ZOOM
            pending = layer.updateVehicles(vehicles, hub, tgt, ph, view, prewarm)
            val missing = layer.takeMissingLabels()
            if (missing.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    for (v in missing) prerenderMarker(context, v.line, vehicleStyle(v), 0, lateMinutes(v))
                }
            }
        }
    }
}


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

private const val VEH_SRC = "vehicles-src"
private const val VEH_LAYER = "vehicles-layer"

// TextureView místo SurfaceView: řeší "BLASTBufferQueue ... Can't acquire next buffer"
// a rozbité vykreslování na Vivo/Funtouch v kombinaci s Compose AndroidView.
private val USE_TEXTURE_MODE = android.os.Build.MANUFACTURER.lowercase() in setOf("vivo", "iqoo")

// max. frekvence předávání pozic vozidel do MapLibre (~30 Hz), animace A -> B je počítaná z času
private const val VEHICLE_UPDATE_INTERVAL_MS = 33L
private const val VEHICLE_UPDATE_INTERVAL_MID_MS = 66L      // štítky, 300–800 vozidel v záběru
private const val VEHICLE_UPDATE_INTERVAL_MANY_MS = 100L    // štítky, víc než 800 vozidel (Praha)
private const val GESTURE_QUIET_MS = 200L
private const val LOCATION_ANIMATION_FPS = 10              // animace modrého bodu (kompas) v MapLibre                  // tak dlouho po pohybu kamery se vozidla neposílají
// oddáleno (tečky): pohyb o pixel trvá dlouho, stačí řídké aktualizace
private const val DOT_UPDATE_INTERVAL_MS = 250L
private const val DOT_UPDATE_INTERVAL_FAR_MS = 500L
/** Tolerance při čekání na další snímek poloh (polovina snímku při 120 Hz). */
private const val FRAME_SLACK_NS = 4_000_000L

/** Čas snímku (System.nanoTime z withFrameNanos) v časové ose SystemClock.elapsedRealtime (ms), jak ji čte pohyb vozidel. */
private fun frameElapsedMs(frameNanos: Long): Long =
    (frameNanos + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())) / 1_000_000L

/** Prolnutí zastávek při objevení / zmizení (ms). */
internal const val STOP_FADE_MS = 250L

private const val STOPS_SRC = "map-stops-src"
private const val STOPS_LAYER = "map-stops"
private const val STOP_IMG_BUS = "map-stop-bus"
private const val STOP_IMG_RAIL = "map-stop-rail"


private const val GOOGLE_VEHICLE_INTERVAL_MS = 33L     // Google: pohyb vozidel v overlayi (~30 Hz, jako MapLibre)
private const val GOOGLE_DOT_INTERVAL_MS = 250L         // oddálené tečky (pod DOT_SMOOTH_ZOOM) se hýbou o zlomky pixelu
private const val DOT_SMOOTH_ZOOM = 10.5f
private const val LABEL_PREWARM_ZOOM = 1.0f            // o kolik úrovní před přepnutím na štítky se začnou kreslit
private const val MAX_NEW_IMAGES_PER_PUSH = 10

// popis vozidla pro vykreslení: ikona + řazení, přepočítá se jen při změně vozidel / zvýraznění
private class VehicleDraw(val key: String, val v: Vehicle, val iconPhase: Int) {
    val style = vehicleStyle(v)
    val late = lateMinutes(v)
    val imgId = "veh|${v.line}|${style.name}|$iconPhase|$late"
    val sort = if (iconPhase != 0) 2 else 1
    val colorKey = style.name
}

private const val VEH_DOT_LAYER = "vehicles-dots"
private const val VEH_HL_LAYER = "vehicles-hl"
private const val VEH_OLD_LAYER = "vehicles-old"   // štítky vozidel se starou polohou (ztlumené)
private const val OLD_ALPHA = 0.65f

private fun vehicleSymbolProps() = arrayOf(
    PropertyFactory.iconImage(Expression.get("img")),
    PropertyFactory.iconAllowOverlap(true),
    PropertyFactory.iconIgnorePlacement(true),
    // průhlednost jen jako konstanta vrstvy (applyLayerAlpha): změna výrazu závislého na datech (get "opacity")
    // nutí MapLibre znovu postavit celý zdroj => při prolínání v Praze sekání v každém snímku
    PropertyFactory.iconOpacity(0f),   // zobrazí až smyčka vozidel (applyLayerAlpha), ať nic neproblikne
    // vyšší hodnota = kreslí se výš (zvýrazněné vozidlo nahoře)
    PropertyFactory.symbolSortKey(Expression.get("sort")),
)

/**
 * Průhlednost řídí smyčka vozidel po snímcích, výchozí přechod stylu (300 ms) by se s ní pral: každá změna by
 * spustila novou animaci od aktuální hodnoty => zpožděné, nerovnoměrné prolínání a problikávání.
 */
private val NO_TRANSITION = TransitionOptions(0, 0, false)

private fun SymbolLayer.noOpacityTransition() = also { it.iconOpacityTransition = NO_TRANSITION }

/**
 * Vrstvy nad jedním zdrojem; průhlednost (prolínání tečky ↔ štítky a objevení) nastavuje smyčka vozidel
 * přes [applyLayerAlpha] jako konstantu vrstvy, vrstva s nulovou průhledností je skrytá:
 *  - [VEH_LAYER]: štítky s číslem linky od [DOT_ZOOM_ML] výš,
 *  - [VEH_OLD_LAYER]: totéž pro vozidla se starou polohou (ztlumená konstantou vrstvy),
 *  - [VEH_DOT_LAYER]: malé tečky pod [DOT_ZOOM_ML],
 *  - [VEH_HL_LAYER]: zvýrazněné vozidlo je vždy štítek (a nahoře).
 */
private fun ensureVehicleLayer(style: Style) {
    if (style.getSource(VEH_SRC) == null) {
        style.addSource(GeoJsonSource(VEH_SRC, FeatureCollection.fromFeatures(emptyList<Feature>())))
        // bez animací umísťování symbolů: každá nová dávka poloh vozidel (GeoJSON) by štítky znovu „prolínala“
        // (300 ms) a mapa by se kvůli tomu překreslovala pořád dokola (render vlákno MapLibre ~80 % v Praze)
        style.transition = TransitionOptions(0, 0, false)
    }
    val notHl = Expression.neq(Expression.get("hl"), Expression.literal(1))
    val old = Expression.has("opacity")
    if (style.getLayer(VEH_LAYER) == null) {
        style.addLayer(
            SymbolLayer(VEH_LAYER, VEH_SRC).withProperties(*vehicleSymbolProps())
                .withFilter(Expression.all(notHl, Expression.not(old))).noOpacityTransition()
        )
    }
    if (style.getLayer(VEH_OLD_LAYER) == null) {
        style.addLayerAbove(
            SymbolLayer(VEH_OLD_LAYER, VEH_SRC).withProperties(*vehicleSymbolProps())
                .withFilter(Expression.all(notHl, old)).noOpacityTransition(),
            VEH_LAYER
        )
    }
    if (style.getLayer(VEH_DOT_LAYER) == null) {
        // tečky POD štítky (při prolínání nesmí prosvítat přes štítek)
        style.addLayerBelow(
            CircleLayer(VEH_DOT_LAYER, VEH_SRC).withProperties(
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(6, 2.0f),
                        Expression.stop(DOT_ZOOM_ML.toInt(), 3.4f)
                    )
                ),
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get("k"), Expression.color(VehicleStyle.BUS.color),
                        *VehicleStyle.entries.flatMap {
                            // stará poloha: "<styl>~", ztlumená barvou (průhlednost vrstvy je konstanta, viz výše)
                            val dim = (it.color and 0xFFFFFF) or ((OLD_ALPHA * 255).toInt() shl 24)
                            listOf(Expression.stop(it.name, Expression.color(it.color)), Expression.stop(it.name + "~", Expression.color(dim)))
                        }.toTypedArray()
                    )
                ),
                PropertyFactory.circleOpacity(0f),
                // tramvaj / metro se zpožděním: červený okraj tečky
                PropertyFactory.circleStrokeColor(
                    Expression.match(Expression.get("late"), Expression.color(0xFFFFFFFF.toInt()),
                        Expression.stop(1, Expression.color(LATE_COLOR)))
                ),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeOpacity(0f),
            ).withFilter(notHl).also {
                it.circleOpacityTransition = NO_TRANSITION
                it.circleStrokeOpacityTransition = NO_TRANSITION
            },
            VEH_LAYER
        )
    }
    if (style.getLayer(VEH_HL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(VEH_HL_LAYER, VEH_SRC).withProperties(*vehicleSymbolProps())
                .withFilter(Expression.eq(Expression.get("hl"), Expression.literal(1))).noOpacityTransition()
        )
    }
}

/**
 * Průhlednost vrstvy vozidel (konstanta vrstvy, levná změna). Vrstvy se neskrývají přes visibility: to je layout
 * vlastnost a každé zobrazení by znovu rozmístilo všechny štítky (zásek na začátku každého přechodu). Skrytou
 * vrstvu štítků nic nestojí, protože vozidla v režimu teček štítek (img) nemají.
 */
private fun applyLayerAlpha(style: Style, id: String, a: Float) {
    val layer = style.getLayer(id) ?: return
    when (id) {
        VEH_DOT_LAYER -> layer.setProperties(
            PropertyFactory.circleOpacity(a),
            PropertyFactory.circleStrokeOpacity(0.8f * a),
        )
        VEH_OLD_LAYER -> layer.setProperties(PropertyFactory.iconOpacity(OLD_ALPHA * a))
        else -> layer.setProperties(PropertyFactory.iconOpacity(a))
    }
}

// vrstvy trasy vždy POD vrstvou vozidel
private fun addBelowMarkers(style: Style, layer: Layer) {
    ensureVehicleLayer(style)
    style.addLayerBelow(layer, VEH_DOT_LAYER)
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
@Composable
private fun MapLibreScreenContent(
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
    val cameraMovedAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }   // poslední pohyb kamery (gesto)
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

                    m.addOnCameraMoveListener {
                        cameraDirty.set(true)
                        cameraMovedAt.set(SystemClock.elapsedRealtime())
                    }
                    // stav kamery se ukládá až když se mapa zastaví, ne při každém framu pohybu
                    m.addOnCameraIdleListener {
                        cameraDirty.set(true)
                        stopsDirty.set(true)
                        cameraMovedAt.set(0L)   // gesto skončilo => vozidla hned dohnat
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
                                        // zastávka do ~14 dp od ťuknutí (metry na pixel jen jednou, žádná projekce každé)
                                        val r = 18.0 * density * m.projection.getMetersPerPixelAtLatitude(ll.latitude)
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
                // nad vozidly (vlak u nástupiště nesmí zakrýt zastávku), pod zvýrazněným vozidlem
                ensureVehicleLayer(style)
                style.addLayerBelow(
                    SymbolLayer(STOPS_LAYER, STOPS_SRC).withProperties(
                        PropertyFactory.iconImage(Expression.get("img")),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconOpacity(0f),
                    ).also { it.iconOpacityTransition = TransitionOptions(STOP_FADE_MS, 0, false) },
                    VEH_HL_LAYER
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
        var view: ViewBox? = null
        var viewAt = 0L
        var dotMode = false          // oddáleno: kreslí se jen tečky (vrstva přepíná GPU, tady jen šetříme práci)
        var needImages = true        // štítky se budou kreslit (nebo jsou těsně před tím)
        var interval = VEHICLE_UPDATE_INTERVAL_MS
        var pending = false          // některému vozidlu ještě chybí obrázek štítku
        val fade = VehicleFade()     // objevení po otevření mapy a prolínání tečky ↔ štítky
        var modeKnown = false
        var alphaStyle: Style? = null
        var lastDotA = -1f; var lastLabelA = -1f; var lastHlA = -1f
        var deferred = false         // aktualizace odložená kvůli gestu
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
                ).expanded(0.15)   // menší okraj = méně štítků k rozmístění (při 0,4 to byla 3,2× plocha záběru)
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
                    applyLayerAlpha(st, VEH_LAYER, la)
                    applyLayerAlpha(st, VEH_OLD_LAYER, la)
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
                view = currentView()
                viewAt = now
                runCatching { m.cameraPosition.zoom }.getOrNull()?.let { z ->
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
            if (!moved && !metaChanged && !camChanged && !pending && !deferred) continue
            // během gesta (zoom, posun) jen kamera: běžné posuny vozidel počkají na konec gesta, aby se MapLibre
            // nepral s překreslováním mapy. Přepnutí tečky ↔ štítky (a chybějící štítky při něm) projde hned.
            if (now - cameraMovedAt.get() < GESTURE_QUIET_MS && !metaChanged && !(pending && fade.switching())) {
                deferred = true
                continue
            }
            deferred = false

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
                    list += VehicleDraw(k, v, if (tgt != null && v.uid == tgt) ph else 0)
                }
                list.sortBy { it.key }     // pevné pořadí = stabilní Z order, nic se nepřeskupuje při aktualizaci
                draws = list
            }

            // obrázky štítků jen pro vozidla ve viditelné oblasti a jen když se štítky kreslí
            // (zvýrazněné vozidlo je štítek vždy). Za jeden průchod nejvýš pár kusů, ať se hlavní vlákno nezasekne.
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
                val newBitmaps = withContext(Dispatchers.Default) {
                    missing.map { (imgId, d) ->
                        val bmp = markerBitmap(context, d.v.line, d.style, d.iconPhase, d.late)
                            .copy(Bitmap.Config.ARGB_8888, false).apply {
                                setDensity(context.resources.displayMetrics.densityDpi)
                            }
                        imgId to bmp
                    }
                }
                for ((imgId, bmp) in newBitmaps) {
                    if (addedImages.add(imgId)) style.addImage(imgId, bmp)
                }
            }
            pending = false

            val features = ArrayList<Feature>(minOf(draws.size, 600))
            for (d in draws) {
                val p = hub.positionOf(d.key) ?: continue
                if (view != null && !view.contains(p.latitude, p.longitude)) continue
                val hl = d.iconPhase != 0
                // během prolínání mají štítek i vozidla mizející do teček
                // štítek (img) jen když jsou štítky vidět nebo se prolínají; v pásmu před přepnutím se obrázky
                // jen chystají (addImage), jinak by se neviditelná vrstva štítků zbytečně rozmísťovala
                var wantsImage = hl || !fade.dotMode || fade.switching()
                if (wantsImage && d.imgId !in addedImages) {
                    pending = true
                    if (hl) continue
                    wantsImage = false   // štítek ještě není => aspoň tečka (při prolínání nesmí vozidlo zmizet)
                }
                features += Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).also {
                    val stale = d.v.ageSec() >= 60
                    if (stale) it.addNumberProperty("opacity", OLD_ALPHA)
                    if (wantsImage) {
                        it.addStringProperty("img", d.imgId)
                        it.addNumberProperty("sort", d.sort)
                    }
                    if (hl) it.addNumberProperty("hl", 1)
                    else {
                        it.addStringProperty("k", if (stale) d.colorKey + "~" else d.colorKey)
                        if (d.late > 0) it.addNumberProperty("late", 1)
                    }
                }
            }

            // první dávka se štítky po přepnutí: prolínání počká, až je MapLibre zpracuje (viz labelsLoading)
            if (modeFlipped && !fade.dotMode && fade.switching()) labelsLoading.set(1)
            style.getSourceAs<GeoJsonSource>(VEH_SRC)
                ?.setGeoJson(FeatureCollection.fromFeatures(features))
            if (features.isNotEmpty()) fade.startAppear()
            lastCount = features.size
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

private fun routeArrowBitmap(color: Int): Bitmap {
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


private fun splitSources(label: String): List<String> {
    val parts = mutableListOf<String>()
    val sb = StringBuilder()
    var depth = 0
    for (ch in label) {
        when {
            ch == '(' -> { depth++; sb.append(ch) }
            ch == ')' -> { depth = (depth - 1).coerceAtLeast(0); sb.append(ch) }
            ch == ',' && depth == 0 -> { parts += sb.toString().trim(); sb.clear() }
            else -> sb.append(ch)
        }
    }
    if (sb.isNotBlank()) parts += sb.toString().trim()
    return parts.filter { it.isNotEmpty() }
}

private fun sourceLabelText(label: String, normal: Color, bad: Color) = buildAnnotatedString {
    withStyle(SpanStyle(color = normal)) { append(tr(R.string.source_prefix)) }
    val staleSuffix = tr(R.string.source_stale_suffix).trim()
    if (label == SourceLabel.NONE) {
        withStyle(SpanStyle(color = bad)) { append(label) }
        return@buildAnnotatedString
    }
    splitSources(label).forEachIndexed { i, part ->
        if (i > 0) withStyle(SpanStyle(color = normal)) { append(", ") }
        val stale = part.endsWith(staleSuffix)
        // zastaralý zdroj stačí červeně, bez textu "(zastaralé)" (značka v popisku slouží jen k rozpoznání)
        val name = if (stale) part.removeSuffix(staleSuffix).trimEnd() else part
        withStyle(SpanStyle(color = if (stale) bad else normal)) { append(name) }
    }
}

/** Trasa, která je právě na Google mapě vidět, a její průhlednost (plynulé objevení / zmizení). */
@Stable
internal class ShownRoute {
    var route by mutableStateOf<List<LatLng>>(emptyList())
    var segments by mutableStateOf<List<List<LatLng>>>(emptyList())
    var full by mutableStateOf<List<LatLng>>(emptyList())
    var walks by mutableStateOf<List<WalkRoute>>(emptyList())
    var stopDots by mutableStateOf<List<LatLng>>(emptyList())
    val alpha = Animatable(0f)
}

/**
 * Nová trasa se plynule objeví (průhlednost 0 → 1), zrušená se ještě drží a plynule zmizí. Výměna jedné trasy
 * za jinou (přesnější tvar po načtení, jiný spoj) se neanimuje, aby trasa neblikla.
 */
@Composable
private fun rememberShownRoute(appState: SpojeAppState, showFullRoute: Boolean): ShownRoute {
    val s = remember { ShownRoute() }
    val route = appState.tripRoute
    val segments = appState.tripRouteSegments
    val full = appState.fullRoute
    val walks = if (appState.journeyWalks.isNotEmpty()) appState.journeyWalks
    else listOfNotNull(appState.journeyWalk)
    val stopDots = appState.stopDots(showFullRoute)
    LaunchedEffect(route, segments, full, walks, stopDots) {
        if (route.isNotEmpty()) {
            val appearing = s.route.isEmpty()
            s.route = route; s.segments = segments; s.full = full; s.walks = walks; s.stopDots = stopDots
            if (appearing) s.alpha.snapTo(0f)
            if (s.alpha.targetValue < 1f || appearing) s.alpha.animateTo(1f, tween(ROUTE_FADE_IN_MS, easing = LinearOutSlowInEasing))
        } else if (s.route.isNotEmpty()) {
            s.alpha.animateTo(0f, tween(ROUTE_FADE_OUT_MS, easing = FastOutLinearInEasing))
            s.route = emptyList(); s.segments = emptyList(); s.full = emptyList(); s.walks = emptyList(); s.stopDots = emptyList()
        }
    }
    return s
}

/**
 * Barva trasy podle druhu dopravy. Drží poslední známý druh: při zrušení trasy se [SpojeAppState.routeStyle]
 * vynuluje hned, ale trasa ještě plynule mizí a nesmí přitom přeskočit na výchozí (modrou) barvu.
 * Každá nová trasa druh nastavuje, takže zapamatovaná hodnota nikdy nepřetrvá do jiné trasy.
 */
@Composable
private fun rememberRouteColor(appState: SpojeAppState, useDarkMap: Boolean): Color {
    val last = remember { mutableStateOf<VehicleStyle?>(null) }
    appState.routeStyle?.let { last.value = it }
    return last.value?.let { Color(if (useDarkMap) it.routeDark else it.route) }
        ?: MaterialTheme.colorScheme.primary
}

/** Horní okraj panelu s odjezdy zastávky jako podíl výšky mapy (panel zabírá zbytek dole). */
private const val STOP_SHEET_TOP_FRACTION = 0.36f

/** Vůz blíž než tolik metrů od trasy ji už jede (nebo stojí na výchozí zastávce). */
private const val VEHICLE_ON_ROUTE_M = 350.0

private const val ROUTE_FADE_IN_MS = 525
private const val ROUTE_FADE_OUT_MS = 260

/** Druh vozidla pro výběr z více vozidel: název (jednotné číslo) a ikona. Trolejbus podle `mode` ze serveru. */
internal fun vehicleKind(v: Vehicle): Pair<Int, ImageVector> =
    if (v.mode == "trolleybus") R.string.vehicle_kind_trolleybus to Icons.Default.DirectionsBus
    else when (vehicleStyle(v)) {
        VehicleStyle.TRAIN -> R.string.vehicle_kind_train to Icons.Default.Train
        VehicleStyle.TRAM -> R.string.vehicle_kind_tram to Icons.Default.Tram
        VehicleStyle.METRO, VehicleStyle.METRO_A, VehicleStyle.METRO_B, VehicleStyle.METRO_C -> R.string.vehicle_kind_metro to Icons.Default.Subway
        VehicleStyle.FERRY -> R.string.vehicle_kind_ferry to Icons.Default.DirectionsBoat
        VehicleStyle.FUNICULAR -> R.string.vehicle_kind_funicular to Icons.Default.Tram
        else -> R.string.vehicle_kind_bus to Icons.Default.DirectionsBus
    }

internal fun categoryIcon(c: MapCategory) = when (c) {
    MapCategory.BUS -> Icons.Default.DirectionsBus
    MapCategory.TRAM -> Icons.Default.Tram
    MapCategory.METRO -> Icons.Default.Subway
    MapCategory.TRAIN -> Icons.Default.Train
}

/** Výběr druhů dopravy: na mapě i v hledání spojení (změna platí hned). */
@Composable
internal fun CategoryFilterDialog(
    selected: Set<MapCategory>,
    onChange: (Set<MapCategory>) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.map_filter_title),
    /** false (hledání spojení): poslední zaškrtnutý druh nejde odebrat */
    allowEmpty: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.FilterList, null) },
        title = { Text(title) },
        text = {
            Column {
                for (c in MapCategory.entries) {
                    val on = c in selected
                    val locked = on && !allowEmpty && selected.size == 1
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !locked) { onChange(if (on) selected - c else selected + c) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, enabled = !locked)
                        Icon(categoryIcon(c), null, tint = Color(c.style.color))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(c.labelRes), style = MaterialTheme.typography.bodyLarge)
                            if (c == MapCategory.BUS) {
                                Text(
                                    stringResource(R.string.map_filter_bus_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
        dismissButton = {
            TextButton(
                onClick = { onChange(MapCategory.entries.toSet()) },
                enabled = selected.size != MapCategory.entries.size
            ) { Text(stringResource(R.string.map_filter_select_all)) }
        }
    )
}

/** Čtení selectedVehicle je izolované tady, aby se při výběru / rematch nerekomponovala celá mapa. */
@Composable
internal fun SelectedVehicleSheet(api: TransitRepository, appState: SpojeAppState) {
    appState.selectedVehicle?.let { v ->
        VehicleDetailBottomSheet(v, api, appState) { appState.selectedVehicle = null }
    }
}