@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDateTime

// ───────────────────────── karta spojení ─────────────────────────


@Composable
internal fun JourneyCard(
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
                            "·",
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

                            Spacer(Modifier.width(1.dp))

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
internal val SPACES_REGEX = Regex("\\s+")


private val AGENCY_FORM_RE = Regex(
    pattern = """(?i)\b(a\.?\s*s\.?|s\.?\s*r\.?\s*o\.?|spol\.?\s*s\s*r\.?\s*o\.?|příspěvková\s+organizace|p\.o\.?|o\.p\.s\.?|v\.o\.s\.?|z\.s\.?|gmbh|inc\.?|ltd\.?)\b""",
    option = RegexOption.IGNORE_CASE
)
private val TRAILING_PUNCT_RE = Regex("""[.,\s]+$""")

fun cleanAgencyName(name: String): String {
    return name
        .replace(AGENCY_FORM_RE, "")
        .replace(TRAILING_PUNCT_RE, "") // Odstraní tečky, čárky i mezery na konci řetězce
        .trim()
}
