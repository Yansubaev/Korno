package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.map.EditableMapPoint
import com.ians.korno.core.map.EditableTrackMap
import com.ians.korno.core.map.MapPoint
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun TrackCorrectionScreen(
    activityId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrackCorrectionViewModel = koinViewModel(parameters = { parametersOf(activityId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.track_correction_saved_snackbar)

    LaunchedEffect(state.saved) {
        if (state.saved) {
            snackbarHostState.showSnackbar(savedMessage)
            delay(600)
            onBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier,
    ) { padding ->
        TrackCorrectionContent(
            state = state,
            onAction = viewModel::onAction,
            onBack = onBack,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun TrackCorrectionContent(
    state: TrackCorrectionState,
    onAction: (TrackCorrectionAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
            Text(
                text = stringResource(R.string.track_correction_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Карта вынесена из прокручиваемой части: она сама обрабатывает жесты
        // (панорамирование, зум и тап по точке).
        val activeOrdered = remember(state.points) { state.points.filterNot { it.deleted } }
        var pendingRange by remember(activeOrdered.size) {
            mutableStateOf(0f..(activeOrdered.size - 1).coerceAtLeast(0).toFloat())
        }
        val mapPoints = remember(activeOrdered, state.mode, pendingRange) {
            val visible = if (state.mode == TrackCorrectionMode.TRIM && activeOrdered.isNotEmpty()) {
                val lastIndex = activeOrdered.lastIndex
                val lo = pendingRange.start.roundToInt().coerceIn(0, lastIndex)
                val hi = pendingRange.endInclusive.roundToInt().coerceIn(lo, lastIndex)
                activeOrdered.subList(lo, hi + 1)
            } else {
                activeOrdered
            }
            visible.map { point ->
                EditableMapPoint(
                    id = point.id,
                    point = point.point,
                    isOutlier = point.isOutlier,
                    isSelected = point.selected,
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            EditableTrackMap(
                points = mapPoints,
                onPointClick = { id -> onAction(TrackCorrectionAction.SelectPoint(id)) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp, 0.dp, 20.dp, 20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                FilterChip(
                    selected = state.mode == TrackCorrectionMode.DELETE_POINTS,
                    onClick = { onAction(TrackCorrectionAction.SetMode(TrackCorrectionMode.DELETE_POINTS)) },
                    label = { Text(stringResource(R.string.track_correction_mode_delete)) },
                )
                FilterChip(
                    selected = state.mode == TrackCorrectionMode.TRIM,
                    onClick = { onAction(TrackCorrectionAction.SetMode(TrackCorrectionMode.TRIM)) },
                    label = { Text(stringResource(R.string.track_correction_mode_trim)) },
                )
            }

            if (state.mode == TrackCorrectionMode.DELETE_POINTS) {
                Row(
                    modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LegendDot(color = MaterialTheme.colorScheme.primary, label = stringResource(R.string.track_correction_legend_point))
                    LegendDot(color = MaterialTheme.colorScheme.error, label = stringResource(R.string.track_correction_legend_outlier))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                    OutlinedActionButton(
                        text = stringResource(R.string.track_correction_delete_point),
                        enabled = state.hasSelection,
                        onClick = { onAction(TrackCorrectionAction.DeleteSelected) },
                    )
                    TextButton(onClick = { onAction(TrackCorrectionAction.Undo) }, enabled = state.canUndo) {
                        Text(stringResource(R.string.track_correction_undo))
                    }
                    TextButton(onClick = { onAction(TrackCorrectionAction.Reset) }) {
                        Text(stringResource(R.string.track_correction_reset))
                    }
                }
            } else {
                if (activeOrdered.size >= 2) {
                    Text(
                        text = stringResource(R.string.track_correction_trim_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    RangeSlider(
                        value = pendingRange,
                        onValueChange = { pendingRange = it },
                        onValueChangeFinished = {
                            onAction(
                                TrackCorrectionAction.TrimRange(
                                    startIndex = pendingRange.start.roundToInt(),
                                    endIndex = pendingRange.endInclusive.roundToInt(),
                                ),
                            )
                        },
                        valueRange = 0f..(activeOrdered.size - 1).toFloat(),
                        steps = (activeOrdered.size - 2).coerceAtLeast(0),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.track_correction_trim_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp, bottom = 20.dp)) {
                    TextButton(onClick = { onAction(TrackCorrectionAction.Undo) }, enabled = state.canUndo) {
                        Text(stringResource(R.string.track_correction_undo))
                    }
                    TextButton(onClick = { onAction(TrackCorrectionAction.Reset) }) {
                        Text(stringResource(R.string.track_correction_reset))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatColumn(stringResource(R.string.track_correction_original), state.originalDistanceText)
                StatColumn(stringResource(R.string.track_correction_current), state.currentDistanceText)
                StatColumn(stringResource(R.string.track_correction_removed), state.removedCount.toString())
            }

            Button(
                onClick = { onAction(TrackCorrectionAction.Save) },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(56.dp),
            ) {
                Text(stringResource(R.string.track_correction_save))
            }
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OutlinedActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, enabled = enabled) {
        Text(text)
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview(showBackground = true)
@Composable
private fun TrackCorrectionContentPreview() {
    KornoTheme {
        TrackCorrectionContent(
            state = TrackCorrectionState(
                isLoading = false,
                points = listOf(
                    CorrectionPointUi(1L, MapPoint(55.7512, 37.6175), false, false, false),
                    CorrectionPointUi(2L, MapPoint(55.7534, 37.6212), true, false, true),
                    CorrectionPointUi(3L, MapPoint(55.7560, 37.6198), false, false, false),
                ),
                originalDistanceText = "5.18 km",
                currentDistanceText = "5.10 km",
                removedCount = 1,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TrackCorrectionTrimPreview() {
    KornoTheme {
        TrackCorrectionContent(
            state = TrackCorrectionState(
                isLoading = false,
                mode = TrackCorrectionMode.TRIM,
                points = listOf(
                    CorrectionPointUi(1L, MapPoint(55.7512, 37.6175), false, false, false),
                    CorrectionPointUi(2L, MapPoint(55.7534, 37.6212), false, false, false),
                    CorrectionPointUi(3L, MapPoint(55.7560, 37.6198), false, false, false),
                    CorrectionPointUi(4L, MapPoint(55.7578, 37.6240), false, false, false),
                ),
                originalDistanceText = "5.18 km",
                currentDistanceText = "5.18 km",
                removedCount = 0,
            ),
            onAction = {},
            onBack = {},
        )
    }
}
