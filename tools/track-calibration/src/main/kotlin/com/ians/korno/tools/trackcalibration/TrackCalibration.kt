package com.ians.korno.tools.trackcalibration

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.trackprocessing.TrackProcessingConfig
import com.ians.korno.core.trackprocessing.TrackProcessor
import com.ians.korno.core.trackprocessing.TrackSource
import com.ians.korno.core.trackprocessing.TrackStatistics
import com.ians.korno.core.trackprocessing.haversineMeters

/** Один трек, прогнанный через pipeline при заданной конфигурации. */
data class TrackRun(
    val outlierCount: Int,
    val stopCount: Int,
    val statistics: TrackStatistics,
)

/**
 * Прогон трека при одной конфигурации.
 *
 * Источник всегда [TrackSource.RAW], даже для собственного экспорта: калибруются как раз
 * те шаги, которые для своего файла пропускаются, и пропустить их здесь значило бы мерить
 * пустоту. Поэтому же трек, выгруженный из Korno, для калибровки хуже сырого — он уже
 * сглажен один раз.
 */
fun runPipeline(
    points: List<GeoPoint>,
    activityType: ActivityType,
    config: TrackProcessingConfig,
): TrackRun {
    val processed = TrackProcessor().process(points, activityType, config, TrackSource.RAW)
    return TrackRun(
        outlierCount = processed.points.count(GeoPoint::isOutlier),
        stopCount = countStopRuns(processed.points),
        statistics = processed.statistics,
    )
}

/**
 * Остановок столько, сколько непрерывных серий помеченных точек, а не сколько самих точек:
 * пользователь считает стоянки, а не сэмплы внутри них.
 */
private fun countStopRuns(points: List<GeoPoint>): Int {
    var count = 0
    var inStop = false
    points.filterNot(GeoPoint::isOutlier).forEach { point ->
        if (point.isStop && !inStop) count++
        inStop = point.isStop
    }
    return count
}

/** Дистанция по сырым точкам, без фильтрации и сглаживания — верхняя оценка. */
fun rawDistanceMeters(points: List<GeoPoint>): Double =
    points.sortedBy(GeoPoint::timestampEpochMillis).zipWithNext().sumOf { (first, second) ->
        haversineMeters(first.latitude, first.longitude, second.latitude, second.longitude)
    }

/** Качество сигнала: по нему видно, на какие пороги трек вообще способен ответить. */
data class SignalQuality(
    val accuracyPercentiles: Percentiles?,
    val intervalSecondsPercentiles: Percentiles?,
    val pointsWithoutAccuracy: Int,
)

data class Percentiles(val p50: Double, val p90: Double, val max: Double)

fun signalQuality(points: List<GeoPoint>): SignalQuality {
    val sorted = points.sortedBy(GeoPoint::timestampEpochMillis)
    val accuracies = sorted.mapNotNull { it.accuracyMeters?.toDouble() }
    val intervals = sorted.zipWithNext()
        .map { (first, second) -> (second.timestampEpochMillis - first.timestampEpochMillis) / 1_000.0 }
        .filter { it > 0.0 }
    return SignalQuality(
        accuracyPercentiles = percentilesOf(accuracies),
        intervalSecondsPercentiles = percentilesOf(intervals),
        pointsWithoutAccuracy = sorted.count { it.accuracyMeters == null },
    )
}

private fun percentilesOf(values: List<Double>): Percentiles? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    fun at(fraction: Double): Double =
        sorted[((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)]
    return Percentiles(p50 = at(0.5), p90 = at(0.9), max = sorted.last())
}

/** Один порог и значения, по которым его имеет смысл прогнать. */
class Sweep(
    val name: String,
    val currentValue: (TrackProcessingConfig) -> Double,
    val values: (TrackProcessingConfig) -> List<Double>,
    val apply: (TrackProcessingConfig, Double) -> TrackProcessingConfig,
)

/**
 * Пороги, которые действительно решают, как выглядит трек. Остальные поля конфигурации
 * либо производные, либо не влияют на итог настолько, чтобы их стоило подбирать глазами.
 */
val SWEEPS: List<Sweep> = listOf(
    Sweep(
        name = "maximumAccuracyMeters",
        currentValue = { it.maximumAccuracyMeters },
        values = { listOf(20.0, 30.0, 40.0, 60.0, 100.0) },
        apply = { config, value -> config.copy(maximumAccuracyMeters = value) },
    ),
    Sweep(
        name = "outlierAccuracySigmas",
        currentValue = { it.outlierAccuracySigmas },
        values = { listOf(1.0, 2.0, 3.0, 4.0, 6.0) },
        apply = { config, value -> config.copy(outlierAccuracySigmas = value) },
    ),
    Sweep(
        name = "processNoiseAcceleration",
        currentValue = { it.processNoiseAccelerationMetersPerSecondSquared },
        // Главный и, по сути, единственный параметр фильтра: чем он меньше, тем упрямее
        // модель движения и тем сильнее срезаются повороты; чем больше — тем ближе трек
        // к сырым фиксам со всем их дрожанием.
        values = { listOf(0.1, 0.25, 0.5, 1.0, 2.0) },
        apply = { config, value ->
            config.copy(processNoiseAccelerationMetersPerSecondSquared = value)
        },
    ),
    Sweep(
        name = "outlierGateChiSquare",
        currentValue = { it.outlierGateChiSquare },
        // Квантили χ² с двумя степенями свободы: 90%, 95%, 99%, 99.9%, 99.99%.
        values = { listOf(4.605, 5.991, 9.210, 13.816, 18.421) },
        apply = { config, value -> config.copy(outlierGateChiSquare = value) },
    ),
    Sweep(
        name = "positionNoiseFloorMeters",
        currentValue = { it.positionNoiseFloorMeters },
        values = { listOf(0.0, 1.0, 3.0, 5.0, 10.0) },
        apply = { config, value -> config.copy(positionNoiseFloorMeters = value) },
    ),
    Sweep(
        name = "noiseDeadbandFactor",
        currentValue = { it.noiseDeadbandFactor },
        values = { listOf(0.0, 0.25, 0.5, 0.75, 1.0) },
        apply = { config, value -> config.copy(noiseDeadbandFactor = value) },
    ),
    Sweep(
        name = "stopSpeedMetersPerSecond",
        currentValue = { it.stopSpeedMetersPerSecond },
        // Порог возобновления двигается вместе с порогом остановки: конфигурация требует
        // resume >= stop, и без этого половина значений просто не построилась бы.
        values = { config -> listOf(0.5, 0.75, 1.0, 1.5, 2.0).map { config.stopSpeedMetersPerSecond * it } },
        apply = { config, value ->
            config.copy(
                stopSpeedMetersPerSecond = value,
                resumeSpeedMetersPerSecond = maxOf(config.resumeSpeedMetersPerSecond, value * 1.5),
            )
        },
    ),
    Sweep(
        name = "stopRadiusMeters",
        currentValue = { it.stopRadiusMeters },
        values = { listOf(6.0, 8.0, 12.0, 16.0, 24.0) },
        apply = { config, value -> config.copy(stopRadiusMeters = value) },
    ),
    Sweep(
        name = "minimumStopDurationSeconds",
        currentValue = { it.minimumStopDurationMillis / 1_000.0 },
        values = { listOf(5.0, 10.0, 20.0, 30.0) },
        apply = { config, value ->
            val millis = (value * 1_000).toLong()
            config.copy(
                minimumStopDurationMillis = millis,
                stopWindowMillis = maxOf(config.stopWindowMillis, millis),
            )
        },
    ),
)
