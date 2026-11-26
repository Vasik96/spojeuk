@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vasik96.spojeuk

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

// ───────────────────────── výběr zastávky ─────────────────────────

private fun getStopMatches(
    query: String,
    indexedStops: List<IndexedStop>,
    originLat: Double,
    originLng: Double
): List<Stop> {
    val cosLat = Math.cos(Math.toRadians(originLat))
    // druhá mocnina vzdálenosti v m² (odmocnina není pro řazení potřeba)
    fun dist2(s: Stop): Double {
        if (!s.hasCoords) return Double.MAX_VALUE
        val dy = (s.lat - originLat) * Geo.M_PER_DEG_LAT
        val dx = (s.lng - originLng) * Geo.M_PER_DEG_LNG * cosLat
        return dx * dx + dy * dy
    }

    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return indexedStops.asSequence()
            .filter { it.usageCount > 0 }
            .map { it to dist2(it.stop) }
            .sortedWith(
                compareByDescending<Pair<IndexedStop, Double>> { it.first.usageCount }
                    .thenBy { it.second }
            )
            .map { it.first.stop }
            .distinctBy { it.name }
            .take(15)
            .toList()
    }
    val terms = normalize(trimmed).split(SPACES_REGEX).filter { it.isNotBlank() }
    if (terms.isEmpty()) return emptyList()

    return indexedStops.asSequence()
        .filter { item -> terms.all { term -> item.normalizedName.contains(term) } }
        .map { it to dist2(it.stop) }            // vzdálenost se spočítá jednou na zastávku
        .sortedWith(
            compareByDescending<Pair<IndexedStop, Double>> { it.first.usageCount }   // 1. použití
                .thenBy { it.second }                                                // 2. poloha
                .thenBy { if (itemStartsWithTerm(it.first.normalizedWords, terms.first())) 0 else 1 } // 3. zbytek
                .thenBy { it.first.stop.name }
        )
        .map { it.first.stop }
        .distinctBy { it.name }
        .take(20)
        .toList()
}

private fun itemStartsWithTerm(words: List<String>, term: String): Boolean =
    words.any { it.startsWith(term) }

/**
 * Textové pole s napovídáním. Text drží LOKÁLNĚ (nezávisle na jakémkoli loadingu / stavu aplikace),
 * filtrování zastávek běží na pozadí a výsledky se jen dosypou, takže psaní i mazání je plynulé.
 */
@Composable
internal fun StopField(
    label: String,
    externalText: () -> String,
    onTextChange: (String) -> Unit,
    onPick: (Stop) -> Unit,
    indexedStops: List<IndexedStop>,
    originLat: Double,
    originLng: Double,
    stopsLoading: Boolean,
    onMyLocation: () -> Unit,
    onRemoveUsed: (Stop) -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    val text = externalText()
    val colors = MaterialTheme.colorScheme

    val selectStopButtonColor = lerp(
        start = colors.surfaceVariant,
        stop = colors.outlineVariant,
        fraction = 0.3f
    )

    Surface(
        onClick = { showSheet = true },
        shape = RoundedCornerShape(18.dp),
        color = selectStopButtonColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (label == stringResource(R.string.search_from)) Icons.Default.TripOrigin else Icons.Default.Place,
                contentDescription = null,
                tint = colors.primary
            )

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
                AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(200)) + slideInVertically { it / 3 })
                            .togetherWith(fadeOut(animationSpec = tween(150)) + slideOutVertically { -it / 3 })
                    },
                    label = "stopFieldText"
                ) { displayText ->
                    if (displayText.isNotEmpty()) {
                        Text(
                            displayText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            stringResource(R.string.search_pick_stop),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (stopsLoading) {
                Wavy(20.dp)
                Spacer(Modifier.width(6.dp))
            }

            AnimatedVisibility(
                visible = text.isNotEmpty(),
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.8f),
                exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.8f)
            ) {
                IconButton(onClick = { onTextChange("") }) {
                    Icon(Icons.Default.Clear, stringResource(R.string.common_clear))
                }
            }
        }
    }

    if (showSheet) {
        StopPickerSheet(
            title = label,
            indexedStops = indexedStops,
            originLat = originLat,
            originLng = originLng,
            onDismiss = { showSheet = false },
            onPick = { showSheet = false; onPick(it) },
            onMyLocation = { showSheet = false; onMyLocation() },
            onRemoveUsed = onRemoveUsed
        )
    }
}

