package com.ians.korno.feature.activitydetail.presentation

import android.content.Context
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.map.MapDistanceMarker
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.map.MapStopMarker
import com.ians.korno.core.presentation.formatDistance
import com.ians.korno.core.presentation.formatElevation
import com.ians.korno.core.presentation.formatPace
import com.ians.korno.core.presentation.formatSpeed
import com.ians.korno.core.presentation.formatTimerDuration
import com.ians.korno.core.trackanalytics.TrackAnalysis
import com.ians.korno.core.trackanalytics.TrackSample
import com.ians.korno.core.trackanalytics.TrackSplit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Больше точек в график не помещается физически, а перерисовывать их — впустую. */
private const val CHART_RESOLUTION = 240

/** Отметок на карте: больше сливается в кашу даже на полноэкранной карте. */
private const val MAX_DISTANCE_MARKERS = 30

/**
 * Хвосты графика обрезаются по процентилям: минута на светофоре растянула бы ось темпа
 * так, что остальная тренировка легла бы в одну линию.
 */
private const val CHART_LOW_PERCENTILE = 0.02
private const val CHART_HIGH_PERCENTILE = 0.98

private const val MINIMUM_SPLIT_BAR = 0.15f

internal fun Context.toInsightsUi(
    analysis: TrackAnalysis,
    activityType: ActivityType,
    system: MeasurementSystem,
): ActivityInsightsUi? {
    if (analysis.isEmpty || analysis.totalDistanceMeters <= 0.0) return null

    val display = activityType.speedDisplay
    val total = analysis.totalDistanceMeters
    val speedScale = analysis.speedScale
    val elevationScale = analysis.elevationScale

    val samples = analysis.samples.map { sample ->
        RouteSampleUi(
            point = MapPoint(
                latitude = sample.latitude,
                longitude = sample.longitude,
                isPaused = sample.isPaused || sample.isStop,
            ),
            distanceMeters = sample.distanceMeters,
            speedFraction = sample.speedMetersPerSecond
                ?.takeIf { !sample.isPaused }
                ?.let { speedScale?.fractionOf(it) },
            elevationFraction = sample.altitudeMeters?.let { elevationScale?.fractionOf(it) },
        )
    }

    val splits = toSplitsUi(analysis, display, system)
    return ActivityInsightsUi(
        samples = samples,
        totalDistanceMeters = total,
        speedDisplay = display,
        availableLenses = buildList {
            add(MapLens.ROUTE)
            if (speedScale != null) add(MapLens.SPEED)
            if (elevationScale != null) add(MapLens.ELEVATION)
            if (analysis.stops.segments.isNotEmpty()) add(MapLens.STOPS)
        },
        speedChart = toSpeedChart(analysis, display, system),
        elevationChart = toElevationChart(analysis, system),
        speedLegend = speedScale?.let {
            LensLegendUi(
                lowLabel = speedText(it.slowestMetersPerSecond, display, system).orEmpty(),
                highLabel = speedText(it.fastestMetersPerSecond, display, system).orEmpty(),
            )
        },
        elevationLegend = elevationScale?.let {
            LensLegendUi(
                lowLabel = formatElevation(it.lowestMeters, system).text,
                highLabel = formatElevation(it.highestMeters, system).text,
            )
        },
        stopMarkers = analysis.stops.segments.map { stop ->
            MapStopMarker(
                point = MapPoint(stop.latitude, stop.longitude),
                durationMillis = stop.durationMillis,
                isManualPause = stop.isManualPause,
            )
        },
        distanceMarkers = toDistanceMarkers(analysis),
        splits = splits,
        fastestSplitNumber = analysis.fastestSplit?.number,
        stops = toStopsUi(analysis),
        elevation = analysis.elevation?.let {
            ElevationSummaryUi(
                gainText = formatElevation(it.gainMeters, system).text,
                lossText = formatElevation(it.lossMeters, system).text,
                rangeText = getString(
                    R.string.activity_detail_elevation_range_value,
                    formatElevation(it.minimumMeters, system).value,
                    formatElevation(it.maximumMeters, system).text,
                ),
            )
        },
    )
}

