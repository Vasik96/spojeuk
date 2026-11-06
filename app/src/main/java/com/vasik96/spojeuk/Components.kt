@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun Wavy(size: Dp, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    CircularWavyProgressIndicator(modifier = modifier.size(size), color = color)
}

@Composable
internal fun AppearAnimated(
    appState: SpojeAppState,
    key: String,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // true jen při prvním zobrazení karty, takže při scrollování zpět se nepřehrává
    val state = remember(key) { MutableTransitionState(!appState.shownKeys.add(key)).apply { targetState = true } }
    val d = index.coerceAtMost(6) * 45
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = fadeIn(tween(280, delayMillis = d)) +
                slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) { it / 5 } +
                scaleIn(
                    initialScale = 0.94f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
    ) { content() }
}

@Composable
internal fun SourceBadges() {
    var showInfo by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Informace o zdrojích dat") { showInfo = true },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogoChip(R.drawable.dpmul, "Dopravní podnik města Ústí nad Labem")
        LogoChip(R.drawable.duk, "Doprava Ústeckého kraje")
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            icon = { Icon(Icons.Default.Info, null) },
            title = { Text("Neoficiální aplikace") },
            text = {
                Text(
                    "Spoje UK nejsou nijak spojeny, provozovány ani schváleny společnostmi " +
                            "Doprava Ústeckého kraje (DÚK) ani Dopravní podnik města Ústí nad Labem (DPMÚL).\n\n" +
                            "Loga slouží pouze pro vizuální informaci o tom, jaká data se v aplikaci zobrazují (a ještě to s tím vypadá lépe), " +
                            "a jsou ochrannými známkami svých vlastníků."
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("Rozumím") }
            }
        )
    }
}

@Composable
private fun LogoChip(@DrawableRes res: Int, description: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = Color.White) {
        Image(
            painter = painterResource(res),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(24.dp).padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
internal fun SetupOverlay(message: String, progress: Float, onBackground: () -> Unit) {
    // Plynulá animace hodnoty progress baru (včetně dojezdu při dokončování)
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 300,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "ProgressAnimation"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "setupPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "setupPulseScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {},
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // --- SEKCE LOGA A NÁZVU ---
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .clip(RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_background),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize()
                )
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMediumEmphasized,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Doprava Ústeckého kraje",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(40.dp))

            // --- SEKCE PROBÍHAJÍCÍ AKCE / SPINNER ---
            CircularWavyProgressIndicator(
                modifier = Modifier.size(56.dp),
                stroke = Stroke(
                    width = 14f,
                    cap = WavyProgressIndicatorDefaults.circularIndicatorStroke.cap
                ),
                trackStroke = Stroke(
                    width = 14f,
                    cap = WavyProgressIndicatorDefaults.circularTrackStroke.cap
                )
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Stahování zastávek",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = message.ifBlank { "Připravuji jízdní řády…" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (progress > 0f) {
                Spacer(Modifier.height(20.dp))
                // Vlnkovitý lineární progress indicator s plynulým animovaným postupem
                LinearWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Při prvním spuštění se stahují jízdní řády (~20 MB). Toto může trvat 30s.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}