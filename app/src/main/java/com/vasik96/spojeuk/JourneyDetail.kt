@file:OptIn(ExperimentalMaterial3Api::class)

package com.vasik96.spojeuk

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds

// ───────────────────────── data ─────────────────────────

/** Zastávka jízdy v detailu spojení; časy v sekundách od půlnoci dne spojení. */
internal data class DetailStop(val name: String, val arr: Int, val dep: Int)

/** Úsek spojení v detailu. [index] = pozice v [Journey.rawLegs] (podle ní se páruje živý vůz). */
internal sealed interface DetailLeg {
    val index: Int

    data class Ride(
        override val index: Int,
        val line: String,
        val headsign: String,
        val agency: String,
        val category: MapCategory,
        val style: VehicleStyle,
        val trainNumber: String?,
        val wheelchair: Boolean,
        val stops: List<DetailStop>,
    ) : DetailLeg

    data class Walk(
        override val index: Int,
        val fromName: String,
        val toName: String,
        val dep: Int,
        val arr: Int,
        val distanceM: Double?,
    ) : DetailLeg
}

private val TIME_COL = 56.dp
private val RAIL_COL = 28.dp

// ───────────────────────── obrazovka ─────────────────────────

/** Podobrazovka hledání: celé spojení jako časová osa se všemi zastávkami, linkami a živými vozy. */
@Composable
internal fun JourneyDetailScreen(
    j: Journey,
    api: TransitRepository,
    now: LocalDateTime,
    onBack: () -> Unit,
    onShowMap: () -> Unit,
    onOpenLeg: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    val legs by produceState(remember(j.key) { api.journeyDetailNow(j) }, j.key) {
        if (value == null) value = runCatching { api.journeyDetail(j) }.getOrDefault(emptyList())
    }
    // živé vozy: jen dokud má smysl (liveOn sám vrací null mimo okno jízdy)
    val lifecycleOwner = LocalLifecycleOwner.current
    val live by produceState(remember(j.key) { api.journeyLiveNow(j) }, j.key, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {   // na pozadí nestahovat
            while (true) {
                runCatching { api.journeyLive(j) }.getOrNull()?.let { value = it }
                delay(15.seconds)
            }
        }
    }

    val nowSec = secondsSinceMidnight(j.day, now)
    val rides = legs.orEmpty().filterIsInstance<DetailLeg.Ride>()

    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.jd_title), style = MaterialTheme.typography.titleLarge)
                        val first = rides.firstOrNull()?.stops?.firstOrNull()?.name
                        val last = rides.lastOrNull()?.stops?.lastOrNull()?.name
                        if (first != null && last != null) {
                            RouteTitle(stopLabel(first), stopLabel(last), MaterialTheme.typography.bodySmall, colors.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "summary") { SummaryCard(j, now, rides, live, onShowMap, onOpenLeg) }

                val l = legs
                if (l == null) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Wavy(40.dp) }
                    }
                } else {
                    var rideNo = 0
                    for (leg in l) {
                        when (leg) {
                            is DetailLeg.Walk -> item(key = "w${leg.index}") { WalkRow(leg) }
                            is DetailLeg.Ride -> {
                                val transfer = if (rideNo > 0) j.transferList.getOrNull(rideNo - 1) else null
                                val k = rideNo - 1
                                if (transfer != null) item(key = "t${leg.index}") {
                                    val status = transferStatus(j.rawLegs, k, live.mapValues { it.value.delay }, nowSec)
                                    TransferRow(transfer, status)
                                }
                                item(key = "r${leg.index}") { RideCard(leg, live[leg.index], nowSec) { onOpenLeg(leg.index) } }
                                rideNo++
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── souhrn ─────────────────────────

@Composable
private fun SummaryCard(
    j: Journey,
    now: LocalDateTime,
    rides: List<DetailLeg.Ride>,
    live: Map<Int, Vehicle>,
    onShowMap: () -> Unit,
    onOpenLeg: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(j.departure.take(5), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                Text(j.arrival.take(5), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(departsIn(j, now), style = MaterialTheme.typography.bodyMedium, color = colors.primary, fontWeight = FontWeight.Medium)
                depDayLabel(j, now)?.let { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                Text("· " + stringResource(R.string.search_ride_minutes, j.durationMinutes), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                j.stopsCount?.let { n ->
                    Text("· " + pluralStringResource(R.plurals.jd_stops, n, n), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Text(
                    "· " + if (j.transfers == 0) stringResource(R.string.jd_no_transfer)
                    else pluralStringResource(R.plurals.transfers, j.transfers, j.transfers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }

            // linky v barvách druhu dopravy
            if (rides.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    rides.forEachIndexed { i, r ->
                        if (i > 0) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                        LineBadge(r, onClick = { onOpenLeg(r.index) })
                    }
                }
            }

            // nejhorší živé zpoždění ze všech jízd
            live.values.maxByOrNull { it.delay }?.let { LiveChip(it.delay) }

            FilledTonalButton(
                onClick = onShowMap,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Map, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.jd_show_map))
            }

            TrackButton(j)
        }
    }
}

/** Sledování cesty v notifikaci (Live Update); jen u spojení, které ještě neskončilo. */
@Composable
private fun TrackButton(j: Journey) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tracked by JourneyTracker.current.collectAsState()
    val isThis = tracked?.key == j.key
    val ended = remember(j.key) { j.depDt.plusMinutes(j.durationMinutes.toLong() + 5).isBefore(nowPrague()) }
    if (ended && !isThis) return
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) JourneyTracker.start(ctx, j)
        else android.widget.Toast.makeText(ctx, tr(R.string.track_no_permission), android.widget.Toast.LENGTH_LONG).show()
    }
    androidx.compose.material3.OutlinedButton(
        onClick = {
            if (isThis) JourneyTracker.stop(ctx)
            else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) JourneyTracker.start(ctx, j)
            else permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) {
        Icon(if (isThis) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (isThis) R.string.track_stop else R.string.track_start))
    }
    if (isThis) {
        Text(
            stringResource(R.string.track_active),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun LineBadge(r: DetailLeg.Ride, big: Boolean = false, onClick: () -> Unit) {
    // TalkBack: "Bus 81, podrobnosti jízdy" místo samotného čísla
    val a11y = stringResource(R.string.a11y_line_details, stringResource(categoryKindRes(r.category)), r.line)
    Surface(
        onClick = onClick,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = a11y },
        shape = RoundedCornerShape(if (big) 12.dp else 9.dp),
        color = Color(r.style.color),
        contentColor = if (r.style.darkText) Color.Black else Color.White
    ) {
        Row(
            Modifier.padding(horizontal = if (big) 12.dp else 9.dp, vertical = if (big) 6.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(categoryIcon(r.category), null, Modifier.size(if (big) 20.dp else 16.dp))
            Text(
                r.line,
                style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun LiveChip(delayMin: Int) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when {
        delayMin > 3 -> colors.errorContainer to colors.onErrorContainer
        delayMin > 0 -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val text = when {
        delayMin > 0 -> stringResource(R.string.jd_live_delay, delayMin)
        delayMin < 0 -> stringResource(R.string.jd_live_early, -delayMin)
        else -> stringResource(R.string.jd_live_on_time)
    }
    Surface(shape = RoundedCornerShape(50), color = bg, contentColor = fg) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(Icons.Default.Sensors, null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

// ───────────────────────── jízda ─────────────────────────

@Composable
private fun RideCard(r: DetailLeg.Ride, vehicle: Vehicle?, nowSec: Int, onOpenLeg: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val lineColor = Color(if (dark) r.style.routeDark else r.style.route)
    val delaySec = (vehicle?.delay ?: 0) * 60

    // zastávky už projeté (podle jízdního řádu + živého zpoždění)
    fun passed(t: Int) = nowSec > t + delaySec

    var expanded by rememberSaveable(r.index) { mutableStateOf(true) }
    val inner = if (r.stops.size > 2) r.stops.subList(1, r.stops.size - 1) else emptyList()
    val rideMin = r.stops.let { if (it.size >= 2) (it.last().arr - it.first().dep) / 60 else 0 }

    ElevatedCard(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer)
    ) {
        Column(Modifier.padding(vertical = 14.dp).animateContentSize()) {
            // hlavička: linka, směr, dopravce, vlastnosti, živý vůz
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LineBadge(r, big = true, onClick = onOpenLeg)
                    Column(Modifier.weight(1f)) {
                        if (r.headsign.isNotBlank()) {
                            Text(
                                stringResource(R.string.search_direction, vehicleStopName(r.headsign, r.category == MapCategory.TRAIN)),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val sub = listOfNotNull(
                            r.trainNumber?.let { stringResource(R.string.jd_train_no, it) },
                            r.agency.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }

                val hasFeatures = r.wheelchair || vehicle?.airConditioned == true || vehicle?.accessible == true
                if (vehicle != null || hasFeatures) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        if (vehicle != null) {
                            LiveChip(vehicle.delay)
                            if (vehicle.id != 0) InfoChip(null, stringResource(R.string.jd_vehicle, vehicle.id.toString()))
                        }
                        if (r.wheelchair || vehicle?.accessible == true) {
                            InfoChip(Icons.AutoMirrored.Filled.Accessible, stringResource(R.string.vd_accessible))
                        }
                        if (vehicle?.airConditioned == true) InfoChip(Icons.Default.AcUnit, stringResource(R.string.vd_ac))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // časová osa zastávek
            val first = r.stops.firstOrNull()
            val last = r.stops.lastOrNull()
            if (first != null) {
                StopRow(first, first.dep, lineColor, top = false, bottom = true, terminal = true,
                    passed = passed(first.dep), train = r.category == MapCategory.TRAIN, delaySec = delaySec, live = vehicle != null)
            }

            if (inner.isNotEmpty()) {
                // přepínač mezizastávek
                RailRow(lineColor, dashed = false, modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded }) {
                    Row(
                        Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            pluralStringResource(R.plurals.jd_intermediate, inner.size, inner.size) +
                                    " · " + stringResource(R.string.fmt_min, rideMin),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary
                        )
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            stringResource(if (expanded) R.string.jd_hide_stops else R.string.jd_show_stops),
                            Modifier.size(18.dp),
                            tint = colors.primary
                        )
                    }
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        for (s in inner) {
                            StopRow(s, s.arr, lineColor, top = true, bottom = true, terminal = false,
                                passed = passed(s.dep), train = r.category == MapCategory.TRAIN, delaySec = delaySec, live = vehicle != null)
                        }
                    }
                }
            }

            if (last != null && last !== first) {
                StopRow(last, last.arr, lineColor, top = true, bottom = false, terminal = true,
                    passed = passed(last.arr), train = r.category == MapCategory.TRAIN, delaySec = delaySec, live = vehicle != null)
            }
        }
    }
}

@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Řádek časové osy: sloupec času, kolejnice (čára + bod) a obsah. */
@Composable
private fun RailRow(
    lineColor: Color,
    dashed: Boolean,
    modifier: Modifier = Modifier,
    top: Boolean = true,
    bottom: Boolean = true,
    dot: Dp = 0.dp,
    hollow: Boolean = false,
    time: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 12.dp, end = 16.dp)) {
        Box(Modifier.width(TIME_COL).padding(top = 6.dp), contentAlignment = Alignment.TopEnd) { time() }
        Box(
            Modifier
                .width(RAIL_COL)
                .fillMaxHeight()
                .drawBehind {
                    val cx = size.width / 2
                    val cy = 16.dp.toPx()   // střed bodu v úrovni prvního řádku textu
                    val w = 4.dp.toPx()
                    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx())) else null
                    val cap = if (dashed) StrokeCap.Round else StrokeCap.Butt
                    if (top) drawLine(lineColor, Offset(cx, 0f), Offset(cx, if (dot > 0.dp) cy else size.height), w, cap, effect)
                    if (bottom) drawLine(lineColor, Offset(cx, if (dot > 0.dp) cy else 0f), Offset(cx, size.height), w, cap, effect)
                    if (dot > 0.dp) {
                        val r = dot.toPx() / 2
                        if (hollow) {
                            drawCircle(surface, r, Offset(cx, cy))
                            drawCircle(lineColor, r - 1.5.dp.toPx(), Offset(cx, cy), style = Stroke(3.dp.toPx()))
                        } else drawCircle(lineColor, r, Offset(cx, cy))
                    }
                }
        )
        Box(Modifier.weight(1f).padding(start = 6.dp)) { content() }
    }
}

@Composable
private fun StopRow(
    s: DetailStop,
    t: Int,
    lineColor: Color,
    top: Boolean,
    bottom: Boolean,
    terminal: Boolean,
    passed: Boolean,
    train: Boolean,
    delaySec: Int,
    live: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val dim = if (passed) 0.5f else 1f
    val (name, town) = splitStopName(s.name)
    val shown = if (train) s.name else name.ifBlank { s.name }
    // TalkBack: jedna položka "Lázně, 16:35" (se zpožděním plánovaný i očekávaný čas), projeté zastávky označené
    val delayedNow = live && delaySec != 0 && !passed
    val a11y = if (delayedNow) stringResource(R.string.a11y_stop_delayed, shown, hhmm(t), hhmm(t + delaySec))
    else stringResource(R.string.a11y_stop, shown, hhmm(t))
    val passedLabel = stringResource(R.string.a11y_stop_passed)
    RailRow(
        lineColor, dashed = false, top = top, bottom = bottom,
        dot = if (terminal) 16.dp else 9.dp, hollow = terminal,
        modifier = Modifier
            .alpha(dim)
            .clearAndSetSemantics {
                contentDescription = a11y
                if (passed) stateDescription = passedLabel
            },
        time = {
            Column(horizontalAlignment = Alignment.End) {
                val delayed = live && delaySec != 0 && !passed
                Text(
                    hhmm(t),
                    style = if (terminal) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (terminal) FontWeight.Bold else FontWeight.Normal,
                    color = if (delayed) colors.onSurfaceVariant else colors.onSurface,
                    textDecoration = if (delayed) TextDecoration.LineThrough else null
                )
                if (delayed) {
                    Text(
                        hhmm(t + delaySec),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (delaySec > 180) colors.error else colors.tertiary
                    )
                }
            }
        }
    ) {
        Column(Modifier.padding(top = 6.dp, bottom = if (terminal) 10.dp else 6.dp)) {
            Text(
                shown,
                style = if (terminal) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (terminal) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (terminal && !train && town.isNotBlank()) {
                Text(town, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

// ───────────────────────── chůze a přestup ─────────────────────────

@Composable
private fun WalkRow(w: DetailLeg.Walk) {
    val colors = MaterialTheme.colorScheme
    val min = maxOf(1, (w.arr - w.dep + 59) / 60)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(TIME_COL), contentAlignment = Alignment.CenterEnd) {
            Text(hhmm(w.dep), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        Box(
            Modifier.width(RAIL_COL).fillMaxHeight().drawBehind {
                val cx = size.width / 2
                drawLine(
                    colors.tertiary, Offset(cx, 0f), Offset(cx, size.height), 4.dp.toPx(), StrokeCap.Round,
                    PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 8.dp.toPx()))
                )
            }
        )
        Row(
            Modifier.weight(1f).padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(shape = RoundedCornerShape(50), color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.padding(6.dp).size(18.dp))
            }
            Column {
                Text(
                    stringResource(R.string.jd_walk, min) + (w.distanceM?.let { " · " + fmtDist(it) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.tertiary
                )
                Text(
                    stringResource(R.string.jd_walk_to, stopLabel(w.toName)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TransferRow(t: TransferInfo, status: TransferStatus?) {
    val colors = MaterialTheme.colorScheme
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 12.dp + TIME_COL + RAIL_COL / 2 - 14.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(50), color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.SyncAlt, null, Modifier.size(16.dp))
                Text(
                    stringResource(R.string.jd_transfer_wait, t.waitMin),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        status?.takeIf { it.risk != TransferRisk.NONE }?.let { TransferRiskChip(it) }
    }
}

/** Upozornění na přestup ohrožený zpožděním (těsný = rezerva pod 2 min, ohrožený = podle živých dat nestihne). */
@Composable
internal fun TransferRiskChip(s: TransferStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val atRisk = s.risk == TransferRisk.AT_RISK
    Surface(
        shape = RoundedCornerShape(50),
        color = if (atRisk) colors.errorContainer else colors.tertiaryContainer,
        contentColor = if (atRisk) colors.onErrorContainer else colors.onTertiaryContainer,
        modifier = modifier
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(Icons.Default.Warning, null, Modifier.size(14.dp))
            Text(
                if (atRisk) stringResource(R.string.jd_transfer_at_risk, s.delayMin)
                else stringResource(R.string.jd_transfer_tight, s.marginMin.coerceAtLeast(0)),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

// ───────────────────────── bottom sheet jedné jízdy ─────────────────────────

/**
 * Detail jedné jízdy spojení (po ťuknutí na číslo linky): linka, směr, dopravce, živý vůz, nástup a výstup
 * a vlastní "Zobrazit na mapě", které ukáže právě tuto jízdu (u spojení s přestupem i druhý / další spoj).
 */
@Composable
internal fun JourneyLegSheet(
    j: Journey,
    legIndex: Int,
    api: TransitRepository,
    onDismiss: () -> Unit,
    onShowMap: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ride by produceState(
        remember(j.key, legIndex) { api.journeyDetailNow(j)?.filterIsInstance<DetailLeg.Ride>()?.firstOrNull { it.index == legIndex } },
        j.key, legIndex
    ) {
        if (value == null) value = runCatching { api.journeyDetail(j) }.getOrNull()
            ?.filterIsInstance<DetailLeg.Ride>()?.firstOrNull { it.index == legIndex }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val vehicle by produceState(remember(j.key, legIndex) { api.journeyLiveNow(j)[legIndex] }, j.key, legIndex, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {   // na pozadí nestahovat
            while (true) {
                runCatching { api.journeyLive(j) }.getOrNull()?.let { value = it[legIndex] }
                delay(15.seconds)
            }
        }
    }
    val rideNo = j.rawLegs.take(legIndex + 1).count { it.kind == "ride" }
    val rideCount = j.rawLegs.count { it.kind == "ride" }

    InlineBottomSheet(onDismiss = onDismiss) { close ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                if (rideCount > 1) stringResource(R.string.jd_leg_n, rideNo, rideCount) else stringResource(R.string.vd_trip_info),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant
            )
            val r = ride
            if (r == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Wavy(32.dp) }
                return@Column
            }
            val train = r.category == MapCategory.TRAIN

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(r.style.color),
                    contentColor = if (r.style.darkText) Color.Black else Color.White
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(categoryIcon(r.category), null, Modifier.size(20.dp))
                        Text(r.line, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Column(Modifier.weight(1f)) {
                    if (r.headsign.isNotBlank()) {
                        Text(
                            stringResource(R.string.search_direction, vehicleStopName(r.headsign, train)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    val sub = listOfNotNull(
                        r.trainNumber?.let { stringResource(R.string.jd_train_no, it) },
                        r.agency.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }

            val v = vehicle
            if (v != null || r.wheelchair) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    if (v != null) {
                        LiveChip(v.delay)
                        if (v.id != 0) InfoChip(null, stringResource(R.string.jd_vehicle, v.id.toString()))
                    }
                    if (r.wheelchair || v?.accessible == true) InfoChip(Icons.AutoMirrored.Filled.Accessible, stringResource(R.string.vd_accessible))
                    if (v?.airConditioned == true) InfoChip(Icons.Default.AcUnit, stringResource(R.string.vd_ac))
                }
            }

            // nástup → výstup
            val first = r.stops.firstOrNull()
            val last = r.stops.lastOrNull()
            if (first != null && last != null) {
                Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
                    Column(Modifier.padding(vertical = 10.dp)) {
                        val delaySec = (v?.delay ?: 0) * 60
                        val nowSec = secondsSinceMidnight(j.day)
                        val dark = colors.surface.luminance() < 0.5f
                        val lineColor = Color(if (dark) r.style.routeDark else r.style.route)
                        StopRow(first, first.dep, lineColor, top = false, bottom = true, terminal = true,
                            passed = nowSec > first.dep + delaySec, train = train, delaySec = delaySec, live = v != null)
                        RailRow(lineColor, dashed = false) {
                            Text(
                                pluralStringResource(R.plurals.jd_intermediate, maxOf(0, r.stops.size - 2), maxOf(0, r.stops.size - 2)) +
                                        " · " + stringResource(R.string.fmt_min, (last.arr - first.dep) / 60),
                                Modifier.padding(vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant
                            )
                        }
                        StopRow(last, last.arr, lineColor, top = true, bottom = false, terminal = true,
                            passed = nowSec > last.arr + delaySec, train = train, delaySec = delaySec, live = v != null)
                    }
                }
            }

            androidx.compose.material3.Button(
                onClick = {
                    onShowMap()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Map, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (rideCount > 1) stringResource(R.string.jd_show_leg_map, r.line)
                    else stringResource(R.string.jd_show_map)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** "Odkud → Kam" s šipkou jako ikonou (textová šipka vypadá v různých fontech špatně). */
@Composable
internal fun RouteTitle(
    from: String,
    to: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color = Color.Unspecified,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(from, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward, null,
            Modifier.size(with(androidx.compose.ui.platform.LocalDensity.current) { style.fontSize.toDp() }),
            tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color
        )
        Text(to, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Druh dopravy jednotným číslem (bus, tramvaj, …) pro popisky. */
internal fun categoryKindRes(c: MapCategory): Int = when (c) {
    MapCategory.BUS -> R.string.vehicle_kind_bus
    MapCategory.TRAM -> R.string.vehicle_kind_tram
    MapCategory.METRO -> R.string.vehicle_kind_metro
    MapCategory.TRAIN -> R.string.vehicle_kind_train
}
