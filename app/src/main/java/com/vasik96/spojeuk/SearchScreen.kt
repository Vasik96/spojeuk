@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import android.content.res.Configuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.Context
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.milliseconds

private const val FIRST_PAGE = 8
private const val MORE_PAGE = 6

private const val WALK_MAX_MIN = 20

// stará poloha by předvyplnila špatnou zastávku
private const val FIX_MAX_AGE_MS = 3 * 60_000L

/**
 * Rychlé zjištění polohy: současně se zeptá cache (lastLocation) a Play Services
 * (getCurrentLocation s maxUpdateAge, takže vrátí čerstvou cache hned a nečeká na GPS).
 * Vyhrává první platná odpověď; onFix(null) jen když selžou obě cesty. Volá se na main vlákně.
 */
@SuppressLint("MissingPermission")
internal fun requestFix(context: Context, maxAgeMs: Long, onFix: (Location?) -> Unit) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    var done = false
    fun deliver(l: Location?) {
        if (done) return
        done = true
        onFix(l)
    }
    client.lastLocation.addOnSuccessListener { l ->
        if (l != null && System.currentTimeMillis() - l.time <= maxAgeMs) deliver(l)
    }
    val req = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        .setMaxUpdateAgeMillis(maxAgeMs)
        .setDurationMillis(10_000)
        .build()
    client.getCurrentLocation(req, null)
        .addOnSuccessListener { l -> deliver(l) }
        .addOnFailureListener { deliver(null) }
}
@SuppressLint("MissingPermission")
@Composable
internal fun SearchScreen(
    modifier: Modifier = Modifier,
    appState: SpojeAppState,
    api: TransitRepository,
    onShowMapForJourney: (Journey) -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = appState.listState
    var isSearchCardVisible by remember { mutableStateOf(true) }
    val context = LocalContext.current
    var locatingTarget by remember { mutableStateOf<String?>(null) }
    var pendingLocation by remember { mutableStateOf<Pair<String, (Stop) -> Unit>?>(null) }

    LaunchedEffect(Unit) {
        if (appState.searchJob?.isActive != true) {
            appState.refreshing = false
            appState.searchLoading = false
            appState.loadingMore = false
            appState.loadingEarlier = false
        }
    }

    // Referenční bod řazení: poloha uživatele, ale jen když je v regionu, jinak střed Ústí
    val origin = remember(appState.userLoc) {
        val u = appState.userLoc
        if (u != null && u.first in 50.35..50.95 && u.second in 13.55..14.60) u
        else UstiCenter.latitude to UstiCenter.longitude
    }

    val osrm = remember { OsrmClient(context.cacheDir) }

    // Pěší trasa od uživatele k zvolené zastávce "Odkud"
    LaunchedEffect(appState.searchedFrom?.name, appState.userLoc) {
        val from = appState.searchedFrom
        val u = appState.userLoc
        if (from == null || u == null || !from.hasCoords) {
            appState.walkRoute = null
            return@LaunchedEffect
        }
        if (Geo.distanceM(u.first, u.second, from.lat, from.lng) > 1200.0) {
            appState.walkRoute = null
            return@LaunchedEffect
        }
        val route = withContext(Dispatchers.IO) {
            osrm.walkRoute(u, from.lat to from.lng)
        }
        appState.walkRoute = route?.takeIf { it.minutes <= WALK_MAX_MIN }
    }

    // Poloha se zjišťuje hned při otevření záložky (nečeká na načtení zastávek),
    // takže předvyplnění pak může začít okamžitě. Spustí se při každém návratu na záložku Spoje.
    LaunchedEffect(Unit) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val fresh = appState.userLoc != null &&
                    System.currentTimeMillis() - appState.userLocTime < FIX_MAX_AGE_MS
            if (!fresh) requestFix(context, FIX_MAX_AGE_MS) { loc ->
                if (loc != null) {
                    appState.userLoc = loc.latitude to loc.longitude
                    appState.userLocTime = System.currentTimeMillis()
                }
            }
        }
    }

    fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    fun resolveNearest(lat: Double, lng: Double, target: String, onPick: (Stop) -> Unit) {
        scope.launch {
            appState.userLoc = lat to lng
            appState.userLocTime = System.currentTimeMillis()

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
        // použije čerstvou polohu, kterou už aplikace má, jinak ji rychle zjistí
        val known = appState.userLoc
        if (known != null && System.currentTimeMillis() - appState.userLocTime < FIX_MAX_AGE_MS) {
            resolveNearest(known.first, known.second, target, onPick)
            return
        }
        requestFix(context, FIX_MAX_AGE_MS) { loc ->
            if (loc != null) resolveNearest(loc.latitude, loc.longitude, target, onPick)
            else { locatingTarget = null; toast("Polohu se nepodařilo zjistit") }
        }
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
            appState.userLocTime = System.currentTimeMillis()
            if (appState.autoFillDone) return@launch
            val untouched = appState.from == null && appState.to == null &&
                    appState.fromQuery.isBlank() && appState.toQuery.isBlank()
            if (!untouched) return@launch

            // širší výběr kandidátů vzdušnou čarou, ať se nevyřadí zastávka, která je po cestě blíž
            val cands = runCatching { api.nearestCandidates(lat, lng, 6) }.getOrNull().orEmpty()
            if (cands.isEmpty()) return@launch

            // zpřesnění po silnici; OsrmClient si při chybě sám zkusí víc pokusů i záložní server
            val refined: List<Pair<Stop, Double>>? =
                if (cands.size > 1) runCatching { api.refineByRoad(lat, lng, cands) }.getOrNull()
                    ?.let { listOf(it) }
                else null
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
            appState.fromQuery = myLocLabel(from.name)
            if (to != null) {
                appState.to = to
                appState.toQuery = stopLabel(to.name)
            }
            appState.autoFillDone = true
        }
    }

    fun autoFill() {
        if (appState.autoFillDone) return
        // poloha zjištěná už při otevření záložky -> začne se hned
        val known = appState.userLoc
        if (known != null && System.currentTimeMillis() - appState.userLocTime < FIX_MAX_AGE_MS) {
            applyAutoFill(known.first, known.second)
            return
        }
        requestFix(context, FIX_MAX_AGE_MS) { loc -> if (loc != null) applyAutoFill(loc.latitude, loc.longitude) }
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

        val s = api.newSearch(a.name, b.name, modeFilter = appState.modeFilter)
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
        appState.searchedFrom = a
        isSearchCardVisible = true

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
                withContext(NonCancellable) {
                    if (appState.search === s) appState.searchLoading = false
                }
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
            // Refresh poběží minimálně 700 ms.
            val refreshStartedAt = System.currentTimeMillis()
            var wasCancelled = false

            try {
                val r = withContext(Dispatchers.Default) {
                    s.loadMore(FIRST_PAGE)
                }

                if (appState.search === s) {
                    appState.journeys = r
                    appState.hasMore = s.hasMore
                }
            } catch (e: CancellationException) {
                wasCancelled = true
                throw e
            } catch (e: Exception) {
                if (appState.search === s) {
                    appState.searchError = "Obnovení selhalo: ${e.message}"
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        val elapsed = System.currentTimeMillis() - refreshStartedAt
                        val remaining = 700L - elapsed
                        if (remaining > 0 && !wasCancelled) {
                            delay(remaining)
                        }
                    } catch (_: CancellationException) {
                    }
                    if (appState.search === s) {
                        appState.refreshing = false
                    }
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
                withContext(NonCancellable) {
                    if (appState.search === s) appState.loadingMore = false
                }
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
                withContext(NonCancellable) {
                    if (appState.search === s) {
                        appState.loadingEarlier = false
                    }
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

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy < -15f) {
                    if (isSearchCardVisible) {
                        isSearchCardVisible = false
                    }
                } else if (dy > 15f) {
                    if (!isSearchCardVisible) {
                        isSearchCardVisible = true
                    }
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { atTop ->
                if (atTop) {
                    isSearchCardVisible = true
                }
            }
    }

    var cardReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cardReady = true
    }
    val cardOffsetY by animateFloatAsState(
        targetValue = if (cardReady) 0f else 35f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "searchCardOffsetY"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (cardReady) 1f else 0f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "searchCardAlpha"
    )

    var swapRotation by remember { mutableFloatStateOf(0f) }
    val animatedSwapRotation by animateFloatAsState(
        targetValue = swapRotation,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "swapRotation"
    )

    val searchCard: @Composable () -> Unit = {
        ElevatedCard(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier.graphicsLayer {
                translationY = cardOffsetY
                alpha = cardAlpha
            }
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
                        useMyLocation("from") { s -> appState.from = s; appState.fromQuery = myLocLabel(s.name) }
                    }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    IconButton(onClick = {
                        swapRotation += 180f
                        val f = appState.from
                        appState.from = appState.to
                        appState.to = f
                        val q = appState.fromQuery
                        appState.fromQuery = appState.toQuery
                        appState.toQuery = q
                    }) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = "Prohodit zastávky",
                            modifier = Modifier.graphicsLayer { rotationZ = animatedSwapRotation }
                        )
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
                        useMyLocation("to") { s -> appState.to = s; appState.toQuery = myLocLabel(s.name) }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModeFilter.entries.forEach { mode ->
                        FilterChip(
                            selected = appState.modeFilter == mode,
                            onClick = {
                                if (appState.modeFilter != mode) {
                                    appState.modeFilter = mode
                                    if (appState.from != null && appState.to != null && appState.searched) {
                                        runSearch()
                                    }
                                }
                            },
                            label = { Text(mode.label) },
                            leadingIcon = when (mode) {
                                ModeFilter.BUS -> ({ Icon(Icons.Default.DirectionsBus, null, Modifier.size(18.dp)) })
                                ModeFilter.TRAIN -> ({ Icon(Icons.Default.Train, null, Modifier.size(18.dp)) })
                                ModeFilter.ALL -> null
                            }
                        )
                    }
                }

                Button(
                    onClick = { runSearch() },
                    enabled = appState.from != null && appState.to != null &&
                            appState.from?.name != appState.to?.name && !appState.searchLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp),
                ) {
                    AnimatedContent(
                        targetState = appState.searchLoading,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(200, delayMillis = 50)) +
                                    scaleIn(initialScale = 0.9f, animationSpec = tween(200, delayMillis = 50)))
                                .togetherWith(fadeOut(animationSpec = tween(100)))
                        },
                        label = "searchButtonContent"
                    ) { loading ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (loading) {
                                CircularWavyProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    wavelength = 8.dp,
                                    amplitude = 1.5f
                                )
                            } else {
                                Icon(Icons.Default.Search, contentDescription = null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (loading) "Hledám spoje…" else "Najít spojení")
                        }
                    }
                }
            }
        }

        appState.searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    val resultsHeader: @Composable () -> Unit = {
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

    val resultsBody: @Composable (Modifier) -> Unit = { mod ->
        if (!appState.searched) {
            val infiniteTransition = rememberInfiniteTransition(label = "busFloating")
            val busFloatY by infiniteTransition.animateFloat(
                initialValue = -5f,
                targetValue = 5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "busFloatY"
            )
            val busScale by infiniteTransition.animateFloat(
                initialValue = 0.97f,
                targetValue = 1.03f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "busScale"
            )

            Box(
                mod.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.DirectionsBus,
                        null,
                        Modifier
                            .size(48.dp)
                            .graphicsLayer {
                                translationY = busFloatY
                                scaleX = busScale
                                scaleY = busScale
                            },
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Kam dnes vyrazíš?", style = MaterialTheme.typography.headlineSmall)
                    Text("Vyber výchozí a cílovou zastávku.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (appState.searchLoading && appState.journeys.isEmpty()) {
            Box(mod.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val strokeWidth = with(LocalDensity.current) { 8.dp.toPx() }
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = MaterialTheme.colorScheme.primary,
                    wavelength = 16.dp,
                    stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    trackStroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        } else {
            PullToRefreshBox(
                isRefreshing = appState.refreshing,
                onRefresh = { refresh() },
                modifier = mod.fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(
                        appState.journeys,
                        key = { _, j -> j.key },
                        contentType = { _, _ -> "journey" }
                    ) { index, journey ->
                        AppearAnimated(appState, journey.key, index, Modifier.animateItem()) {
                            JourneyCard(journey, now, appState.walkRoute) { onShowMapForJourney(journey) }
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

    if (isLandscape) {
        Row(modifier.fillMaxSize()) {
            // vlevo formulář (scrolluje se, aby tlačítko bylo vždy dostupné)
            Column(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                searchCard()
            }

            // vpravo výsledky
            Column(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    resultsHeader()
                }
                resultsBody(Modifier.weight(1f))
            }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(
                    visible = isSearchCardVisible || !appState.searched,
                    enter = expandVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + fadeIn(animationSpec = tween(200)),
                    exit = shrinkVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + fadeOut(animationSpec = tween(200))
                ) {
                    searchCard()
                }
                resultsHeader()
            }
            resultsBody(
                Modifier
                    .weight(1f)
                    .nestedScroll(nestedScrollConnection)
            )
        }
    }
}

// ───────────────────────── výběr zastávky ─────────────────────────

private fun getStopMatches(
    query: String,
    indexedStops: List<IndexedStop>,
    originLat: Double,
    originLng: Double
): List<Stop> {
    val cosLat = Math.cos(Math.toRadians(originLat))
    // druhá mocnina vzdálenosti v m² (odmocnina není pro řazení potřeba)
    fun dist2(s: Stop): Double {
        if (!s.hasCoords) return Double.MAX_VALUE
        val dy = (s.lat - originLat) * Geo.M_PER_DEG_LAT
        val dx = (s.lng - originLng) * Geo.M_PER_DEG_LNG * cosLat
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
    var showSheet by remember { mutableStateOf(false) }
    val text = externalText()
    val colors = MaterialTheme.colorScheme

    val selectStopButtonColor = lerp(
        start = colors.surfaceVariant,
        stop = colors.outlineVariant,
        fraction = 0.3f
    )

    Surface(
        onClick = { showSheet = true },
        shape = RoundedCornerShape(18.dp),
        color = selectStopButtonColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (label == "Odkud") Icons.Default.TripOrigin else Icons.Default.Place,
                contentDescription = null,
                tint = colors.primary
            )

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
                AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(200)) + slideInVertically { it / 3 })
                            .togetherWith(fadeOut(animationSpec = tween(150)) + slideOutVertically { -it / 3 })
                    },
                    label = "stopFieldText"
                ) { displayText ->
                    if (displayText.isNotEmpty()) {
                        Text(
                            displayText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            "Vyber zastávku",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (stopsLoading) {
                Wavy(20.dp)
                Spacer(Modifier.width(6.dp))
            }

            AnimatedVisibility(
                visible = text.isNotEmpty(),
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.8f),
                exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.8f)
            ) {
                IconButton(onClick = { onTextChange("") }) {
                    Icon(Icons.Default.Clear, "Vymazat")
                }
            }
        }
    }

    if (showSheet) {
        StopPickerSheet(
            title = label,
            indexedStops = indexedStops,
            originLat = originLat,
            originLng = originLng,
            onDismiss = { showSheet = false },
            onPick = { showSheet = false; onPick(it) },
            onMyLocation = { showSheet = false; onMyLocation() },
            onRemoveUsed = onRemoveUsed
        )
    }
}

@Composable
private fun rememberHideKeyboard(): () -> Unit {
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    return remember(view, focusManager) {
        {
            focusManager.clearFocus(force = true)
            view.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
        }
    }
}

@Composable
private fun StopPickerSheet(
    title: String,
    indexedStops: List<IndexedStop>,
    originLat: Double,
    originLng: Double,
    onDismiss: () -> Unit,
    onPick: (Stop) -> Unit,
    onMyLocation: () -> Unit,
    onRemoveUsed: (Stop) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }

    var query by remember { mutableStateOf("") }
    var options by remember { mutableStateOf<List<Stop>>(emptyList()) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(query, indexedStops, originLat, originLng) {
        delay(60.milliseconds)
        options = withContext(Dispatchers.Default) {
            getStopMatches(query, indexedStops, originLat, originLng)
        }
    }

    LaunchedEffect(Unit) {
        delay(150.milliseconds)
        focusRequester.requestFocus()
    }


    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = {
            // tady jsme uvnitř dialogu, takže view i focusManager jsou správné
            val hideKeyboard = rememberHideKeyboard()
            Box(
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(pass = PointerEventPass.Initial)
                        hideKeyboard()
                    }
                }
            ) {
                BottomSheetDefaults.DragHandle()
            }
        }
    ) {
        val hideKeyboard = rememberHideKeyboard()

        // jakmile sheet míří dolů (tažení, handle, scrim, zpět, výběr), schovej klávesnici
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.targetValue }
                .collect { if (it == SheetValue.Hidden) hideKeyboard() }
        }

        val scrollHidesKeyboard = remember(hideKeyboard) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y != 0f) hideKeyboard()
                    return Offset.Zero
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .imePadding()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { hideKeyboard() })
                }
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Hledat zastávku…") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, "Vymazat")
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(scrollHidesKeyboard),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (query.isBlank()) {
                    item(key = "myloc") {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                            },
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
                            onClick = { hideKeyboard(); onMyLocation() }
                        )
                    }
                    if (options.isNotEmpty()) {
                        item(key = "header") {
                            Text(
                                "Často používané zastávky",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                items(options, key = { it.name }, contentType = { "stop" }) { stop ->
                    val (stopPart, city) = splitStopName(stop.name)
                    DropdownMenuItem(
                        contentPadding = PaddingValues(
                            horizontal = 24.dp,
                            vertical = 12.dp
                        ),

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
                        trailingIcon = if (query.isBlank()) {
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
                            hideKeyboard()

                            scope.launch {
                                sheetState.hide()
                                onPick(stop)
                            }
                        }
                    )
                }

                if (options.isEmpty() && query.isNotBlank()) {
                    item(key = "empty") {
                        Text(
                            "Nic nenalezeno",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────── karta spojení ─────────────────────────




@Composable
private fun JourneyCard(
    j: Journey,
    now: LocalDateTime,
    walk: WalkRoute?,
    onMap: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    ElevatedCard(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = colors.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // 1. Časy + Mapa
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            j.departure.take(5),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )

                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Příjezd",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            j.arrival.take(5),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            departsIn(j, now),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.primary,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            "· ",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )


                        Text(
                            "${j.durationMinutes} min jízdy",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )

                        // zpoždění:
                        if (j.realtime && j.delay > 0) {
                            val (bg, fg, label) = when {
                                j.delay > 3 -> Triple(
                                    colors.errorContainer,
                                    colors.onErrorContainer,
                                    "Zpoždění ${j.delay} min"
                                )
                                else -> Triple(
                                    colors.tertiaryContainer,
                                    colors.onTertiaryContainer,
                                    "Zpoždění ${j.delay} min"
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(50),
                                color = bg,
                                contentColor = fg
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.padding(
                                        horizontal = 9.dp,
                                        vertical = 4.dp
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }


                    }
                }


                FilledTonalButton(
                    onClick = onMap,
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        Icons.Default.Map,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Mapa")
                }
            }

            HorizontalDivider(
                color = colors.outlineVariant.copy(alpha = 0.35f)
            )

            // 2. Linky + zpoždění
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer
                ) {
                    Text(
                        j.line,
                        modifier = Modifier.padding(
                            horizontal = 10.dp,
                            vertical = 7.dp
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                j.lines.drop(1).forEach { extraLine ->
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Přestup",
                        modifier = Modifier.size(20.dp),
                        tint = colors.onSurfaceVariant
                    )

                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer
                    ) {
                        Text(
                            extraLine,
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 7.dp
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3. Chůze k zastávce
            val walkText = when {
                j.boardWalkMin != null ->
                    "${j.boardWalkMin} min pěšky na ${splitStopName(j.boardStopName.orEmpty()).first}"
                walk != null -> "${walk.minutes} min chůze k zastávce · ${fmtDist(walk.distanceM)}"
                else -> null
            }
            if (walkText != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(16.dp), tint = colors.tertiary)
                    Text(walkText, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, color = colors.tertiary)
                }
            }

            HorizontalDivider(
                color = colors.outlineVariant.copy(alpha = 0.35f)
            )

            val displayStops = remember(j.routeStops) {
                fun norm(name: String): String {
                    val label = splitStopName(prettyName(name)).first.ifBlank { name }
                    return foldText(label).replace(Regex("\\bhl\\.?n\\.?\\b"), "hlavni nadrazi").replace(Regex("\\s+"), "")
                }
                val result = mutableListOf<String>()
                for (stop in j.routeStops) {
                    if (result.isEmpty() || norm(result.last()) != norm(stop)) {
                        result.add(stop)
                    }
                }
                result
            }

            // Trasa spoje – pouze začátek, přestupy a cíl
            if (displayStops.size >= 2) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        "Trasa spoje",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        displayStops.forEachIndexed { index, stop ->
                            Text(
                                splitStopName(stop).first,
                                style = if (
                                    index == 0 || index == displayStops.lastIndex
                                ) {
                                    MaterialTheme.typography.bodyMedium
                                } else {
                                    MaterialTheme.typography.bodySmall
                                },
                                fontWeight = if (
                                    index == 0 || index == displayStops.lastIndex
                                ) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                                color = when (index) {
                                    0 -> colors.primary
                                    displayStops.lastIndex -> colors.tertiary
                                    else -> colors.onSurfaceVariant
                                }
                            )

                            if (index < displayStops.lastIndex) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = colors.outlineVariant.copy(alpha = 0.35f)
                )
            }


            // 4. Detaily spoje
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (j.isTransfer) {
                    j.transferList.forEachIndexed { idx, t ->
                        val prefix = if (j.transferList.size > 1) "${idx + 1}. přestup" else "Přestup"
                        val at = t.at.takeIf { it.isNotBlank() }
                        if (at != null && at.contains("→")) {
                            val parts = at.split("→", limit = 2)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "$prefix: ${parts[0].trim()}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "do",
                                    modifier = Modifier.size(14.dp),
                                    tint = colors.onSurface
                                )
                                Text(
                                    text = parts[1].trim(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Text(
                                "$prefix: ${at ?: "mezi spoji"}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            "čekání ${t.waitMin} min" +
                                    (t.walkMin?.let { " · $it min pěšky" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        "${j.stopsCount ?: "—"} zastávek · přímý spoj",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (j.destination.isNotBlank()) {
                    Text(
                        "Směr ${splitStopName(j.destination).first}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }

                val cleanedAgency = cleanAgencyName(j.agency)
                if (cleanedAgency.isNotBlank()) {
                    Text(
                        text = cleanedAgency,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}


fun cleanAgencyName(name: String): String {
    val regex = Regex(
        pattern = """(?i)\b(a\.?\s*s\.?|s\.?\s*r\.?\s*o\.?|spol\.?\s*s\s*r\.?\s*o\.?|příspěvková\s+organizace|p\.o\.?|o\.p\.s\.?|v\.o\.s\.?|z\.s\.?|gmbh|inc\.?|ltd\.?)\b""",
        option = RegexOption.IGNORE_CASE
    )
    return name
        .replace(regex, "")
        .replace(Regex("""[.,\s]+$"""), "") // Odstraní tečky, čárky i mezery na konci řetězce
        .trim()
}