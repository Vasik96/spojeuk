@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.DirectionsBus
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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

// Google: pozice markerů se přepisují v každém snímku (vsync), animace A -> B je počítaná z času.
// Viditelná oblast (pro vynechání markerů mimo obrazovku) se přepočítává jen občas.
private const val GOOGLE_VIEW_REFRESH_MS = 250L

@SuppressLint("MissingPermission")
@Composable
internal fun MapScreen(modifier: Modifier = Modifier, appState: SpojeAppState, api: TransitRepository, active: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showFullRoute by remember { mutableStateOf(false) }
    var isMapReady by remember { mutableStateOf(false) }

    var mapInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(140.milliseconds)
        mapInitialized = true
    }

    if (!mapInitialized) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Wavy(size = 48.dp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Načítání mapy…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    LaunchedEffect(appState.mapProvider) {
        isMapReady = false
    }

    // polling poloh
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (appState.vehicles.isEmpty()) appState.vehiclesLoaded = false
        while (true) {
            runCatching { api.vehiclePositions() }
                .onSuccess {
                    appState.updateVehicles(it)
                    appState.heldCount = api.heldCount()
                    appState.vehicleSource = api.sourceLabel()
                    appState.mapError = null
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    appState.mapError = "Polohy vozidel se nepodařilo načíst"
                }
            appState.vehiclesLoaded = true
            delay(6_000)
        }
    }

    // pomalý zdroj (DPMÚL) dokončil dotaz na pozadí: zobraz ho hned, ne až v dalším kole pollingu
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        api.vehicleUpdates.collect {
            appState.updateVehicles(api.currentVehicles())
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

    val animateCameraTo: suspend (LatLng, Float) -> Unit = { targetLatLng, targetZoom ->
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
                    "ID spoje nenalezeno. Zobrazen nejbližší bus linky ${ref.line}, může být jiný.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            animateCameraTo(LatLng(v.lat, v.lng), 16f)
        } else {
            appState.focusedTripVehicle = null
            appState.focusedTripRef = null
            appState.highlightedTrip = null
            appState.pulsePhase = 0
            val msg = if (appState.mapError != null)
                "Polohy vozidel se nepodařilo načíst"
            else
                "Autobus tohoto spoje teď není na mapě, možná ještě nevyjel"
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            if (route.isNotEmpty()) {
                val lats = route.map { it.latitude }
                val lons = route.map { it.longitude }
                val span = maxOf(lats.max() - lats.min(), lons.max() - lons.min())
                val zoom =
                    if (span > 0.3) 10f else if (span > 0.15) 11f else if (span > 0.07) 12f else 13f
                animateCameraTo(
                    LatLng((lats.max() + lats.min()) / 2, (lons.max() + lons.min()) / 2), zoom
                )
            }
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

    val target = locked ?: appState.selectedVehicle

    val keyer = remember { VehicleKeyer() }
    val routes = remember(api) { VehicleRoutes(api) }
    val keyed by produceState(emptyList<Pair<String, Vehicle>>(), allVehicles) {
        value = withContext(Dispatchers.Default) { keyer.assign(allVehicles) }
    }

    val hideOthers = appState.hideOtherVehiclesOnMap
    val mapMode = appState.mapModeFilter
    val activeVehicles: List<Pair<String, Vehicle>> = remember(
        keyed, strictMode, hideOthers, locked, target, mapMode
    ) {
        if (strictMode && hideOthers && locked != null) {
            val match = keyed.firstOrNull { it.second.uid == locked.uid }
            listOf(match ?: ("locked_${locked.uid}" to locked))
        } else {
            keyed.filter { (_, x) ->
                val isTarget = target != null && x.uid == target.uid
                // vybrané vozidlo zůstane vidět i při jiném filtru
                isTarget || (
                        mapMode.accepts(x.train) && (
                                x.source != SpravaZeleznicSource.ID ||
                                        x.inUsteckyKraj()
                                )
                        )
            }
        }
    }

    // ── pohyb všech vozidel na jednom místě ──
    val hub = remember(routes, scope) { MotionHub(routes, scope) }
    DisposableEffect(hub) { onDispose { hub.clear() } }
    val predict = appState.predictVehiclePositions
    LaunchedEffect(activeVehicles, predict) { hub.update(activeVehicles, predict) }

    // Google: jedna smyčka synchronizovaná s vykreslováním (každý snímek), žádný delay, žádné čekání na kameru.
    // Pohyb vozidel se tak nezastaví ani při posunu / zoomu mapy.
    LaunchedEffect(hub, active, appState.mapProvider) {
        if (!active || appState.mapProvider != MapProvider.GOOGLE) return@LaunchedEffect
        var view: ViewBox? = null
        var viewAt = 0L
        while (true) {
            withFrameNanos { }
            val now = SystemClock.elapsedRealtime()
            hub.tick(now)
            if (now - viewAt >= GOOGLE_VIEW_REFRESH_MS || view == null) {
                viewAt = now
                view = cameraPositionState.projection?.visibleRegion?.latLngBounds?.let {
                    ViewBox(
                        it.southwest.latitude, it.southwest.longitude,
                        it.northeast.latitude, it.northeast.longitude
                    ).expanded(0.3)
                } ?: view
            }
            hub.applyToMarkerStates(view)
        }
    }

    // Google: viditelná oblast pro šipky (aktualizuje se, až když se kamera zastaví)
    var arrowView by remember { mutableStateOf<ViewBox?>(null) }
    LaunchedEffect(cameraPositionState.isMoving, appState.tripRoute) {
        if (!cameraPositionState.isMoving) {
            arrowView = cameraPositionState.projection?.visibleRegion?.latLngBounds?.let {
                ViewBox(
                    it.southwest.latitude, it.southwest.longitude,
                    it.northeast.latitude, it.northeast.longitude
                ).expanded(0.5)
            }
        }
    }

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
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                mapColorScheme = scheme,
                onMapLoaded = { isMapReady = true },
                properties = MapProperties(isMyLocationEnabled = isMapReady && hasLocationPermission),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false
                )
            ) {
                if (!isMapReady) return@GoogleMap

                val primaryColor = MaterialTheme.colorScheme.primary
                val arrowArgb = MaterialTheme.colorScheme.onBackground.toArgb()

                // zoom po půlkách: velikost / rozestup šipek se nepřepočítává při každé setině zoomu
                val zoomBucket by remember { derivedStateOf { (cameraPositionState.position.zoom * 2).toInt() } }
                val arrowScale = remember(zoomBucket) { arrowSizeForZoom(zoomBucket / 2f) }
                val arrowSpacing = remember(zoomBucket) { arrowSpacingForZoom(zoomBucket / 2f) }

                val arrowIcon = remember(arrowArgb, arrowScale) {
                    routeArrowDescriptor(primaryColor = arrowArgb, scale = arrowScale)
                }

                val walkList = remember(appState.journeyWalks, appState.walkRoute) {
                    if (appState.journeyWalks.isNotEmpty()) appState.journeyWalks
                    else listOfNotNull(appState.journeyWalk ?: appState.walkRoute)
                }
                for (walk in walkList) {
                    if (walk.points.size >= 2 && appState.tripRoute.isNotEmpty()) {
                        val walkPoints = remember(walk) { walk.points.map { LatLng(it.first, it.second) } }
                        Polyline(
                            points = walkPoints,
                            color = MaterialTheme.colorScheme.tertiary,
                            width = 14f,
                            zIndex = 0.2f,
                            pattern = listOf(Dot(), Gap(14f)),
                            startCap = RoundCap(),
                            endCap = RoundCap(),
                            jointType = JointType.ROUND
                        )
                    }
                }

                if (appState.tripRoute.isNotEmpty()) {
                    val full = appState.fullRoute
                    val segments = if (showFullRoute && full.size >= 2) listOf(full)
                    else if (appState.tripRouteSegments.isNotEmpty()) appState.tripRouteSegments
                    else listOf(appState.tripRoute)

                    for (line in segments) {
                        if (line.size >= 2) {
                            Polyline(
                                points = line,
                                color = primaryColor,
                                width = 12f,
                                zIndex = 0f,
                                startCap = RoundCap(),
                                endCap = RoundCap(),
                                jointType = JointType.ROUND
                            )
                        }
                    }
                }

                if (appState.tripRoute.size >= 2) {
                    val route = appState.tripRoute

                    // výpočet šipek mimo main vlákno, kreslí se jen ty ve viditelné oblasti
                    val arrows by produceState(emptyList<RouteArrow>(), route, arrowSpacing) {
                        value = withContext(Dispatchers.Default) {
                            createRouteArrows(route = route, spacingMeters = arrowSpacing)
                        }
                    }
                    val visibleArrows = remember(arrows, arrowView) {
                        val v = arrowView
                        if (v == null) arrows
                        else arrows.filter { v.contains(it.position.latitude, it.position.longitude) }
                    }

                    visibleArrows.forEach { arrow ->
                        key(arrow) {
                            Marker(
                                state = rememberUpdatedMarkerState(position = arrow.position),
                                icon = arrowIcon,
                                anchor = Offset(0.5f, 0.5f),
                                flat = true,
                                rotation = arrow.bearing,
                                zIndex = 1f,
                                onClick = { true }
                            )
                        }
                    }

                    val full = appState.fullRoute
                    val useFull = showFullRoute && full.size >= 2
                    val startPos = if (useFull) full.first() else route.first()
                    val endPos = if (useFull) full.last() else route.last()

                    val startIcon = remember(context) { routeStartDescriptor(context) }
                    val endIcon = remember(context) { routeEndDescriptor(context) }

                    // Začátek trasy (zelený kruh)
                    Marker(
                        state = rememberUpdatedMarkerState(position = startPos),
                        icon = startIcon,
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 0.5f,
                        onClick = { true }
                    )

                    // Konec trasy (červený kruh)
                    Marker(
                        state = rememberUpdatedMarkerState(position = endPos),
                        icon = endIcon,
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 0.5f,
                        onClick = { true }
                    )
                }

                for ((vkey, v) in activeVehicles) {
                    key(vkey) {
                        val iconPhase = if (target != null && v.uid == target.uid) phase else 0
                        val icon = remember(v.line, v.train, v.delay > 3, iconPhase) {
                            markerDescriptor(context, v.line, v.train, v.delay, iconPhase)
                        }
                        Marker(
                            state = hub.markerState(vkey, v),
                            icon = icon,
                            anchor = Offset(0.5f, 0.5f),
                            zIndex = if (iconPhase != 0) 3f else 2f,
                            onClick = { appState.selectedVehicle = v; true }
                        )
                    }
                }
            }
        } else {
            MapLibreScreenContent(
                modifier = Modifier.fillMaxSize(),
                appState = appState,
                active = active,
                useDarkMap = useDarkMap,
                activeVehicles = activeVehicles,
                primaryColor = MaterialTheme.colorScheme.primary,
                showFullRoute = showFullRoute,
                phase = phase,
                target = target,
                onMapReady = { maplibreMapRef = it },
                onStyleLoaded = { isMapReady = true },
                onMapDisposed = {
                    maplibreMapRef = null
                    isMapReady = false
                },
                hasLocationPermission = hasLocationPermission,
                tertiaryColor = MaterialTheme.colorScheme.tertiary,
                arrowColor = MaterialTheme.colorScheme.onBackground,
                hub = hub
            )
        }

        var showHeldInfo by remember { mutableStateOf(false) }

        ElevatedCard(
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column {
                if (!appState.showOnlyMapFilters) {
                    Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Mapa vozidel", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (!appState.vehiclesLoaded && appState.vehicles.isEmpty()) "Načítání poloh vozidel…"
                                else if (mapMode == ModeFilter.ALL) "${appState.vehicles.size} poloh · aktualizace po 6s"
                                else "${activeVehicles.size} z ${appState.vehicles.size} poloh · aktualizace po 6s",
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
                            Text("Offline", color = MaterialTheme.colorScheme.error)
                        }
                        //if (appState.heldCount > OUTAGE_MIN) {
                        IconButton(onClick = { showHeldInfo = true }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "Upozornění na zdroj dat",
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
                    ModeFilter.entries.forEach { mode ->
                        FilterChip(
                            selected = appState.mapModeFilter == mode,
                            onClick = { appState.mapModeFilter = mode },
                            label = { Text(mode.label) },
                            leadingIcon = when (mode) {
                                ModeFilter.BUS -> ({ Icon(Icons.Default.DirectionsBus, null, Modifier.size(18.dp)) })
                                ModeFilter.TRAIN -> ({ Icon(Icons.Default.Train, null, Modifier.size(18.dp)) })
                                ModeFilter.ALL -> null
                            }
                        )
                    }
                }
            }
        }

        if (active && showHeldInfo) {
            AlertDialog(
                onDismissRequest = { showHeldInfo = false },
                icon = { Icon(Icons.Default.Info, null) },
                title = { Text("Zdroje dat") },
                text = {
                    Text(
                        "Některé zdroje nemusí poskytovat aktuální polohu – ty starší jsou označené červeně. Přesné stáří dat najdete v detailu konkrétního vozidla."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showHeldInfo = false }) { Text("Rozumím") }
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
                    appState.tripRouteSegments = emptyList()
                    appState.fullRoute = emptyList()
                    appState.tripStops = emptyList()
                    appState.focusedTripVehicle = null
                    appState.focusedTripRef = null
                    appState.highlightedTrip = null
                    appState.focusTrip = null
                    appState.walkRoute = null
                    appState.journeyWalks = emptyList()
                }
            ) { Icon(Icons.Default.Close, contentDescription = "Skrýt trasu") }
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
        ) { Icon(Icons.Default.MyLocation, contentDescription = "Moje poloha") }

        if (active) {
            appState.selectedVehicle?.let { v ->
                VehicleDetailBottomSheet(v, api, appState) { appState.selectedVehicle = null }
            }
        }

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
                            text = "Načítání mapy…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}


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

