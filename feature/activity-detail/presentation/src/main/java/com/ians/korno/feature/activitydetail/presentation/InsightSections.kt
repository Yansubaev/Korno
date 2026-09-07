package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SplitNumberColumn = 76.dp

/** Заголовок раздела экрана. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

/**
 * Выбранная на графике или в сплитах точка.
 *
 * Панель появляется только при выборе: постоянно висящая строка со значениями «в нуле»
 * читалась бы как показатель активности, а не как курсор.
 */
@Composable
internal fun SelectionCard(
    selection: TrackSelectionUi,
    speedDisplay: SpeedDisplay,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SelectionValue(
                label = stringResource(R.string.activity_detail_distance),
                value = selection.distanceText,
            )
            SelectionValue(
                label = stringResource(R.string.activity_detail_selection_time),
                value = selection.timeText,
            )
            selection.speedText?.let {
                SelectionValue(
                    label = stringResource(speedDisplay.labelRes()),
                    value = it,
                )
            }
            selection.elevationText?.let {
                SelectionValue(
                    label = stringResource(R.string.activity_detail_selection_altitude),
                    value = it,
                )
            }
        }
        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.activity_detail_selection_clear),
            )
        }
    }
}

@Composable
private fun SelectionValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Сплиты.
 *
 * Список, а не LazyColumn: экран уже прокручивается целиком, и вложенная ленивая прокрутка
 * либо перехватывала бы жест, либо требовала фиксированной высоты.
 */
@Composable
internal fun SplitsSection(
    splits: List<SplitUi>,
    selectedSplitNumber: Int?,
    speedDisplay: SpeedDisplay,
    onSplitClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            SplitHeader(
                text = stringResource(R.string.activity_detail_splits_number),
                modifier = Modifier.width(SplitNumberColumn),
            )
            SplitHeader(
                text = stringResource(
                    when (speedDisplay) {
                        SpeedDisplay.PACE -> R.string.activity_detail_splits_pace
                        SpeedDisplay.SPEED -> R.string.activity_detail_splits_speed
                    },
                ),
                modifier = Modifier.weight(1f),
            )
            SplitHeader(
                text = stringResource(R.string.activity_detail_splits_time),
                modifier = Modifier.width(64.dp),
            )
            SplitHeader(
                text = stringResource(R.string.activity_detail_splits_elevation),
                modifier = Modifier.width(56.dp),
            )
        }
        splits.forEach { split ->
            SplitRow(
                split = split,
                isSelected = split.number == selectedSplitNumber,
                onClick = { onSplitClick(split.number) },
            )
        }
    }
}

@Composable
private fun SplitHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun SplitRow(
    split: SplitUi,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        isSelected -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick),
    ) {
        // Полоска — фон строки, а не отдельный столбец: так сравнение сплитов читается
        // с одного взгляда и не отнимает места у чисел. matchParentSize берёт высоту у
        // соседней строки, поэтому её не приходится задавать числом.
        Row(modifier = Modifier.matchParentSize()) {
            val fraction = split.speedFraction.coerceIn(0.01f, 1f)
            Spacer(
                modifier = Modifier
                    .weight(fraction)
                    .fillMaxHeight()
                    .background(
                        if (split.isFastest) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        },
                    ),
            )
            if (fraction < 1f) Spacer(modifier = Modifier.weight(1f - fraction))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // В этой колонке живёт номер сплита, а у последнего — его длина,
                // поэтому ширина рассчитана на «0.33 km», а не на одну цифру.
                text = split.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.width(SplitNumberColumn),
            )
            Text(
                text = split.valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (split.isFastest) FontWeight.Bold else null,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = split.durationText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(64.dp),
            )
            Text(
                text = split.elevationText.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp),
            )
        }
    }
}

/**
 * Движение и остановки.
 *
 * Автоматические стоянки и ручные паузы показаны раздельно: первое — свойство маршрута,
 * второе — решение пользователя, и в одном числе они не значат ничего.
 */
@Composable
internal fun StopsSection(stops: StopsUi, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(100))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            ShareBar(fraction = stops.movingFraction, color = MaterialTheme.colorScheme.primary)
            ShareBar(
                fraction = stops.autoStopFraction,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            ShareBar(
                fraction = stops.pauseFraction,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StopsValue(
                label = stringResource(R.string.activity_detail_stops_moving_share),
                value = stops.movingShareText,
                modifier = Modifier.weight(1f),
            )
            StopsValue(
                label = stringResource(R.string.activity_detail_stops_total),
                value = stops.stoppedText,
                modifier = Modifier.weight(1f),
            )
            StopsValue(
                label = stringResource(R.string.activity_detail_stops_count),
                value = stops.countText,
                modifier = Modifier.weight(1f),
            )
            StopsValue(
                label = stringResource(R.string.activity_detail_stops_longest),
                value = stops.longestText,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RowScope.ShareBar(fraction: Float, color: Color) {
    if (fraction <= 0f) return
    Spacer(modifier = Modifier.weight(fraction).fillMaxHeight().background(color))
}

@Composable
private fun StopsValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Сводка по высоте: набор, сброс и диапазон. */
@Composable
internal fun ElevationSummary(elevation: ElevationSummaryUi, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StopsValue(
            label = stringResource(R.string.activity_detail_elevation_gain),
            value = elevation.gainText,
            modifier = Modifier.weight(1f),
        )
        StopsValue(
            label = stringResource(R.string.activity_detail_elevation_loss),
            value = elevation.lossText,
            modifier = Modifier.weight(1f),
        )
        StopsValue(
            label = stringResource(R.string.activity_detail_elevation_range),
            value = elevation.rangeText,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Столбец значений подписывается тем же словом, что и вся активность: темп или скорость. */
private fun SpeedDisplay.labelRes(): Int = when (this) {
    SpeedDisplay.PACE -> R.string.activity_detail_selection_pace
    SpeedDisplay.SPEED -> R.string.activity_detail_selection_speed
}
