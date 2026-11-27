@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import kotlinx.coroutines.launch

internal fun openReleasePage(context: Context, release: AppRelease) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, release.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Text chyby stažení / instalace. */
@Composable
private fun failedText(reason: AppUpdater.Reason): String = stringResource(
    when (reason) {
        AppUpdater.Reason.DOWNLOAD -> R.string.update_failed_download
        AppUpdater.Reason.INVALID -> R.string.update_failed_invalid
        AppUpdater.Reason.SIGNATURE -> R.string.update_failed_signature
        AppUpdater.Reason.OLDER -> R.string.update_failed_older
    }
)

/** Fáze dialogu (přechody se animují jen při změně fáze, ne při každém posunu průběhu). */
private enum class Phase { OFFER, DOWNLOADING, READY, FAILED }

private fun phaseOf(s: AppUpdater.State): Phase = when (s) {
    AppUpdater.State.Idle -> Phase.OFFER
    is AppUpdater.State.Downloading -> Phase.DOWNLOADING
    is AppUpdater.State.Ready -> Phase.READY
    is AppUpdater.State.Failed -> Phase.FAILED
}

private fun mb(bytes: Long): String = String.format(java.util.Locale.getDefault(), "%.1f", bytes / 1_048_576.0)

/** Přechod obsahu dialogu: nový zespodu s prolnutím, velikost dialogu se plynule přizpůsobí. */
private fun <S> AnimatedContentTransitionScope<S>.dialogSwap(): ContentTransform =
    (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 4 })
        .togetherWith(fadeOut(tween(120)))
        .using(SizeTransform(clip = false) { _, _ -> spring(stiffness = Spring.StiffnessMediumLow) })

/**
 * Dialog nové verze (po spuštění, nebo z nastavení: [manual]). "Stáhnout a nainstalovat" stáhne APK přímo v aplikaci:
 * průběh plynule ukazuje vlnitý pruh, po stažení se otevře systémová instalace. Release bez APK jen otevře GitHub.
 * [onDismiss] dostane, jestli uživatel zaškrtl „Znovu neukazovat“ (jen u dialogu po spuštění; vypne upozornění).
 * Během stahování dialog nejde zavřít.
 */
