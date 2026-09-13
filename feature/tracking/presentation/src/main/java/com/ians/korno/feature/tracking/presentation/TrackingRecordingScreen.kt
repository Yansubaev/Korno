package com.ians.korno.feature.tracking.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.presentation.R as CoreR
import com.ians.korno.core.presentation.SELECTABLE_SEGMENT_KINDS
import com.ians.korno.core.presentation.icon
import com.ians.korno.core.presentation.labelRes
import com.ians.korno.core.map.TrackMap
import com.ians.korno.core.map.TrackMapCamera
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun TrackingRecordingScreen(
    onFinished: (activityId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrackingRecordingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        snapshotFlow { state.finishedActivityId }.collectLatest { id ->
            if (id != null) onFinished(id)
        }
    }

    TrackingRecordingContent(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
    )
}

@Composable
private fun TrackingRecordingContent(
    state: TrackingRecordingState,
    onAction: (TrackingRecordingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            RecordingHeader(state = state, onAction = onAction)

            MapWithSheet(
                state = state,
                onAction = onAction,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            RecordingControls(state = state, onAction = onAction)
        }

        if (state.finishDialogOpen) {
            AlertDialog(
                onDismissRequest = { onAction(TrackingRecordingAction.CancelFinish) },
                title = { Text(stringResource(R.string.tracking_finish_dialog_title)) },
                text = { Text(stringResource(R.string.tracking_finish_dialog_body)) },
                confirmButton = {
                    TextButton(onClick = { onAction(TrackingRecordingAction.ConfirmFinish) }) {
                        Text(stringResource(R.string.tracking_finish_dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onAction(TrackingRecordingAction.CancelFinish) }) {
                        Text(stringResource(R.string.tracking_finish_dialog_keep_going))
                    }
                },
            )
        }
    }
}

@Composable
private fun RecordingHeader(
    state: TrackingRecordingState,
    onAction: (TrackingRecordingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(horizontal = SCREEN_PADDING),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val segmentKind = state.segmentKind
        if (segmentKind != null) {
            SegmentKindChip(
                kind = segmentKind,
                menuOpen = state.segmentMenuOpen,
                onAction = onAction,
            )
        } else Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = state.activityType.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(state.activityType.labelRes()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        GpsIndicator(hasFix = state.currentPoint != null)
    }
}

/**
 * Вид текущего сегмента в авторежиме. Тап — ручная поправка: детектор ошибается, а
 * человек знает, спешился он или нет.
 */
@Composable
private fun SegmentKindChip(
    kind: SegmentKind,
    menuOpen: Boolean,
    onAction: (TrackingRecordingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AssistChip(
            onClick = { onAction(TrackingRecordingAction.ToggleSegmentMenu) },
            leadingIcon = { Icon(kind.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.tracking_cd_change_segment_kind),
                    modifier = Modifier.size(18.dp),
                )
            },
            label = {
                Text(stringResource(R.string.tracking_segment_auto_label, stringResource(kind.labelRes())))
            },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { onAction(TrackingRecordingAction.ToggleSegmentMenu) },
        ) {
            SELECTABLE_SEGMENT_KINDS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    leadingIcon = { Icon(option.icon(), contentDescription = null) },
                    trailingIcon = if (option == kind) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = { onAction(TrackingRecordingAction.ChangeSegmentKind(option)) },
                )
            }
        }
    }
}