@Composable
private fun rememberHideKeyboard(): () -> Unit {
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    return remember(view, focusManager) {
        {
            focusManager.clearFocus(force = true)
            view.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
        }
    }
}

@Composable
private fun StopPickerSheet(
    title: String,
    indexedStops: List<IndexedStop>,
    originLat: Double,
    originLng: Double,
    onDismiss: () -> Unit,
    onPick: (Stop) -> Unit,
    onMyLocation: () -> Unit,
    onRemoveUsed: (Stop) -> Unit
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val focusRequester = remember { FocusRequester() }

    var query by remember { mutableStateOf("") }
    var options by remember { mutableStateOf<List<Stop>>(emptyList()) }
    // dotaz, ke kterému patří zobrazené [options] (hlavička, „moje poloha“ se mění až s výsledky, ne s psaním)
    var shownQuery by remember { mutableStateOf("") }
    val listAlpha = remember { Animatable(0f) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(query, indexedStops, originLat, originLng) {
        delay(60.milliseconds)
        val found = withContext(Dispatchers.Default) {
            getStopMatches(query, indexedStops, originLat, originLng)
        }
        // rychlé prolnutí: starý seznam krátce zeslábne, nový se plynule objeví (žádné skokové přeskládání)
        if (found != options || query.isBlank() != shownQuery.isBlank()) {
            if (listAlpha.value > 0.35f) listAlpha.animateTo(0.35f, tween(55, easing = LinearEasing))
            options = found
            shownQuery = query
        }
        listAlpha.animateTo(1f, tween(150, easing = LinearOutSlowInEasing))
    }

    LaunchedEffect(Unit) {
        delay(150.milliseconds)
        focusRequester.requestFocus()
    }


    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = {
            // tady jsme uvnitř dialogu, takže view i focusManager jsou správné
            val hideKeyboard = rememberHideKeyboard()
            Box(
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(pass = PointerEventPass.Initial)
                        hideKeyboard()
                    }
                }
            ) {
                BottomSheetDefaults.DragHandle()
            }
        }
    ) {
        val hideKeyboard = rememberHideKeyboard()

        // jakmile sheet míří dolů (tažení, handle, scrim, zpět, výběr), schovej klávesnici
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.targetValue }
                .collect { if (it == SheetValue.Hidden) hideKeyboard() }
        }

        val scrollHidesKeyboard = remember(hideKeyboard) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y != 0f) hideKeyboard()
                    return Offset.Zero
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .imePadding()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { hideKeyboard() })
                }
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_stop_placeholder)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, stringResource(R.string.common_clear))
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(scrollHidesKeyboard)
                    .graphicsLayer { alpha = listAlpha.value },
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (shownQuery.isBlank()) {
                    item(key = "myloc") {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            text = {
                                Column {
                                    Text(stringResource(R.string.my_location), fontWeight = FontWeight.Medium)
                                    Text(
                                        stringResource(R.string.search_find_nearest),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = { hideKeyboard(); onMyLocation() }
                        )
                    }
                    if (options.isNotEmpty()) {
                        item(key = "header") {
                            Text(
                                stringResource(R.string.search_frequent_stops),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                items(options, key = { it.name }, contentType = { "stop" }) { stop ->
                    val (stopPart, city) = splitStopName(stop.name)
                    DropdownMenuItem(
                        contentPadding = PaddingValues(
                            horizontal = 24.dp,
                            vertical = 12.dp
                        ),

                        text = {
                            Column {
                                Text(stopPart.ifBlank { stop.name }, fontWeight = FontWeight.Medium)
                                if (city.isNotBlank()) Text(
                                    city,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        trailingIcon = if (shownQuery.isBlank()) {
                            {
                                IconButton(
                                    onClick = { onRemoveUsed(stop) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.search_remove_frequent),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        } else null,
                        onClick = {
                            hideKeyboard()

                            scope.launch {
                                sheetState.hide()
                                onPick(stop)
                            }
                        }
                    )
                }

                if (options.isEmpty() && shownQuery.isNotBlank()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.search_nothing_found),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            }
        }
    }
}
