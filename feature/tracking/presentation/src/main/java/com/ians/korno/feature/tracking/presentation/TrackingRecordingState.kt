package com.ians.korno.feature.tracking.presentation

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.map.MapPoint

internal data class TrackingRecordingState(
    val recordingStatus: RecordingStatus = RecordingStatus.IDLE,
    val activityType: ActivityType = ActivityType.RUNNING,
    /** Вид текущего сегмента в авторежиме; `null` — фиксированный режим. */
    val segmentKind: SegmentKind? = null,
    /** Дистанция по встреченным видам в единицах пользователя, без `UNKNOWN`. */
    val segmentDistances: List<Pair<SegmentKind, Double>> = emptyList(),
    val segmentMenuOpen: Boolean = false,
    val elapsedTimeMillis: Long = 0L,
    val movingTimeMillis: Long = 0L,
    val distanceValue: Double = 0.0,
    val currentSpeedValue: Double = 0.0,
    val averageSpeedValue: Double = 0.0,
    val elevationGainValue: Double = 0.0,
    /** `null` — тип активности шаги не считает. */
    val stepCount: Int? = null,
    val measurementSystem: MeasurementSystem = MeasurementSystem.METRIC,
    val routePoints: List<MapPoint> = emptyList(),
    val currentPoint: MapPoint? = null,
    val mapSheetMode: MapSheetMode = MapSheetMode.SPLIT,
    val finishDialogOpen: Boolean = false,
    val finishedActivityId: Long? = null,
) {
    /** Для пешего вида главная метрика — темп, для колёс — скорость. */
    val showsPace: Boolean
        get() = segmentKind == SegmentKind.ON_FOOT || segmentKind == SegmentKind.RUNNING
}
