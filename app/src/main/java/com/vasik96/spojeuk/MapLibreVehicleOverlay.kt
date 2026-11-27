package com.vasik96.spojeuk

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext

/**
 * Kamera mapy pro overlay vozidel: střed, zoom v měřítku Google (svět = 256 dp · 2^zoom; MapLibre má dlaždice
 * 512, tj. zoom o 1 menší) a otočení.
 */
internal class OverlayCamera(val lat: Double, val lng: Double, val zoom: Float, val bearing: Float)

/**
 * Vozidla nad MapLibre: stejný overlay (sprite batch z atlasu, [DotPainter]) jako nad Google mapou
 * ([GoogleDotOverlay]), jen kameru dodává [camera]. Volá se při kreslení: když čte stav Compose (počítadlo pohybu
 * kamery), překreslí se overlay při každé jeho změně, bez rekompozice. MapLibre jede v režimu TextureView, takže se
 * skládá ve stejném snímku okna jako tenhle overlay (vozidla drží s mapou).
 */
@Composable
internal fun VehicleCanvasOverlay(
    layer: GoogleVehicleLayer,
    modifier: Modifier = Modifier,
    camera: () -> OverlayCamera?,
) {
    val context = LocalContext.current
    val painter = remember(context) { DotPainter(context) }
    // prolínání: překreslení každý snímek jen po dobu animace
    val frame = remember { mutableIntStateOf(0) }
    // vozidla v pohybu: překreslit každý snímek, polohu dopočítá painter z rychlosti k času snímku
    LaunchedEffect(layer) {
        snapshotFlow { layer.moving.value }.collectLatest { mv ->
            while (mv) {
                layer.frameNanos = withFrameNanos { it }
                frame.intValue++
            }
        }
    }
    // prolínání zastávek: překreslení každý snímek, dokud doznívá
    LaunchedEffect(layer) {
        snapshotFlow { layer.stopFadeKick.intValue }.collectLatest {
            while (true) {
                withFrameNanos { }
                frame.intValue++
                if (!layer.stopsFading(SystemClock.elapsedRealtime())) break
            }
        }
    }
    // prolínání jednotlivých vozidel (nová / zmizelá / dokreslený štítek)
    LaunchedEffect(layer) {
        snapshotFlow { layer.itemFadeKick.intValue }.collectLatest {
            while (true) {
                withFrameNanos { }
                frame.intValue++
                if (!layer.itemsFading(SystemClock.elapsedRealtime())) break
            }
        }
    }
    LaunchedEffect(layer) {
        snapshotFlow { layer.fadeKick.intValue }.collectLatest {
            while (true) {
                withFrameNanos { }
                frame.intValue++
                if (!layer.fade.animating()) break
            }
        }
    }
    Spacer(
        modifier.drawBehind {
            layer.version.intValue                     // odběr změn vozidel
            frame.intValue                             // odběr snímků animace
            val cam = camera() ?: return@drawBehind   // odběr pohybu kamery (jen kreslení, žádná rekompozice)
            painter.draw(
                drawContext.canvas.nativeCanvas, layer,
                cam.lat, cam.lng, cam.zoom, cam.bearing,
                size.width, size.height, density
            )
        }
    )
}

