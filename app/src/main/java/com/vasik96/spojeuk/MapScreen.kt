@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.android.style.expressions.Expression
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import org.maplibre.android.annotations.Annotation as MapLibreAnnotation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import org.maplibre.android.style.layers.Layer
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.android.annotations.Marker as MapLibreMarker
import org.maplibre.android.camera.CameraPosition as MapLibreCameraPosition
import org.maplibre.android.camera.CameraUpdateFactory as MapLibreCameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MapLibreLatLng
import org.maplibre.android.maps.MapView as MapLibreMapView
import androidx.core.graphics.createBitmap
import com.google.maps.android.compose.MapProperties
import kotlin.text.toInt
import kotlin.times


@SuppressLint("MissingPermission")
@Composable
internal fun MapScreen(modifier: Modifier = Modifier, appState: SpojeAppState, api: TransitRepository, active: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showFullRoute by remember { mutableStateOf(false) }

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



    val focused = appState.focusedTripVehicle
    val trackingTrip = appState.focusedTripRef != null
    val keyer = remember { VehicleKeyer() }
    val routes = remember(api) { VehicleRoutes(api) }
    val keyed = remember(allVehicles) { keyer.assign(allVehicles) }

    val activeVehicles: List<Pair<String, Vehicle>> =
        if (strictMode && appState.hideOtherVehiclesOnMap && locked != null) {
            val match = keyed.firstOrNull { it.second.uid == locked.uid }
            listOf(match ?: ("locked_${locked.uid}" to locked))
        } else {
            keyed
            // filter na ustecky kraj
                /*
                .filter { (_, x) ->
                (target != null && x.uid == target.uid) ||
                        (x.lat in 50.35..50.95 && x.lng in 13.55..14.60)
            }*/
        }

    LaunchedEffect(allVehicles) {
        android.util.Log.d("Motion", "id0=${allVehicles.count { it.id == 0 }} / ${allVehicles.size}")
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
                properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false
                )
            ) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val arrowArgb = MaterialTheme.colorScheme.onBackground.toArgb()

                val arrowScale by remember { derivedStateOf { arrowSizeForZoom(cameraPositionState.position.zoom) } }
                val arrowSpacing by remember { derivedStateOf { arrowSpacingForZoom(cameraPositionState.position.zoom) } }

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
                    val stops = appState.tripStops

                    val arrows = remember(route, arrowSpacing) {
                        createRouteArrows(route = route, spacingMeters = arrowSpacing)
                    }

                    arrows.forEachIndexed { index, arrow ->
                        key("arrow_$index") {
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
                        val motion = remember { VehicleMotion(v.lat, v.lng, v.observedAtMs) }
                        val markerState = remember { MarkerState(LatLng(v.lat, v.lng)) }
                        val predict = appState.predictVehiclePositions

                        // trasa spoje tohoto vozidla (jen při zapnuté predikci)
                        var route by remember { mutableStateOf<PathMeasure?>(null) }
                        val sig = "${v.tid}|${v.cis}|${v.trip}"
                        LaunchedEffect(sig, predict) {
                            route = null
                            if (!predict) return@LaunchedEffect
                            var tries = 0
                            while (tries < 4) {
                                val r = routes.get(v)
                                if (r != null) { route = r; break }
                                tries++
                                delay(20_000)
                            }
                        }

                        val lat = v.lat
                        val lng = v.lng
                        val obs = v.observedAtMs
                        LaunchedEffect(lat, lng, route, predict) {
                            motion.setRoute(if (predict) route else null)
                            motion.onFix(lat, lng, obs)
                            motion.animate(markerState, predict)
                        }

                        val iconPhase = if (target != null && v.uid == target.uid) phase else 0
                        Marker(
                            state = markerState,
                            icon = markerDescriptor(context, v.line, v.train, v.delay, iconPhase),
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
                useDarkMap = useDarkMap,
                activeVehicles = activeVehicles,
                primaryColor = MaterialTheme.colorScheme.primary,
                showFullRoute = showFullRoute,
                phase = phase,
                target = target,
                onMapReady = { maplibreMapRef = it },
                hasLocationPermission = hasLocationPermission,
                tertiaryColor = MaterialTheme.colorScheme.tertiary,
                arrowColor = MaterialTheme.colorScheme.onBackground,
                routes = routes
            )
        }

        var showHeldInfo by remember { mutableStateOf(false) }

        ElevatedCard(
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mapa vozidel", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${appState.vehicles.size} poloh · aktualizace po 6s",
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
                if (appState.routeLoading) { Wavy(24.dp); Spacer(Modifier.width(8.dp)) }
                if (appState.mapError != null) {
                    Text("Offline", color = MaterialTheme.colorScheme.error)
                }
                if (appState.heldCount > OUTAGE_MIN) {
                    IconButton(onClick = { showHeldInfo = true }) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Upozornění na zdroj dat",
                        )
                    }
                }
            }
        }

        if (active && showHeldInfo) {
            AlertDialog(
                onDismissRequest = { showHeldInfo = false },
                icon = { Icon(Icons.Default.Warning, null) },
                title = { Text("API nespolehlivé") },
                text = {
                    Text(
                        "Zdroj dat (Portabo) dočasně nedodává ${appState.heldCount} vozidel, " +
                                "většinou MHD/DPMUL. U nich zobrazuji poslední známou polohu, může být zastaralá. " +
                                "Detail konkrétního vozidla ukáže, jak stará poloha je."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showHeldInfo = false }) { Text("Rozumím") }
                }
            )
        }

        // logo Google je vlevo dole, proto odsazení
        // tlacitko X (vykrizkovani)
        if (appState.tripRoute.isNotEmpty()) {
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
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 18.dp)
            ) { Icon(Icons.Default.Close, contentDescription = "Skrýt trasu") }
        }

        FloatingActionButton(
            onClick = {
                if (!hasLocationPermission) {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                } else {
                    LocationServices.getFusedLocationProviderClient(context).lastLocation
                        .addOnSuccessListener { loc ->
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

    }
}

private fun closestPointOnRoute(
    point: LatLng,
    route: List<LatLng>
): LatLng {
    if (route.isEmpty()) return point

    if (route.size == 1) return route.first()

    var closest = route.first()
    var minDistance = Double.MAX_VALUE

    for (i in 0 until route.lastIndex) {
        val a = route[i]
        val b = route[i + 1]

        val dx = b.longitude - a.longitude
        val dy = b.latitude - a.latitude

        val lengthSquared = dx * dx + dy * dy

        val t = if (lengthSquared == 0.0) {
            0.0
        } else {
            (
                    ((point.longitude - a.longitude) * dx +
                            (point.latitude - a.latitude) * dy) / lengthSquared
                    ).coerceIn(0.0, 1.0)
        }

        val projected = LatLng(
            a.latitude + t * dy,
            a.longitude + t * dx
        )

        val distance =
            (point.latitude - projected.latitude) *
                    (point.latitude - projected.latitude) +
                    (point.longitude - projected.longitude) *
                    (point.longitude - projected.longitude)

        if (distance < minDistance) {
            minDistance = distance
            closest = projected
        }
    }

    return closest
}


private fun cartoStyleUrl(dark: Boolean): String {
    val name = if (dark) "dark-matter-gl-style" else "voyager-gl-style"
    return "https://basemaps.cartocdn.com/gl/$name/style.json?key=${BuildConfig.CARTO_API_KEY}"
}



@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
@Composable
private fun MapLibreScreenContent(
    modifier: Modifier = Modifier,
    appState: SpojeAppState,
    useDarkMap: Boolean,
    activeVehicles: List<Pair<String, Vehicle>>,
    primaryColor: Color,
    showFullRoute: Boolean,
    phase: Int,
    target: Vehicle?,
    hasLocationPermission: Boolean,
    onMapReady: (MapLibreMap) -> Unit,
    tertiaryColor: Color,
    arrowColor: Color,
    routes: VehicleRoutes,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember(context) {
        MapLibreMapView(context)
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onDestroy() }
        }
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleVersion by remember { mutableIntStateOf(0) }
    var appliedDark by remember { mutableStateOf<Boolean?>(null) }

    // co je aktuálně na mapě (aby se nekreslilo vše znovu)
    val routeAnnotations = remember { mutableListOf<MapLibreAnnotation>() }
    val vehicleMarkers = remember { mutableMapOf<String, MapLibreMarker>() }
    val vehicleLook = remember { mutableMapOf<String, String>() }
    val latestVehicles by rememberUpdatedState(activeVehicles)

    val density = context.resources.displayMetrics.density
    var googleZoom by remember { mutableFloatStateOf(appState.mapZoom) }
    val arrowScale by remember { derivedStateOf { arrowSizeForZoom(googleZoom) } }
    val arrowSpacing by remember { derivedStateOf { arrowSpacingForZoom(googleZoom) } }
    val dotBucket by remember { derivedStateOf { (googleZoom * 4f).toInt() } }

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { m ->
                    // kamera hned, ne až po načtení stylu
                    m.cameraPosition = MapLibreCameraPosition.Builder()
                        .target(MapLibreLatLng(appState.mapCenter.latitude, appState.mapCenter.longitude))
                        .zoom(appState.mapZoom.toDouble() - 1.0)
                        .build()
                    m.uiSettings.isAttributionEnabled = false
                    m.uiSettings.isLogoEnabled = false
                    m.uiSettings.isCompassEnabled = false
                    m.addOnCameraMoveListener {
                        m.cameraPosition.target?.let {
                            appState.mapCenter = LatLng(it.latitude, it.longitude)
                            val z = m.cameraPosition.zoom.toFloat() + 1f
                            appState.mapZoom = z
                            googleZoom = z
                        }
                    }
                    map = m
                    onMapReady(m)
                }
            }
        },
        modifier = modifier
    )

    val predict = appState.predictVehiclePositions
    for ((vkey, v) in activeVehicles) {
        key(vkey) {
            MapLibreVehicleMotion(v, routes, predict) { pos ->
                vehicleMarkers[vkey]?.position = MapLibreLatLng(pos.latitude, pos.longitude)
            }
        }
    }

    // styl se nastavuje jen jednou a jen při změně tématu
    LaunchedEffect(useDarkMap, map) {
        val m = map ?: return@LaunchedEffect
        if (appliedDark == useDarkMap) return@LaunchedEffect
        appliedDark = useDarkMap
        m.setStyle(Style.Builder().fromUri(cartoStyleUrl(useDarkMap))) { style ->
            if (useDarkMap) softenDark(style)
            m.clear()
            routeAnnotations.clear()
            vehicleMarkers.clear()
            vehicleLook.clear()
            styleVersion++
        }
    }

    // modrý bod polohy
    LaunchedEffect(map, hasLocationPermission, styleVersion) {
        val m = map ?: return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        if (!hasLocationPermission || !style.isFullyLoaded) return@LaunchedEffect
        runCatching {
            m.locationComponent.apply {
                activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, style).build()
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
    val walkList = if (appState.journeyWalks.isNotEmpty()) appState.journeyWalks else listOfNotNull(appState.journeyWalk ?: appState.walkRoute)
    val tripRoute = appState.tripRoute
    val tripRouteSegments = appState.tripRouteSegments
    val fullRoute = appState.fullRoute

    // TRASY
    LaunchedEffect(map, tripRoute, tripRouteSegments, fullRoute, walkList, showFullRoute, primaryColorArgb, tertiaryArgb, arrowArgb, arrowScale, arrowSpacing, dotBucket, styleVersion) {
        val m = map ?: return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        routeAnnotations.forEach { m.removeAnnotation(it) }
        routeAnnotations.clear()
        removeRouteLayers(style)

        if (tripRoute.size >= 2) {
            val segments = if (showFullRoute && fullRoute.size >= 2) listOf(fullRoute)
            else if (tripRouteSegments.isNotEmpty()) tripRouteSegments
            else listOf(tripRoute)

            val routeFeatures = segments.mapNotNull { seg ->
                if (seg.size >= 2) Feature.fromGeometry(seg.toLineString()) else null
            }

            if (routeFeatures.isNotEmpty()) {
                // hlavní trasa
                style.addSource(GeoJsonSource("route-src", FeatureCollection.fromFeatures(routeFeatures)))
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

            // pěší trasa: tečky
            val walkFeatures = mutableListOf<Feature>()
            for (w in walkList) {
                if (w.points.size >= 2) {
                    val pts = w.points.map { LatLng(it.first, it.second) }
                    val zoom = dotBucket / 4f
                    val mpp = metersPerPixel(zoom, pts.first().latitude)      // metry na dp
                    val totalMeters = routeLength(pts)

                    val wantedDp = 28f / density                              // rozestup jako u Google
                    val minDots = 14
                    val spacingDp = minOf(wantedDp.toDouble(), totalMeters / mpp / minDots).coerceAtLeast(3.0)

                    val dots = dotsAlong(pts, spacingDp * mpp)
                    dots.forEach { dot ->
                        walkFeatures.add(Feature.fromGeometry(Point.fromLngLat(dot.longitude, dot.latitude)))
                    }
                }
            }
            if (walkFeatures.isNotEmpty()) {
                val dotDp = 14f / density
                style.addSource(GeoJsonSource("walk-src", FeatureCollection.fromFeatures(walkFeatures)))
                style.addImage("walk-dot", walkDotBitmap(tertiaryArgb))
                addBelowMarkers(
                    style,
                    SymbolLayer("walk-dots", "walk-src").withProperties(
                        PropertyFactory.iconImage("walk-dot"),
                        PropertyFactory.iconSize((dotDp / 32f).coerceAtLeast(0.15f)),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                )
            }

            // šipky
            val arrows = createRouteArrows(route = tripRoute, spacingMeters = arrowSpacing)
            if (arrows.isNotEmpty()) {
                val features = arrows.map { a ->
                    Feature.fromGeometry(Point.fromLngLat(a.position.longitude, a.position.latitude)).also {
                        it.addNumberProperty("bearing", a.bearing)
                    }
                }
                style.addSource(GeoJsonSource("arrows-src", FeatureCollection.fromFeatures(features)))
                style.addImage("route-arrow", routeArrowBitmap(arrowArgb))
                val iconPx = (96f * arrowScale).coerceAtLeast(18f)
                addBelowMarkers(
                    style,
                    SymbolLayer("route-arrows", "arrows-src").withProperties(
                        PropertyFactory.iconImage("route-arrow"),
                        PropertyFactory.iconSize(iconPx / 96f / density),
                        PropertyFactory.iconRotate(Expression.get("bearing")),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                )
            }

            // začátek a konec jako vrstva (pod autobusy)
            val routeLine = if (showFullRoute && fullRoute.size >= 2) fullRoute else tripRoute
            val s = routeLine.first()
            val e = routeLine.last()
            val ends = FeatureCollection.fromFeatures(
                listOf(
                    Feature.fromGeometry(Point.fromLngLat(s.longitude, s.latitude)).also { it.addStringProperty("img", "route-start") },
                    Feature.fromGeometry(Point.fromLngLat(e.longitude, e.latitude)).also { it.addStringProperty("img", "route-end") }
                )
            )
            style.addSource(GeoJsonSource("ends-src", ends))
            style.addImage("route-start", routeStartBitmap(context))
            style.addImage("route-end", routeEndBitmap(context))
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

    // VOZIDLA: jen přesun existujících markerů, nový icon jen když se změnil vzhled
    LaunchedEffect(map, activeVehicles, phase, target, styleVersion) {
        val m = map ?: return@LaunchedEffect
        if (m.style == null) return@LaunchedEffect
        val iconFactory = IconFactory.getInstance(context)
        val wanted = activeVehicles.toMap()

        (vehicleMarkers.keys - wanted.keys).toList().forEach { k ->
            vehicleMarkers.remove(k)?.let { m.removeMarker(it) }
            vehicleLook.remove(k)
        }

        for ((k, v) in wanted) {
            val iconPhase = if (target != null && v.uid == target.uid) phase else 0
            val look = "${v.line}|${v.train}|${v.delay}|$iconPhase"
            val pos = MapLibreLatLng(v.lat, v.lng)
            val existing = vehicleMarkers[k]
            if (existing == null) {
                vehicleMarkers[k] = m.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .icon(iconFactory.fromBitmap(markerBitmap(context, v.line, v.train, v.delay, iconPhase)))
                )
                vehicleLook[k] = look
            } else {
                existing.title = v.uid
                if (vehicleLook[k] != look) {
                    existing.icon = iconFactory.fromBitmap(markerBitmap(context, v.line, v.train, v.delay, iconPhase))
                    vehicleLook[k] = look
                }
            }
        }

        m.setOnMarkerClickListener { marker ->
            val key = vehicleMarkers.entries.firstOrNull { it.value == marker }?.key
            val v = key?.let { k -> latestVehicles.firstOrNull { it.first == k }?.second }
            if (v != null) appState.selectedVehicle = v
            true // vždy true, jinak MapLibre ukáže info okno s titlem
        }
    }
}


@Composable
private fun MapLibreVehicleMotion(
    v: Vehicle,
    routes: VehicleRoutes,
    predict: Boolean,
    onPosition: (LatLng) -> Unit,
) {
    val motion = remember { VehicleMotion(v.lat, v.lng, v.observedAtMs) }
    val markerState = remember { MarkerState(LatLng(v.lat, v.lng)) }
    val onPos by rememberUpdatedState(onPosition)

    var route by remember { mutableStateOf<PathMeasure?>(null) }
    val sig = "${v.tid}|${v.cis}|${v.trip}"
    LaunchedEffect(sig, predict) {
        route = null
        if (!predict) return@LaunchedEffect
        var tries = 0
        while (tries < 4) {
            val r = routes.get(v)
            if (r != null) { route = r; break }
            tries++
            delay(20_000)
        }
    }

    val lat = v.lat
    val lng = v.lng
    val obs = v.observedAtMs
    LaunchedEffect(lat, lng, route, predict) {
        motion.setRoute(if (predict) route else null)
        motion.onFix(lat, lng, obs)
        motion.animate(markerState, predict)
    }

    // každou animovanou pozici přepošli do MapLibre markeru
    LaunchedEffect(markerState) {
        snapshotFlow { markerState.position }.collect { onPos(it) }
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

private fun walkDotBitmap(color: Int): Bitmap {
    val size = 32
    val bitmap = createBitmap(size, size)
    bitmap.density = android.util.DisplayMetrics.DENSITY_DEFAULT
    Canvas(bitmap).drawCircle(
        size / 2f, size / 2f, size / 2f - 1f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    )
    return bitmap
}

private fun removeRouteLayers(style: Style) {
    listOf("route-ends", "route-arrows", "walk-dots", "route-line").forEach { style.removeLayer(it) }
    listOf("ends-src", "arrows-src", "walk-src", "route-src").forEach { style.removeSource(it) }
    listOf("route-arrow", "walk-dot", "route-start", "route-end").forEach { style.removeImage(it) }
}

// vrstvy trasy vždy POD legacy markery (vozidla), jejichž vrstva je "com.mapbox.annotations.points"
private fun addBelowMarkers(style: Style, layer: Layer) {
    val ref = style.layers.firstOrNull { it.id.contains("annotation", ignoreCase = true) }?.id
    if (ref != null) style.addLayerBelow(layer, ref) else style.addLayer(layer)
}

private fun metersPerPixel(zoom: Float, lat: Double): Double =
    156543.03392 * Math.cos(Math.toRadians(lat)) / Math.pow(2.0, zoom.toDouble())

private fun dotsAlong(route: List<LatLng>, spacing: Double): List<LatLng> {
    val out = mutableListOf<LatLng>()
    if (route.size < 2 || spacing <= 0.0) return out
    var travelled = 0.0
    var next = spacing / 2
    for (i in 0 until route.lastIndex) {
        val a = route[i]
        val b = route[i + 1]
        val dx = (b.longitude - a.longitude) * Math.cos(Math.toRadians(a.latitude)) * 111_320.0
        val dy = (b.latitude - a.latitude) * 110_540.0
        val len = Math.hypot(dx, dy)
        if (len <= 0.0) continue
        while (next <= travelled + len) {
            val t = (next - travelled) / len
            out += LatLng(
                a.latitude + (b.latitude - a.latitude) * t,
                a.longitude + (b.longitude - a.longitude) * t
            )
            next += spacing
            if (out.size > 4000) return out
        }
        travelled += len
    }
    return out
}

private fun routeLength(route: List<LatLng>): Double {
    var sum = 0.0
    for (i in 0 until route.lastIndex) {
        val a = route[i]; val b = route[i + 1]
        val dx = (b.longitude - a.longitude) * Math.cos(Math.toRadians(a.latitude)) * 111_320.0
        val dy = (b.latitude - a.latitude) * 110_540.0
        sum += Math.hypot(dx, dy)
    }
    return sum
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