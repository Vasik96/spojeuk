@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

private val UstiCenter = LatLng(50.6606, 14.0323)
private const val FIRST_PAGE = 8
private const val MORE_PAGE = 6

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { SpojeApp() }
    }
}

data class IndexedStop(
    val stop: Stop,
    val normalizedName: String,
    val normalizedWords: List<String>,
    val usageCount: Int
)

/** Odkaz na konkrétní spoj (každá instance je unikátní → opakovaný klik znovu spustí zvýraznění). */
class TripRef(val cis: String, val trip: String)

class SpojeAppState(
    val context: Context,
    val usageManager: StopUsageManager = StopUsageManager(context),
    val history: TripHistory = TripHistory(context),
    val baseline: UsageBaseline = UsageBaseline(context)
) {
    var selectedTab by mutableIntStateOf(0)
    var stops by mutableStateOf<List<Stop>>(emptyList())
    var indexedStops by mutableStateOf<List<IndexedStop>>(emptyList())
    var stopsLoading by mutableStateOf(false)

    var from by mutableStateOf<Stop?>(null)
    var to by mutableStateOf<Stop?>(null)
    var fromQuery by mutableStateOf("")
    var toQuery by mutableStateOf("")

    val listState = LazyListState()
    val shownKeys = HashSet<String>()


    var showSetup by mutableStateOf(false)
    var setupMessage by mutableStateOf("")
    var setupProgress by mutableFloatStateOf(0f)
    var setupDismissed by mutableStateOf(false)


    var journeys by mutableStateOf<List<Journey>>(emptyList())
    var searchLoading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
    var loadMoreFailed by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
    var searched by mutableStateOf(false)

    var search: JourneySearch? = null
    var searchJob: Job? = null

    var loadingEarlier by mutableStateOf(false)
    var hasEarlier by mutableStateOf(true)

    // Mapa
    var vehicles by mutableStateOf<List<Vehicle>>(emptyList())
    var vehiclesLoaded by mutableStateOf(false)
    var mapError by mutableStateOf<String?>(null)
    var selectedVehicle by mutableStateOf<Vehicle?>(null)
    var tripRoute by mutableStateOf<List<LatLng>>(emptyList())
    var routeLoading by mutableStateOf(false)

    /** Spoj, na který má mapa přiblížit (jednorázově). */
    var focusTrip by mutableStateOf<TripRef?>(null)

    /** Spoj, jehož marker se má na chvíli zvýraznit. */
    var highlightedTrip by mutableStateOf<TripRef?>(null)
    var pulsePhase by mutableIntStateOf(0) // 0 = nic, 1 / 2 = střídání zvýraznění

    // Poslední pozice kamery (aby mapa po přepnutí tabu neskákala zpět)
    var mapCenter: LatLng = UstiCenter
    var mapZoom: Float = 11f

    var heldCount by mutableIntStateOf(0)

    var userLoc by mutableStateOf<Pair<Double, Double>?>(null)

    var autoFillDone = false      // předvyplnění proběhlo
    var autoFillAsked = false     // o oprávnění jsme už žádali


    private fun effectiveUsage(name: String): Int =
        (usageManager.getUsageCount(name) - baseline.get(name)).coerceAtLeast(0)

    suspend fun forgetStop(name: String) {
        baseline.set(name, usageManager.getUsageCount(name))
        history.forget(name)
        refreshUsage()
    }


    suspend fun updateIndexedStops(newStops: List<Stop>) {
        val idx = withContext(Dispatchers.Default) {
            newStops.distinctBy { it.name }.map { s ->
                val norm = normalize(s.name)
                val words = norm.split(Regex("\\s+")).filter { it.isNotBlank() }
                IndexedStop(s, norm, words, effectiveUsage(s.name))
            }
        }
        stops = newStops
        indexedStops = idx
    }

    suspend fun refreshUsage() {
        val idx = withContext(Dispatchers.Default) {
            indexedStops.map { it.copy(usageCount = effectiveUsage(it.stop.name)) }
        }
        indexedStops = idx
    }
}

@Composable
private fun Wavy(size: Dp, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    CircularWavyProgressIndicator(modifier = modifier.size(size), color = color)
}

