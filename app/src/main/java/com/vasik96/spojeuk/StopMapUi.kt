package com.vasik96.spojeuk

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Train
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Kolik hodin dopředu panel zastávky ukazuje odjezdy. */
internal const val STOP_DEPARTURE_HOURS = 3

/** Od jakého zoomu (Google) se kreslí ikony zastávek. */
internal const val STOP_MIN_ZOOM_GOOGLE = 14.5f

/** Bitmapy zastávek na mapě: kruh s ikonou autobusu / vlaku. */
internal class StopIcons(val bus: Bitmap, val rail: Bitmap)

/** Průměr ikony zastávky (dp), včetně světlého lemu. */
internal const val STOP_ICON_DP = 30f

/** Ikony zastávek v barvách Material You (podle aktuálního motivu). */
@Composable
internal fun rememberStopIcons(): StopIcons {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val busPainter = rememberVectorPainter(Icons.Rounded.DirectionsBus)
    val railPainter = rememberVectorPainter(Icons.Rounded.Train)
    val fill = cs.primary
    val glyph = cs.onPrimary
    val ring = cs.surface
    return remember(fill, glyph, ring, density) {
        val px = with(density) { STOP_ICON_DP.dp.toPx() }
        val ringW = with(density) { 2.5.dp.toPx() }
        fun icon(painter: androidx.compose.ui.graphics.painter.Painter): Bitmap {
            val img = ImageBitmap(px.toInt(), px.toInt())
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(px, px)) {
                val c = Offset(px / 2, px / 2)
                drawCircle(ring, px / 2, c)
                drawCircle(fill, px / 2 - ringW, c)
                val g = px * 0.58f
                translate(c.x - g / 2, c.y - g / 2) {
                    with(painter) { draw(Size(g, g), colorFilter = ColorFilter.tint(glyph)) }
                }
            }
            return img.asAndroidBitmap()
        }
        StopIcons(icon(busPainter), icon(railPainter))
    }
}

/** Panel s odjezdy ze zastávky vybrané na mapě. Čtení selectedStop je izolované tady (mapa se nerekomponuje). */
@Composable
internal fun SelectedStopSheet(api: TransitRepository, appState: SpojeAppState) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    appState.selectedStop?.let { st ->
        StopDeparturesSheet(
            st, api,
            onPick = { d -> appState.loadStopDepartureRoute(api, d, st, scope) },
            onDismiss = { appState.selectedStop = null },
        )
    }
}

@Composable
private fun StopDeparturesSheet(
    stop: MapStop,
    api: TransitRepository,
    onPick: (StopDeparture) -> Unit,
    onDismiss: () -> Unit,
) {
    // odjezdy se přepočítají každou minutu (odjeté zmizí)
    val deps by produceState<List<StopDeparture>?>(null, stop.key) {
        while (true) {
            value = runCatching { api.stopDepartures(stop, STOP_DEPARTURE_HOURS) }.getOrDefault(value ?: emptyList())
            delay(60.seconds)
        }
    }
    val now by produceState(nowPrague(), stop.key) {
        while (true) { delay(15.seconds); value = nowPrague() }
    }

    val pickScope = androidx.compose.runtime.rememberCoroutineScope()
    InlineBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.stop_departures),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stop.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.stop_departures_note, STOP_DEPARTURE_HOURS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            val list = deps
            when {
                list == null -> Text(stringResource(R.string.stop_departures_loading), style = MaterialTheme.typography.bodyMedium)
                list.isEmpty() -> Text(
                    stringResource(R.string.stop_departures_none, STOP_DEPARTURE_HOURS),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(list, key = { "${it.tid}:${it.depSec}" }) { d ->
                        DepartureRow(d, now) {
                            // panel se zavře až po kousku animace ripple (jinak zmizí dřív, než je vidět)
                            pickScope.launch { delay(170); onPick(d) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DepartureRow(d: StopDeparture, now: java.time.LocalDateTime, onClick: () -> Unit) {
    val at = d.day.atStartOfDay().plusSeconds(d.depSec.toLong())
    val min = Duration.between(now, at).toMinutes()
    val style = when (d.category) {
        MapCategory.TRAIN -> VehicleStyle.TRAIN
        MapCategory.TRAM -> VehicleStyle.TRAM
        MapCategory.METRO -> when (d.line.trim().uppercase()) {
            "A" -> VehicleStyle.METRO_A; "B" -> VehicleStyle.METRO_B; "C" -> VehicleStyle.METRO_C
            else -> VehicleStyle.METRO
        }
        MapCategory.BUS -> VehicleStyle.BUS
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
            )
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(style.color),
            contentColor = if (style.darkText) Color.Black else Color.White,
            modifier = Modifier.widthIn(min = 56.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
            ) {
                Icon(categoryIcon(d.category), null, Modifier.size(16.dp))
                Text(d.line, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
        Text(
            stringResource(R.string.vehicle_towards, vehicleStopName(d.headsign, d.category == MapCategory.TRAIN)),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "%02d:%02d".format(at.hour, at.minute),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End
            )
            Text(
                if (min <= 0) stringResource(R.string.stop_now) else stringResource(R.string.stop_in_min, min.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
