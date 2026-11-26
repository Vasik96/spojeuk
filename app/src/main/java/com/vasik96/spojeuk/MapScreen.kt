@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, com.google.maps.android.compose.MapsComposeExperimentalApi::class)

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.tan
import kotlin.math.pow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory as MapLibreCameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MapLibreLatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Point
import kotlin.time.Duration.Companion.milliseconds

// Ústecký kraj + trochu Prahy a okolních krajů (filtr se používá jen pro Správu železnic)
private val USTECKY_LAT = 49.90..51.10
private val USTECKY_LNG = 12.80..14.80

private fun Vehicle.inUsteckyKraj() = lat in USTECKY_LAT && lng in USTECKY_LNG

// Při oddálení (pod touto úrovní, v měřítku Google Maps) se místo štítků s číslem linky kreslí malé tečky
// (tisíce štítků přes sebe jsou nečitelné; u MapLibre navíc tisíce ikon a GeoJSON prvků při každém snímku).
internal const val DOT_ZOOM_GOOGLE = 10.8f
internal const val DOT_HYSTERESIS = 0.15f             // jen Google: proti blikání na hranici
internal const val DOT_ZOOM_ML = 11f                   // MapLibre (má zoom o 1 menší, tj. Google 12); stop výrazu potřebuje celé číslo

/** Jak dlouho po kliku na vozidlo se pozastaví pohyb vozidel na mapě (otevírání bottom sheetu). */
internal const val SHEET_OPEN_GRACE_MS = 900L

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

internal const val ROUTE_FADE_IN_MS = 525
internal const val ROUTE_FADE_OUT_MS = 260

/** Druh vozidla pro výběr z více vozidel: název (jednotné číslo) a ikona. Trolejbus podle `mode` ze serveru. */
internal fun vehicleKind(v: Vehicle): Pair<Int, ImageVector> {
    val vs = vehicleStyle(v)
    return if (vs == VehicleStyle.TROLLEYBUS || vs == VehicleStyle.TROLLEYBUS_LATE) R.string.vehicle_kind_trolleybus to Icons.Default.DirectionsBus
    else when (vs) {
        VehicleStyle.TRAIN -> R.string.vehicle_kind_train to Icons.Default.Train
        VehicleStyle.TRAM -> R.string.vehicle_kind_tram to Icons.Default.Tram
        VehicleStyle.METRO, VehicleStyle.METRO_A, VehicleStyle.METRO_B, VehicleStyle.METRO_C -> R.string.vehicle_kind_metro to Icons.Default.Subway
        VehicleStyle.FERRY -> R.string.vehicle_kind_ferry to Icons.Default.DirectionsBoat
        VehicleStyle.FUNICULAR -> R.string.vehicle_kind_funicular to Icons.Default.Tram
        else -> R.string.vehicle_kind_bus to Icons.Default.DirectionsBus
    }
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
