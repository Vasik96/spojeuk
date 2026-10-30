@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.milliseconds

private const val FIRST_PAGE = 8
private const val MORE_PAGE = 6

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

        val s = api.newSearch(a.name, b.name)
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
            // Refresh poběží minimálně 700 ms.
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
                            useMyLocation("from") { s -> appState.from = s; appState.fromQuery = myLocLabel(s.name) }
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
                            useMyLocation("to") { s -> appState.to = s; appState.toQuery = myLocLabel(s.name) }
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
                                wavelength = 8.dp,
                                amplitude = 1.5f
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
        val q = if (tfv.text.startsWith("Moje poloha (")) "" else tfv.text
        options = withContext(Dispatchers.Default) {
            getStopMatches(q, indexedStops, originLat, originLng)
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
            val isMyLoc = tfv.text.startsWith("Moje poloha (")
            if (tfv.text.isBlank() || isMyLoc) {
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
                        val picked = stopLabel(stop.name)
                        tfv = TextFieldValue(picked, TextRange(picked.length))
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

// ───────────────────────── karta spojení ─────────────────────────

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