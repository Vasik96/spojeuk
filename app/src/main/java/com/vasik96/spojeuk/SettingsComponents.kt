package com.vasik96.spojeuk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

/** Karta nastavení: ikona, nadpis, popis a vpravo libovolný prvek ([trailing]). Celá karta je klikatelná. */
@Composable
internal fun SettingCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    descriptionColor: Color = Color.Unspecified,
    trailing: @Composable () -> Unit = {},
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (descriptionColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant
                    else descriptionColor
                )
            }
            trailing()
        }
    }
    if (onClick != null) OutlinedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) { content() }
    else OutlinedCard(modifier = modifier.fillMaxWidth()) { content() }
}

/** Karta s přepínačem (ťuknutí kamkoli na kartu přepne). */
@Composable
internal fun SettingSwitchCard(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingCard(
        icon = icon,
        title = title,
        description = description,
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.semantics { role = Role.Switch },
    ) {
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
