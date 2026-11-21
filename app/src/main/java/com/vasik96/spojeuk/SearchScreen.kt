@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

private const val FIRST_PAGE = 8
private const val MORE_PAGE = 6

private const val WALK_MAX_MIN = 20


private fun departChipLabel(dt: LocalDateTime): String {
    val today = todayPrague()
    val day = when (dt.toLocalDate()) {
        today -> tr(R.string.day_today)
        today.plusDays(1) -> tr(R.string.day_tomorrow)
        else -> dt.format(DateTimeFormatter.ofPattern(tr(R.string.date_short_pattern), I18n.locale()))
    }
    return "$day ${dt.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

/** Popisek zvoleného času: "zítra 08:00", u hledání podle příjezdu "Příjezd do zítra 08:00". */
private fun whenLabel(dt: LocalDateTime, arrive: Boolean): String =
    if (arrive) tr(R.string.search_arrive_by_label, departChipLabel(dt).replaceFirstChar { it.lowercase(I18n.locale()) })
    else departChipLabel(dt)

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
    val context = LocalContext.current
    var locatingTarget by remember { mutableStateOf<String?>(null) }
    // spinner u "Odkud" od úplného začátku (už při první kompozici), dokud předvyplnění neskončí
    var autoFillPending by remember {
        mutableStateOf(
            !appState.autoFillDone && appState.from == null && appState.to == null &&
                    appState.fromQuery.isBlank() && appState.toQuery.isBlank() &&
                    (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        )
    }
    fun endAutoFillIndicator() {
        autoFillPending = false
        if (locatingTarget == "from") locatingTarget = null
    }

    /**
     * Nejbližší zastávky + pořadí po silnici, sdílené mezi předpočítáním (hned po zjištění polohy) a předvyplněním.
     * Díky tomu OSRM běží už během načítání zastávek a předvyplnění pak často nečeká vůbec.
     */
    fun nearestFor(lat: Double, lng: Double): Deferred<NearestResult> {
        val k = appState.nearestKey
        val j = appState.nearestJob
        if (k != null && j != null && !j.isCancelled &&
            System.currentTimeMillis() - appState.nearestKeyTime < FIX_MAX_AGE_MS &&
            Geo.distanceM(lat, lng, k.first, k.second) < 30.0
        ) return j
        val job = scope.async {
            val cands = runCatching { api.nearestCandidates(lat, lng, 6) }.getOrNull().orEmpty()
            val refined = if (cands.size > 1) runCatching { api.refineByRoad(lat, lng, cands) }.getOrNull() else null
            // neúspěšný výsledek se nekešuje, další pokus začne znovu
            if (cands.size > 1 && refined == null) appState.nearestKey = null
            NearestResult(cands, refined)
        }
        appState.nearestKey = lat to lng
        appState.nearestKeyTime = System.currentTimeMillis()
        appState.nearestJob = job
        return job
    }
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
            if (fresh) {
                appState.userLoc?.let { if (!appState.autoFillDone) nearestFor(it.first, it.second) }
            } else requestFix(context, FIX_MAX_AGE_MS) { loc ->
                if (loc != null) {
                    appState.userLoc = loc.latitude to loc.longitude
                    appState.userLocTime = System.currentTimeMillis()
                    if (!appState.autoFillDone) nearestFor(loc.latitude, loc.longitude)
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
                toast(tr(R.string.search_nearest_not_found))
                return@launch
            }

            // po silnici; když OSRM selže, záloha = nejbližší vzdušnou čarou
            val (stop, dist) = (if (cands.size > 1) runCatching { api.refineByRoad(lat, lng, cands) }.getOrNull() else null)
                ?: cands[0]

            locatingTarget = null
            val picked = appState.stops.firstOrNull { it.name == stop.name } ?: stop
            onPick(picked)
            toast(tr(R.string.search_nearest_found, splitStopName(picked.name).first, fmtDist(dist)))
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
            else { locatingTarget = null; toast(tr(R.string.search_location_failed)) }
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
        else toast(tr(R.string.search_no_location_permission))
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
            try {
                appState.userLoc = lat to lng
                appState.userLocTime = System.currentTimeMillis()
                if (appState.autoFillDone) return@launch
                val untouched = appState.from == null && appState.to == null &&
                        appState.fromQuery.isBlank() && appState.toQuery.isBlank()
                if (!untouched) return@launch

                // širší výběr kandidátů vzdušnou čarou, ať se nevyřadí zastávka, která je po cestě blíž
                // (zpřesnění po silnici běží možná už od zjištění polohy; OsrmClient si při chybě sám zkusí víc pokusů)
                val res = runCatching { nearestFor(lat, lng).await() }.getOrNull() ?: return@launch
                val cands = res.cands
                if (cands.isEmpty()) return@launch
                val refined: Pair<Stop, Double>? = res.refined
                val best = refined ?: cands[0]

                // limit až na vzdálenost po silnici
                if (best.second > 700.0) return@launch

                // vyplní odkud/kam podle zvolené nejbližší zastávky a historie
                fun fill(best: Pair<Stop, Double>): Boolean {
                    // pro historii použij pořadí podle silnice: nejlepší první
                    val ordered = listOf(best) + cands.filter { it.first.name != best.first.name }
                    val near = ordered.filter { it.second <= 700.0 || it === best }

                    val sug = appState.history.suggest(near.map { it.first.name to it.second })
                    val fromName = sug?.first ?: best.first.name
                    val from = appState.stops.firstOrNull { it.name == fromName } ?: return false
                    val to = sug?.second?.let { n -> appState.stops.firstOrNull { it.name == n } }

                    appState.from = from
                    appState.fromQuery = myLocLabel(from.name)
                    if (to != null) {
                        appState.to = to
                        appState.toQuery = stopLabel(to.name)
                    }
                    appState.autoFillDone = true
                    return true
                }

                if (!fill(best)) return@launch
                endAutoFillIndicator()

                // OSRM selhal, předvyplnilo se vzdušnou čarou (rychle, ale může vybrat špatnou zastávku).
                // Na pozadí se zkusí zjistit pořadí po silnici a když uživatel mezitím nic neměnil, opraví se to.
                if (refined == null && cands.size > 1) {
                    val setFrom = appState.from
                    val setFromQ = appState.fromQuery
                    val setTo = appState.to
                    val setToQ = appState.toQuery
                    for (round in 0 until 4) {
                        delay(2_500L)
                        val unchanged = appState.from == setFrom && appState.fromQuery == setFromQ &&
                                appState.to == setTo && appState.toQuery == setToQ
                        if (!unchanged) return@launch
                        val better = runCatching { api.refineByRoad(lat, lng, cands) }.getOrNull() ?: continue
                        if (better.second <= 700.0 && better.first.name != best.first.name) fill(better)
                        return@launch
                    }
                }
            } finally {
                // indikátor načítání vždy zmizí, ať skončí předvyplnění kdekoliv
                endAutoFillIndicator()
            }
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
        requestFix(context, FIX_MAX_AGE_MS) { loc ->
            if (loc != null) applyAutoFill(loc.latitude, loc.longitude)
            else endAutoFillIndicator()
        }
    }

    val autoFillPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { p ->
        if (p[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            p[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) autoFill()
    }

    LaunchedEffect(appState.indexedStops.isNotEmpty()) {
        if (appState.autoFillDone) { endAutoFillIndicator(); return@LaunchedEffect }
        if (appState.indexedStops.isEmpty()) return@LaunchedEffect
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

        val s = api.newSearch(a.name, b.name, modeFilter = appState.modeFilter, departAt = appState.departAt,
            arriveBy = appState.arriveBy && appState.departAt != null)
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
        appState.searchedTo = b
        appState.searchedArriveBy = appState.arriveBy && appState.departAt != null
        appState.historyRev++
        appState.detailJourney = null
        appState.showResults = true

        appState.shownKeys.clear()
        listState.requestScrollToItem(0)

        appState.searchJob = scope.launch {
            try {
                val r = withContext(Dispatchers.Default) { s.loadMore(FIRST_PAGE) }
                if (appState.search === s) {
                    appState.journeys = r
                    appState.hasMore = s.hasMore
                    // podle příjezdu: nejlepší (nejpozdější odjezd, který ještě stihne) je poslední => ukázat ho
                    if (appState.searchedArriveBy && r.isNotEmpty()) listState.requestScrollToItem(r.lastIndex)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (appState.search === s) appState.searchError = tr(R.string.search_failed, e.message.orEmpty())
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
                    appState.searchError = tr(R.string.search_refresh_failed, e.message.orEmpty())
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
                    appState.searchError = tr(R.string.search_more_failed, e.message.orEmpty())
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
                        tr(R.string.search_earlier_failed, e.message.orEmpty())
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

    // ───── výběr data a času odjezdu ─────
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    if (showDatePicker) {
        val todayUtcMs = todayPrague().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val initial = (appState.departAt?.toLocalDate() ?: todayPrague())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = initial,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayUtcMs
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = dateState.selectedDateMillis != null,
                    onClick = {
                        dateState.selectedDateMillis?.let { ms ->
                            pickedDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            showDatePicker = false
                            showTimePicker = true
                        }
                    }
                ) { Text(stringResource(R.string.common_next)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.common_cancel)) } }
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTimePicker) {
        val base = appState.departAt?.toLocalTime() ?: nowPrague().toLocalTime()
        val timeState = rememberTimePickerState(
            initialHour = base.hour,
            initialMinute = base.minute,
            is24Hour = true
        )
        var arrive by remember { mutableStateOf(appState.arriveBy) }
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(if (arrive) R.string.search_arrival_time else R.string.search_departure_time)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // odjezd v / příjezd do
                    androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false to R.string.search_mode_depart, true to R.string.search_mode_arrive).forEachIndexed { i, (value, label) ->
                            SegmentedButton(
                                selected = arrive == value,
                                onClick = { arrive = value },
                                shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 2)
                            ) { Text(stringResource(label)) }
                        }
                    }
                    TimePicker(state = timeState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val d = pickedDate ?: todayPrague()
                    appState.arriveBy = arrive
                    appState.departAt = LocalDateTime.of(d, LocalTime.of(timeState.hour, timeState.minute))
                    showTimePicker = false   // hledá se až tlačítkem "Najít spojení"
                }) { Text(stringResource(R.string.common_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false; showDatePicker = true }) { Text(stringResource(R.string.common_back)) }
            }
        )
    }

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
                    label = stringResource(R.string.search_from),
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
                    stopsLoading = appState.stopsLoading || locatingTarget == "from" ||
                            (autoFillPending && appState.from == null && appState.fromQuery.isBlank()),
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
                            contentDescription = stringResource(R.string.search_swap),
                            modifier = Modifier.graphicsLayer { rotationZ = animatedSwapRotation }
                        )
                    }
                    HorizontalDivider(Modifier.weight(1f))
                }

                StopField(
                    label = stringResource(R.string.search_to),
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

                // druhy dopravy: jedno tlačítko se shrnutím a výběr v dialogu, stejně jako filtr mapy
                val cats = appState.searchCategories
                val allCats = cats.size == MapCategory.entries.size
                var catsAtOpen by remember { mutableStateOf<Set<MapCategory>?>(null) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !allCats,
                        onClick = { catsAtOpen = cats },
                        label = {
                            Text(
                                when {
                                    allCats -> stringResource(R.string.map_filter_all)
                                    cats.isEmpty() -> stringResource(R.string.map_filter_none)
                                    else -> MapCategory.entries.filter { it in cats }.joinToString(", ") { tr(it.labelRes) }
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(18.dp)) },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) }
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (c in MapCategory.entries) if (c in cats) {
                            Icon(categoryIcon(c), null, tint = Color(c.style.color), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (catsAtOpen != null) {
                    CategoryFilterDialog(
                        selected = cats,
                        onChange = { appState.updateSearchCategories(it) },
                        onDismiss = {
                            catsAtOpen = null
                            // hledá se až tlačítkem "Najít spojení"
                        },
                        title = stringResource(R.string.search_filter_title),
                        allowEmpty = false,   // hledat „ničím“ nedává smysl
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = appState.departAt == null,
                        onClick = {
                            if (appState.departAt != null) {
                                appState.departAt = null
                                appState.arriveBy = false
                                // hledá se až tlačítkem "Najít spojení"
                            }
                        },
                        label = { Text(stringResource(R.string.search_now)) },
                        leadingIcon = { Icon(Icons.Default.Schedule, null, Modifier.size(18.dp)) }
                    )
                    FilterChip(
                        selected = appState.departAt != null,
                        onClick = { showDatePicker = true },
                        label = { Text(appState.departAt?.let { whenLabel(it, appState.arriveBy) } ?: stringResource(R.string.search_pick_datetime)) },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp)) }
                    )
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
                            Text(if (loading) stringResource(R.string.search_searching) else stringResource(R.string.search_find))
                        }
                    }
                }
            }
        }

        appState.searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    val resultsBody: @Composable (Modifier) -> Unit = { mod ->
        if (!appState.searched) {
            SearchEmptyState(mod)
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
                            JourneyCard(journey, now, appState.walkRoute, onOpen = { appState.detailJourney = journey }, onOpenLeg = { appState.legSheet = journey to it }) { onShowMapForJourney(journey) }
                        }
                    }

                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            when {
                                appState.loadMoreFailed -> TextButton(onClick = { appState.loadMoreFailed = false }) {
                                    Text(stringResource(R.string.search_load_more_retry))
                                }
                                appState.loadingMore -> Wavy(32.dp)
                                // omezené druhy dopravy (třeba tramvaj a metro v Ústí): spoj neexistuje, ne „už dnes nejede“
                                appState.journeys.isEmpty() && !appState.hasMore && !appState.searchLoading && !appState.modeFilter.isAll ->
                                    Text(
                                        stringResource(
                                            R.string.search_none_for_modes,
                                            MapCategory.entries.filter { it in appState.searchCategories }.joinToString(", ") { tr(it.labelRes) }
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                appState.searchedArriveBy && appState.journeys.isEmpty() && !appState.searchLoading ->
                                    Text(stringResource(R.string.search_none_arrive_by), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                appState.searchedArriveBy -> Unit   // "to je vše na dnes" u příjezdu nedává smysl
                                appState.journeys.isEmpty() && !appState.hasMore && !appState.searchLoading ->
                                    Text(stringResource(R.string.search_none_until_end_of_day), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                !appState.hasMore ->
                                    Text(stringResource(R.string.search_all_for_today), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    // ───── hlavní obrazovka: formulář + historie hledání ─────
    val history = remember(appState.historyRev) { appState.history.recent(15) }
    val favorites = remember(appState.historyRev) { appState.history.pinned() }

    fun searchFromHistory(fromName: String, toName: String) {
        val a = appState.stops.firstOrNull { it.name == fromName }
        val b = appState.stops.firstOrNull { it.name == toName }
        if (a == null || b == null) {
            toast(tr(R.string.search_history_stop_missing))
            return
        }
        appState.from = a
        appState.fromQuery = stopLabel(a.name)
        appState.to = b
        appState.toQuery = stopLabel(b.name)
        runSearch()
    }

    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }

    // Kolikrát byla dvojice odebrána: je součástí klíče řádku. Stav swipu se ukládá podle klíče (rememberSaveable),
    // takže vrácená položka ("Zpět" nebo nové hledání) by se stejným klíčem převzala stav "smazáno" a hned zmizela.
    val removedGen = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }

    /** Odebrání swipem: hned zmizí, snackbar nabídne "Zpět" (omylem přejetý řádek jde vrátit). */
    fun removeHistory(fromName: String, toName: String) {
        val removed = appState.history.remove(fromName, toName) ?: return
        val pair = "$fromName|$toName"
        removedGen[pair] = (removedGen[pair] ?: 0) + 1
        appState.historyRev++
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()   // předchozí "Zpět" už neplatí
            val r = snackbar.showSnackbar(
                message = tr(R.string.search_history_removed, stopLabel(fromName), stopLabel(toName)),
                actionLabel = tr(R.string.common_undo),
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                appState.history.restore(removed)
                appState.historyRev++
            }
        }
    }

    fun setFavorite(fromName: String, toName: String, pinned: Boolean) {
        appState.history.setPinned(fromName, toName, pinned)
        appState.historyRev++
    }

    // odjezdy z nejbližší zastávky (podle polohy), obnova každou minutu
    val nearby by produceState<NearbyDepartures?>(null, appState.userLoc, appState.indexedStops.isNotEmpty()) {
        while (true) {
            val u = appState.userLoc
            if (u != null) runCatching {
                // stejná zastávka jako předvyplněné "Odkud": nejbližší po cestě (sdílený výpočet), ne vzdušnou čarou
                val near = runCatching { nearestFor(u.first, u.second).await() }.getOrNull()
                api.nearbyDepartures(u.first, u.second, near?.refined ?: near?.cands?.firstOrNull())
            }
                .onFailure { android.util.Log.w("Nearby", "odjezdy z blízké zastávky: ${it.message}") }
                .getOrNull()?.let { value = it }
            // dokud není jízdní řád načtený (po startu), zkouší se často, pak jednou za minutu
            delay(if (value == null) 3_000 else 60_000)
        }
    }

    val historyItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        nearby?.let { nb ->
            item(key = "nearby") {
                NearbyDeparturesCard(
                    nb, now,
                    onPick = { d, platform ->
                        appState.loadStopDepartureRoute(api, d, platform, scope)
                        appState.selectedTab = 1
                    },
                    onOpenStop = {
                        appState.focusPoint = com.google.android.gms.maps.model.LatLng(nb.stop.lat, nb.stop.lng)
                        appState.openStop(api, nb.stop, scope)
                        appState.selectedTab = 1
                    },
                    modifier = Modifier.animateItem()
                )
            }
        }
        if (history.isEmpty() && favorites.isEmpty()) {
            item(key = "empty") { SearchEmptyState(Modifier.padding(top = 40.dp, bottom = 24.dp)) }
        }
        fun section(key: String, title: Int, list: List<Pair<String, String>>, pinned: Boolean) {
            if (list.isEmpty()) return
            item(key = key) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp).animateItem()
                )
            }
            items(list, key = { "$key:${it.first}|${it.second}:${removedGen["${it.first}|${it.second}"] ?: 0}" }) { (f, t) ->
                HistoryRow(
                    fromName = f,
                    toName = t,
                    pinned = pinned,
                    enabled = appState.stops.isNotEmpty(),
                    onClick = { searchFromHistory(f, t) },
                    onReverse = { searchFromHistory(t, f) },
                    onTogglePin = { setFavorite(f, t, !pinned) },
                    onRemove = { removeHistory(f, t) },
                    modifier = Modifier.animateItem()
                )
            }
        }
        section("fav", R.string.search_favorites_title, favorites, pinned = true)
        section("h", R.string.search_history_title, history, pinned = false)
    }

    Box(modifier.fillMaxSize()) {
        if (isLandscape) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) { searchCard() }
                LazyColumn(
                    modifier = Modifier.weight(0.55f).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = historyItems
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "form") {
                    Column(
                        Modifier.padding(bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) { searchCard() }
                }
                historyItems()
            }
        }

        androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))

        // ───── výsledky: samostatná obrazovka přes hlavní (zpět = formulář s historií) ─────
        PredictiveBackLayer(
            visible = appState.showResults,
            onBack = { appState.showResults = false },
            enabled = appState.selectedTab == 0
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = {
                            Column {
                                val f = appState.searchedFrom?.name?.let { stopLabel(it) }.orEmpty()
                                val to = appState.searchedTo?.name?.let { stopLabel(it) }.orEmpty()
                                RouteTitle(f, to, MaterialTheme.typography.titleMedium)
                                // čas hledání a počet nalezených (dřív zvláštní řádek "Výsledky" pod lištou)
                                val whenLabel = appState.departAt?.let { whenLabel(it, appState.arriveBy) } ?: stringResource(R.string.search_now)
                                val found = if (appState.searched && !appState.searchLoading)
                                    " · " + stringResource(R.string.search_found, appState.journeys.size) else ""
                                Text(
                                    whenLabel + found,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            if (appState.searched && !appState.searchLoading && appState.hasEarlier) {
                                TextButton(onClick = { loadEarlier() }, enabled = !appState.loadingEarlier) {
                                    if (appState.loadingEarlier) Wavy(20.dp)
                                    else Icon(Icons.Default.ArrowUpward, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.search_earlier))
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { appState.showResults = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        windowInsets = WindowInsets(0)
                    )
                    appState.searchError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    resultsBody(Modifier.weight(1f))
                }
            }
        }

        // ───── detail spojení: podobrazovka přes výsledky ─────
        var shownDetail by remember { mutableStateOf(appState.detailJourney) }
        appState.detailJourney?.let { shownDetail = it }
        PredictiveBackLayer(
            visible = appState.detailJourney != null,
            onBack = { appState.detailJourney = null },
            enabled = appState.selectedTab == 0
        ) {
            shownDetail?.let { dj ->
                JourneyDetailScreen(
                    j = dj,
                    api = api,
                    now = now,
                    onBack = { appState.detailJourney = null },
                    onShowMap = { onShowMapForJourney(dj) },
                    onOpenLeg = { appState.legSheet = dj to it }
                )
            }
        }
    }
}

