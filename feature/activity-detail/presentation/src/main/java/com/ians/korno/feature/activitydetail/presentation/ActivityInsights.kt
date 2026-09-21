package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.runtime.Immutable
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.countsSteps
import com.ians.korno.core.map.MapDistanceMarker
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.map.MapStopMarker

/**
 * Режим показа маршрута. Один и тот же трек, разные вопросы к нему: где шёл, где быстро,
 * где высоко, где стоял.
 */
internal enum class MapLens { KINDS, ROUTE, SPEED, ELEVATION, STOPS }

/**
 * Чем измерять движение.
 *
 * Пешие активности живут в темпе: «5:30 мин/км» человек сравнивает со своим опытом, а
 * «10,9 км/ч» — нет. Велосипед и автомобиль — наоборот.
 */
internal enum class SpeedDisplay { PACE, SPEED }

/**
 * Точка маршрута вместе с её местом в шкалах линз.
 *
 * Доли, а не цвета: палитру выбирает composable, который знает тему, и одна и та же
 * выборка обслуживает все линзы без пересчёта.
 */
@Immutable
internal data class RouteSampleUi(
    val point: MapPoint,
    val distanceMeters: Double,
    /** Место в шкале «Темп/скорость», 0..1; `null` — пауза или нет данных. */
    val speedFraction: Float?,
    /** Место в шкале «Высота», 0..1; `null` — высота не записана. */
    val elevationFraction: Float?,
)

/** Одно значение графика. `y == null` — разрыв: пауза, стоянка или нет данных. */
@Immutable
internal data class ChartValueUi(val x: Float, val y: Float?)

/**
 * Данные одного графика.
 *
 * Ось X у всех графиков экрана общая — доля пройденной дистанции, 0..1. Благодаря этому
 * касание в любом графике переводится в точку маршрута одним и тем же способом.
 */
@Immutable
internal data class InsightChartUi(
    val values: List<ChartValueUi>,
    val minY: Float,
    val maxY: Float,
    val averageY: Float?,
    /** Ось Y растёт вниз: меньший темп — быстрее, и он должен быть сверху. */
    val invertY: Boolean,
    val topLabel: String,
    val bottomLabel: String,
    val averageLabel: String?,
    /** Доли дистанции, на которых были остановки. */
    val stopMarks: List<Float>,
)

/** Подписи к цветовой шкале линзы: цвет без чисел ничего не сообщает. */
@Immutable
internal data class LensLegendUi(
    val lowLabel: String,
    val highLabel: String,
)

@Immutable
internal data class SplitUi(
    val number: Int,
    /** Номер сплита или длина неполного остатка. */
    val label: String,
    /** Темп или скорость — в зависимости от типа активности. */
    val valueText: String,
    val durationText: String,
    val elevationText: String?,
    /** Длина полоски относительно самого быстрого сплита, 0..1. */
    val speedFraction: Float,
    val isFastest: Boolean,
    val isPartial: Boolean,
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
)

@Immutable
internal data class StopsUi(
    val movingText: String,
    val stoppedText: String,
    val countText: String,
    val longestText: String,
    val movingShareText: String,
    val movingFraction: Float,
    val autoStopFraction: Float,
    val pauseFraction: Float,
    val hasManualPause: Boolean,
)

@Immutable
internal data class ElevationSummaryUi(
    val gainText: String,
    val lossText: String,
    val rangeText: String,
)

/** Точка, выбранная касанием графика или строкой сплита. */
@Immutable
internal data class TrackSelectionUi(
    val point: MapPoint,
    /** Доля дистанции — та же ось, что у графиков. */
    val x: Float,
    val distanceText: String,
    val timeText: String,
    val speedText: String?,
    val elevationText: String?,
)

internal val SegmentKind.speedDisplay: SpeedDisplay
    get() = if (countsSteps) SpeedDisplay.PACE else SpeedDisplay.SPEED

/** Строка разбивки по видам: сколько, как долго и как быстро на этом виде. */
@Immutable
internal data class KindSummaryUi(
    val kind: SegmentKind,
    val distanceText: String,
    val movingText: String,
    /** Темп для пеших видов, скорость для колёс. */
    val movementText: String,
    /** Только у пеших видов: шаги на велосипеде хранятся, но не показываются. */
    val stepsText: String?,
)

/** Участок записи в списке сегментов. */
@Immutable
internal data class SegmentUi(
    val index: Int,
    val kind: SegmentKind,
    val timeRangeText: String,
    val distanceText: String,
    val isManuallyEdited: Boolean,
)

/** Всё, что посчитано по треку и уже переведено в текст и доли. */
@Immutable
internal data class ActivityInsightsUi(
    val samples: List<RouteSampleUi>,
    val totalDistanceMeters: Double,
    val speedDisplay: SpeedDisplay,
    val availableLenses: List<MapLens>,
    val speedChart: InsightChartUi?,
    val elevationChart: InsightChartUi?,
    val speedLegend: LensLegendUi?,
    val elevationLegend: LensLegendUi?,
    val stopMarkers: List<MapStopMarker>,
    val distanceMarkers: List<MapDistanceMarker>,
    val splits: List<SplitUi>,
    val fastestSplitNumber: Int?,
    val stops: StopsUi?,
    val elevation: ElevationSummaryUi?,
)
