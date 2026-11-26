@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.google.android.gms.maps.model.LatLng
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds



private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Kořen aplikace: téma, spodní navigace a tři záložky (Spoje, Mapa, Nastavení). */
@Composable
internal fun SpojeApp() {
    val context = LocalContext.current
    val appState = remember { SpojeAppState(context) }
    val api = remember { TransitRepository.get(context) }
    val scope = rememberCoroutineScope()

    // Inicializovat vozidla v appState ihned z paměti/cache repozitáře (bez čekání na síť)
    val initialVehicles = remember(api) { api.currentVehicles() }
    if (initialVehicles.isNotEmpty() && appState.vehicles.isEmpty()) {
        appState.updateVehicles(initialVehicles)
        appState.heldCount = api.heldCount()
        appState.vehicleSource = api.sourceLabel()
        appState.vehiclesLoaded = true
    }

    LaunchedEffect(api, appState) {
        api.setDisabledVehicleSources(appState.disabledVehicleSources)
    }

    // spojení na routovací servery rovnou při startu (první hledání nejbližší zastávky pak není pomalé)
    LaunchedEffect(Unit) { runCatching { api.warmUpRouting() } }

    // nová verze aplikace: jednou po spuštění (ne v debug buildu, viz UpdateChecker)
    LaunchedEffect(appState.notifyAppUpdates) {
        if (!appState.notifyAppUpdates || BuildConfig.DEBUG) return@LaunchedEffect
        val rel = runCatching { UpdateChecker.fetchLatest() }.getOrNull() ?: return@LaunchedEffect
        if (UpdateChecker.shouldNotify(rel, APP_VERSION, UpdateChecker.buildTimeMs)) appState.appUpdateOffer = rel
    }

    // živé polohy rovnou při startu, ať jsou na mapě hned při jejím otevření
    LaunchedEffect(api, appState.disabledVehicleSources) {
        api.prewarmVehicles()
        val curr = api.currentVehicles()
        appState.updateVehicles(curr)
        appState.heldCount = api.heldCount()
        appState.vehicleSource = api.sourceLabel()
        appState.vehiclesLoaded = true
    }

    // po návratu do appky (minimalizace, zamčený telefon) se polohy stáhnou hned jednou, ne až za dalších pár sekund;
    // zobrazené polohy mezitím zůstávají, a hub navíc hlídá, aby dotazy nechodily častěji než jednou za 2 s
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, api) {
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (firstResume) { firstResume = false; return@LifecycleEventObserver } // start appky řeší prewarm

            // 1. Okamžitě načíst minulé vozy z RAM, aby obrazovka/mapa nebyla prázdná
            val cached = api.currentVehicles()
            if (cached.isNotEmpty()) {
                appState.updateVehicles(cached)
                appState.heldCount = api.heldCount()
                appState.vehicleSource = api.sourceLabel()
                appState.vehiclesLoaded = true
            }

            // 2. Hned requestnout čerstvá data ze serveru
            scope.launch {
                runCatching { api.vehiclePositions() }.onSuccess { list ->
                    appState.updateVehicles(list)
                    appState.heldCount = api.heldCount()
                    appState.vehicleSource = api.sourceLabel()
                    appState.vehiclesLoaded = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Poslouchat aktualizace z repozitáře (jak z prewarmu při startu, tak z cache nebo periodického refreshu)
    LaunchedEffect(api) {
        api.vehicleUpdates.collect {
            val curr = api.currentVehicles()
            appState.updateVehicles(curr)
            appState.heldCount = api.heldCount()
            appState.vehicleSource = api.sourceLabel()
            appState.vehiclesLoaded = true
        }
    }

    // druhy dopravy na zastávkách pro chipy Vše / Autobusy / Vlaky
    LaunchedEffect(appState.indexedStops.isNotEmpty()) {
        if (appState.indexedStops.isEmpty() || appState.stopModes != null) return@LaunchedEffect
        runCatching { api.loadStopModes() }
            .onSuccess { appState.stopModes = it }
            .onFailure { if (it is CancellationException) throw it }
    }

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
                    appState.searchError = tr(R.string.stops_load_failed, it.message.orEmpty())
                }
            guard?.cancel()
            appState.showSetup = false
            appState.stopsLoading = false
        }
    }

    val autoUpdate = remember { AutoUpdatePolicy(context) }

    LaunchedEffect(Unit) {
        // počkat, až doběhne první načtení zastávek (jinak by se dvě stažení pralo)
        snapshotFlow { appState.stopsLoading to appState.stops.isNotEmpty() }
            .first { (loading, hasStops) -> !loading && hasStops }

        if (autoUpdate.isDue() && autoUpdate.isOnUnmeteredNetwork()) {
            // NonCancellable: blokující import se stejně nedá přerušit, a po otočení/zavření
            // by se jinak nezapsal čas úspěchu a stahovalo by se znovu
            val ok = withContext(NonCancellable) { api.autoUpdate() }
            if (ok) autoUpdate.lastSuccessMs = System.currentTimeMillis()
        }
    }


    // Mapa se vytvoří v pozadí po ustálení startu (nebo ihned při přechodu na ni) a pak zůstane naživu
    var mapVisited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Mapa se vytvoří hned, jak je venku první snímek a hlavní vlákno nemá co dělat (schovaná pod překryvem);
        // žádný pevný časovač. Když uživatel klepne dřív, vytvoří se při klepnutí (viz níže).
        withFrameNanos { }
        awaitMainThreadIdle()
        mapVisited = true
    }
    LaunchedEffect(appState.selectedTab) {
        if (appState.selectedTab == 1 && !mapVisited) {
            mapVisited = true
        }
    }

    // ťuknutí na notifikaci sledované cesty => detail spojení na záložce Spoje
    LaunchedEffect(Unit) {
        JourneyTracker.openRequested.collect { requested ->
            if (!requested) return@collect
            JourneyTracker.openRequested.value = false
            JourneyTracker.current.value?.let { j ->
                appState.legSheet = null
                appState.selectedTab = 0
                appState.detailJourney = j
            }
        }
    }

    // ───── zpět z mapy = záložka Spoje (s náhledem gesta) ─────
    val mapBack = remember { PredictiveBackState() }
    val mapFade = remember { androidx.compose.animation.core.Animatable(1f) }
    var mapLeaving by remember { mutableStateOf(false) }
    androidx.activity.compose.PredictiveBackHandler(enabled = appState.selectedTab == 1) { events ->
        mapBack.active = true
        appState.backPreviewSearch = true
        try {
            events.collect { e ->
                mapBack.edge = e.swipeEdge
                mapBack.progress.snapTo(e.progress)
            }
            withContext(NonCancellable) {
                mapLeaving = true
                mapFade.animateTo(0f, tween(160))
                appState.selectedTab = 0
                mapBack.active = false
                mapLeaving = false
                appState.backPreviewSearch = false
                mapBack.progress.snapTo(0f)
                mapFade.snapTo(1f)
            }
        } catch (e: CancellationException) {
            mapBack.active = false
            appState.backPreviewSearch = false
            scope.launch { mapBack.progress.animateTo(0f, tween(180)) }
            throw e
        }
    }

    /** [hideUserWalk]: zobrazuje se až další jízda spojení, pěší trasa uživatele k výchozí zastávce nemá smysl. */
    fun showJourneyOnMap(journey: Journey, hideUserWalk: Boolean = false) {
        appState.legSheet = null   // po návratu z mapy se sheet jízdy nemá znovu objevit
        appState.hideUserWalk = hideUserWalk
        val bp = api.boardingPoint(journey)
        val isPast = journey.depDt.isBefore(nowPrague())
        val ref = TripRef(
            cis = journey.cis,
            trip = journey.trip,
            line = journey.line,
            boardLat = bp?.first,
            boardLng = bp?.second,
            destination = journey.destination,
            isPast = isPast,
            departureTimeMillis = journey.depDt
                .atZone(java.time.ZoneId.of("Europe/Prague"))
                .toInstant()
                .toEpochMilli(),
            tid = journey.rawLegs.firstOrNull { it.kind == "ride" }?.tid ?: 0
        )
        appState.loadJourneyRoute(api, journey, scope, ref)
    }

    /** Jízda [legIndex] spojení na mapě; první jízda = celé spojení (s pěší trasou k zastávce). */
    fun showLegOnMap(journey: Journey, legIndex: Int) {
        val firstRide = journey.rawLegs.indexOfFirst { it.kind == "ride" }
        if (legIndex == firstRide) showJourneyOnMap(journey)
        else showJourneyOnMap(api.legJourney(journey, legIndex) ?: journey, hideUserWalk = true)
    }

    /** Tlačítka mapy u spojení: ukáže jízdu, která je právě aktuální (po odjezdu první už další). */
    fun showJourneyOnMapSmart(journey: Journey) = showLegOnMap(journey, api.activeLegIndex(journey))


    val systemDark = isSystemInDarkTheme()
    val dark = when (appState.appTheme) {
        AppTheme.SYSTEM -> systemDark
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }

    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()


    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = scheme) {
        // uvnitř motivu aplikace, jinak by dialog měl výchozí (světlé) barvy i v tmavém režimu
        appState.appUpdateOffer?.let { rel ->
            AppUpdateDialog(rel, onDismiss = { dontShowAgain ->
                appState.appUpdateOffer = null
                if (dontShowAgain) appState.updateNotifyAppUpdates(false)
            })
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            actions = { AppInfoButton(); Spacer(Modifier.width(4.dp)) }
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = appState.selectedTab == 0,
                                onClick = { appState.selectedTab = 0 },
                                icon = { Icon(Icons.Default.DirectionsBus, null) },
                                label = { Text(stringResource(R.string.nav_trips)) }
                            )

                            NavigationBarItem(
                                selected = appState.selectedTab == 1,
                                onClick = { appState.selectedTab = 1 },
                                icon = { Icon(Icons.Default.Map, null) },
                                label = { Text(stringResource(R.string.nav_map)) }
                            )

                            NavigationBarItem(
                                selected = appState.selectedTab == 2,
                                onClick = { appState.selectedTab = 2 },
                                icon = { Icon(Icons.Default.Settings, null) },
                                label = { Text(stringResource(R.string.nav_settings)) }
                            )
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        if (mapVisited) {
                            // během gesta zpět je mapa nad Spoji (ty se pod ní ukážou jako náhled)
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .zIndex(if (mapBack.active || mapLeaving) 1f else 0f)
                                    .predictiveBack(mapBack)
                                    .graphicsLayer { alpha = mapFade.value }
                            ) {
                                MapScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    appState = appState,
                                    api = api,
                                    active = appState.selectedTab == 1
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = appState.selectedTab != 1,
                            enter = fadeIn(tween(200, easing = FastOutSlowInEasing)),
                            exit = fadeOut(tween(180, easing = FastOutLinearInEasing))
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize().pointerInput(Unit) {},
                                color = MaterialTheme.colorScheme.background
                            ) {}
                        }

                        AnimatedVisibility(
                            visible = appState.selectedTab == 0 || appState.backPreviewSearch,
                            enter = fadeIn(tween(260, easing = FastOutSlowInEasing)) + scaleIn(
                                initialScale = 0.97f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                            ),
                            exit = fadeOut(tween(160, easing = FastOutLinearInEasing)) + scaleOut(
                                targetScale = 0.98f,
                                animationSpec = tween(160, easing = FastOutLinearInEasing)
                            )
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize().pointerInput(Unit) {},
                                color = MaterialTheme.colorScheme.background
                            ) {
                                SearchScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    appState = appState,
                                    api = api,
                                    onShowMapForJourney = { showJourneyOnMapSmart(it) }
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = appState.selectedTab == 2,
                            enter = fadeIn(tween(260, easing = FastOutSlowInEasing)) + scaleIn(
                                initialScale = 0.97f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                            ),
                            exit = fadeOut(tween(160, easing = FastOutLinearInEasing)) + scaleOut(
                                targetScale = 0.98f,
                                animationSpec = tween(160, easing = FastOutLinearInEasing)
                            )
                        ) {
                            // zpět z nastavení = záložka Spoje, s náhledem gesta (Spoje se pod nastavením složí)
                            val back = rememberPredictiveBack(enabled = appState.selectedTab == 2) { appState.selectedTab = 0 }
                            LaunchedEffect(back.active) { appState.backPreviewSearch = back.active }
                            Surface(
                                modifier = Modifier.fillMaxSize().predictiveBack(back).pointerInput(Unit) {},
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

                // bottom sheet vozidla nad celým UI (i nad spodní lištou), ale bez Dialog okna => naběhne hned
                // detail jedné jízdy spojení (ťuknutí na číslo linky v hledání)
                if (appState.selectedTab == 0) {
                    appState.legSheet?.let { (j, idx) ->
                        JourneyLegSheet(j, idx, api, onDismiss = { appState.legSheet = null }) { showLegOnMap(j, idx) }
                    }
                }

                if (appState.selectedTab == 1) {
                    SelectedVehicleSheet(api, appState)
                    SelectedStopSheet(api, appState)
                }

                AnimatedVisibility(
                    visible = appState.showSetup && !appState.setupDismissed,
                    enter = fadeIn(tween(300)) + scaleIn(
                        initialScale = 0.92f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
                    ),
                    exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.95f, animationSpec = tween(200))
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