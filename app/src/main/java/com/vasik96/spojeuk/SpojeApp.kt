@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Kořen aplikace: téma, spodní navigace a tři záložky (Spoje, Mapa, Nastavení). */
@Composable
internal fun SpojeApp() {
    val context = LocalContext.current
    val appState = remember { SpojeAppState(context) }
    val api = remember { TransitRepository(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (appState.stops.isEmpty()) {
            appState.stopsLoading = true
            val guard = if (api.isFirstRun()) launch {
                delay(600)
                if (appState.stopsLoading) appState.showSetup = true
            } else null
            runCatching {
                api.loadStops(
                    log = { appState.setupMessage = it },
                    progress = { appState.setupProgress = it }
                )
            }
                .onSuccess { loaded -> appState.updateIndexedStops(loaded) }
                .onFailure {
                    if (it is CancellationException) throw it
                    appState.searchError = "Zastávky se nepodařilo načíst: ${it.message}"
                }
            guard?.cancel()
            appState.showSetup = false
            appState.stopsLoading = false
        }
    }

    // mapa se vytvoří až při první návštěvě a pak zůstane naživu
    var mapVisited by remember { mutableStateOf(false) }
    LaunchedEffect(appState.selectedTab) {
        if (appState.selectedTab == 1 && !mapVisited) {
            delay(120.milliseconds)
            mapVisited = true
        }
    }

    BackHandler(enabled = appState.selectedTab == 2) {
        if (appState.settingsSubscreen == SettingsSubscreen.DATA_UPDATES) {
            appState.settingsSubscreen = SettingsSubscreen.MAIN
        } else {
            appState.selectedTab = 0
        }
    }

    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("Spoje UK", style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("Doprava Ústeckého kraje", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            actions = { SourceBadges(); Spacer(Modifier.width(12.dp)) }
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = appState.selectedTab == 0,
                                onClick = { appState.selectedTab = 0 },
                                icon = { Icon(Icons.Default.DirectionsBus, null) },
                                label = { Text("Spoje") }
                            )
                            NavigationBarItem(
                                selected = appState.selectedTab == 1,
                                onClick = { appState.selectedTab = 1 },
                                icon = { Icon(Icons.Default.Map, null) },
                                label = { Text("Mapa") }
                            )
                            NavigationBarItem(
                                selected = appState.selectedTab == 2,
                                onClick = { appState.selectedTab = 2 },
                                icon = { Icon(Icons.Default.Settings, null) },
                                label = { Text("Nastavení") }
                            )
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        if (mapVisited) {
                            MapScreen(
                                modifier = Modifier.fillMaxSize(),
                                appState = appState,
                                api = api,
                                active = appState.selectedTab == 1
                            )
                        }

                        AnimatedVisibility(
                            visible = appState.selectedTab != 1,
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(150))
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {}
                        }

                        AnimatedVisibility(
                            visible = appState.selectedTab == 0,
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(150))
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize().pointerInput(Unit) {},
                                color = MaterialTheme.colorScheme.background
                            ) {
                                SearchScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    appState = appState,
                                    api = api,
                                    onShowMapForJourney = { journey ->
                                        val bp = api.boardingPoint(journey)
                                        val ref = TripRef(journey.cis, journey.trip, journey.line, bp?.first, bp?.second)
                                        appState.selectedVehicle = null
                                        appState.focusedTripVehicle = null
                                        appState.focusedTripRef = ref
                                        appState.tripRoute = emptyList()
                                        appState.fullRoute = emptyList()
                                        appState.routeLoading = true
                                        appState.vehiclesLoaded = false
                                        appState.focusTrip = ref
                                        appState.highlightedTrip = ref
                                        appState.selectedTab = 1
                                        scope.launch {
                                            val points = api.journeyGeometry(journey)
                                            val full = api.firstLegFullRoute(journey)
                                            appState.tripRoute = points.map { LatLng(it.first, it.second) }
                                            appState.fullRoute = full.map { LatLng(it.first, it.second) }
                                            appState.routeLoading = false
                                        }
                                    }
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = appState.selectedTab == 2,
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(150))
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize().pointerInput(Unit) {},
                                color = MaterialTheme.colorScheme.background
                            ) {
                                SettingsScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    appState = appState,
                                    api = api
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = appState.showSetup && !appState.setupDismissed,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    SetupOverlay(
                        message = appState.setupMessage,
                        progress = appState.setupProgress,
                        onBackground = { appState.setupDismissed = true }
                    )
                }
            }
        }
    }
}