/** Точка маршрута под указанной долей дистанции: касание графика и строка сплита ведут сюда же. */
internal fun Context.toSelectionUi(
    analysis: TrackAnalysis,
    fraction: Float,
    activityType: ActivityType,
    system: MeasurementSystem,
): TrackSelectionUi? {
    if (analysis.isEmpty || analysis.totalDistanceMeters <= 0.0) return null
    val target = fraction.coerceIn(0f, 1f) * analysis.totalDistanceMeters
    val sample = analysis.samples.nearestByDistance(target) ?: return null
    val display = activityType.speedDisplay

    return TrackSelectionUi(
        point = MapPoint(sample.latitude, sample.longitude),
        x = (sample.distanceMeters / analysis.totalDistanceMeters).toFloat().coerceIn(0f, 1f),
        distanceText = formatDistance(sample.distanceMeters, system).text,
        timeText = formatTimerDuration(sample.timeOffsetMillis),
        speedText = when {
            sample.isPaused -> getString(R.string.activity_detail_selection_paused)
            sample.isStop -> getString(R.string.activity_detail_selection_stopped)
            else -> sample.speedMetersPerSecond?.let { speedText(it, display, system) }
        },
        elevationText = sample.altitudeMeters?.let { formatElevation(it, system).text },
    )
}

/**
 * Дистанция вдоль трека не убывает, поэтому подходит двоичный поиск: линейный проход по
 * тысячам точек на каждое движение пальца по графику заметен.
 */
private fun List<TrackSample>.nearestByDistance(targetMeters: Double): TrackSample? {
    if (isEmpty()) return null
    var low = 0
    var high = lastIndex
    while (low < high) {
        val middle = (low + high) / 2
        if (this[middle].distanceMeters < targetMeters) low = middle + 1 else high = middle
    }
    val candidate = this[low]
    val previous = getOrNull(low - 1) ?: return candidate
    return if (
        abs(previous.distanceMeters - targetMeters) <= abs(candidate.distanceMeters - targetMeters)
    ) {
        previous
    } else {
        candidate
    }
}

private fun Context.toSpeedChart(
    analysis: TrackAnalysis,
    display: SpeedDisplay,
    system: MeasurementSystem,
): InsightChartUi? {
    val total = analysis.totalDistanceMeters
    val values = analysis.samples.map { sample ->
        ChartValueUi(
            x = (sample.distanceMeters / total).toFloat(),
            y = sample.speedMetersPerSecond
                ?.takeIf { !sample.isPaused }
                ?.let { chartSpeedValue(it, display, system) },
        )
    }.downsampled()
    if (values.none { it.y != null }) return null

    val range = values.mapNotNull(ChartValueUi::y).percentileRange() ?: return null
    val averageSpeed = analysis.stops.movingMillis
        .takeIf { it > 0L }
        ?.let { total / (it / 1_000.0) }
    val invert = display == SpeedDisplay.PACE
    // Сверху всегда «быстрее»: у темпа это меньшее число, у скорости — большее.
    val top = if (invert) range.start else range.endInclusive
    val bottom = if (invert) range.endInclusive else range.start

    return InsightChartUi(
        values = values,
        minY = range.start,
        maxY = range.endInclusive,
        averageY = averageSpeed?.let { chartSpeedValue(it, display, system) },
        invertY = invert,
        topLabel = speedText(top.toSpeed(display, system), display, system).orEmpty(),
        bottomLabel = speedText(bottom.toSpeed(display, system), display, system).orEmpty(),
        averageLabel = averageSpeed?.let { speedText(it, display, system) },
        stopMarks = analysis.stops.segments.map {
            (it.distanceFromStartMeters / total).toFloat().coerceIn(0f, 1f)
        },
    )
}

