@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds


@SuppressLint("MissingPermission")
@Composable
internal fun MapScreen(modifier: Modifier = Modifier, appState: SpojeAppState, api: TransitRepository, active: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()


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
            appState.mapCenter = cameraPositionState.position.target
            appState.mapZoom = cameraPositionState.position.zoom
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
        if (appState.highlightedTrip == null) {
            appState.pulsePhase = 0; return@LaunchedEffect
        }
        if (!appState.vehiclesLoaded || appState.routeLoading) return@LaunchedEffect
        repeat(6) { i -> appState.pulsePhase = if (i % 2 == 0) 1 else 2; delay(400.milliseconds) }
        appState.pulsePhase = 0
        appState.highlightedTrip = null
    }

    // přiblížení po kliknutí na "Mapa"
    LaunchedEffect(appState.focusTrip, appState.vehiclesLoaded, appState.routeLoading) {
        val ref = appState.focusTrip ?: return@LaunchedEffect

        if (!appState.vehiclesLoaded || appState.routeLoading) return@LaunchedEffect
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
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(v.lat, v.lng),
                    16f
                ), 700
            )
        } else {
            appState.focusedTripVehicle = null
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
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng((lats.max() + lats.min()) / 2, (lons.max() + lons.min()) / 2), zoom
                    ), 700
                )
            }
        }
        appState.focusTrip = null
    }

    val phase = appState.pulsePhase
    val ref = appState.highlightedTrip
    val allVehicles = appState.vehicles
    val target = if (ref != null)
        findVehicle(allVehicles, ref, appState.fullRoute.ifEmpty { appState.tripRoute })?.vehicle
    else appState.focusedTripVehicle ?: appState.selectedVehicle


    val keyer = remember { VehicleKeyer() }
    val routes = remember(api) { VehicleRoutes(api) }
    val keyed = remember(allVehicles) { keyer.assign(allVehicles) }

    val focused = appState.focusedTripVehicle
    val activeVehicles: List<Pair<String, Vehicle>> =
        if (appState.hideOtherVehiclesOnMap && focused != null && appState.tripRoute.isNotEmpty()) {
            keyed.filter { it.second.sameAs(focused) }.ifEmpty { listOf("focused" to focused) }
        } else {
            keyed.filter { (_, x) ->
                (target != null && x.sameAs(target)) || (x.lat in 50.35..50.95 && x.lng in 13.55..14.60)
            }
        }

    LaunchedEffect(allVehicles) {
        android.util.Log.d("Motion", "id0=${allVehicles.count { it.id == 0 }} / ${allVehicles.size}")
    }


    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
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

            if (appState.tripRoute.isNotEmpty()) {
                Polyline(
                    points = appState.tripRoute,
                    color = primaryColor,
                    width = 12f,
                    zIndex = 0f,
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                    jointType = JointType.ROUND
                )
            }

            if (appState.tripRoute.size >= 2) {
                val route = appState.tripRoute

                val arrows = remember(route, arrowSpacing) {
                    createRouteArrows(route = route, spacingMeters = arrowSpacing)
                }

                arrows.forEachIndexed { index, arrow ->
                    key(index) {
                        Marker(
                            state = rememberUpdatedMarkerState(position = arrow.position),
                            icon = arrowIcon,
                            anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                            flat = true,
                            rotation = arrow.bearing,
                            zIndex = 1f,
                            onClick = { true }
                        )
                    }
                }
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

                    val iconPhase = if (target != null && v.sameAs(target)) phase else 0
                    Marker(
                        state = markerState,
                        icon = markerDescriptor(context, v.line, v.train, v.delay, iconPhase),
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        zIndex = if (iconPhase != 0) 3f else 2f,
                        onClick = { appState.selectedVehicle = v; true }
                    )
                }
            }
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
                            "Zdroj: ${appState.vehicleSource}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if ("zastaralé" in appState.vehicleSource || appState.vehicleSource == "žádný zdroj")
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
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
        if (appState.tripRoute.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = {
                    appState.tripRoute = emptyList()
                    appState.fullRoute = emptyList()
                    appState.focusedTripVehicle = null
                    appState.focusedTripRef = null
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 52.dp)
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
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 14f), 700
                                )
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