@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime

/** Karta s odjezdy z nejbližší zastávky: ťuknutí na odjezd = trasa spoje na mapě, "Na mapě" = panel zastávky. */
@Composable
internal fun NearbyDeparturesCard(
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
                FilledTonalButton(
                    onClick = onOpenStop,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    elevation = null,
                ) { Text(stringResource(R.string.search_nearby_on_map)) }
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
internal fun SearchEmptyState(modifier: Modifier = Modifier) {
    // Pár zhoupnutí po zobrazení, pak klid. Nekonečná animace (dřív rememberInfiniteTransition) nutila celé okno
    // překreslovat se každý snímek (~120×/s), dokud appka stála na záložce Spoje bez hledání: autobus často jen
    // vykukuje nad spodní lištou, a stálo to desítky procent CPU, GPU a baterii.
    val phase = remember { Animatable(0.5f) }   // 0 / 1 = krajní polohy, 0,5 = klid uprostřed
    LaunchedEffect(Unit) {
        val half = tween<Float>(BUS_SWING_MS / 2, easing = EaseInOutCubic)
        val full = tween<Float>(BUS_SWING_MS, easing = EaseInOutCubic)
        phase.animateTo(1f, half)
        repeat(BUS_SWINGS) { i -> phase.animateTo(if (i % 2 == 0) 0f else 1f, full) }
        phase.animateTo(0.5f, half)
    }
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
                        val t = phase.value          // čte se jen při kreslení vrstvy, bez rekompozice
                        translationY = -5f + 10f * t
                        scaleX = 0.97f + 0.06f * t
                        scaleY = scaleX
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

private const val BUS_SWING_MS = 1_400
private const val BUS_SWINGS = 3            // ~5,6 s pohybu po zobrazení prázdné obrazovky

/** Jakou část šířky řádku je potřeba přetáhnout, aby se odebral z historie. */
private const val DISMISS_FRACTION = 0.5f

/**
 * Jedno spojení z historie / oblíbených: ťuknutí = hledat znovu, šipky = opačný směr, hvězdička = oblíbené,
 * přejetí do strany = odebrat.
 */
@Composable
internal fun HistoryRow(
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