@Composable
private fun SpojeApp() {
    val context = LocalContext.current
    val appState = remember { SpojeAppState(context) }
    val api = remember { DukApi(context) }
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
    val showMap = mapVisited

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
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        if (showMap) {
                            MapScreen(
                                modifier = Modifier.fillMaxSize(),
                                appState = appState,
                                api = api,
                                active = appState.selectedTab == 1
                            )
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
                                        val ref = TripRef(journey.cis, journey.trip)
                                        appState.selectedVehicle = null
                                        appState.tripRoute = emptyList()
                                        appState.routeLoading = true
                                        appState.vehiclesLoaded = false
                                        appState.focusTrip = ref
                                        appState.highlightedTrip = ref
                                        appState.selectedTab = 1
                                        scope.launch {
                                            val points = api.journeyGeometry(journey)
                                            appState.tripRoute = points.map { LatLng(it.first, it.second) }
                                            appState.routeLoading = false
                                        }
                                    }
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

@SuppressLint("MissingPermission")
@Composable
private fun SearchScreen(
    modifier: Modifier = Modifier,
    appState: SpojeAppState,
    api: DukApi,
    onShowMapForJourney: (Journey) -> Unit
) {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = appState.listState
    val context = LocalContext.current
    var locatingTarget by remember { mutableStateOf<String?>(null) }
    var pendingLocation by remember { mutableStateOf<Pair<String, (Stop) -> Unit>?>(null) }

    // Referenční bod řazení: poloha uživatele, ale jen když je v regionu, jinak střed Ústí
    val origin = remember(appState.userLoc) {
        val u = appState.userLoc
        if (u != null && u.first in 50.35..50.95 && u.second in 13.55..14.60) u
        else UstiCenter.latitude to UstiCenter.longitude
    }

    // Poloha jen z cache, bez zapínání GPS; spustí se při každém návratu na záložku Spoje
    LaunchedEffect(Unit) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) appState.userLoc = loc.latitude to loc.longitude
                }
        }
    }



    fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    fun resolveNearest(lat: Double, lng: Double, target: String, onPick: (Stop) -> Unit) {
        scope.launch {
            appState.userLoc = lat to lng

            val cands = runCatching { api.nearestCandidates(lat, lng) }.getOrNull().orEmpty()
            if (cands.isEmpty()) {
                locatingTarget = null
                toast("Nejbližší zastávku se nepodařilo najít")
                return@launch
            }

            // po silnici; když OSRM selže, záloha = nejbližší vzdušnou čarou
            val (stop, dist) = (if (cands.size > 1) runCatching { api.refineByRoad(lat, lng, cands) }.getOrNull() else null)
                ?: cands[0]

            locatingTarget = null
            val picked = appState.stops.firstOrNull { it.name == stop.name } ?: stop
            onPick(picked)
            toast("Nejbližší zastávka: ${splitStopName(picked.name).first} (${fmtDist(dist)})")
        }
    }

    fun fetchNearest(target: String, onPick: (Stop) -> Unit) {
        locatingTarget = target
        val client = LocationServices.getFusedLocationProviderClient(context)
        fun fail() { locatingTarget = null; toast("Polohu se nepodařilo zjistit") }
        fun requestFresh() {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) resolveNearest(loc.latitude, loc.longitude, target, onPick) else fail()
                }
                .addOnFailureListener { fail() }
        }
        client.lastLocation
            .addOnSuccessListener { last ->
                if (last != null && System.currentTimeMillis() - last.time < 3 * 60_000) {
                    resolveNearest(last.latitude, last.longitude, target, onPick)
                } else requestFresh()
            }
            .addOnFailureListener { requestFresh() }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { p ->
        val granted = p[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                p[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val pending = pendingLocation
        pendingLocation = null
        if (granted && pending != null) fetchNearest(pending.first, pending.second)
        else toast("Bez oprávnění k poloze nelze najít nejbližší zastávku")
    }

    fun useMyLocation(target: String, onPick: (Stop) -> Unit) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) fetchNearest(target, onPick)
        else {
            pendingLocation = target to onPick
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // ───── předvyplnění odkud/kam podle polohy a historie ─────
    fun applyAutoFill(lat: Double, lng: Double) {
        scope.launch {
            appState.userLoc = lat to lng
            if (appState.autoFillDone) return@launch
            val untouched = appState.from == null && appState.to == null &&
                    appState.fromQuery.isBlank() && appState.toQuery.isBlank()
            if (!untouched) return@launch

            // širší výběr kandidátů vzdušnou čarou, ať se nevyřadí zastávka, která je po cestě blíž
            val cands = runCatching { api.nearestCandidates(lat, lng, 6) }.getOrNull().orEmpty()
            if (cands.isEmpty()) return@launch

            // zpřesnění po silnici (s jedním opakováním, OSRM bývá první pokus pomalý)
            var refined: List<Pair<Stop, Double>>? = null
            if (cands.size > 1) {
                repeat(2) {
                    if (refined == null) {
                        refined = runCatching { api.refineByRoad(lat, lng, cands) }.getOrNull()
                            ?.let { listOf(it) }
                    }
                }
            }
            val best = refined?.firstOrNull() ?: cands[0]

            // limit až na vzdálenost po silnici
            if (best.second > 700.0) return@launch

            // pro historii použij pořadí podle silnice: nejlepší první
            val ordered = listOf(best) + cands.filter { it.first.name != best.first.name }
            val near = ordered.filter { it.second <= 700.0 || it === best }

            val sug = appState.history.suggest(near.map { it.first.name to it.second })
            val fromName = sug?.first ?: best.first.name
            val from = appState.stops.firstOrNull { it.name == fromName } ?: return@launch
            val to = sug?.second?.let { n -> appState.stops.firstOrNull { it.name == n } }

            appState.from = from
            appState.fromQuery = stopLabel(from.name)
            if (to != null) {
                appState.to = to
                appState.toQuery = stopLabel(to.name)
            }
            appState.autoFillDone = true
        }
    }

    fun autoFill() {
        if (appState.autoFillDone) return
        val client = LocationServices.getFusedLocationProviderClient(context)
        fun fresh() {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc -> if (loc != null) applyAutoFill(loc.latitude, loc.longitude) }
        }
        client.lastLocation
            .addOnSuccessListener { last ->
                // stará poloha z cache by předvyplnila špatnou zastávku, proto max 5 min
                if (last != null && System.currentTimeMillis() - last.time < 5 * 60_000)
                    applyAutoFill(last.latitude, last.longitude)
                else fresh()
            }
            .addOnFailureListener { fresh() }
    }

    val autoFillPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { p ->
        if (p[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            p[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) autoFill()
    }

    LaunchedEffect(appState.indexedStops.isNotEmpty()) {
        if (appState.indexedStops.isEmpty() || appState.autoFillDone) return@LaunchedEffect
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) autoFill()
        else if (!appState.autoFillAsked) {
            appState.autoFillAsked = true
            autoFillPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }



    val now by produceState(initialValue = nowPrague()) {
        while (true) {
            delay(30_000)
            value = nowPrague()
        }
    }

    fun removeUsed(stop: Stop) {
        scope.launch { appState.forgetStop(stop.name) }
    }

    fun dismissKeyboard() {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    // ───── nové hledání ─────
    fun runSearch() {
        val a = appState.from ?: return
        val b = appState.to ?: return
        dismissKeyboard()
        appState.searchJob?.cancel()

        appState.usageManager.recordUsage(a.name)
        appState.usageManager.recordUsage(b.name)

        appState.history.record(a.name, b.name)

        scope.launch {
            appState.refreshUsage()
        }


        val s = api.newSearch(
            from = appState.stops.filter { it.name == a.name },
            to = appState.stops.filter { it.name == b.name },
            fromName = a.name,
            toName = b.name
        )
        appState.search = s
        appState.journeys = emptyList()
        appState.searched = true
        appState.searchLoading = true
        appState.loadingMore = false
        appState.refreshing = false
        appState.loadMoreFailed = false
        appState.hasMore = true
        appState.hasEarlier = true
        appState.loadingEarlier = false
        appState.searchError = null

        appState.shownKeys.clear()
        listState.requestScrollToItem(0)

        appState.searchJob = scope.launch {
            try {
                val r = withContext(Dispatchers.Default) { s.loadMore(FIRST_PAGE) }
                if (appState.search === s) {
                    appState.journeys = r
                    appState.hasMore = s.hasMore
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (appState.search === s) appState.searchError = "Hledání selhalo: ${e.message}"
            } finally {
                if (appState.search === s) appState.searchLoading = false
            }
        }
    }

    // ───── pull-to-refresh (nahoře) ─────

    fun refresh() {
        val old = appState.search ?: return
        appState.searchJob?.cancel()
        val s = old.fresh()
        appState.search = s
        appState.refreshing = true
        appState.loadingMore = false
        appState.loadMoreFailed = false
        appState.hasEarlier = true
        appState.loadingEarlier = false
        appState.searchError = null

        appState.shownKeys.clear()

        appState.searchJob = scope.launch {
            // Refresh poběží minimálně 1 sekundu.
            val refreshStartedAt = System.currentTimeMillis()

            try {
                val r = withContext(Dispatchers.Default) {
                    s.loadMore(FIRST_PAGE)
                }

                if (appState.search === s) {
                    appState.journeys = r
                    appState.hasMore = s.hasMore
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (appState.search === s) {
                    appState.searchError = "Obnovení selhalo: ${e.message}"
                }
            } finally {
                val elapsed = System.currentTimeMillis() - refreshStartedAt
                val remaining = 700L - elapsed

                if (remaining > 0) delay(remaining)

                if (appState.search === s) {
                    appState.refreshing = false
                }
            }
        }
    }


    // ───── doplnění dalších spojů (dole) ─────
    fun loadMore() {
        val s = appState.search ?: return
        if (appState.loadingMore || appState.searchLoading || appState.refreshing ||
            appState.loadMoreFailed || !s.hasMore
        ) return
        appState.loadingMore = true

        appState.searchJob = scope.launch {
            try {
                val r = withContext(Dispatchers.Default) { s.loadMore(MORE_PAGE) }
                if (appState.search === s) {
                    appState.journeys = r
                    appState.hasMore = s.hasMore
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (appState.search === s) {
                    appState.searchError = "Načtení dalších spojů selhalo: ${e.message}"
                    appState.loadMoreFailed = true
                }
            } finally {
                if (appState.search === s) appState.loadingMore = false
            }
        }
    }

    fun loadEarlier() {
        val s = appState.search ?: return

        if (appState.loadingEarlier || appState.loadingMore ||
            appState.searchLoading || appState.refreshing
        ) return

        // Zapamatuj si spoj, který je právě vidět, a jeho pozici.
        val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull()
        val anchorKey = visibleItem?.key
        val anchorOffset = listState.firstVisibleItemScrollOffset

        appState.loadingEarlier = true

        scope.launch {
            try {
                val r = withContext(Dispatchers.Default) {
                    s.loadEarlier()
                }

                if (appState.search === s) {
                    val newIndex = r.indexOfFirst {
                        it.key == anchorKey
                    }

                    // Nastav pozici ještě před změnou seznamu.
                    if (newIndex >= 0) {
                        listState.requestScrollToItem(
                            index = newIndex,
                            scrollOffset = anchorOffset
                        )
                    }

                    appState.journeys = r
                    appState.hasEarlier = s.hasEarlier
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (appState.search === s) {
                    appState.searchError =
                        "Načtení dřívějších spojů selhalo: ${e.message}"
                }
            } finally {
                if (appState.search === s) {
                    appState.loadingEarlier = false
                }
            }
        }
    }


    // Automaticky dotahuje, dokud je konec seznamu na dohled a server má další odjezdy.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(
        nearEnd, appState.journeys.size, appState.loadingMore, appState.searchLoading,
        appState.refreshing, appState.loadMoreFailed, appState.hasMore
    ) {
        if (nearEnd && appState.searched) loadMore()
    }



    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ElevatedCard(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {


                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StopField(
                        label = "Odkud",
                        externalText = { appState.fromQuery },
                        onTextChange = { t ->
                            appState.fromQuery = t
                            if (appState.from?.name != t) appState.from = null
                        },
                        onPick = {
                            appState.from = it
                            appState.fromQuery = stopLabel(it.name)
                        },
                        indexedStops = appState.indexedStops,
                        originLat = origin.first,
                        originLng = origin.second,
                        stopsLoading = appState.stopsLoading || locatingTarget == "from",
                        onRemoveUsed = { removeUsed(it) },
                        onMyLocation = {
                            useMyLocation("from") { s -> appState.from = s; appState.fromQuery = stopLabel(s.name) }
                        }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f))
                        IconButton(onClick = {
                            val f = appState.from
                            appState.from = appState.to
                            appState.to = f
                            val q = appState.fromQuery
                            appState.fromQuery = appState.toQuery
                            appState.toQuery = q
                        }) {
                            Icon(Icons.Default.SwapVert, "Prohodit zastávky")
                        }
                        HorizontalDivider(Modifier.weight(1f))
                    }

                    StopField(
                        label = "Kam",
                        externalText = { appState.toQuery },
                        onTextChange = { t ->
                            appState.toQuery = t
                            if (appState.to?.name != t) appState.to = null
                        },
                        onPick = {
                            appState.to = it
                            appState.toQuery = stopLabel(it.name)
                        },
                        indexedStops = appState.indexedStops,
                        stopsLoading = appState.stopsLoading || locatingTarget == "to",
                        originLat = origin.first,
                        originLng = origin.second,
                        onRemoveUsed = { removeUsed(it) },
                        onMyLocation = {
                            useMyLocation("to") { s -> appState.to = s; appState.toQuery = stopLabel(s.name) }
                        }
                    )

                    Button(
                        onClick = { runSearch() },
                        enabled = appState.from != null && appState.to != null &&
                                appState.from?.name != appState.to?.name && !appState.searchLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        if (appState.searchLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                wavelength = 8.dp,                           // Hustota vln
                                amplitude = 1.5f                           // Menší výška vln, aby neroztahovaly layout
                            )
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (appState.searchLoading) "Hledám spoje…" else "Najít spojení")
                    }
                }
            }

            appState.searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (appState.searched && !appState.searchLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Výsledky", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (appState.hasEarlier) {
                        TextButton(
                            onClick = { loadEarlier() },
                            enabled = !appState.loadingEarlier
                        ) {
                            if (appState.loadingEarlier) {
                                Wavy(24.dp)
                                Spacer(Modifier.width(4.dp))
                                Text("Načítám…")
                            } else {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Dřívější")
                            }
                        }
                    }
                    Text(
                        "${appState.journeys.size} nalezeno",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (!appState.searched) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.DirectionsBus, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Kam dnes vyrazíš?", style = MaterialTheme.typography.headlineSmall)
                    Text("Vyber výchozí a cílovou zastávku.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (appState.searchLoading && appState.journeys.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val activeStrokeWidth = with(LocalDensity.current) { 8.dp.toPx() } // Tlustá aktivní vlnka
                val trackStrokeWidth = with(LocalDensity.current) { 8.dp.toPx() }  // Tenká stopa v pozadí

                CircularWavyProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = MaterialTheme.colorScheme.primary,
                    wavelength = 16.dp, // Větší číslo = méně vln (delší vlnová délka)
                    stroke = Stroke(
                        width = activeStrokeWidth,
                        cap = StrokeCap.Round
                    ),
                    trackStroke = Stroke(
                        width = trackStrokeWidth,
                        cap = StrokeCap.Round
                    )
                )
            }
        } else {
            PullToRefreshBox(
                isRefreshing = appState.refreshing,
                onRefresh = { refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {


                    itemsIndexed(appState.journeys, key = { _, j -> j.key }) { index, journey ->
                        AppearAnimated(appState, journey.key, index, Modifier.animateItem()) {
                            JourneyCard(journey, now) { onShowMapForJourney(journey) }
                        }
                    }

                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            when {
                                appState.loadMoreFailed -> TextButton(onClick = { appState.loadMoreFailed = false }) {
                                    Text("Načíst další – zkusit znovu")
                                }
                                appState.loadingMore -> Wavy(32.dp)
                                appState.journeys.isEmpty() && !appState.hasMore && !appState.searchLoading ->
                                    Text("Nenašel se žádný spoj do konce dne.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                !appState.hasMore ->
                                    Text("To je vše na dnes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun AppearAnimated(
    appState: SpojeAppState,
    key: String,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // true jen při prvním zobrazení karty, takže při scrollování zpět se nepřehrává
    val state = remember(key) { MutableTransitionState(!appState.shownKeys.add(key)).apply { targetState = true } }
    val d = index.coerceAtMost(6) * 50
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = fadeIn(tween(300, delayMillis = d)) + slideInVertically(tween(350, delayMillis = d)) { it / 5 }
    ) { content() }
}


@Composable
private fun SourceBadges() {
    var showInfo by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Informace o zdrojích dat") { showInfo = true },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogoChip(R.drawable.duk, "Doprava Ústeckého kraje")
        LogoChip(R.drawable.dpmul, "Dopravní podnik města Ústí nad Labem")
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            icon = { Icon(Icons.Default.Info, null) },
            title = { Text("Neoficiální aplikace") },
            text = {
                Text(
                    "Spoje UK nejsou nijak spojeny, provozovány ani schváleny společnostmi " +
                            "Doprava Ústeckého kraje (DÚK) ani Dopravní podnik města Ústí nad Labem (DPMÚL).\n\n" +
                            "Loga slouží pouze pro vizuální informaci o tom, jaká data se v aplikaci zobrazují (a ještě to s tím vypadá lépe), " +
                            "a jsou ochrannými známkami svých vlastníků."
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("Rozumím") }
            }
        )
    }
}


@Composable
private fun LogoChip(@DrawableRes res: Int, description: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = Color.White) {
        Image(
            painter = painterResource(res),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(24.dp).padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}


@Composable
private fun SetupOverlay(message: String, progress: Float, onBackground: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {},   // blokuje dotyky na mapu pod ním
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Wavy(64.dp)
            Spacer(Modifier.height(24.dp))
            Text("Stahování zastávek", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                message.ifBlank { "Připravuji jízdní řády…" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (progress > 0f) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Při prvním spuštění se stahují jízdní řády (~20 MB). Toto může trvat 30s.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onBackground) { Text("Stahovat na pozadí") }
        }
    }
}


private fun fmtMinutes(m: Long): String = when {
    m < 60 -> "$m\u00A0min"
    m % 60 == 0L -> "${m / 60}\u00A0h"
    else -> "${m / 60}\u00A0h\u00A0${m % 60}\u00A0min"
}

private fun departsIn(j: Journey, now: LocalDateTime): String {
    val secs = ChronoUnit.SECONDS.between(now, j.depDt)
    return when {
        secs >= 60 -> "za ${fmtMinutes(secs / 60)}"
        secs > -60 -> "právě odjíždí"
        else -> "odjel před ${fmtMinutes(-secs / 60)}"
    }
}


/** "Ústí nad Labem,Divadlo" → ("Divadlo", "Ústí nad Labem") */
private fun splitStopName(name: String): Pair<String, String> {
    val i = name.indexOf(',')
    return if (i < 0) name.trim() to "" else name.substring(i + 1).trim() to name.substring(0, i).trim()
}

private fun fmtDist(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else String.format("%.1f km", m / 1000)

private fun fmtAge(s: Long): String = when {
    s < 60 -> "$s s"
    s < 3600 -> "${s / 60} min ${s % 60} s"
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}

private fun ageLabel(s: Long): String = when {
    s <= 10 -> "Aktuální poloha"
    s <= 40 -> "Poloha před ${fmtAge(s)}"
    else -> "Poloha stará ${fmtAge(s)}. Zdroj teď vozidlo nedodává."
}

private fun stopLabel(name: String): String = splitStopName(name).first.ifBlank { name }


private fun getStopMatches(
    query: String,
    indexedStops: List<IndexedStop>,
    originLat: Double,
    originLng: Double
): List<Stop> {
    val cosLat = Math.cos(Math.toRadians(originLat))
    // druhá mocnina vzdálenosti v m² (odmocnina není pro řazení potřeba)
    fun dist2(s: Stop): Double {
        if (s.lat == 0.0 && s.lng == 0.0) return Double.MAX_VALUE
        val dy = (s.lat - originLat) * 110540.0
        val dx = (s.lng - originLng) * 111320.0 * cosLat
        return dx * dx + dy * dy
    }

    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return indexedStops.asSequence()
            .filter { it.usageCount > 0 }
            .map { it to dist2(it.stop) }
            .sortedWith(
                compareByDescending<Pair<IndexedStop, Double>> { it.first.usageCount }
                    .thenBy { it.second }
            )
            .map { it.first.stop }
            .distinctBy { it.name }
            .take(15)
            .toList()
    }
    val terms = normalize(trimmed).split(Regex("\\s+")).filter { it.isNotBlank() }
    if (terms.isEmpty()) return emptyList()

    return indexedStops.asSequence()
        .filter { item -> terms.all { term -> item.normalizedName.contains(term) } }
        .map { it to dist2(it.stop) }            // vzdálenost se spočítá jednou na zastávku
        .sortedWith(
            compareByDescending<Pair<IndexedStop, Double>> { it.first.usageCount }   // 1. použití
                .thenBy { it.second }                                                // 2. poloha
                .thenBy { if (itemStartsWithTerm(it.first.normalizedWords, terms.first())) 0 else 1 } // 3. zbytek
                .thenBy { it.first.stop.name }
        )
        .map { it.first.stop }
        .distinctBy { it.name }
        .take(20)
        .toList()
}

private fun itemStartsWithTerm(words: List<String>, term: String): Boolean =
    words.any { it.startsWith(term) }

private fun isUstiArea(stop: Stop): Boolean =
    stop.lat in 50.45..50.85 && stop.lng in 13.75..14.40

/**
 * Textové pole s napovídáním. Text drží LOKÁLNĚ (nezávisle na jakémkoli loadingu / stavu aplikace),
 * filtrování zastávek běží na pozadí a výsledky se jen dosypou, takže psaní i mazání je plynulé.
 */
@Composable
private fun StopField(
    label: String,
    externalText: () -> String,
    onTextChange: (String) -> Unit,
    onPick: (Stop) -> Unit,
    indexedStops: List<IndexedStop>,
    originLat: Double,
    originLng: Double,
    stopsLoading: Boolean,
    onMyLocation: () -> Unit,
    onRemoveUsed: (Stop) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var tfv by remember { mutableStateOf(TextFieldValue(externalText())) }
    var options by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }

    // Změna zvenku (prohození zastávek) → promítnout do pole
    LaunchedEffect(Unit) {
        snapshotFlow(externalText).collect { ext ->
            if (ext != tfv.text) tfv = TextFieldValue(ext, TextRange(ext.length))
        }
    }

    // Filtrování mimo hlavní vlákno; psaní tím není nijak blokované
    LaunchedEffect(tfv.text, indexedStops, originLat, originLng) {
        delay(60.milliseconds)
        options = withContext(Dispatchers.Default) {
            getStopMatches(tfv.text, indexedStops, originLat, originLng)
        }
    }

    val showMenu = expanded && (options.isNotEmpty() || tfv.text.isBlank())

    ExposedDropdownMenuBox(
        expanded = showMenu,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = tfv,
            onValueChange = { new ->
                val changed = new.text != tfv.text
                tfv = new
                if (changed) {
                    onTextChange(new.text)
                    expanded = true
                }
            },
            label = { Text(label) },
            placeholder = { Text("Hledat zastávku…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Map, null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (stopsLoading) {
                        Wavy(20.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (tfv.text.isNotEmpty()) {
                        IconButton(onClick = {
                            tfv = TextFieldValue("")
                            onTextChange("")
                            expanded = true
                        }) {
                            Icon(Icons.Default.Clear, "Vymazat")
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .onFocusChanged { if (it.isFocused) expanded = true },
            shape = RoundedCornerShape(18.dp)
        )

        ExposedDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { expanded = false }
        ) {
            if (tfv.text.isBlank()) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary) },
                    text = {
                        Column {
                            Text("Moje poloha", fontWeight = FontWeight.Medium)
                            Text(
                                "Najít nejbližší zastávku",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        keyboard?.hide()
                        focusManager.clearFocus()
                        onMyLocation()
                    }
                )
                if (options.isNotEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Často používané zastávky",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {}
                    )
                }
            }
            options.forEach { stop ->
                val (stopPart, city) = splitStopName(stop.name)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stopPart.ifBlank { stop.name }, fontWeight = FontWeight.Medium)
                            if (city.isNotBlank()) Text(
                                city,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },

                    trailingIcon = if (tfv.text.isBlank()) {
                        {
                            IconButton(
                                onClick = { onRemoveUsed(stop) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Odebrat z používaných",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else null,
                    onClick = {
                        val label = stopLabel(stop.name)
                        tfv = TextFieldValue(label, TextRange(label.length))
                        expanded = false
                        onPick(stop)
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }

                )
            }
        }
    }
}

@Composable
private fun JourneyCard(j: Journey, now: LocalDateTime, onMap: () -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )

    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${j.departure.take(5)}  →  ${j.arrival.take(5)}",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                    )
                    Text(
                        departsIn(j, now),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "${j.durationMinutes} min jízdy",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                }
                FilledTonalButton(onClick = onMap, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Default.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Mapa")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(j.line, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.Bold)
                }
                if (j.line2 != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Text("Přestup ${j.line2}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.Medium)
                    }
                }
                if (j.realtime) {
                    val (bg, fg, label) = when {
                        j.delay > 3 -> Triple(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                            "Zpoždění ${j.delay} min"
                        )
                        j.delay > 0 -> Triple(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                            "Zpoždění ${j.delay} min"
                        )
                        else -> Triple(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                            "Bez zpoždění"
                        )
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = bg, contentColor = fg) {
                        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (j.isTransfer) {
                Text(
                    "Přestup: ${j.transferAt ?: "mezi spoji"} · čekání ${j.transferWait ?: "?"} min",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text("${j.stopsCount ?: "—"} zastávek · přímý spoj", style = MaterialTheme.typography.bodyMedium)
            }
            if (j.destination.isNotBlank()) Text(
                "Směr ${j.destination}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
            if (j.agency.isNotBlank()) Text(
                j.agency, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ───────────────────────── mapa ─────────────────────────

private val markerIconCache = ConcurrentHashMap<String, BitmapDrawable>()


private val descCache = ConcurrentHashMap<String, BitmapDescriptor>()
private fun markerDescriptor(ctx: Context, line: String, train: Boolean, delay: Int, phase: Int) =
    descCache.getOrPut("$line:$train:${delay > 3}:$phase") {
        BitmapDescriptorFactory.fromBitmap(
            createLineMarkerDrawable(
                ctx,
                line,
                train,
                delay,
                phase
            ).bitmap
        )
    }
/** phase: 0 = normální, 1 = zvýraznění A (žlutá, velká), 2 = zvýraznění B (oranžová, menší). */
private fun createLineMarkerDrawable(
    context: Context, line: String, isTrain: Boolean, delay: Int, phase: Int
): BitmapDrawable {
    val key = "$line:$isTrain:${delay > 3}:$phase"
    markerIconCache[key]?.let { return it }

    val density = context.resources.displayMetrics.density
    val highlighted = phase != 0

    val textSizeDp = when (phase) { 1 -> 17; 2 -> 15; else -> 13 }
    val heightDp = when (phase) { 1 -> 38; 2 -> 32; else -> 26 }
    val padDp = if (highlighted) 26 else 18
    val strokePx = if (highlighted) 2.5f * density else 0f

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (phase == 1) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        textSize = textSizeDp * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    val displayLine = if (line.length > 6) line.take(6) else line
    val textWidthPx = textPaint.measureText(displayLine)

    val widthPx = maxOf((36 * density).toInt(), (textWidthPx + padDp * density).toInt())
    val heightPx = (heightDp * density).toInt()

    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgColor = when {
        phase == 1 -> 0xFFFFD54F.toInt()
        phase == 2 -> 0xFFFF6D00.toInt()
        isTrain -> 0xFF0277BD.toInt()
        delay > 3 -> 0xFFC62828.toInt()
        else -> 0xFF2E7D32.toInt()
    }

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }
    val inset = strokePx / 2f
    val rect = RectF(inset, inset, widthPx - inset, heightPx - inset)
    val radius = rect.height() / 2f
    canvas.drawRoundRect(rect, radius, radius, bgPaint)

    if (highlighted) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    val textY = (heightPx / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(displayLine, widthPx / 2f, textY, textPaint)

    val drawable = BitmapDrawable(context.resources, bitmap)
    markerIconCache[key] = drawable
    return drawable
}

/** Modrá tečka jako v Google Mapách (bílý okraj + jemný stín). */
private fun createLocationDot(context: Context): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (34 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f

    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        maskFilter = BlurMaskFilter(3f * d, BlurMaskFilter.Blur.NORMAL)
    }
    c.drawCircle(cx, cy + 1f * d, 10f * d, shadow)

    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    c.drawCircle(cx, cy, 10f * d, white)

    val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4285F4.toInt() }
    c.drawCircle(cx, cy, 7.5f * d, blue)
    return bmp
}

private fun findVehicle(list: List<Vehicle>, ref: TripRef): Vehicle? =
    list.firstOrNull { it.cis == ref.cis && it.trip == ref.trip && it.state in 0..1 }


private fun bearingBetween(
    start: LatLng,
    end: LatLng
): Float {
    val lat1 = Math.toRadians(start.latitude)
    val lat2 = Math.toRadians(end.latitude)
    val dLon = Math.toRadians(end.longitude - start.longitude)

    val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2)
    val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.cos(dLon)

    return (
            Math.toDegrees(kotlin.math.atan2(y, x)).toFloat() + 360f
            ) % 360f
}

private fun routeArrowDescriptor(
    primaryColor: Int,
    scale: Float
): BitmapDescriptor {
    val size = (96 * scale).toInt().coerceAtLeast(18)
    val bitmap = Bitmap.createBitmap(
        size, size, Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)

    // Souřadnice se škálují společně s bitmapou.
    canvas.save()
    canvas.scale(size / 72f, size / 72f)

    val path = android.graphics.Path().apply {
        moveTo(13f, 43f)
        lineTo(36f, 15f)
        lineTo(59f, 43f)
    }

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawPath(path, borderPaint)

    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryColor
        style = Paint.Style.STROKE
        strokeWidth = 11f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawPath(path, arrowPaint)

    canvas.restore()

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}


private fun arrowSizeForZoom(zoom: Float): Float {
    return when {
        zoom >= 18f -> 1.4f
        zoom >= 16f -> 1.15f
        zoom >= 14f -> 0.9f
        zoom >= 12f -> 0.65f
        zoom >= 10f -> 0.45f
        else -> 0.3f
    }
}


private fun arrowSpacingForZoom(zoom: Float): Float = when {
    zoom >= 17f -> 180f
    zoom >= 15f -> 300f
    zoom >= 13f -> 500f
    zoom >= 11f -> 900f
    zoom >= 9f -> 1600f
    else -> 3000f
}


@SuppressLint("MissingPermission")
@Composable
private fun MapScreen(modifier: Modifier = Modifier, appState: SpojeAppState, api: DukApi, active: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // polling poloh
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (appState.vehicles.isEmpty()) appState.vehiclesLoaded = false
        while (true) {
            runCatching { api.vehiclePositions() }
                .onSuccess {
                    appState.vehicles = it
                    appState.heldCount = api.heldCount()
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
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    // zvýraznění markeru
    LaunchedEffect(appState.highlightedTrip, appState.vehiclesLoaded) {
        if (appState.highlightedTrip == null) { appState.pulsePhase = 0; return@LaunchedEffect }
        if (!appState.vehiclesLoaded) return@LaunchedEffect
        repeat(6) { i -> appState.pulsePhase = if (i % 2 == 0) 1 else 2; delay(400) }
        appState.pulsePhase = 0
        appState.highlightedTrip = null
    }

    // přiblížení po kliknutí na "Mapa"
    LaunchedEffect(appState.focusTrip, appState.vehiclesLoaded, appState.routeLoading) {
        val ref = appState.focusTrip ?: return@LaunchedEffect
        if (!appState.vehiclesLoaded || appState.routeLoading) return@LaunchedEffect
        val v = findVehicle(appState.vehicles, ref)
        val route = appState.tripRoute
        if (v != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(v.lat, v.lng), 16f), 700)
        } else {
            android.widget.Toast.makeText(
                context,
                "Autobus tohoto spoje teď není na mapě, možná ještě nevyjel",
                android.widget.Toast.LENGTH_LONG
            ).show()
            if (route.isNotEmpty()) {
                val lats = route.map { it.latitude }
                val lons = route.map { it.longitude }
                val span = maxOf(lats.max() - lats.min(), lons.max() - lons.min())
                val zoom = if (span > 0.3) 10f else if (span > 0.15) 11f else if (span > 0.07) 12f else 13f
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
    val target = if (ref != null) findVehicle(allVehicles, ref) else null
    val activeVehicles = allVehicles.filter {
        it === target || (it.lat in 50.35..50.95 && it.lng in 13.55..14.60)
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
            val primaryArgb = primaryColor.toArgb()
            val arrowColor = MaterialTheme.colorScheme.onBackground
            val arrowArgb = arrowColor.toArgb()


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
                    createRouteArrows(
                        route = route,
                        spacingMeters = arrowSpacing
                    )
                }

                arrows.forEachIndexed { index, arrow ->
                    key(index) {
                        Marker(
                            state = rememberUpdatedMarkerState(
                                position = arrow.position
                            ),
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
            for (v in activeVehicles) {
                key(v.id) {
                    val pos = LatLng(v.lat, v.lng)
                    val markerState = remember { MarkerState(pos) }
                    markerState.position = pos
                    val iconPhase = if (v === target) phase else 0
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
                onClick = { appState.tripRoute = emptyList() },
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


@Composable
private fun VehicleDetailBottomSheet(
    vehicle: Vehicle,
    api: DukApi,
    appState: SpojeAppState,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var tripInfo by remember(vehicle.id) { mutableStateOf<VehicleTripInfo?>(null) }
    LaunchedEffect(vehicle.id) {
        tripInfo = runCatching { api.vehicleTripInfo(vehicle) }.getOrNull()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Informace o spoji:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (vehicle.train) Color(0xFF0277BD) else Color(0xFF2E7D32),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(if (vehicle.train) Icons.Default.Train else Icons.Default.DirectionsBus, null, Modifier.size(20.dp))
                        Text(vehicle.line, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                }

                /*
                Text(
                    text = if (vehicle.destination.isNotBlank()) "→ ${vehicle.destination}" else vehicle.line,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )*/

            }

            Text("ID spoje: ${vehicle.trip}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val ageSec by produceState(api.vehicleAgeSec(vehicle), vehicle) {
                while (true) {
                    value = api.vehicleAgeSec(vehicle)
                    delay(1000.milliseconds)
                }
            }
            Text(
                ageLabel(ageSec),
                style = MaterialTheme.typography.bodySmall,
                color = if (ageSec > 40) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    scope.launch {
                        appState.routeLoading = true
                        val points = api.tripGeometry(vehicle)
                        if (points.isNotEmpty()) {
                            appState.tripRoute = points.map { LatLng(it.first, it.second) }
                            onDismiss()
                        } else {
                            android.widget.Toast.makeText(ctx, "Trasu se nepodařilo určit", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        appState.routeLoading = false
                    }
                },
                enabled = !appState.routeLoading && vehicle.state in 0..1,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (appState.routeLoading) {
                    Wavy(20.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Zobrazit trasu spoje")
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Zpoždění:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val delayText = if (vehicle.delay != 0) "${vehicle.delay} min" else "Včas (0 min)"
                val delayBg = if (vehicle.delay > 3) Color(0xFFC62828) else Color(0xFF00838F)
                Surface(shape = RoundedCornerShape(16.dp), color = delayBg, contentColor = Color.White) {
                    Text(delayText, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }

            if (vehicle.agency.isNotBlank()) {
                Text("Dopravce: ${vehicle.agency}", style = MaterialTheme.typography.bodyMedium)
            }

            tripInfo?.nextStop?.let {
                Text("Příští zastávka: $it", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            tripInfo?.finalStop?.let {
                Text("Konečná zastávka: $it", style = MaterialTheme.typography.bodyMedium)
            }

            if (vehicle.airConditioned == true || vehicle.accessible == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vehicle.airConditioned == true) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AcUnit, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Klimatizace", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    if (vehicle.accessible == true) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Accessible, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Nízkopodlažní", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private data class RouteArrow(
    val position: LatLng,
    val bearing: Float
)

private fun distanceMeters(a: LatLng, b: LatLng): Float {
    val result = FloatArray(1)

    android.location.Location.distanceBetween(
        a.latitude, a.longitude,
        b.latitude, b.longitude,
        result
    )

    return result[0]
}

private fun pointAtDistance(
    route: List<LatLng>,
    targetMeters: Float
): LatLng {
    if (route.size < 2 || targetMeters <= 0f) {
        return route.first()
    }

    var travelled = 0f

    for (i in 0 until route.lastIndex) {
        val start = route[i]
        val end = route[i + 1]
        val segmentLength = distanceMeters(start, end)

        if (segmentLength <= 0f) continue

        if (travelled + segmentLength >= targetMeters) {
            val fraction = (
                    (targetMeters - travelled) / segmentLength
                    ).coerceIn(0f, 1f).toDouble()

            return LatLng(
                start.latitude +
                        (end.latitude - start.latitude) * fraction,
                start.longitude +
                        (end.longitude - start.longitude) * fraction
            )
        }

        travelled += segmentLength
    }

    return route.last()
}

private fun createRouteArrows(
    route: List<LatLng>,
    spacingMeters: Float = 300f
): List<RouteArrow> {
    if (route.size < 2) return emptyList()

    val totalLength = route.zipWithNext()
        .sumOf { (a, b) -> distanceMeters(a, b).toDouble() }
        .toFloat()

    if (totalLength < 100f) return emptyList()

    val arrows = mutableListOf<RouteArrow>()

    // První šipka bude přibližně 150 m od začátku.
    var distance = spacingMeters / 2f

    while (distance < totalLength - 20f) {
        val position = pointAtDistance(route, distance)

        // Směr určíme podle bodů těsně před a za šipkou.
        // Tím se omezí chybné natočení na ostrých zatáčkách.
        val before = pointAtDistance(
            route,
            (distance - 15f).coerceAtLeast(0f)
        )

        val after = pointAtDistance(
            route,
            (distance + 15f).coerceAtMost(totalLength)
        )

        arrows += RouteArrow(
            position = position,
            bearing = bearingBetween(before, after)
        )

        distance += spacingMeters
    }

    return arrows
}