private fun Context.toElevationChart(
    analysis: TrackAnalysis,
    system: MeasurementSystem,
): InsightChartUi? {
    val total = analysis.totalDistanceMeters
    val values = analysis.samples.map { sample ->
        ChartValueUi(
            x = (sample.distanceMeters / total).toFloat(),
            y = sample.altitudeMeters?.let { toElevationUnits(it, system) },
        )
    }.downsampled()
    val present = values.mapNotNull(ChartValueUi::y)
    if (present.isEmpty()) return null

    // Профиль высоты не обрезается по процентилям: вершина и низшая точка — как раз то,
    // ради чего на него смотрят.
    val minimum = present.min()
    val maximum = present.max()
    val padding = ((maximum - minimum) * 0.1f).coerceAtLeast(1f)

    return InsightChartUi(
        values = values,
        minY = minimum - padding,
        maxY = maximum + padding,
        averageY = null,
        invertY = false,
        topLabel = formatElevationUnits(maximum, system),
        bottomLabel = formatElevationUnits(minimum, system),
        averageLabel = null,
        stopMarks = emptyList(),
    )
}

private fun Context.toSplitsUi(
    analysis: TrackAnalysis,
    display: SpeedDisplay,
    system: MeasurementSystem,
): List<SplitUi> {
    if (analysis.splits.isEmpty()) return emptyList()
    val speeds = analysis.splits.map(TrackSplit::speedMetersPerSecond)
    val slowest = speeds.min()
    val fastest = speeds.max()
    val span = fastest - slowest

    return analysis.splits.map { split ->
        SplitUi(
            number = split.number,
            label = if (split.isPartial) {
                formatDistance(split.distanceMeters, system, decimals = 2).text
            } else {
                split.number.toString()
            },
            valueText = speedText(split.speedMetersPerSecond, display, system)
                ?: getString(R.string.activity_detail_value_unavailable),
            durationText = formatTimerDuration(split.movingMillis),
            elevationText = split.elevationDeltaMeters
                ?.takeIf { abs(it) >= 1.0 }
                ?.let { signedElevation(it, system) },
            // Полоска всегда видна: сплит с нулевой длиной читался бы как ошибка вёрстки.
            speedFraction = if (span <= 0.0) {
                1f
            } else {
                MINIMUM_SPLIT_BAR + (1f - MINIMUM_SPLIT_BAR) *
                    ((split.speedMetersPerSecond - slowest) / span).toFloat()
            },
            isFastest = split.number == analysis.fastestSplit?.number,
            isPartial = split.isPartial,
            startDistanceMeters = split.startDistanceMeters,
            endDistanceMeters = split.endDistanceMeters,
        )
    }
}

private fun Context.toStopsUi(analysis: TrackAnalysis): StopsUi? {
    val stops = analysis.stops
    if (stops.totalMillis <= 0L) return null
    val total = stops.totalMillis.toFloat()

    return StopsUi(
        movingText = formatTimerDuration(stops.movingMillis),
        stoppedText = formatTimerDuration(stops.stoppedMillis),
        countText = stops.count.toString(),
        longestText = formatTimerDuration(stops.longestMillis),
        movingShareText = String.format(
            Locale.getDefault(),
            "%d%%",
            (100.0 * stops.movingMillis / stops.totalMillis).roundToInt(),
        ),
        movingFraction = stops.movingMillis / total,
        autoStopFraction = stops.autoStopMillis / total,
        pauseFraction = stops.manualPauseMillis / total,
        hasManualPause = stops.manualPauseMillis > 0L,
    )
}

/**
 * Отметки ставятся на границах сплитов. Когда их слишком много, показывается каждая
 * n-я: подписи всё равно перекрыли бы друг друга.
 */
private fun toDistanceMarkers(analysis: TrackAnalysis): List<MapDistanceMarker> {
    val fullSplits = analysis.splits.filterNot(TrackSplit::isPartial)
    if (fullSplits.isEmpty()) return emptyList()
    val step = (fullSplits.size + MAX_DISTANCE_MARKERS - 1) / MAX_DISTANCE_MARKERS

    return fullSplits
        .filterIndexed { index, _ -> (index + 1) % step == 0 }
        .mapNotNull { split ->
            val sample = analysis.samples.nearestByDistance(split.endDistanceMeters) ?: return@mapNotNull null
            MapDistanceMarker(
                point = MapPoint(sample.latitude, sample.longitude),
                label = split.number.toString(),
            )
        }
}