// popis vozidla pro vykreslení: ikona + řazení, přepočítá se jen při změně vozidel / zvýraznění
private class VehicleDraw(val key: String, val imgId: String, val sort: Int)

private fun ensureVehicleLayer(style: Style) {
    if (style.getSource(VEH_SRC) == null) {
        style.addSource(GeoJsonSource(VEH_SRC, FeatureCollection.fromFeatures(emptyList<Feature>())))
    }
    if (style.getLayer(VEH_LAYER) == null) {
        style.addLayer(
            SymbolLayer(VEH_LAYER, VEH_SRC).withProperties(
                PropertyFactory.iconImage(Expression.get("img")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                // vyšší hodnota = kreslí se výš (zvýrazněné vozidlo nahoře)
                PropertyFactory.symbolSortKey(Expression.get("sort"))
            )
        )
    }
}

// vrstvy trasy vždy POD vrstvou vozidel
private fun addBelowMarkers(style: Style, layer: Layer) {
    ensureVehicleLayer(style)
    style.addLayerBelow(layer, VEH_LAYER)
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
    target: Vehicle?,
    hasLocationPermission: Boolean,
    onMapReady: (MapLibreMap) -> Unit,
    onStyleLoaded: () -> Unit = {},
    tertiaryColor: Color,
    arrowColor: Color,
    hub: MotionHub,
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
    var appliedDark by remember { mutableStateOf<Boolean?>(null) }

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
    val latestTarget by rememberUpdatedState(target)

    val dirty = remember { AtomicBoolean(true) }
    // kamera se pohnula => přepočítat, která vozidla jsou ve viditelné oblasti
    val cameraDirty = remember { AtomicBoolean(true) }
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
                        val cp = m.cameraPosition
                        cp.target?.let {
                            appState.mapCenter = LatLng(it.latitude, it.longitude)
                            appState.mapZoom = cp.zoom.toFloat() + 1f
                        }
                    }

                    // klik na vozidlo: výpočet vzdálenosti v Kotlinu (zcela async / neblokující, žádné queryRenderedFeatures)
                    m.addOnMapClickListener { latLng ->
                        if (m.style?.getLayer(VEH_LAYER) == null) return@addOnMapClickListener false
                        val clickPt = m.projection.toScreenLocation(latLng)
                        val radiusPx = 28f * density
                        var clickedVehicle: Vehicle? = null
                        var minSq = Float.MAX_VALUE

                        for ((k, v) in latestVehicles) {
                            val pos = hub.positionOf(k) ?: LatLng(v.lat, v.lng)
                            val vehPt = m.projection.toScreenLocation(MapLibreLatLng(pos.latitude, pos.longitude))
                            val dx = vehPt.x - clickPt.x
                            val dy = vehPt.y - clickPt.y
                            val distSq = dx * dx + dy * dy
                            if (distSq <= radiusPx * radiusPx && distSq < minSq) {
                                minSq = distSq
                                clickedVehicle = v
                            }
                        }

                        if (clickedVehicle != null) {
                            appState.selectedVehicle = clickedVehicle
                            true
                        } else false
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
    LaunchedEffect(activeVehicles, phase, target) { dirty.set(true) }

    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
        if (m.style?.isFullyLoaded == true) {
            onStyleLoaded()
        }
    }

    // ── styl se nastavuje jen jednou a jen při změně tématu ──
    LaunchedEffect(useDarkMap, map) {
        val m = map ?: return@LaunchedEffect
        if (appliedDark == useDarkMap) {
            if (m.style?.isFullyLoaded == true) onStyleLoaded()
            return@LaunchedEffect
        }
        appliedDark = useDarkMap
        m.setStyle(Style.Builder().fromUri(cartoStyleUrl(useDarkMap))) { style ->
            if (useDarkMap) softenDark(style)
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
                        .build()
                )
                isLocationComponentEnabled = true
                cameraMode = CameraMode.NONE
                renderMode = RenderMode.COMPASS
            }
        }
    }

    val primaryColorArgb = primaryColor.toArgb()
    val tertiaryArgb = tertiaryColor.toArgb()
    val arrowArgb = arrowColor.toArgb()
    val walkList = if (appState.journeyWalks.isNotEmpty()) appState.journeyWalks
    else listOfNotNull(appState.journeyWalk ?: appState.walkRoute)
    val tripRoute = appState.tripRoute
    val tripRouteSegments = appState.tripRouteSegments
    val fullRoute = appState.fullRoute

    // ── TRASY (nezávisí na zoomu, šipky a tečky kreslí MapLibre nativně) ──
    LaunchedEffect(
        map, tripRoute, tripRouteSegments, fullRoute, walkList, showFullRoute,
        primaryColorArgb, tertiaryArgb, arrowArgb, styleVersion
    ) {
        val m = map ?: return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        ensureVehicleLayer(style) // vrstva vozidel musí existovat dřív než trasy a být nad nimi
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
        }
    }

    // ── VOZIDLA: jedna smyčka synchronizovaná s vykreslováním, nikdy se nezastavuje (ani při pohybu kamery) ──
    LaunchedEffect(map, styleVersion, active) {
        val m = map ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect

        var draws: List<VehicleDraw> = emptyList()
        var lastPush = 0L

        while (true) {
            withFrameNanos { }
            val now = SystemClock.elapsedRealtime()
            if (now - lastPush < VEHICLE_UPDATE_INTERVAL_MS) continue

            val moved = hub.tick(now)
            val metaChanged = dirty.getAndSet(false)
            val camChanged = cameraDirty.getAndSet(false)
            if (!moved && !metaChanged && !camChanged) continue

            val style = m.style
            if (style == null || !style.isFullyLoaded) {
                dirty.set(true); continue
            }
            ensureVehicleLayer(style)

            if (metaChanged || draws.isEmpty()) {
                val vehicles = latestVehicles
                val tgt = latestTarget
                val ph = latestPhase

                val list = ArrayList<VehicleDraw>(vehicles.size)
                val missing = LinkedHashMap<String, Vehicle>()
                for ((k, v) in vehicles) {
                    val iconPhase = if (tgt != null && v.uid == tgt.uid) ph else 0
                    val imgId = "veh|${v.line}|${v.train}|${v.delay > 3}|$iconPhase"
                    list += VehicleDraw(k, imgId, if (iconPhase != 0) 2 else 1)
                    if (!addedImages.contains(imgId) && imgId !in missing) missing[imgId] = v
                }
                draws = list

                if (missing.isNotEmpty()) {
                    val newBitmaps = withContext(Dispatchers.Default) {
                        missing.map { (imgId, v) ->
                            val iconPhase = if (tgt != null && v.uid == tgt.uid) ph else 0
                            val bmp = markerBitmap(context, v.line, v.train, v.delay, iconPhase)
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
            }

            // do zdroje jen vozidla v (rozšířené) viditelné oblasti
            val view = runCatching {
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
                    ).expanded(0.4)
                }
            }.getOrNull()

            val features = ArrayList<Feature>(draws.size)
            for (d in draws) {
                val p = hub.positionOf(d.key) ?: continue
                if (view != null && !view.contains(p.latitude, p.longitude)) continue
                features += Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).also {
                    it.addStringProperty("img", d.imgId)
                    it.addStringProperty("key", d.key)
                    it.addNumberProperty("sort", d.sort)
                }
            }

            style.getSourceAs<GeoJsonSource>(VEH_SRC)
                ?.setGeoJson(FeatureCollection.fromFeatures(features))
            lastPush = now
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

private fun removeRouteLayers(style: Style) {
    listOf("route-ends", "route-arrows", "walk-dots", "route-line").forEach { style.removeLayer(it) }
    listOf("ends-src", "arrows-src", "walk-src", "route-src").forEach { style.removeSource(it) }
    listOf("route-arrow", "route-start", "route-end").forEach { style.removeImage(it) }
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
    withStyle(SpanStyle(color = normal)) { append("Zdroj: ") }
    if (label == SourceLabel.NONE) {
        withStyle(SpanStyle(color = bad)) { append(label) }
        return@buildAnnotatedString
    }
    splitSources(label).forEachIndexed { i, part ->
        if (i > 0) withStyle(SpanStyle(color = normal)) { append(", ") }
        val stale = "zastaral" in part.lowercase()
        withStyle(SpanStyle(color = if (stale) bad else normal)) { append(part) }
    }
}