@Composable
internal fun AppUpdateDialog(release: AppRelease, manual: Boolean = false, onDismiss: (dontShowAgain: Boolean) -> Unit) {
    val context = LocalContext.current
    var dontShow by remember { mutableStateOf(false) }
    val dl by AppUpdater.state.collectAsState()
    val s = AppUpdater.stateFor(dl, release)
    val phase = phaseOf(s)
    val direct = AppUpdater.canDownload(release)

    // průběh: pružina navazuje na rychlost, takže pruh jede plynule i mezi hlášeními (~50 ms); po stažení dojede do konce
    val target = when (s) {
        is AppUpdater.State.Downloading -> s.progress ?: 0f
        is AppUpdater.State.Ready -> 1f
        else -> 0f
    }
    val progress by animateFloatAsState(target, ProgressIndicatorDefaults.ProgressAnimationSpec, label = "updateProgress")

    // během stahování dialog nejde zavřít (ťuknutí mimo, zpět ani tlačítkem), ať se omylem nezavře;
    // stahování má časové limity, takže nezůstane viset (chyba = zase jde zavřít)
    val downloading = phase == Phase.DOWNLOADING
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss(dontShow && !manual) },
        properties = DialogProperties(dismissOnBackPress = !downloading, dismissOnClickOutside = !downloading),
        icon = {
            AnimatedContent(phase, transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.6f))
                    .togetherWith(fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.6f))
            }, label = "updateIcon") { p ->
                when (p) {
                    Phase.OFFER -> Icon(Icons.Default.NewReleases, contentDescription = null)
                    Phase.DOWNLOADING -> Icon(Icons.Default.Download, contentDescription = null)
                    Phase.READY -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Phase.FAILED -> Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            }
        },
        title = { Text(stringResource(R.string.update_dialog_title, release.version)) },
        text = {
            // stahování -> staženo: stejný obsah (pruh dojede do konce, změní se jen text), ne přechod celého bloku
            val bodyKey = if (phase == Phase.READY) Phase.DOWNLOADING else phase
            AnimatedContent(bodyKey, transitionSpec = { dialogSwap() }, label = "updateBody") { p ->
                Column(Modifier.fillMaxWidth()) {
                    when (p) {
                        Phase.OFFER -> {
                            Text(stringResource(R.string.update_dialog_text, APP_VERSION))
                            if (!manual) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(value = dontShow, role = Role.Checkbox, onValueChange = { dontShow = it }),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = dontShow, onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.update_dont_show_again), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Phase.DOWNLOADING, Phase.READY -> {
                            val d = s as? AppUpdater.State.Downloading
                            val ready = phase == Phase.READY
                            val known = ready || d?.progress != null
                            Text(
                                when {
                                    ready -> stringResource(R.string.update_ready)
                                    known -> stringResource(R.string.update_downloading, (progress * 100).roundToInt().coerceIn(0, 100))
                                    else -> stringResource(R.string.update_downloading_unknown)
                                }
                            )
                            Spacer(Modifier.height(16.dp))
                            if (known) {
                                LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            if (d != null && d.totalBytes > 0) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.update_size_mb, mb(d.doneBytes), mb(d.totalBytes)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Phase.FAILED -> (s as? AppUpdater.State.Failed)?.let {
                            Text(failedText(it.reason), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val failed = s as? AppUpdater.State.Failed
            AnimatedContent(phase, transitionSpec = { fadeIn(tween(200, delayMillis = 60)).togetherWith(fadeOut(tween(100))) }, label = "updateConfirm") { p ->
                when {
                    !direct -> TextButton(onClick = { openReleasePage(context, release); onDismiss(dontShow && !manual) }) {
                        Text(stringResource(R.string.update_download))
                    }
                    p == Phase.OFFER -> Button(onClick = { AppUpdater.downloadAndInstall(context, release) }) {
                        Text(stringResource(R.string.update_install))
                    }
                    p == Phase.READY -> Button(onClick = { (s as? AppUpdater.State.Ready)?.let { AppUpdater.install(context, it.file) } }) {
                        Text(stringResource(R.string.update_install_now))
                    }
                    p == Phase.FAILED && failed?.reason == AppUpdater.Reason.SIGNATURE ->
                        TextButton(onClick = { openReleasePage(context, release); onDismiss(false) }) {
                            Text(stringResource(R.string.update_open_github))
                        }
                    p == Phase.FAILED && failed?.reason != AppUpdater.Reason.OLDER ->
                        TextButton(onClick = { AppUpdater.downloadAndInstall(context, release) }) {
                            Text(stringResource(R.string.update_retry))
                        }
                    else -> Unit   // stahování běží / není co dělat
                }
            }
        },
        dismissButton = {
            val failed = s as? AppUpdater.State.Failed
            AnimatedContent(phase, transitionSpec = { fadeIn(tween(200, delayMillis = 60)).togetherWith(fadeOut(tween(100))) }, label = "updateDismiss") { p ->
                when {
                    p == Phase.OFFER -> TextButton(onClick = { onDismiss(dontShow && !manual) }) { Text(stringResource(R.string.update_later)) }
                    p == Phase.FAILED && failed?.reason == AppUpdater.Reason.DOWNLOAD || p == Phase.FAILED && failed?.reason == AppUpdater.Reason.INVALID ->
                        TextButton(onClick = { openReleasePage(context, release); onDismiss(false) }) { Text(stringResource(R.string.update_open_github)) }
                    p == Phase.FAILED && failed?.reason == AppUpdater.Reason.SIGNATURE ->
                        TextButton(onClick = { onDismiss(false) }) { Text(stringResource(R.string.update_later)) }
                    p == Phase.DOWNLOADING -> Unit   // stahování: bez tlačítka zavřít
                    else -> TextButton(onClick = { onDismiss(false) }) { Text(stringResource(R.string.update_hide)) }
                }
            }
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
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ManualCheck>(ManualCheck.Idle) }
    val dl by AppUpdater.state.collectAsState()
    fun dlFor(release: AppRelease) = AppUpdater.stateFor(dl, release)

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
            is ManualCheck.Available -> when (val d = dlFor(s.release)) {
                is AppUpdater.State.Downloading ->
                    stringResource(R.string.settings_update_downloading, d.version, ((d.progress ?: 0f) * 100).toInt())
                is AppUpdater.State.Ready -> stringResource(R.string.settings_update_ready, d.version)
                is AppUpdater.State.Failed -> when (d.reason) {
                    AppUpdater.Reason.SIGNATURE -> stringResource(R.string.settings_update_github, d.version)
                    AppUpdater.Reason.OLDER -> stringResource(R.string.update_failed_older)
                    else -> stringResource(R.string.settings_update_retry, d.version)
                }
                else -> stringResource(R.string.settings_update_available, s.release.version)
            }
            ManualCheck.Failed -> stringResource(R.string.settings_update_failed)
        },
        descriptionColor = when {
            s is ManualCheck.Available && dlFor(s.release) is AppUpdater.State.Failed -> MaterialTheme.colorScheme.error
            s is ManualCheck.Available -> MaterialTheme.colorScheme.primary
            else -> androidx.compose.ui.graphics.Color.Unspecified
        },
        onClick = {
            when (s) {
                ManualCheck.Checking -> Unit
                // stejný dialog jako po spuštění (stažení, průběh, instalace)
                is ManualCheck.Available -> appState.showAppUpdate(s.release, manual = true)
                else -> scope.launch {
                    state = ManualCheck.Checking
                    state = runCatching { UpdateChecker.fetchLatest() }.fold(
                        onSuccess = { if (UpdateChecker.isNewer(it, APP_VERSION)) ManualCheck.Available(it) else ManualCheck.Latest },
                        onFailure = { ManualCheck.Failed }
                    )
                    (state as? ManualCheck.Available)?.let { appState.showAppUpdate(it.release, manual = true) }
                }
            }
        },
    )
}
