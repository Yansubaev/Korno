package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus

data class ProcessedTrack(
    val points: List<GeoPoint>,
    val statistics: TrackStatistics,
)

class TrackProcessor(
    private val outlierFilter: OutlierFilterUseCase = OutlierFilterUseCase(),
    private val smoothing: SmoothingUseCase = SmoothingUseCase(),
    private val stopDetection: StopDetectionUseCase = StopDetectionUseCase(),
    private val statisticsCalculator: StatisticsCalculatorUseCase = StatisticsCalculatorUseCase(),
) {
    fun process(
        points: List<GeoPoint>,
        activityType: ActivityType,
        config: TrackProcessingConfig = TrackProcessingConfig.forActivityType(activityType),
    ): ProcessedTrack {
        val processedPoints = processPoints(points, config)
        return ProcessedTrack(
            points = processedPoints,
            statistics = statisticsCalculator(processedPoints, config),
        )
    }

    /**
     * Вердикты по точкам без подсчёта статистики: живой записи статистика нужна
     * накопительная, а не пересчитанная с нуля (см. [IncrementalTrackProcessor]).
     */
    internal fun processPoints(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
    ): List<GeoPoint> = points
        .sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id))
        .splitWhen { previous, current ->
            previous.recordingStatus.isManualPause != current.recordingStatus.isManualPause
        }
        .flatMap { segment ->
            val filtered = outlierFilter(segment, config)
            // Остановки ищутся по отфильтрованным, но ещё не сглаженным точкам.
            // Пороги детектора выражены в погрешности исходного фикса, а сглаживание
            // эту погрешность как раз и размывает: соседние точки затягивают в стоянку
            // движение, которое началось после неё, и наоборот. На границе «стоял —
            // пошёл» это давало сдвиг вердикта на радиус окна.
            val detected = if (segment.first().recordingStatus.isManualPause) {
                // Ручное перемещение на паузе нужно сохранить для карты, но оно не
                // участвует ни в автопаузе, ни в расчёте остановок.
                filtered.map { it.copy(isStop = false) }
            } else {
                stopDetection(filtered, config)
            }
            smoothing(detected, config.smoothingRadius, config.maximumSegmentDurationMillis)
        }
}

private val RecordingStatus.isManualPause: Boolean
    get() = this == RecordingStatus.MANUAL_PAUSE

private inline fun <T> List<T>.splitWhen(crossinline boundary: (T, T) -> Boolean): List<List<T>> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<MutableList<T>>()
    forEach { item ->
        val current = result.lastOrNull()
        if (current == null || boundary(current.last(), item)) {
            result += mutableListOf(item)
        } else {
            current += item
        }
    }
    return result
}
