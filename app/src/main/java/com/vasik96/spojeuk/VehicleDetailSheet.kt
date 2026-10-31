@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

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
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    var tripInfo by remember(vehicle.uid) { mutableStateOf<VehicleTripInfo?>(null) }

    LaunchedEffect(vehicle.uid) {
        tripInfo = runCatching { api.vehicleTripInfo(vehicle) }.getOrNull()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Informace o spoji:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (vehicle.train) Color(0xFF0277BD) else Color(0xFF2E7D32),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(if (vehicle.train) Icons.Default.Train else Icons.Default.DirectionsBus, null, Modifier.size(20.dp))
                        Text(vehicle.line, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (vehicle.id != 0) {
                        "ID vozidla: ${vehicle.source}:${vehicle.id}"
                    } else {
                        "ID vozidla: není dostupné"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "Zdroj polohy: ${api.sourceName(vehicle.source)}",
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
                        val points = api.tripGeometry(vehicle)
                        val stops = api.tripStopLocations(vehicle)
                        if (points.isNotEmpty()) {
                            appState.tripRoute = points.map { LatLng(it.first, it.second) }
                            appState.tripStops = stops.map { LatLng(it.first, it.second) }
                            onDismiss()
                        } else {
                            android.widget.Toast.makeText(ctx, "Trasu se nepodařilo určit", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        appState.routeLoading = false
                    }
                },
                enabled = !appState.routeLoading && vehicle.state in 0..1,
            ) {
                if (appState.routeLoading) {
                    Wavy(20.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Zobrazit trasu spoje")
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Zpoždění:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val delayText = if (vehicle.delay != 0) "${vehicle.delay} min" else "Včas (0 min)"
                val delayBg = if (vehicle.delay > 3) Color(0xFFC62828) else Color(0xFF00838F)
                Surface(shape = RoundedCornerShape(16.dp), color = delayBg, contentColor = Color.White) {
                    Text(delayText, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }

            if (vehicle.agency.isNotBlank()) {
                Text("Dopravce: ${vehicle.agency}", style = MaterialTheme.typography.bodyMedium)
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tripInfo?.nextStop?.let {
                    Text(
                        "Příští zastávka: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                tripInfo?.finalStop?.let {
                    Text(
                        "Konečná zastávka: $it",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (vehicle.airConditioned == true || vehicle.accessible == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vehicle.airConditioned == true) {
                        FeatureChip(Icons.Default.AcUnit, "Klimatizace")
                    }
                    if (vehicle.accessible == true) {
                        FeatureChip(Icons.Default.Accessible, "Bezbariérový")
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