/** Karta s odjezdy z nejbližší zastávky: ťuknutí na odjezd = trasa spoje na mapě, "Na mapě" = panel zastávky. */
@Composable
private fun NearbyDeparturesCard(
    nb: NearbyDepartures,
    now: LocalDateTime,
    onPick: (StopDeparture, MapStop) -> Unit,
    onOpenStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Place, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.search_nearby_title, fmtDist(nb.distanceM)),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        stopLabel(nb.stop.name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onOpenStop) { Text(stringResource(R.string.search_nearby_on_map)) }
            }
            if (nb.departures.isEmpty()) {
                Text(
                    stringResource(R.string.search_nearby_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, bottom = 4.dp)
                )
            } else {
                for ((d, platform) in nb.departures) {
                    DepartureRow(d, now) { onPick(d, platform) }
                }
            }
        }
    }
}

/** Prázdná hlavní obrazovka (zatím žádná historie): poskakující autobus a nápověda. */
@Composable
private fun SearchEmptyState(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "busFloating")
    val busFloatY by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "busFloatY"
    )
    val busScale by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "busScale"
    )
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
            Text(stringResource(R.string.search_empty_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.search_empty_text),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Jakou část šířky řádku je potřeba přetáhnout, aby se odebral z historie. */
private const val DISMISS_FRACTION = 0.5f

/**
 * Jedno spojení z historie / oblíbených: ťuknutí = hledat znovu, šipky = opačný směr, hvězdička = oblíbené,
 * přejetí do strany = odebrat.
 */
@Composable
private fun HistoryRow(
    fromName: String,
    toName: String,
    pinned: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onReverse: () -> Unit,
    onTogglePin: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // odebrání až po přetažení přes polovinu řádku, ať se nesmaže omylem. Samotný práh nestačí: rychlé
    // švihnutí (i krátké) by řádek dorazilo podle rychlosti, proto se smazání potvrdí jen podle skutečné vzdálenosti.
    var rowWidth by remember { mutableIntStateOf(0) }
    lateinit var dismiss: androidx.compose.material3.SwipeToDismissBoxState
    @Suppress("DEPRECATION")
    dismiss = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            v == androidx.compose.material3.SwipeToDismissBoxValue.Settled ||
                    (rowWidth > 0 && kotlin.math.abs(dismiss.requireOffset()) >= rowWidth * DISMISS_FRACTION)
        },
        positionalThreshold = { total -> total * DISMISS_FRACTION }
    )
    val removeLabel = stringResource(R.string.search_history_remove)
    val pinLabel = stringResource(if (pinned) R.string.search_favorite_remove else R.string.search_favorite_add)
    androidx.compose.material3.SwipeToDismissBox(
        state = dismiss,
        modifier = modifier.onSizeChanged { rowWidth = it.width },
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp)
                    .background(colors.errorContainer, RoundedCornerShape(22.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = if (dismiss.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd)
                    Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Icon(Icons.Default.Delete, null, tint = colors.onErrorContainer)
            }
        },
        onDismiss = { onRemove() }
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(22.dp),
            color = colors.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                // TalkBack: celý řádek jako jedno spojení + akce místo gesta přejetí
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(removeLabel) { onRemove(); true },
                        CustomAccessibilityAction(pinLabel) { onTogglePin(); true },
                    )
                }
        ) {
            Row(
                Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(50), color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                    Icon(if (pinned) Icons.Default.Star else Icons.Default.History, null, Modifier.padding(8.dp).size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stopLabel(fromName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                        Text(
                            stopLabel(toName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onReverse, enabled = enabled) {
                    Icon(Icons.Default.SwapVert, stringResource(R.string.search_history_reverse), tint = colors.onSurfaceVariant)
                }
                IconButton(onClick = onTogglePin) {
                    Icon(
                        if (pinned) Icons.Default.Star else Icons.Default.StarBorder,
                        pinLabel,
                        tint = colors.onSurfaceVariant
                    )
                }
            }
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
                imageVector = if (label == stringResource(R.string.search_from)) Icons.Default.TripOrigin else Icons.Default.Place,
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
                            stringResource(R.string.search_pick_stop),
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
                    Icon(Icons.Default.Clear, stringResource(R.string.common_clear))
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
    // dotaz, ke kterému patří zobrazené [options] (hlavička, „moje poloha“ se mění až s výsledky, ne s psaním)
    var shownQuery by remember { mutableStateOf("") }
    val listAlpha = remember { Animatable(0f) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(query, indexedStops, originLat, originLng) {
        delay(60.milliseconds)
        val found = withContext(Dispatchers.Default) {
            getStopMatches(query, indexedStops, originLat, originLng)
        }
        // rychlé prolnutí: starý seznam krátce zeslábne, nový se plynule objeví (žádné skokové přeskládání)
        if (found != options || query.isBlank() != shownQuery.isBlank()) {
            if (listAlpha.value > 0.35f) listAlpha.animateTo(0.35f, tween(55, easing = LinearEasing))
            options = found
            shownQuery = query
        }
        listAlpha.animateTo(1f, tween(150, easing = LinearOutSlowInEasing))
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
                placeholder = { Text(stringResource(R.string.search_stop_placeholder)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, stringResource(R.string.common_clear))
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
                    .nestedScroll(scrollHidesKeyboard)
                    .graphicsLayer { alpha = listAlpha.value },
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (shownQuery.isBlank()) {
                    item(key = "myloc") {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            text = {
                                Column {
                                    Text(stringResource(R.string.my_location), fontWeight = FontWeight.Medium)
                                    Text(
                                        stringResource(R.string.search_find_nearest),
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
                                stringResource(R.string.search_frequent_stops),
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
                        trailingIcon = if (shownQuery.isBlank()) {
                            {
                                IconButton(
                                    onClick = { onRemoveUsed(stop) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.search_remove_frequent),
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

                if (options.isEmpty() && shownQuery.isNotBlank()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.search_nothing_found),
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
    onOpen: () -> Unit,
    onOpenLeg: (Int) -> Unit,
    onMap: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    ElevatedCard(
        onClick = onOpen,
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
                            contentDescription = stringResource(R.string.search_arrival),
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            j.arrival.take(5),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // FlowRow: když se nevejde (info + Mapa vpravo), chip zpoždění přejde celý na další řádek
                    FlowRow(
                        itemVerticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            departsIn(j, now),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.primary,
                            fontWeight = FontWeight.Medium
                        )

                        depDayLabel(j, now)?.let { label ->
                            Text(
                                "· $label",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Text(
                            "· ",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )


                        Text(
                            stringResource(R.string.search_ride_minutes, j.durationMinutes),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )

                        // zpoždění:
                        if (j.realtime && j.delay > 0) {
                            val (bg, fg, label) = when {
                                j.delay > 3 -> Triple(
                                    colors.errorContainer,
                                    colors.onErrorContainer,
                                    tr(R.string.search_delay_min, j.delay)
                                )
                                else -> Triple(
                                    colors.tertiaryContainer,
                                    colors.onTertiaryContainer,
                                    tr(R.string.search_delay_min, j.delay)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(50),
                                color = bg,
                                contentColor = fg
                            ) {
                                Text(
                                    label,
                                    maxLines = 1,
                                    softWrap = false,
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


                // podrobnosti spojení (zastávky, směr, dopravce): malé info vedle mapy
                IconButton(onClick = onOpen) {
                    Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.jd_open))
                }
                Spacer(Modifier.width(2.dp))
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
                    Text(stringResource(R.string.nav_map))
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
                // ťuknutí na číslo linky = detail té jízdy (index úseku v rawLegs)
                val rideIdx = remember(j.key) { j.rawLegs.indices.filter { j.rawLegs[it].kind == "ride" } }
                val lineA11y = stringResource(R.string.a11y_line_details_plain, j.line)
                Surface(
                    onClick = { rideIdx.getOrNull(0)?.let(onOpenLeg) },
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = lineA11y },
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

                j.lines.drop(1).forEachIndexed { i, extraLine ->
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.search_transfer),
                        modifier = Modifier.size(20.dp),
                        tint = colors.onSurfaceVariant
                    )

                    val extraA11y = stringResource(R.string.a11y_line_details_plain, extraLine)
                    Surface(
                        onClick = { rideIdx.getOrNull(i + 1)?.let(onOpenLeg) },
                        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = extraA11y },
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
                    tr(R.string.search_walk_to_board, j.boardWalkMin, splitStopName(j.boardStopName.orEmpty()).first)
                walk != null -> tr(R.string.search_walk_to_stop, walk.minutes, fmtDist(walk.distanceM))
                else -> null
            }
            if (walkText != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(16.dp), tint = colors.tertiary)
                    Text(walkText, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, color = colors.tertiary)
                }
            }

            val displayStops = remember(j.routeStops) {
                fun norm(name: String): String {
                    val label = splitStopName(prettyName(name)).first.ifBlank { name }
                    return foldText(label).replace(HL_N_REGEX, "hlavni nadrazi").replace(SPACES_REGEX, "")
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
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        stringResource(R.string.search_route),
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
                            val edge = index == 0 || index == displayStops.lastIndex
                            Text(
                                splitStopName(stop).first,
                                style = if (edge) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                                fontWeight = if (edge) FontWeight.Bold else FontWeight.Normal,
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
            }

            // 4. Přestupy (směr, dopravce a počet zastávek jsou v detailu; "přímý spoj" je zřejmý z jedné linky)
            if (j.isTransfer && j.transferList.isNotEmpty()) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    j.transferList.forEachIndexed { idx, t ->
                        val prefix = if (j.transferList.size > 1) tr(R.string.search_transfer_n, idx + 1) else tr(R.string.search_transfer)
                        val at = t.at.takeIf { it.isNotBlank() }
                        if (at != null && at.contains("→")) {
                            val parts = at.split("→", limit = 2)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("$prefix: ${parts[0].trim()}", style = MaterialTheme.typography.bodyMedium)
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = stringResource(R.string.search_to_arrow),
                                    modifier = Modifier.size(14.dp),
                                    tint = colors.onSurface
                                )
                                Text(parts[1].trim(), style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Text(
                                "$prefix: ${at ?: tr(R.string.search_between_trips)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            tr(R.string.search_wait, t.waitMin) +
                                    (t.walkMin?.let { tr(R.string.search_wait_walk, it) } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                        // zpoždění přijíždějícího spoje ukrajuje z čekání
                        transferStatus(j.rawLegs, idx, j.legDelays, secondsSinceMidnight(j.day, now))
                            ?.takeIf { it.risk != TransferRisk.NONE }
                            ?.let { TransferRiskChip(it, Modifier.padding(top = 2.dp)) }
                    }
                }
            }
        }
    }
}

private val HL_N_REGEX = Regex("\\bhl\\.?n\\.?\\b")
private val SPACES_REGEX = Regex("\\s+")


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