private fun Context.speedText(
    metersPerSecond: Double,
    display: SpeedDisplay,
    system: MeasurementSystem,
): String? = when (display) {
    SpeedDisplay.PACE -> formatPace(metersPerSecond, system)?.text
    SpeedDisplay.SPEED -> formatSpeed(metersPerSecond, system).text
}

/** Значение оси Y: секунды на единицу для темпа, км/ч или mph для скорости. */
private fun chartSpeedValue(
    metersPerSecond: Double,
    display: SpeedDisplay,
    system: MeasurementSystem,
): Float? {
    if (metersPerSecond <= 0.0 || !metersPerSecond.isFinite()) return null
    return when (display) {
        SpeedDisplay.PACE -> (system.unitMeters / metersPerSecond).toFloat()
        SpeedDisplay.SPEED -> when (system) {
            MeasurementSystem.METRIC -> metersPerSecond * 3.6
            MeasurementSystem.IMPERIAL -> metersPerSecond * 2.2369362921
        }.toFloat()
    }
}

/** Обратное преобразование: подписи оси строятся из тех же форматтеров, что и остальной экран. */
private fun Float.toSpeed(display: SpeedDisplay, system: MeasurementSystem): Double = when (display) {
    SpeedDisplay.PACE -> if (this <= 0f) 0.0 else system.unitMeters / this
    SpeedDisplay.SPEED -> when (system) {
        MeasurementSystem.METRIC -> this / 3.6
        MeasurementSystem.IMPERIAL -> this / 2.2369362921
    }
}

private val MeasurementSystem.unitMeters: Double
    get() = when (this) {
        MeasurementSystem.METRIC -> 1_000.0
        MeasurementSystem.IMPERIAL -> 1_609.344
    }

private fun toElevationUnits(meters: Double, system: MeasurementSystem): Float = when (system) {
    MeasurementSystem.METRIC -> meters
    MeasurementSystem.IMPERIAL -> meters * 3.280839895
}.toFloat()

private fun Context.formatElevationUnits(value: Float, system: MeasurementSystem): String {
    val meters = when (system) {
        MeasurementSystem.METRIC -> value.toDouble()
        MeasurementSystem.IMPERIAL -> value / 3.280839895
    }
    return formatElevation(meters, system).text
}

private fun Context.signedElevation(meters: Double, system: MeasurementSystem): String {
    val formatted = formatElevation(abs(meters), system)
    val sign = if (meters >= 0) "+" else "−"
    return "$sign${formatted.text}"
}

private fun List<ChartValueUi>.downsampled(): List<ChartValueUi> {
    if (size <= CHART_RESOLUTION) return this
    return List(CHART_RESOLUTION) { bucket ->
        val from = bucket * size / CHART_RESOLUTION
        val to = ((bucket + 1) * size / CHART_RESOLUTION).coerceAtLeast(from + 1)
        val slice = subList(from, to)
        val present = slice.mapNotNull(ChartValueUi::y)
        ChartValueUi(
            x = slice.map(ChartValueUi::x).average().toFloat(),
            // Ведро без значений остаётся разрывом: усреднить его с соседями значило бы
            // замазать паузу непрерывной линией.
            y = if (present.isEmpty()) null else present.average().toFloat(),
        )
    }
}

private fun List<Float>.percentileRange(): ClosedFloatingPointRange<Float>? {
    if (isEmpty()) return null
    val sorted = sorted()
    val low = sorted[((sorted.size - 1) * CHART_LOW_PERCENTILE).toInt().coerceIn(0, sorted.lastIndex)]
    val high = sorted[((sorted.size - 1) * CHART_HIGH_PERCENTILE).toInt().coerceIn(0, sorted.lastIndex)]
    // Вырожденный диапазон превращает график в прямую по краю области: раздвигаем.
    return if (high - low < 0.001f) (low - 1f)..(high + 1f) else low..high
}
