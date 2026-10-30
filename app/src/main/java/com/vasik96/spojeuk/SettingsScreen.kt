@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    appState: SpojeAppState,
    api: TransitRepository
) {
    AnimatedContent(
        targetState = appState.settingsSubscreen,
        modifier = modifier,
        transitionSpec = {
            if (targetState == SettingsSubscreen.DATA_UPDATES) {
                (slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(280)) { -it / 4 } + fadeOut(tween(200)))
            } else {
                (slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(280)) { it / 4 } + fadeOut(tween(200)))
            }
        },
        label = "settingsSubscreen"
    ) { screen ->
        // Surface s pozadím, aby se obě obrazovky při přechodu nepřekrývaly průhledně
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                SettingsSubscreen.DATA_UPDATES -> DataUpdatesScreen(
                    modifier = Modifier.fillMaxSize(),
                    appState = appState,
                    api = api,
                    onBack = { appState.settingsSubscreen = SettingsSubscreen.MAIN }
                )
                SettingsSubscreen.MAIN -> SettingsMain(
                    modifier = Modifier.fillMaxSize(),
                    appState = appState
                )
            }
        }
    }
}

@Composable
private fun SettingsMain(
    modifier: Modifier = Modifier,
    appState: SpojeAppState
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionTitle("Zobrazení a mapa")

        val checked = appState.hideOtherVehiclesOnMap

        OutlinedCard(
            onClick = { appState.setHideOtherVehicles(!checked) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { role = Role.Switch }
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Skrýt ostatní vozidla na mapě",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Při zobrazení konkrétního spoje na mapě skryje ostatní autobusy a vlaky, pokud byl autobus správně nalezen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    thumbContent = {
                        Icon(
                            imageVector = if (checked) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                            tint = if (checked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }
                )
            }
        }

        val predictChecked = appState.predictVehiclePositions

        OutlinedCard(
            onClick = { appState.updatePredictVehiclePositions(!predictChecked) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { role = Role.Switch }
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Route,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Předpovídat plynulý pohyb autobusu",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Odhaduje plynulý pohyb vozidla mezi aktualizacemi dat. Pokud vůz 2 a více aktualizací stojí, zůstane stát na místě.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = predictChecked,
                    onCheckedChange = null,
                    thumbContent = {
                        Icon(
                            imageVector = if (predictChecked) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                            tint = if (predictChecked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        SectionTitle("Data a aktualizace")

        OutlinedCard(
            onClick = {
                scope.launch {
                    delay(70.milliseconds)
                    appState.settingsSubscreen = SettingsSubscreen.DATA_UPDATES
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Aktualizace dat",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Stahování jízdních řádů GTFS, tvarů tras (Shapes), živých dat a vyčištění keše tras OSRM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Otevřít aktualizace dat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        SectionTitle("O aplikaci")

        OutlinedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Spoje UK",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Doprava Ústeckého kraje",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun DataUpdatesScreen(
    modifier: Modifier = Modifier,
    appState: SpojeAppState,
    api: TransitRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val timetableMeta = remember(appState.isUpdatingData) { api.getTimetableMeta() }
    val shapesMeta = remember(appState.isUpdatingData) { api.getShapesMeta() }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Zpět do nastavení"
                )
            }
            SectionTitle("Aktualizace dat")
        }

        Text(
            "Vyberte, zda chcete stáhnout ze zdrojů kompletně všechna data, nebo aktualizovat pouze jednotlivé položky.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (appState.isUpdatingData || appState.updateStatusMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                        Text(
                            "Probíhá aktualizace…",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    appState.updateStatusMessage?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (appState.updateProgress > 0f) {
                        LinearProgressIndicator(
                            progress = { appState.updateProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        appState.updateSuccess?.let { successMsg ->
            StatusCard(
                message = successMsg,
                icon = Icons.Default.CheckCircle,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }

        appState.updateError?.let { errorMsg ->
            StatusCard(
                message = errorMsg,
                icon = Icons.Default.Error,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Stáhnout ze zdrojů úplně všechno",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "Kompletní aktualizace: Jízdní řády GTFS, trasy linek Shapes, živá poloha vozidel a vyčištění keše tras.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }

                Button(
                    onClick = { appState.performUpdateAll(api, scope) },
                    enabled = !appState.isUpdatingData,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Aktualizovat kompletně vše")
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            "Jednotlivé položky ke stažení",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        SettingUpdateItemCard(
            title = "Jízdní řády a spojení (GTFS)",
            description = "Stáhnout nejnovější databázi spojů, linek a zastávek z GitHubu.",
            infoBadge = timetableMeta["tag"]?.let { "Verze $it" } ?: timetableMeta["built"]?.let { "Sestaveno $it" },
            icon = Icons.Default.DirectionsBus,
            buttonText = "Aktualizovat JŘ",
            enabled = !appState.isUpdatingData,
            onClick = { appState.performUpdateTimetable(api, scope) }
        )

        SettingUpdateItemCard(
            title = "Trasy a geometrie linek (Shapes)",
            description = "Stáhnout přesné tvary tras pro zobrazení linek na mapě.",
            infoBadge = shapesMeta?.let { "Sestaveno $it" },
            icon = Icons.Default.Route,
            buttonText = "Aktualizovat trasy",
            enabled = !appState.isUpdatingData,
            onClick = { appState.performUpdateShapes(api, scope) }
        )

        SettingUpdateItemCard(
            title = "Živá poloha vozidel (Realtime)",
            description = "Obnovit okamžité polohy autobusů, trolejbusů a vlaků.",
            infoBadge = "Aktuální polohy",
            icon = Icons.Default.LocationOn,
            buttonText = "Obnovit polohy",
            enabled = !appState.isUpdatingData,
            onClick = { appState.performUpdateRealtime(api, scope) }
        )

        SettingUpdateItemCard(
            title = "Keš pěších a silničních tras (OSRM)",
            description = "Vymazat a obnovit uložené trasy OSRM v zařízení.",
            infoBadge = null,
            icon = Icons.Default.DeleteSweep,
            buttonText = "Vyčistit keš",
            enabled = !appState.isUpdatingData,
            onClick = { appState.performClearOsrmCache(api, scope) }
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(
    message: String,
    icon: ImageVector,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = content)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SettingUpdateItemCard(
    title: String,
    description: String,
    infoBadge: String?,
    icon: ImageVector,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (infoBadge != null) {
                        Spacer(Modifier.height(4.dp))
                        SuggestionChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    infoBadge,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(buttonText)
            }
        }
    }
}