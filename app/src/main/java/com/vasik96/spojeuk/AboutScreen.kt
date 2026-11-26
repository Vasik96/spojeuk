package com.vasik96.spojeuk

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val GITHUB_USER = "Vasik96"
private const val GITHUB = "https://github.com/$GITHUB_USER"
private const val REPO_APP = "$GITHUB/spojeuk"
private const val REPO_SERVER = "$GITHUB/spojeuk-server"
private const val REPO_DATA = "$GITHUB/spojeuk-data"
private val ICON_SIZE = 96.dp

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Podobrazovka nastavení „O aplikaci“: ikona, verze, autor, odkazy na GitHub, licence a zdroje dat. */
@Composable
internal fun AboutScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    // adaptivní ikona spouštěče (maska podle tvaru ikon v systému); painterResource adaptivní ikonu neumí
    val icon = remember(iconPx) {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(iconPx, iconPx)?.asImageBitmap()
    }
    val buildDate = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .format(Instant.ofEpochMilli(UpdateChecker.buildTimeMs).atZone(ZoneId.systemDefault()))
    }

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
            }
            SectionTitle(stringResource(R.string.settings_section_about))
        }

        // hlavička: ikona, název, popisek, verze, autor
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.about_version_built, APP_VERSION, buildDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { openUrl(context, GITHUB) }) {
                Text(stringResource(R.string.about_by, GITHUB_USER))
            }
        }

        SectionTitle(stringResource(R.string.about_links))
        LinkCard(Icons.Default.Code, stringResource(R.string.about_source_app), stringResource(R.string.about_source_app_desc)) {
            openUrl(context, REPO_APP)
        }
        LinkCard(Icons.Default.Dns, stringResource(R.string.about_source_server), stringResource(R.string.about_source_server_desc)) {
            openUrl(context, REPO_SERVER)
        }
        LinkCard(Icons.Default.Storage, stringResource(R.string.about_source_data), stringResource(R.string.about_source_data_desc)) {
            openUrl(context, REPO_DATA)
        }
        LinkCard(Icons.Default.NewReleases, stringResource(R.string.about_releases), stringResource(R.string.about_releases_desc)) {
            openUrl(context, "$REPO_APP/releases")
        }
        LinkCard(Icons.Default.BugReport, stringResource(R.string.about_issue), stringResource(R.string.about_issue_desc)) {
            openUrl(context, "$REPO_APP/issues")
        }
        LinkCard(Icons.Default.Gavel, stringResource(R.string.about_license), stringResource(R.string.about_license_desc)) {
            openUrl(context, "$REPO_APP/blob/master/LICENSE")
        }

        SectionTitle(stringResource(R.string.about_data))
        InfoCard {
            InfoText(stringResource(R.string.about_data_timetables))
            InfoText(stringResource(R.string.about_data_vehicles))
            InfoText(stringResource(R.string.about_data_maps))
        }

        SectionTitle(stringResource(R.string.about_libs))
        InfoCard { InfoText(stringResource(R.string.about_libs_text)) }

        Text(
            stringResource(R.string.about_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LinkCard(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    SettingCard(icon = icon, title = title, description = description, onClick = onClick) {
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
