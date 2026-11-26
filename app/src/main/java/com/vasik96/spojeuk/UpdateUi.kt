package com.vasik96.spojeuk

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import kotlinx.coroutines.launch

internal fun openReleasePage(context: Context, release: AppRelease) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, release.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Dialog po spuštění: vyšla novější verze. */
@Composable
internal fun AppUpdateDialog(release: AppRelease, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.NewReleases, contentDescription = null) },
        title = { Text(stringResource(R.string.update_dialog_title, release.version)) },
        text = { Text(stringResource(R.string.update_dialog_text, APP_VERSION)) },
        confirmButton = {
            TextButton(onClick = { openReleasePage(context, release); onDismiss() }) {
                Text(stringResource(R.string.update_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
        }
    )
}

private sealed interface ManualCheck {
    data object Idle : ManualCheck
    data object Checking : ManualCheck
    data object Latest : ManualCheck
    data class Available(val release: AppRelease) : ManualCheck
    data object Failed : ManualCheck
}

/** Nastavení: přepínač upozornění a ruční kontrola nové verze. */
@Composable
internal fun AppUpdateSettings(appState: SpojeAppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ManualCheck>(ManualCheck.Idle) }

    SettingSwitchCard(
        icon = Icons.Default.NewReleases,
        title = stringResource(R.string.settings_update_notify),
        description = stringResource(R.string.settings_update_notify_desc),
        checked = appState.notifyAppUpdates,
        onCheckedChange = { appState.updateNotifyAppUpdates(it) },
    )

    val s = state
    SettingCard(
        icon = Icons.Default.SystemUpdate,
        title = stringResource(R.string.settings_update_check),
        description = when (s) {
            ManualCheck.Idle -> stringResource(R.string.settings_update_tap)
            ManualCheck.Checking -> stringResource(R.string.settings_update_checking)
            ManualCheck.Latest -> stringResource(R.string.settings_update_latest, APP_VERSION)
            is ManualCheck.Available -> stringResource(R.string.settings_update_available, s.release.version)
            ManualCheck.Failed -> stringResource(R.string.settings_update_failed)
        },
        descriptionColor = if (s is ManualCheck.Available) MaterialTheme.colorScheme.primary
        else androidx.compose.ui.graphics.Color.Unspecified,
        onClick = {
            when (s) {
                ManualCheck.Checking -> Unit
                is ManualCheck.Available -> openReleasePage(context, s.release)
                else -> scope.launch {
                    state = ManualCheck.Checking
                    state = runCatching { UpdateChecker.fetchLatest() }.fold(
                        onSuccess = { if (UpdateChecker.isNewer(it, APP_VERSION)) ManualCheck.Available(it) else ManualCheck.Latest },
                        onFailure = { ManualCheck.Failed }
                    )
                }
            }
        },
    )
}
