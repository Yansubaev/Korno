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
        val ordered = points.sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id))
        val processedPoints = ordered
            .splitWhen { previous, current ->
                previous.recordingStatus.isManualPause != current.recordingStatus.isManualPause
            }
            .flatMap { segment ->
                val filtered = outlierFilter(segment, config)
                val smoothed = smoothing(filtered, config.smoothingRadius)
                if (segment.first().recordingStatus.isManualPause) {
                    // Ручное перемещение на паузе нужно сохранить для карты, но оно не
                    // участвует ни в автопаузе, ни в расчёте остановок.
                    smoothed.map { it.copy(isStop = false) }
                } else {
                    stopDetection(smoothed, config)
                }
            }
        return ProcessedTrack(
            points = processedPoints,
            statistics = statisticsCalculator(processedPoints, config.elevationNoiseMeters),
        )
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
