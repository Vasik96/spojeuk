@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import androidx.compose.material.icons.filled.Place

import androidx.compose.foundation.layout.Box

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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    appState: SpojeAppState,
    api: TransitRepository
) {
    // hlavní nastavení zůstává pod podobrazovkou složené, aby ho gesto zpět mohlo ukázat jako náhled
    Box(modifier) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SettingsMain(modifier = Modifier.fillMaxSize(), appState = appState, api = api)
        }
        PredictiveBackLayer(
            visible = appState.settingsSubscreen == SettingsSubscreen.DATA_UPDATES,
            onBack = { appState.settingsSubscreen = SettingsSubscreen.MAIN },
            enabled = appState.selectedTab == 2
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                DataUpdatesScreen(
                    modifier = Modifier.fillMaxSize(),
                    appState = appState,
                    api = api,
                    onBack = { appState.settingsSubscreen = SettingsSubscreen.MAIN }
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
            title = { Text(stringResource(R.string.settings_app_theme_dialog_title)) },
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
                    Text(stringResource(R.string.common_close))
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
                Text(stringResource(R.string.settings_map_provider))
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
                                        if (provider == MapProvider.MAPYCZ && BuildConfig.MAPYCZ_API_KEY.isBlank()) {
                                            android.widget.Toast.makeText(
                                                appState.context,
                                                tr(R.string.settings_mapycz_key_missing),
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
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
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }

    if (showMapThemeDialog) {
        AlertDialog(
            onDismissRequest = { showMapThemeDialog = false },
            icon = { Icon(Icons.Default.Palette, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_map_theme)) },
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
                TextButton(onClick = { showMapThemeDialog = false }) { Text(stringResource(R.string.common_close)) }
            }
        )
    }


    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionTitle(stringResource(R.string.settings_section_display))

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
                        stringResource(R.string.settings_app_theme),
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

        // 1b. Jazyk aplikace: systémové nastavení jazyka pro tuto appku (Android 13+), jazyky viz res/xml/locales_config.xml
        LanguageSettingsCard()

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
                        stringResource(R.string.settings_map_theme),
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
                        stringResource(R.string.settings_map_provider),
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
                        stringResource(R.string.settings_hide_others),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_hide_others_desc),
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
                        stringResource(R.string.settings_predict),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_predict_desc),
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
                        stringResource(R.string.settings_compact_banner),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_compact_banner_desc),
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

        val mapStopsChecked = appState.showMapStops

        OutlinedCard(
            onClick = { appState.updateShowMapStops(!mapStopsChecked) },
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
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_map_stops),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_map_stops_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = mapStopsChecked,
                    onCheckedChange = null,
                    thumbContent = {
                        Icon(
                            imageVector = if (mapStopsChecked) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                            tint = if (mapStopsChecked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        SectionTitle(stringResource(R.string.settings_section_data))

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
                        stringResource(R.string.settings_data_updates),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_data_updates_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.settings_open_data_updates),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        VehicleSourcesSettingsCard(
            appState = appState,
            api = api
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        SectionTitle(stringResource(R.string.settings_section_about))

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
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.settings_version, APP_VERSION),
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
    // seznam zdrojů je ze serveru: nový zdroj se tu objeví sám s další odpovědí
    val sources by produceState(api.vehicleSourceOptions(), api) {
        api.vehicleUpdates.collect {
            val now = api.vehicleSourceOptions()
            if (now != value) value = now
        }
    }

    val disabledIds = appState.disabledVehicleSources

    fun toggle(id: String, on: Boolean) {
        val updated = if (on) disabledIds - id else disabledIds + id
        appState.updateDisabledVehicleSources(updated)
        api.setDisabledVehicleSources(updated)
    }

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
                        stringResource(R.string.settings_sources_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.settings_sources_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            sources.forEach { source ->
                val checked = source.id !in disabledIds

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { toggle(source.id, !checked) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            source.name,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (source.desc.isNotBlank()) {
                            Text(
                                source.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = checked,
                        onCheckedChange = { toggle(source.id, it) },
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
                    appState.updateDisabledVehicleSources(emptySet())
                    api.setDisabledVehicleSources(emptySet())
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.settings_sources_reset))
            }
        }
    }
}
/** Aktuální jazyk appky + otevření systémového výběru (appka se po změně sama přenačte). */
@Composable
private fun LanguageSettingsCard() {
    val context = LocalContext.current
    val appLocales = remember { context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales }
    val current = if (appLocales == null || appLocales.isEmpty) stringResource(R.string.settings_language_system)
    else appLocales[0].let { l -> l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) } }
    OutlinedCard(
        onClick = {
            val pkg = android.net.Uri.fromParts("package", context.packageName, null)
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, pkg))
            }.onFailure {
                // výrobce bez obrazovky jazyka aplikace: aspoň detail aplikace
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
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
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    current,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.settings_language_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    val lastTimetableUpdate = remember(appState.isUpdatingData) { api.getLastTimetableUpdateMs() }
    val lastShapesUpdate = remember(appState.isUpdatingData) { api.getLastShapesUpdateMs() }
    val lastRealtimeUpdate = remember(appState.isUpdatingData) { api.getLastRealtimeUpdateMs() }
    val lastOsrmClear = remember(appState.isUpdatingData) { api.getLastOsrmClearMs() }
    val lastCompleteUpdate = remember(appState.isUpdatingData) { api.getLastCompleteUpdateMs() }

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
                    contentDescription = stringResource(R.string.settings_back)
                )
            }
            SectionTitle(stringResource(R.string.settings_data_updates))
        }

        Text(
            stringResource(R.string.updates_intro),
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
                            stringResource(R.string.updates_in_progress),
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
            val (successContainer, onSuccessContainer) = successColors()
            StatusCard(
                message = successMsg,
                icon = Icons.Default.CheckCircle,
                container = successContainer,
                content = onSuccessContainer
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
                            stringResource(R.string.updates_all_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            stringResource(R.string.updates_all_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                        if (lastCompleteUpdate > 0L) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.updates_last_complete, formatLastUpdate(lastCompleteUpdate)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                            )
                        }
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
                    Text(stringResource(R.string.updates_all_button))
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            stringResource(R.string.updates_items_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        val timetableBadge = buildString {
            val tag = timetableMeta["tag"]
            val built = timetableMeta["built"]
            if (tag != null) append(tr(R.string.updates_badge_version, tag))
            else if (built != null) append(tr(R.string.updates_badge_built, built))
            if (lastTimetableUpdate > 0L) {
                if (isNotEmpty()) append(" • ")
                append(tr(R.string.updates_badge_downloaded, formatLastUpdate(lastTimetableUpdate)))
            }
        }.ifBlank {
            if (lastTimetableUpdate > 0L) tr(R.string.updates_badge_downloaded, formatLastUpdate(lastTimetableUpdate)) else null
        }

        SettingUpdateItemCard(
            title = stringResource(R.string.updates_tt_title),
            description = stringResource(R.string.updates_tt_desc),
            infoBadge = timetableBadge,
            icon = Icons.Default.DirectionsBus,
            buttonText = stringResource(R.string.updates_tt_button),
            enabled = !appState.isUpdatingData,
            onClick = { appState.performUpdateTimetable(api, scope) }
        )

        val shapesBadge = buildString {
            if (shapesMeta != null) append(tr(R.string.updates_badge_built, shapesMeta))
            if (lastShapesUpdate > 0L) {
                if (isNotEmpty()) append(" • ")
                append(tr(R.string.updates_badge_downloaded, formatLastUpdate(lastShapesUpdate)))
            }
        }.ifBlank {
            if (lastShapesUpdate > 0L) tr(R.string.updates_badge_downloaded, formatLastUpdate(lastShapesUpdate)) else null
        }

        SettingUpdateItemCard(
            title = stringResource(R.string.updates_shapes_title),
            description = stringResource(R.string.updates_shapes_desc),
            infoBadge = shapesBadge,
            icon = Icons.Default.Route,
            buttonText = stringResource(R.string.updates_shapes_button),
            enabled = !appState.isUpdatingData,
            onClick = { appState.performUpdateShapes(api, scope) }
        )

        val realtimeBadge = if (lastRealtimeUpdate > 0L) stringResource(R.string.updates_rt_last, formatLastUpdate(lastRealtimeUpdate)) else stringResource(R.string.updates_rt_current)

        SettingUpdateItemCard(
            title = stringResource(R.string.updates_rt_title),
            description = stringResource(R.string.updates_rt_desc),
            infoBadge = realtimeBadge,
            icon = Icons.Default.LocationOn,
            buttonText = stringResource(R.string.updates_rt_button),
            enabled = !appState.isUpdatingData,
            onClick = { appState.performUpdateRealtime(api, scope) }
        )

        val osrmBadge = if (lastOsrmClear > 0L) stringResource(R.string.updates_osrm_last, formatLastUpdate(lastOsrmClear)) else null

        SettingUpdateItemCard(
            title = stringResource(R.string.updates_osrm_title),
            description = stringResource(R.string.updates_osrm_desc),
            infoBadge = osrmBadge,
            icon = Icons.Default.DeleteSweep,
            buttonText = stringResource(R.string.updates_osrm_button),
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

/**
 * Vlastní barevná role "úspěch" (Material 3 žádnou zelenou nemá; doporučuje doplnit roli z tonální palety):
 * kontejner zelená tón 90 / 30 (světlý / tmavý motiv), obsah tón 10 / 90, stejný kontrast jako ostatní *Container role.
 */
@Composable
private fun successColors(): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) androidx.compose.ui.graphics.Color(0xFF1E5130) to androidx.compose.ui.graphics.Color(0xFFB6F2C4)
    else androidx.compose.ui.graphics.Color(0xFFC6F0D0) to androidx.compose.ui.graphics.Color(0xFF00210E)
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