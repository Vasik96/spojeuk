@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun VehicleDetailBottomSheet(
    vehicle: Vehicle,
    api: TransitRepository,
    appState: SpojeAppState,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // diagnostika: kolik ms od kliku do první kompozice sheetu / do začátku animace
    val clickAt = appState.vehicleSelectedAtMs
    androidx.compose.runtime.SideEffect {
        android.util.Log.d("SheetLag", "composed +${android.os.SystemClock.elapsedRealtime() - clickAt} ms od kliku")
    }

    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        android.util.Log.d("SheetLag", "1. snímek po kliku +${android.os.SystemClock.elapsedRealtime() - clickAt} ms")
        androidx.compose.runtime.withFrameNanos { }
        android.util.Log.d("SheetLag", "2. snímek po kliku +${android.os.SystemClock.elapsedRealtime() - clickAt} ms")
        androidx.compose.runtime.withFrameNanos { }
        android.util.Log.d("SheetLag", "3. snímek po kliku +${android.os.SystemClock.elapsedRealtime() - clickAt} ms")
    }

    var tripInfo by remember(vehicle.uid) { mutableStateOf<VehicleTripInfo?>(null) }

    val currentVehicle by rememberUpdatedState(vehicle)

    LaunchedEffect(vehicle.uid) {
        while (true) {
            runCatching { api.vehicleTripInfo(currentVehicle) }
                .getOrNull()
                ?.let { tripInfo = it }   // při chybě necháme poslední známou hodnotu
            delay(10_000.milliseconds)
        }
    }

    InlineBottomSheet(onDismiss = onDismiss) { closeSheet ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.vd_trip_info), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // barva podle druhu jako na mapě (tramvaj fialová, metro v barvě linky A / B / C); bus zůstává zelený
                // i se zpožděním (zpoždění ukazuje řádek níž), vlak modrý
                val vs = vehicleStyle(vehicle)
                val badgeStyle = when (vs) {
                    VehicleStyle.BUS, VehicleStyle.BUS_LATE -> VehicleStyle.BUS
                    VehicleStyle.TROLLEYBUS, VehicleStyle.TROLLEYBUS_LATE -> VehicleStyle.TROLLEYBUS
                    else -> vs
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(badgeStyle.color),
                    contentColor = if (badgeStyle.darkText) Color.Black else Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(vehicleKind(vehicle).second, null, Modifier.size(20.dp))
                        Text(vehicle.line, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Bez čísla vozu (metro PID ho nehlásí): server vůz sleduje podle spoje, uid "gtrip:<GTFS spoj>"
                val tripRef = vehicle.serverUid.takeIf { it.startsWith("gtrip:") }?.removePrefix("gtrip:")
                Text(
                    when {
                        vehicle.id != 0 -> stringResource(R.string.vd_vehicle_id, "${vehicle.source}:${vehicle.id}")
                        tripRef != null -> stringResource(R.string.vd_trip_id, tripRef)
                        else -> stringResource(R.string.vd_vehicle_id_na)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (vehicle.id == 0 && vehicleStyle(vehicle).name.startsWith("METRO")) {
                    Text(
                        stringResource(R.string.vd_metro_no_set),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    stringResource(R.string.vd_source, api.sourceName(vehicle.source)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val ageSec by produceState(vehicle.ageSec(), vehicle) {
                    while (true) {
                        value = vehicle.ageSec()
                        delay(1000.milliseconds)
                    }
                }

                Text(
                    ageLabel(ageSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ageSec > 40) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        appState.routeLoading = true
                        try {
                            appState.walkRoute = null
                            val points = api.tripGeometry(vehicle)
                            val stops = api.tripStopLocations(vehicle)
                            if (points.isNotEmpty()) {
                                appState.tripRouteSegments = emptyList()
                                appState.routeStyle = vehicleStyle(vehicle)
                                appState.tripRoute = points.map { LatLng(it.first, it.second) }
                                appState.tripStops = stops.map { LatLng(it.first, it.second) }
                                appState.journeyStops = null
                                closeSheet() // plynule sjede dolů, trasa se mezitím objevuje
                            } else {
                                android.widget.Toast.makeText(ctx, tr(R.string.route_not_found), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("VehicleDetailSheet", "Chyba při načítání trasy: ${e.message}")
                            // dřív se chyba jen zalogovala a tlačítko "nic neudělalo"
                            android.widget.Toast.makeText(ctx, tr(R.string.route_not_found), android.widget.Toast.LENGTH_SHORT).show()
                        } finally {
                            appState.routeLoading = false
                        }
                    }
                },
                enabled = !appState.routeLoading && vehicle.state != 255,
            ) {
                AnimatedContent(
                    targetState = appState.routeLoading,
                    transitionSpec = {
                        (fadeIn(tween(200)) + scaleIn(initialScale = 0.9f))
                            .togetherWith(fadeOut(tween(100)))
                    },
                    label = "routeLoadingAnimation"
                ) { loading ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (loading) {
                            Wavy(20.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.vd_loading_route))
                        } else {
                            Text(stringResource(R.string.vd_show_route))
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.vd_delay), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val delayText = if (vehicle.delay != 0) stringResource(R.string.vd_delay_min, vehicle.delay) else stringResource(R.string.vd_on_time)
                val delayBg = if (vehicle.delay > 3) Color(0xFFC62828) else Color(0xFF00838F)
                AnimatedContent(
                    targetState = delayText to delayBg,
                    transitionSpec = {
                        (fadeIn(tween(200)) + scaleIn(initialScale = 0.85f))
                            .togetherWith(fadeOut(tween(100)))
                    },
                    label = "delayBadgeAnimation"
                ) { (txt, bg) ->
                    Surface(shape = RoundedCornerShape(16.dp), color = bg, contentColor = Color.White) {
                        Text(txt, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tripInfo?.nextStop?.let {
                    Text(
                        stringResource(R.string.vd_next_stop, vehicleStopName(it, vehicle.train)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                tripInfo?.finalStop?.let {
                    Text(
                        stringResource(R.string.vd_final_stop, vehicleStopName(it, vehicle.train)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (vehicle.agency.isNotBlank()) {
                Text(stringResource(R.string.vd_operator, cleanAgencyName(vehicle.agency)), style = MaterialTheme.typography.bodyMedium)
            }

            if (vehicle.airConditioned == true || vehicle.accessible == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vehicle.airConditioned == true) {
                        FeatureChip(Icons.Default.AcUnit, stringResource(R.string.vd_ac))
                    }
                    if (vehicle.accessible == true) {
                        FeatureChip(Icons.AutoMirrored.Filled.Accessible, stringResource(R.string.vd_accessible))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FeatureChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Bottom sheet přímo v kompozici (bez Dialog okna). ModalBottomSheet otevírá nové okno nad mapou (GL surface),
 * což na zařízení trvalo 0,5–1 s, než se vůbec začala hrát animace. Tohle naběhne hned.
 * Scrim, tažení dolů pro zavření, tlačítko zpět.
 */
@Composable
internal fun InlineBottomSheet(
    onDismiss: () -> Unit,
    /** Parametr obsahu: zavření s animací (sjetí dolů), na rozdíl od [onDismiss], které sheet odstraní hned. */
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }          // 0 = schováno, 1 = zobrazeno
    var dragPx by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableIntStateOf(0) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { shown.animateTo(1f, tween(220, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }

    fun dismiss() {
        if (closing) return
        closing = true
        scope.launch {
            shown.animateTo(0f, tween(200))
            onDismiss()
        }
    }

    // prediktivní zpět: sheet během gesta trochu sjede a zmenší se, dokončené gesto ho zavře
    var backP by remember { mutableFloatStateOf(0f) }
    androidx.activity.compose.PredictiveBackHandler { events ->
        try {
            events.collect { backP = it.progress }
            dismiss()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            scope.launch { animate(backP, 0f, animationSpec = tween(160)) { v, _ -> backP = v } }
            throw e
        }
    }

    Box(Modifier.fillMaxSize()) {
        // scrim
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = shown.value }
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { heightPx = it.height }
                .graphicsLayer {
                    alpha = if (heightPx == 0) 0f else 1f
                    translationY = (1f - shown.value) * heightPx + dragPx + backP * 48.dp.toPx()
                    val s = 1f - 0.06f * backP
                    scaleX = s
                    scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                }
                .pointerInput(Unit) { detectTapGestures { } }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { d -> dragPx = (dragPx + d).coerceAtLeast(0f) },
                    onDragStopped = { velocity ->
                        if (dragPx > heightPx * 0.3f || velocity > 1500f) {
                            closing = true
                            scope.launch {
                                animate(dragPx, heightPx.toFloat(), animationSpec = tween(160)) { v, _ -> dragPx = v }
                                onDismiss()
                            }
                        } else {
                            scope.launch { animate(dragPx, 0f, animationSpec = tween(160)) { v, _ -> dragPx = v } }
                        }
                    }
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
            shadowElevation = 8.dp
        ) {
            Column(Modifier.navigationBarsPadding()) {
                // drag handle
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp, bottom = 4.dp)
                        .width(32.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                )
                content(::dismiss)
            }
        }
    }
}