/** Четыре растущих столбика + подпись GPS: тусклые, пока нет ни одной точки. */
@Composable
private fun GpsIndicator(hasFix: Boolean, modifier: Modifier = Modifier) {
    val barColor = if (hasFix) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.height(14.dp),
        ) {
            GPS_BAR_HEIGHTS.forEach { barHeight ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(barHeight)
                        .background(barColor, RoundedCornerShape(1.dp)),
                )
            }
        }
        Text(
            text = stringResource(R.string.tracking_gps_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Карта во всю область и шторка со статистикой поверх неё.
 *
 * Высота шторки — единственный источник правды для внешнего вида: и режимы
 * ([MapSheetMode]), и перетаскивание сводятся к ней, а размеры внутри
 * интерполируются между тремя опорными точками.
 */
@Composable
private fun MapWithSheet(
    state: TrackingRecordingState,
    onAction: (TrackingRecordingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    val anchors = remember(containerHeightPx, density) { sheetAnchors(containerHeightPx, density) }
    val sheetHeight = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Перетаскивание само доводит шторку до ближайшей позиции, поэтому эффект
    // нужен только для смены режима извне и для пересчёта при смене размера.
    LaunchedEffect(anchors, state.mapSheetMode) {
        if (anchors == null) return@LaunchedEffect
        val target = anchors.heightFor(state.mapSheetMode)
        if (sheetHeight.value == 0f) sheetHeight.snapTo(target) else sheetHeight.animateTo(target, SHEET_ANIMATION)
    }

    Box(
        modifier = modifier.onSizeChanged { containerHeightPx = it.height.toFloat() },
    ) {
        TrackMap(
            points = state.routePoints,
            currentPoint = state.currentPoint,
            camera = TrackMapCamera.FollowLatest,
            // Не анимированная высота, а высота позиции: камера переставляется
            // один раз на смену режима, а не каждый кадр анимации шторки.
            bottomInset = with(density) { (anchors?.cameraInsetFor(state.mapSheetMode) ?: 0f).toDp() },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.currentPoint == null) {
            Text(
                text = stringResource(R.string.tracking_map_waiting_gps),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(100))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (anchors != null) {
            RecordingSheet(
                state = state,
                heightPx = sheetHeight.value,
                anchors = anchors,
                onHandleClick = { onAction(TrackingRecordingAction.CycleMapSheetMode) },
                onDrag = { delta ->
                    scope.launch {
                        sheetHeight.snapTo((sheetHeight.value - delta).coerceIn(anchors.map, anchors.text))
                    }
                },
                onDragStopped = {
                    val nearest = anchors.nearestMode(sheetHeight.value)
                    scope.launch { sheetHeight.animateTo(anchors.heightFor(nearest), SHEET_ANIMATION) }
                    onAction(TrackingRecordingAction.MapSheetModeChanged(nearest))
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun RecordingSheet(
    state: TrackingRecordingState,
    heightPx: Float,
    anchors: SheetAnchors,
    onHandleClick: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val height = with(density) { heightPx.toDp() }
    val progress = anchors.progressAt(heightPx)
    val draggableState = rememberDraggableState(onDelta = onDrag)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
            .background(MaterialTheme.colorScheme.background)
            // Жест висит на всей шторке, а не на одной ручке: тянуть можно откуда
            // угодно, и заодно ни одно касание не проваливается на карту под ней —
            // карта остаётся управляемой только на видимой части.
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = { onDragStopped() },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDLE_HEIGHT)
                .clickable(onClickLabel = stringResource(R.string.tracking_cd_toggle_map), onClick = onHandleClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Box центрует блок, пока он помещается, а когда шторка ниже него —
        // unbounded = true даёт содержимому померяться целиком и обрезаться
        // снизу краем шторки, а не сплющиться.
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .padding(horizontal = SCREEN_PADDING),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RecordingStatusBadge(state.recordingStatus)
                Text(
                    text = formatElapsed(state.elapsedTimeMillis),
                    fontSize = progress.lerp(TIME_FONT_MAP, TIME_FONT_SPLIT, TIME_FONT_TEXT),
                    lineHeight = progress.lerp(TIME_FONT_MAP, TIME_FONT_SPLIT, TIME_FONT_TEXT) * 1.05f,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                MovingTimeLine(state = state, progress = progress)
                if (state.segmentDistances.isNotEmpty()) {
                    SegmentBreakdownLine(state = state, progress = progress)
                }
                StatsGrid(state = state, progress = progress)
            }
        }
    }
}

/**
 * «В движении» — единственный блок, который сворачивается в режиме карты:
 * место в компактной шторке важнее отдать дистанции и скорости.
 */
@Composable
private fun MovingTimeLine(
    state: TrackingRecordingState,
    progress: SheetProgress,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(progress.lerp(0.dp, 22.dp, 28.dp))
            .clipToBounds()
            .wrapContentHeight(align = Alignment.Top, unbounded = true),
    ) {
        val movingText = state.stepCount?.let { steps ->
            stringResource(
                R.string.tracking_moving_value_with_steps,
                formatElapsed(state.movingTimeMillis),
                pluralStringResource(R.plurals.tracking_steps_count, steps, steps),
            )
        } ?: stringResource(R.string.tracking_moving_value, formatElapsed(state.movingTimeMillis))
        Text(
            text = movingText,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = progress.lerp(13.sp, 15.sp, 19.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = progress.fade()),
            maxLines = 1,
        )
    }
}

/**
 * Разбивка дистанции по встреченным видам: «🚶 1.20 · 🚲 8.40 · 🚗 3.00 км». Как и
 * «в движении», сворачивается в режиме карты.
 */
@Composable
private fun SegmentBreakdownLine(
    state: TrackingRecordingState,
    progress: SheetProgress,
    modifier: Modifier = Modifier,
) {
    val distanceUnit = stringResource(
        if (state.measurementSystem == MeasurementSystem.METRIC) CoreR.string.unit_km else CoreR.string.unit_mi,
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = progress.fade())
    val fontSize = progress.lerp(13.sp, 15.sp, 19.sp)
    Box(
        modifier = modifier
            .height(progress.lerp(0.dp, 22.dp, 28.dp))
            .clipToBounds()
            .wrapContentHeight(align = Alignment.Top, unbounded = true),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.segmentDistances.forEachIndexed { index, (kind, distance) ->
                if (index > 0) Text("·", color = color, fontSize = fontSize)
                Icon(
                    imageVector = kind.icon(),
                    contentDescription = stringResource(kind.labelRes()),
                    tint = color,
                    modifier = Modifier.size(with(LocalDensity.current) { fontSize.toDp() }),
                )
                Text(
                    text = "${formatDistance(distance)} $distanceUnit",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = fontSize,
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Сетка 2×2: слева «путь» (дистанция и набор высоты), справа скорости —
 * связанные величины стоят в одной колонке и сравниваются взглядом сверху вниз.
 *
 * В режиме карты вторая строка сворачивается: там остаются только дистанция
 * и текущая скорость — то, за чем смотрят на ходу.
 */
@Composable
private fun StatsGrid(state: TrackingRecordingState, progress: SheetProgress, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val isMetric = state.measurementSystem == MeasurementSystem.METRIC
    val distanceUnit = stringResource(if (isMetric) CoreR.string.unit_km else CoreR.string.unit_mi)
    val speedUnit = stringResource(if (isMetric) CoreR.string.unit_kmh else CoreR.string.unit_mph)
    val paceUnit = stringResource(if (isMetric) CoreR.string.unit_pace_km else CoreR.string.unit_pace_mi)
    val elevationUnit = stringResource(if (isMetric) CoreR.string.unit_m else CoreR.string.unit_ft)
    val visibility = progress.fade()
    val gap = progress.lerp(6.dp, 10.dp, 14.dp)
    // Высота второй строки берётся из первой: плитки устроены одинаково,
    // поэтому её не нужно пересчитывать по шрифтам и отступам вручную.
    var tileHeight by remember { mutableStateOf(0.dp) }

    Row(
        modifier = modifier
            .widthIn(max = progress.lerp(340.dp, 400.dp, 460.dp))
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(gap * visibility),
        ) {
            StatTile(
                label = "${stringResource(R.string.tracking_distance_label)}, $distanceUnit",
                value = formatDistance(state.distanceValue),
                progress = progress,
                modifier = Modifier.onSizeChanged { tileHeight = with(density) { it.height.toDp() } },
            )
            CollapsingTile(height = tileHeight * visibility, contentAlpha = visibility) {
                StatTile(
                    label = "${stringResource(R.string.tracking_elevation_label)}, $elevationUnit",
                    value = formatElevation(state.elevationGainValue),
                    progress = progress,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(gap * visibility),
        ) {
            // В авторежиме на ногах главная метрика — темп: «5:30» читается, «10,9 км/ч» — нет.
            StatTile(
                label = if (state.showsPace) {
                    "${stringResource(R.string.tracking_pace_label)}, $paceUnit"
                } else {
                    "${stringResource(R.string.tracking_current_label)}, $speedUnit"
                },
                value = if (state.showsPace) formatPace(state.currentSpeedValue) else formatSpeed(state.currentSpeedValue),
                progress = progress,
            )
            CollapsingTile(height = tileHeight * visibility, contentAlpha = visibility) {
                StatTile(
                    label = if (state.showsPace) {
                        "${stringResource(R.string.tracking_avg_pace_label)}, $paceUnit"
                    } else {
                        "${stringResource(R.string.tracking_avg_label)}, $speedUnit"
                    },
                    value = if (state.showsPace) {
                        formatPace(state.averageSpeedValue)
                    } else {
                        formatSpeed(state.averageSpeedValue)
                    },
                    progress = progress,
                )
            }
        }
    }
}

@Composable
private fun CollapsingTile(height: Dp, contentAlpha: Float, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { alpha = contentAlpha }
            .clipToBounds()
            .wrapContentHeight(align = Alignment.Top, unbounded = true),
    ) {
        content()
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    progress: SheetProgress,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(progress.lerp(12.dp, 16.dp, 20.dp)),
            )
            .padding(vertical = progress.lerp(6.dp, 12.dp, 16.dp), horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontSize = progress.lerp(10.sp, 12.sp, 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontSize = progress.lerp(18.sp, 28.sp, 40.sp),
            lineHeight = progress.lerp(24.sp, 36.sp, 50.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
internal fun RecordingControls(
    state: TrackingRecordingState,
    onAction: (TrackingRecordingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paused = state.recordingStatus == RecordingStatus.MANUAL_PAUSE
    // Пока сигнала нет, запись и так стоит — паузе нечего останавливать.
    val waitingForGps = state.recordingStatus == RecordingStatus.WAITING_FOR_GPS
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = SCREEN_PADDING, end = SCREEN_PADDING, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onAction(TrackingRecordingAction.CycleMapSheetMode) },
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
            modifier = Modifier.size(SECONDARY_BUTTON_SIZE),
        ) {
            Icon(
                imageVector = Icons.Filled.SwapVert,
                contentDescription = stringResource(R.string.tracking_cd_toggle_map),
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(
            onClick = {
                onAction(
                    if (paused) TrackingRecordingAction.ResumeClicked else TrackingRecordingAction.PauseClicked,
                )
            },
            enabled = !waitingForGps,
            colors = IconButtonDefaults.filledIconButtonColors(),
            modifier = Modifier.size(PRIMARY_BUTTON_SIZE),
        ) {
            Icon(
                imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = stringResource(
                    if (paused) R.string.tracking_resume else R.string.tracking_pause,
                ),
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(
            onClick = { onAction(TrackingRecordingAction.RequestFinish) },
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
            modifier = Modifier.size(SECONDARY_BUTTON_SIZE),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.tracking_stop),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun RecordingStatusBadge(status: RecordingStatus, modifier: Modifier = Modifier) {
    val (labelRes, container, content) = when (status) {
        RecordingStatus.RECORDING -> Triple(
            R.string.tracking_status_recording,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        RecordingStatus.WAITING_FOR_GPS -> Triple(
            R.string.tracking_status_waiting_gps,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        RecordingStatus.AUTO_PAUSE -> Triple(
            R.string.tracking_status_autopaused,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        else -> Triple(
            R.string.tracking_status_paused,
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val transition = rememberInfiniteTransition(label = "pulse")
    // Пульсирует и поиск сигнала: точка мигает ровно там, где идёт работа.
    val pulsing = status == RecordingStatus.RECORDING || status == RecordingStatus.WAITING_FOR_GPS
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Row(
        modifier = modifier
            .background(container, RoundedCornerShape(100))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(content.copy(alpha = alpha), CircleShape),
        )
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** Три опорные высоты шторки в пикселях. */
@Immutable
private data class SheetAnchors(val map: Float, val split: Float, val text: Float) {

    fun heightFor(mode: MapSheetMode): Float = when (mode) {
        MapSheetMode.MAP -> map
        MapSheetMode.SPLIT -> split
        MapSheetMode.TEXT -> text
    }

    fun nearestMode(heightPx: Float): MapSheetMode =
        MapSheetMode.entries.minBy { abs(heightFor(it) - heightPx) }

    /**
     * Насколько шторка перекрывает карту с точки зрения камеры. В режиме
     * [MapSheetMode.TEXT] карты не видно, и кадр сохраняется таким же, как был
     * в [MapSheetMode.SPLIT] — при возврате не будет лишнего перелёта.
     */
    fun cameraInsetFor(mode: MapSheetMode): Float = when (mode) {
        MapSheetMode.MAP -> map
        MapSheetMode.SPLIT, MapSheetMode.TEXT -> split
    }

    fun progressAt(heightPx: Float): SheetProgress = SheetProgress(
        fraction = when {
            split <= map || text <= split -> 1f
            heightPx <= split -> ((heightPx - map) / (split - map)).coerceIn(0f, 1f)
            else -> 1f + ((heightPx - split) / (text - split)).coerceIn(0f, 1f)
        },
    )
}

/**
 * Положение шторки как число от 0 (компактная) через 1 (половина) до 2 (на весь экран).
 * Значения между опорными точками интерполируются линейно.
 */
@JvmInline
private value class SheetProgress(val fraction: Float) {

    private fun coefficient(): Pair<Float, Boolean> =
        if (fraction <= 1f) fraction to false else (fraction - 1f) to true

    fun lerp(atMap: Dp, atSplit: Dp, atText: Dp): Dp {
        val (t, upper) = coefficient()
        return if (upper) atSplit + (atText - atSplit) * t else atMap + (atSplit - atMap) * t
    }

    fun lerp(atMap: TextUnit, atSplit: TextUnit, atText: TextUnit): TextUnit {
        val (t, upper) = coefficient()
        val value = if (upper) {
            atSplit.value + (atText.value - atSplit.value) * t
        } else {
            atMap.value + (atSplit.value - atMap.value) * t
        }
        return value.sp
    }

    /** Прозрачность второстепенных блоков: они полностью исчезают в режиме карты. */
    fun fade(): Float = fraction.coerceIn(0f, 1f)
}

/**
 * Высоты заданы по содержимому, а не долей экрана: шторка ровно обнимает свои
 * блоки, под плитками не остаётся пустой полосы, а вся остальная высота
 * достаётся карте. Масштаб системного шрифта учитывается — иначе при крупном
 * шрифте низ шторки обрезался бы.
 */
private fun sheetAnchors(containerHeightPx: Float, density: Density): SheetAnchors? {
    if (containerHeightPx <= 0f) return null
    val scale = density.fontScale.coerceAtLeast(1f)
    val map = (with(density) { MAP_SHEET_HEIGHT.toPx() } * scale).coerceAtMost(containerHeightPx)
    val split = (with(density) { SPLIT_SHEET_HEIGHT.toPx() } * scale)
        .coerceAtMost(containerHeightPx * MAX_SPLIT_FRACTION)
        .coerceIn(map, containerHeightPx)
    return SheetAnchors(map = map, split = split, text = containerHeightPx)
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatDistance(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun formatSpeed(value: Double): String = String.format(Locale.US, "%.1f", value)

/**
 * Темп из скорости в км/ч или миль/ч: минуты на единицу. Стоянка и ползание медленнее
 * часа на единицу темпа не имеют.
 */
private fun formatPace(speedPerHour: Double): String {
    if (speedPerHour <= 1.0 || !speedPerHour.isFinite()) return "—"
    val secondsPerUnit = (3_600.0 / speedPerHour).toLong()
    return String.format(Locale.US, "%d:%02d", secondsPerUnit / 60, secondsPerUnit % 60)
}

private fun formatElevation(value: Double): String = String.format(Locale.US, "%.0f", value)

private val HEADER_HEIGHT = 48.dp
private val SCREEN_PADDING = 20.dp
private val SHEET_CORNER = 20.dp
private val HANDLE_HEIGHT = 34.dp
private val SECONDARY_BUTTON_SIZE = 56.dp
private val PRIMARY_BUTTON_SIZE = 88.dp
private val GPS_BAR_HEIGHTS = listOf(5.dp, 8.dp, 11.dp, 14.dp)

/** Ручка, бейдж, компактный таймер и одна строка плиток. */
private val MAP_SHEET_HEIGHT = 176.dp

/** То же плюс «в движении» и вторая строка плиток, всё в крупном наборе. */
private val SPLIT_SHEET_HEIGHT = 326.dp

/** Даже на низком экране половинный режим оставляет карте четверть высоты. */
private const val MAX_SPLIT_FRACTION = 0.72f

private val TIME_FONT_MAP = 28.sp
private val TIME_FONT_SPLIT = 46.sp
private val TIME_FONT_TEXT = 76.sp

private val SHEET_ANIMATION = tween<Float>(
    durationMillis = 360,
    easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
)

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun TrackingRecordingSplitPreview() {
    KornoTheme {
        TrackingRecordingContent(
            state = previewState(MapSheetMode.SPLIT),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun TrackingRecordingMapPreview() {
    KornoTheme {
        TrackingRecordingContent(
            state = previewState(MapSheetMode.MAP),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun TrackingRecordingTextPreview() {
    KornoTheme {
        TrackingRecordingContent(
            state = previewState(MapSheetMode.TEXT),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun TrackingRecordingAutoModePreview() {
    KornoTheme {
        TrackingRecordingContent(
            state = previewState(MapSheetMode.SPLIT).copy(
                segmentKind = SegmentKind.ON_FOOT,
                segmentDistances = listOf(SegmentKind.ON_FOOT to 1.2, SegmentKind.CYCLING to 8.4, SegmentKind.VEHICLE to 3.0),
                currentSpeedValue = 5.1,
                averageSpeedValue = 4.8,
            ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun TrackingRecordingWaitingForGpsPreview() {
    KornoTheme {
        TrackingRecordingContent(
            state = TrackingRecordingState(recordingStatus = RecordingStatus.WAITING_FOR_GPS),
            onAction = {},
        )
    }
}

private fun previewState(mode: MapSheetMode) = TrackingRecordingState(
    recordingStatus = RecordingStatus.RECORDING,
    elapsedTimeMillis = 1_484_000L,
    movingTimeMillis = 1_402_000L,
    distanceValue = 5.24,
    currentSpeedValue = 12.4,
    averageSpeedValue = 11.8,
    elevationGainValue = 148.0,
    mapSheetMode = mode,
)
