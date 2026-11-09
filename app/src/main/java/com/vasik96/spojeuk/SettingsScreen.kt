@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
                (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(150))) togetherWith
                        (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(200, delayMillis = 100, easing = FastOutLinearInEasing)))
            } else {
                (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(150))) togetherWith
                        (slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200, easing = FastOutLinearInEasing)))
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
                    appState = appState,
                    api = api
                )
            }
        }
    }
}

@Composable
private fun SettingsMain(
    modifier: Modifier = Modifier,
    appState: SpojeAppState,
    api: TransitRepository
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showMapProviderDialog by remember { mutableStateOf(false) }
    var showMapThemeDialog by remember { mutableStateOf(false) }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            icon = { Icon(Icons.Default.Palette, contentDescription = null) },
            title = { Text("Výběr motivu aplikace") },
            text = {
                Column {
                    AppTheme.entries.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (appState.appTheme == theme),
                                    onClick = {
                                        appState.updateAppTheme(theme)
                                        showThemeDialog = false
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (appState.appTheme == theme),
                                onClick = null
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = theme.label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Zavřít")
                }
            }
        )
    }

    if (showMapProviderDialog) {
        AlertDialog(
            onDismissRequest = { showMapProviderDialog = false },
            icon = {
                Icon(Icons.Default.Map, contentDescription = null)
            },
            title = {
                Text("Poskytovatel mapy")
            },
            text = {
                Column {
                    MapProvider.entries.forEach { provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (appState.mapProvider == provider),
                                    onClick = {
                                        appState.updateMapProvider(provider)
                                        showMapProviderDialog = false
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (appState.mapProvider == provider),
                                onClick = null
                            )

                            Spacer(Modifier.width(12.dp))

                            Text(
                                text = provider.label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showMapProviderDialog = false }
                ) {
                    Text("Zavřít")
                }
            }
        )
    }

    if (showMapThemeDialog) {
        AlertDialog(
            onDismissRequest = { showMapThemeDialog = false },
            icon = { Icon(Icons.Default.Palette, contentDescription = null) },
            title = { Text("Motiv mapy") },
            text = {
                Column {
                    MapTheme.entries.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (appState.mapTheme == theme),
                                    onClick = {
                                        appState.updateMapTheme(theme)
                                        showMapThemeDialog = false
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (appState.mapTheme == theme), onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(theme.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMapThemeDialog = false }) { Text("Zavřít") }
            }
        )
    }


    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionTitle("Zobrazení a mapa")

        // 1. Motiv aplikace (Dialog)
        OutlinedCard(
            onClick = { showThemeDialog = true },
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
                    imageVector = Icons.Default.DarkMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Motiv aplikace",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appState.appTheme.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 2. motiv mapy
        OutlinedCard(
            onClick = { showMapThemeDialog = true },
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
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Motiv mapy",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appState.mapTheme.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 3. Poskytovatel mapy (Dialog)
        OutlinedCard(
            onClick = { showMapProviderDialog = true },
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
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Poskytovatel mapy",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        appState.mapProvider.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }



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
                            imageVector = if (predictChecked) {
                                Icons.Default.Check
                            } else {
                                Icons.Default.Close
                            },
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                            tint = if (predictChecked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        )
                    }
                )
            }
        }

        val compactBannerChecked = appState.showOnlyMapFilters

        OutlinedCard(
            onClick = { appState.updateShowOnlyMapFilters(!compactBannerChecked) },
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
                        "Kompaktní banner mapy",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Zobrazí v horním banneru mapy pouze filtry (Vše, Autobusy, Vlaky) bez informací o zdrojích a počtu vozidel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = compactBannerChecked,
                    onCheckedChange = null,
                    thumbContent = {
                        Icon(
                            imageVector = if (compactBannerChecked) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                            tint = if (compactBannerChecked) MaterialTheme.colorScheme.primary
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
                appState.settingsSubscreen = SettingsSubscreen.DATA_UPDATES
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
                        "Stahování jízdních řádů GTFS, tvarů tras (Shapes), živých dat a vyčištění cache tras OSRM.",
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

        VehicleSourcesSettingsCard(
            appState = appState,
            api = api
        )

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
                    stringResource(id = R.string.app_name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Doprava Ústeckého kraje",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Verze $APP_VERSION",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}


@Composable
private fun VehicleSourcesSettingsCard(
    appState: SpojeAppState,
    api: TransitRepository
) {
    val sources = remember(api) {
        api.vehicleSourceOptions()
    }

    val enabledIds = appState.enabledVehicleSources

    OutlinedCard(
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
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Zdroje poloh vozidel",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Vyberte, ze kterých zdrojů se mají načítat živé polohy autobusů a vlaků.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            sources.forEach { source ->
                val checked = enabledIds == null || source.id in enabledIds

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val current = enabledIds ?: sources.map { it.id }.toSet()

                            val updated = if (checked) {
                                current - source.id
                            } else {
                                current + source.id
                            }

                            appState.updateEnabledVehicleSources(updated)
                            api.setEnabledVehicleSources(updated)
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            source.name,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Switch(
                        checked = checked,
                        onCheckedChange = { newChecked ->
                            val current = enabledIds ?: sources.map { it.id }.toSet()

                            val updated = if (newChecked) {
                                current + source.id
                            } else {
                                current - source.id
                            }

                            appState.updateEnabledVehicleSources(updated)
                            api.setEnabledVehicleSources(updated)
                        },
                        thumbContent = {
                            Icon(
                                imageVector = if (checked) {
                                    Icons.Default.Check
                                } else {
                                    Icons.Default.Close
                                },
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                tint = if (checked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                }
                            )
                        }
                    )
                }
            }

            TextButton(
                onClick = {
                    appState.updateEnabledVehicleSources(null)
                    api.setEnabledVehicleSources(null)
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Obnovit výchozí výběr")
            }
        }
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
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary
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
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onPrimary
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
                            "Kompletní aktualizace: Jízdní řády GTFS, trasy linek Shapes, živá poloha vozidel a vyčištění cache tras.",
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
            title = "Cache pěších a silničních tras (OSRM)",
            description = "Vymazat a obnovit uložené trasy OSRM v zařízení.",
            infoBadge = null,
            icon = Icons.Default.DeleteSweep,
            buttonText = "Vyčistit cache",
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