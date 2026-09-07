package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.map.TrackOverlay

/**
 * Палитра линзы «Темп/скорость» — viridis.
 *
 * Она воспринимается монотонно (светлее = быстрее) и остаётся различимой при основных
 * нарушениях цветовосприятия, поэтому цвет здесь несёт величину, а не эмоцию: жёлтый
 * означает «быстро», а не «внимание».
 */
private val SpeedRamp = listOf(
    Color(0xFF440154),
    Color(0xFF3B518B),
    Color(0xFF21918C),
    Color(0xFF5EC962),
    Color(0xFFFDE725),
)

/** Палитра линзы «Высота» — cividis: тёмное внизу, светлое наверху. */
private val ElevationRamp = listOf(
    Color(0xFF00224E),
    Color(0xFF35456C),
    Color(0xFF666970),
    Color(0xFF9A9068),
    Color(0xFFFFEA46),
)

/** Цвет по доле 0..1 — линейная интерполяция между опорными точками палитры. */
private fun List<Color>.sample(fraction: Float): Color {
    if (size < 2) return firstOrNull() ?: Color.Unspecified
    val position = fraction.coerceIn(0f, 1f) * (size - 1)
    val index = position.toInt().coerceAtMost(size - 2)
    return lerp(this[index], this[index + 1], position - index)
}

/**
 * Маршрут, окрашенный по активной линзе.
 *
 * Когда разбора нет (слишком короткий трек), рисуется сырой маршрут: показать линию без
 * аналитики лучше, чем пустую карту.
 */
@Composable
internal fun rememberLensRoute(state: ActivityDetailState): List<MapPoint> {
    val samples = state.insights?.samples
    val fallback = state.routePoints
    val lens = state.lens
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(samples, fallback, lens, neutral) {
        if (samples == null) return@remember fallback
        when (lens) {
            MapLens.ROUTE -> samples.map(RouteSampleUi::point)
            MapLens.SPEED -> samples.map { sample ->
                sample.point.copy(shade = sample.speedFraction?.let(SpeedRamp::sample))
            }
            MapLens.ELEVATION -> samples.map { sample ->
                sample.point.copy(shade = sample.elevationFraction?.let(ElevationRamp::sample))
            }
            // Линза остановок говорит о простое, поэтому сама линия уходит на второй план.
            MapLens.STOPS -> samples.map { it.point.copy(shade = neutral) }
        }
    }
}

/** Отметки, остановки и выделение — то, что показывает активная линза. */
internal fun lensOverlay(
    insights: ActivityInsightsUi?,
    lens: MapLens,
    selection: TrackSelectionUi?,
    highlightedSection: List<MapPoint>,
): TrackOverlay = TrackOverlay(
    stops = if (lens == MapLens.STOPS) insights?.stopMarkers.orEmpty() else emptyList(),
    distanceMarkers = if (lens == MapLens.ROUTE) insights?.distanceMarkers.orEmpty() else emptyList(),
    highlight = selection?.point,
    highlightedSection = highlightedSection,
)

/** Отрезок маршрута выбранного сплита — для подсветки на карте. */
@Composable
internal fun rememberSplitSection(
    insights: ActivityInsightsUi?,
    selectedSplitNumber: Int?,
): List<MapPoint> = remember(insights, selectedSplitNumber) {
    val split = insights?.splits?.firstOrNull { it.number == selectedSplitNumber }
        ?: return@remember emptyList()
    insights.samples
        .filter { it.distanceMeters in split.startDistanceMeters..split.endDistanceMeters }
        .map(RouteSampleUi::point)
}

@Composable
internal fun MapLensSelector(
    lenses: List<MapLens>,
    selected: MapLens,
    speedDisplay: SpeedDisplay,
    onSelect: (MapLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lenses.size < 2) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        lenses.forEach { lens ->
            FilterChip(
                selected = lens == selected,
                onClick = { onSelect(lens) },
                label = { Text(stringResource(lens.labelRes(speedDisplay))) },
            )
        }
    }
}

private fun MapLens.labelRes(speedDisplay: SpeedDisplay): Int = when (this) {
    MapLens.ROUTE -> R.string.activity_detail_lens_route
    // Линза называется тем же словом, каким подписаны её значения: «Темп» для пеших
    // активностей и «Скорость» для остальных.
    MapLens.SPEED -> when (speedDisplay) {
        SpeedDisplay.PACE -> R.string.activity_detail_lens_pace
        SpeedDisplay.SPEED -> R.string.activity_detail_lens_speed
    }
    MapLens.ELEVATION -> R.string.activity_detail_lens_elevation
    MapLens.STOPS -> R.string.activity_detail_lens_stops
}

/**
 * Легенда активной линзы.
 *
 * Цветовая шкала без чисел не сообщает ничего: без подписей «тёплое» и «холодное» — просто
 * разные цвета, и по ним нельзя сказать, быстро это или медленно.
 */
@Composable
internal fun LensLegend(
    lens: MapLens,
    insights: ActivityInsightsUi,
    modifier: Modifier = Modifier,
) {
    val legend = when (lens) {
        MapLens.SPEED -> insights.speedLegend
        MapLens.ELEVATION -> insights.elevationLegend
        else -> null
    }
    when {
        legend != null -> GradientLegend(
            ramp = if (lens == MapLens.SPEED) SpeedRamp else ElevationRamp,
            legend = legend,
            modifier = modifier,
        )
        lens == MapLens.STOPS -> StopsLegend(modifier = modifier)
        else -> Unit
    }
}

@Composable
private fun GradientLegend(
    ramp: List<Color>,
    legend: LensLegendUi,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(100))
                .background(Brush.horizontalGradient(ramp)),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = legend.lowLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = legend.highLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StopsLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            label = stringResource(R.string.activity_detail_legend_auto_stop),
        )
        LegendDot(
            color = MaterialTheme.colorScheme.tertiary,
            label = stringResource(R.string.activity_detail_legend_manual_pause),
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(100))
                .background(color.copy(alpha = 0.5f)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
