@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, com.google.maps.android.compose.MapsComposeExperimentalApi::class)

package com.vasik96.spojeuk

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Šipky na trase, začátek / konec a zastávky jako nativní markery; vozidla jako snímek pro [GoogleDotOverlay]
 * (viz [GoogleVehicleLayer]). Nic tady nečte pozici kamery během kompozice => pohyb / zoom mapy nevyvolá rekompozici.
 * Vozidla obsluhuje jedna smyčka: pohyb (hub), režim tečky / štítky a příprava snímku.
 */
@Composable
internal fun GoogleVehicleOverlay(
    layer: GoogleVehicleLayer,
    hub: MotionHub,
    cameraPositionState: CameraPositionState,
    activeVehicles: List<Pair<String, Vehicle>>,
    targetUid: String?,
    phase: Int,
    appState: SpojeAppState,
    showFullRoute: Boolean,
    active: Boolean,
    shown: ShownRoute,
    stopIndex: StopIndex?,
    /** Barva trasy (druh dopravy): v ní jsou i tečky zastávek na trase. */
    routeColor: Int,
) {
    val arrowArgb = MaterialTheme.colorScheme.onBackground.toArgb()
    val context = LocalContext.current

    // rozestup šipek se mění jen na hranicích zoomu => rekompozice jen tehdy, ne při každém snímku zoomu
    val arrowSpacing by remember(cameraPositionState) {
        derivedStateOf { arrowSpacingForZoom(cameraPositionState.position.zoom) }
    }

    // trasa, která je vidět (i zrušená, dokud plynule nezmizí), a průhlednost jejích markerů
    val route = shown.route
    val full = shown.full
    val useFull = showFullRoute && full.size >= 2
    val stopDots = shown.stopDots
    LaunchedEffect(layer, shown) {
        snapshotFlow { shown.alpha.value }.collect { layer.setRouteAlpha(it) }
    }

    // šipky i s trasou, pro kterou jsou spočítané: nová trasa nesmí ani na chvíli dostat šipky předchozí
    val arrowsOf by produceState(emptyList<LatLng>() to emptyList<RouteArrow>(), route, arrowSpacing) {
        value = route to (if (route.size < 2) emptyList()
        else withContext(Dispatchers.Default) { createRouteArrows(route = route, spacingMeters = arrowSpacing) })
    }
    val arrows = arrowsOf.second.takeIf { arrowsOf.first === route } ?: emptyList()

    LaunchedEffect(route, full, useFull, stopDots, arrows, arrowArgb, routeColor) {
        layer.updateRoute(route, full, useFull, stopDots, arrows, arrowArgb, routeColor)
    }

    val latestVehicles by rememberUpdatedState(activeVehicles)
    val latestTarget by rememberUpdatedState(targetUid)
    val latestPhase by rememberUpdatedState(phase)
    val latestStops by rememberUpdatedState(stopIndex)

    LaunchedEffect(layer, hub, active) {
        if (!active) return@LaunchedEffect
        layer.restartAppear()     // po otevření mapy se vozidla plynule objeví
        var lastCam: CameraPosition? = null
        var view: ViewBox? = null
        var baseView: ViewBox? = null
        var lastVehicles: List<Pair<String, Vehicle>>? = null
        var lastTarget: String? = null
        var lastPhase = -1
        var pending = false
        var lastTickFt = 0L
        var lastStopIdx: StopIndex? = null

        while (true) {
            // Polohy se počítají k času snímku (ne k náhodnému probuzení po delay), v pravidelném rytmu celých
            // snímků; overlay mezi nimi dopočítává polohu z rychlosti v každém snímku => plynulý pohyb.
            val ft = withFrameNanos { it }
            // čeká se na štítky kreslené na pozadí => hned další snímek, jinak běžný interval
            val interval = if (layer.isDotMode && cameraPositionState.position.zoom < DOT_SMOOTH_ZOOM) GOOGLE_DOT_INTERVAL_MS
            else GOOGLE_VEHICLE_INTERVAL_MS   // oddálené tečky se hýbou o zlomky pixelu, stačí řidčeji
            if (!pending && ft - lastTickFt < interval * 1_000_000L - FRAME_SLACK_NS) continue
            lastTickFt = ft
            val now = frameElapsedMs(ft)
            if (now - appState.vehicleSelectedAtMs < SHEET_OPEN_GRACE_MS) continue

            val cam = cameraPositionState.position
            val camChanged = cam != lastCam || view == null
            if (camChanged) {
                lastCam = cam
                val b = runCatching { cameraPositionState.projection?.visibleRegion?.latLngBounds }.getOrNull()
                if (b != null) {
                    val base = ViewBox(b.southwest.latitude, b.southwest.longitude, b.northeast.latitude, b.northeast.longitude)
                    baseView = base
                    view = base.expanded(0.15)
                }
            }

            // zastávky v záběru (jen přiblížená mapa); pod hranicí zoomu plynule zmizí (seznam zůstane, ať je co prolínat)
            val sIdx = latestStops
            if (camChanged || sIdx !== lastStopIdx) {
                lastStopIdx = sIdx
                val bv = baseView
                val show = sIdx != null && bv != null && cam.zoom >= STOP_MIN_ZOOM_GOOGLE
                // velký okraj: při rychlém posunu (33 ms mezi přepočty) musí být zastávky za okrajem obrazovky už připravené
            if (show) layer.setStops(sIdx.inView(bv.expanded(STOP_VIEW_MARGIN), limit = STOP_VIEW_LIMIT))
                layer.setStopsVisible(show, now)
            }

            val vehicles = latestVehicles
            val tgt = latestTarget
            val ph = latestPhase
            val metaChanged = vehicles !== lastVehicles || tgt != lastTarget || ph != lastPhase
            lastVehicles = vehicles; lastTarget = tgt; lastPhase = ph

            val wasDot = layer.isDotMode
            if (camChanged || metaChanged) {
                val bv = baseView
                var visible = 0
                if (bv == null) visible = vehicles.size
                else for ((_, v) in vehicles) if (bv.contains(v.lat, v.lng)) visible++
                layer.updateMode(cam.zoom, visible)
            }
            val modeChanged = wasDot != layer.isDotMode

            // vozidla kreslí overlay => můžou se hýbat i během pohybu kamery (žádné volání mapy)
            hub.ensureRoutes(view, enabled = !layer.isDotMode)
            val moved = hub.tick(now, view)
            // samotný pohyb kamery snímek nemění (overlay si kameru čte sám), jen u štítků přibývají nové v záběru
            // (stojící vozidla: jednou ještě poslat nulové rychlosti, jinak by je overlay dál dopočítával)
            if (!moved && !metaChanged && !modeChanged && !pending && !(camChanged && !layer.isDotMode) &&
                !layer.moving.value
            ) continue
            layer.stampNanos = ft

            // štítky se chystají už kousek před přepnutím z teček, ať se při přepnutí jen vymění
            val prewarm = layer.isDotMode && cam.zoom >= DOT_ZOOM_GOOGLE - LABEL_PREWARM_ZOOM
            val smooth = !(layer.isDotMode && cam.zoom < DOT_SMOOTH_ZOOM)
            pending = layer.updateVehicles(vehicles, hub, tgt, ph, view, prewarm, smooth)
            val missing = layer.takeMissingLabels()
            if (missing.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    for (v in missing) prerenderMarker(context, v.line, vehicleStyle(v), 0, lateMinutes(v), v.christmas)
                }
            }
        }
    }
}
