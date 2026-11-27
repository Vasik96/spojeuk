package com.vasik96.spojeuk

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Smyčka vozidel pro overlay ([VehicleCanvasOverlay]) nad libovolnou mapou: pohyb (hub), režim tečky / štítky,
 * zastávky a příprava snímku pro [GoogleVehicleLayer]. Mapové jádro o vozidlech neví: overlay si polohu na
 * obrazovce počítá z kamery sám, takže pohyb mapy ani nová dávka poloh mapu nic nestojí (u MapLibre dřív každá
 * dávka = nový GeoJSON, jeho rozřezání na dlaždice a rozmístění všech štítků, až 30× za sekundu).
 *
 * [camera] a [visibleBounds] se volají z hlavního vlákna jen v této smyčce (~30× za sekundu, výřez jen při pohybu).
 * [stopIndex] = null: zastávky kreslí mapa sama (MapLibre), ne overlay.
 */
@Composable
internal fun VehicleOverlayLoop(
    layer: GoogleVehicleLayer,
    hub: MotionHub,
    active: Boolean,
    appState: SpojeAppState,
    activeVehicles: List<Pair<String, Vehicle>>,
    targetUid: String?,
    phase: Int,
    stopIndex: StopIndex?,
    stopMinZoom: Float,
    camera: () -> OverlayCamera?,
    visibleBounds: () -> ViewBox?,
) {
    val context = LocalContext.current
    val latestVehicles by rememberUpdatedState(activeVehicles)
    val latestTarget by rememberUpdatedState(targetUid)
    val latestPhase by rememberUpdatedState(phase)
    val latestStops by rememberUpdatedState(stopIndex)
    val latestCamera by rememberUpdatedState(camera)
    val latestBounds by rememberUpdatedState(visibleBounds)

    LaunchedEffect(layer, hub, active) {
        if (!active) return@LaunchedEffect
        layer.restartAppear()     // po otevření mapy se vozidla plynule objeví
        runVehicleLoop(context, layer, hub, appState, stopMinZoom,
            { latestVehicles }, { latestTarget }, { latestPhase }, { latestStops }, { latestCamera() }, { latestBounds() })
    }
}

private suspend fun runVehicleLoop(
    context: Context,
    layer: GoogleVehicleLayer,
    hub: MotionHub,
    appState: SpojeAppState,
    stopMinZoom: Float,
    vehiclesOf: () -> List<Pair<String, Vehicle>>,
    targetOf: () -> String?,
    phaseOf: () -> Int,
    stopsOf: () -> StopIndex?,
    cameraOf: () -> OverlayCamera?,
    boundsOf: () -> ViewBox?,
) {
    var lastLat = Double.NaN
    var lastLng = Double.NaN
    var lastZoom = Float.NaN
    var lastBearing = Float.NaN
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
        val cam = cameraOf() ?: continue
        // oddálené tečky se hýbou o zlomky pixelu, stačí řidčeji
        val interval = if (layer.isDotMode && cam.zoom < DOT_SMOOTH_ZOOM) GOOGLE_DOT_INTERVAL_MS else GOOGLE_VEHICLE_INTERVAL_MS
        // čeká se na štítky kreslené na pozadí => hned další snímek, jinak běžný interval
        if (!pending && ft - lastTickFt < interval * 1_000_000L - FRAME_SLACK_NS) continue
        lastTickFt = ft
        val now = frameElapsedMs(ft)
        if (now - appState.vehicleSelectedAtMs < SHEET_OPEN_GRACE_MS) continue

        val camChanged = cam.lat != lastLat || cam.lng != lastLng || cam.zoom != lastZoom || cam.bearing != lastBearing || view == null
        if (camChanged) {
            lastLat = cam.lat; lastLng = cam.lng; lastZoom = cam.zoom; lastBearing = cam.bearing
            boundsOf()?.let { base ->
                baseView = base
                view = base.expanded(0.15)
            }
        }

        // zastávky v záběru (jen přiblížená mapa); pod hranicí zoomu plynule zmizí (seznam zůstane, ať je co prolínat)
        val sIdx = stopsOf()
        if (camChanged || sIdx !== lastStopIdx) {
            lastStopIdx = sIdx
            val bv = baseView
            val show = sIdx != null && bv != null && cam.zoom >= stopMinZoom
            if (show) layer.setStops(sIdx.inView(bv.expanded(0.1)))
            layer.setStopsVisible(show, now)
        }

        val vehicles = vehiclesOf()
        val tgt = targetOf()
        val ph = phaseOf()
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
