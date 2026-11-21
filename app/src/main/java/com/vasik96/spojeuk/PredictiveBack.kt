package com.vasik96.spojeuk

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Stav prediktivního gesta zpět: průběh 0..1 a okraj, od kterého uživatel táhne. */
@Stable
internal class PredictiveBackState {
    val progress = Animatable(0f)
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
    /** Gesto právě probíhá (pod obrazovkou má být vidět ta předchozí). */
    var active by mutableStateOf(false)
}

/**
 * Prediktivní zpět: během gesta se jen sleduje průběh (náhled předchozí obrazovky), [onBack] se zavolá
 * až po dokončení gesta; zrušené gesto obrazovku vrátí zpět na místo.
 */
@Composable
internal fun rememberPredictiveBack(enabled: Boolean, onBack: () -> Unit): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled) { events ->
        state.active = true
        try {
            events.collect { e ->
                state.edge = e.swipeEdge
                state.progress.snapTo(e.progress)
            }
            currentOnBack()
            state.active = false
        } catch (e: CancellationException) {
            state.active = false
            scope.launch { state.progress.animateTo(0f, tween(180)) }
            throw e
        }
    }
    return state
}

/**
 * Náhled gesta zpět u celoobrazovkové plochy (podle Material): zmenšení na 90 %, posun k okraji, zaoblené rohy.
 */
internal fun Modifier.predictiveBack(state: PredictiveBackState): Modifier = graphicsLayer {
    val p = state.progress.value
    if (p <= 0f) return@graphicsLayer
    val s = 1f - 0.1f * p
    scaleX = s
    scaleY = s
    val dir = if (state.edge == BackEventCompat.EDGE_LEFT) 1f else -1f
    translationX = dir * p * (size.width / 20f - 8.dp.toPx()).coerceAtLeast(0f)
    shape = RoundedCornerShape((32 * p).dp)
    clip = true
}

/**
 * Podobrazovka přes obsah pod ní (ten zůstává složený, takže ho gesto zpět může ukázat jako náhled):
 * vjede zprava, odjede doprava, gesto zpět s náhledem.
 */
@Composable
internal fun PredictiveBackLayer(
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(150)),
        exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200, delayMillis = 60)),
    ) {
        // stav uvnitř AnimatedVisibility: při každém otevření začíná od nuly
        val back = rememberPredictiveBack(enabled = enabled && visible, onBack = onBack)
        Box(Modifier.fillMaxSize().predictiveBack(back)) { content() }
    }
}
