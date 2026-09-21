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
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.presentation.labelRes
import com.ians.korno.core.map.TrackOverlay
import com.ians.korno.core.map.withShadesFrom

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

/**
 * Цвета видов движения — палитра Okabe–Ito: различима при основных нарушениях
 * цветовосприятия и не пересекается с viridis и cividis соседних линз.
 */
internal fun SegmentKind.color(): Color? = when (this) {
    SegmentKind.ON_FOOT -> Color(0xFF009E73)
    SegmentKind.RUNNING -> Color(0xFFD55E00)
    SegmentKind.CYCLING -> Color(0xFF0072B2)
    SegmentKind.VEHICLE -> Color(0xFFCC79A7)
    SegmentKind.HIKING -> Color(0xFFE69F00)
    SegmentKind.UNKNOWN, SegmentKind.PAUSE -> null
}

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
    val routeKinds = state.routeKinds
    val lens = state.lens
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(samples, fallback, routeKinds, lens, neutral) {
        // Виды и сам маршрут смешанной записи рисуются по всем точкам: разбор построен
        // только по доминирующему виду, и без этого автобус пропал бы с карты.
        if (lens == MapLens.KINDS) {
            return@remember fallback.mapIndexed { index, point ->
                point.copy(shade = routeKinds.getOrNull(index)?.color())
            }
        }
        // Остальные линзы тоже красят полную линию, а не выборку разбора: в разборе нет
        // выбросов, и линия по нему прошла бы прямой через отбракованный участок. Точки
        // других видов смешанной записи разбор не покрывает — они уходят в нейтральный.
        if (samples == null) return@remember fallback
        when (lens) {
            MapLens.KINDS, MapLens.ROUTE -> fallback
            MapLens.SPEED -> fallback.withShadesFrom(
                samples.map { it.point.copy(shade = it.speedFraction?.let(SpeedRamp::sample)) },
                unmatched = neutral,
            )
            MapLens.ELEVATION -> fallback.withShadesFrom(
                samples.map { it.point.copy(shade = it.elevationFraction?.let(ElevationRamp::sample)) },
                unmatched = neutral,
            )
            // Линза остановок говорит о простое, поэтому сама линия уходит на второй план.
            MapLens.STOPS -> fallback.map { it.copy(shade = neutral) }
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
    MapLens.KINDS -> R.string.activity_detail_lens_kinds
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
    kinds: List<SegmentKind>,
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
        // Только встреченные виды: легенда про велосипед в пешей прогулке — шум.
        lens == MapLens.KINDS -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            kinds.forEach { kind ->
                kind.color()?.let { LegendDot(color = it, label = stringResource(kind.labelRes()), alpha = 1f) }
            }
        }
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
private fun LegendDot(color: Color, label: String, alpha: Float = 0.5f) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(100))
                .background(color.copy(alpha = alpha)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
