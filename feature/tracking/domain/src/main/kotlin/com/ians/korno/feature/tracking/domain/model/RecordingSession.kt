package com.ians.korno.feature.tracking.domain.model

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind

data class RecordingSession(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val activityId: Long? = null,
    val activityType: ActivityType = ActivityType.OTHER,
    val startTimeEpochMillis: Long = 0L,
    val pausedAccumulatedMillis: Long = 0L,
    val currentPauseStartMillis: Long? = null,
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    val currentSpeedMetersPerSecond: Float = 0f,
    val maxSpeedMetersPerSecond: Float = 0f,
    val elevationGainMeters: Double = 0.0,
    val lastPoint: GeoPoint? = null,
    /** `null` — шаги для этого типа активности не считаются (см. [ActivityType.countsSteps]). */
    val stepCount: Int? = null,
    /**
     * Авторежим: вид движения определяет детектор, и [activityType] следует за видом
     * текущего сегмента, а не за выбором пользователя.
     */
    val isAutoMode: Boolean = false,
    /** Вид текущего сегмента в авторежиме; `null` — фиксированный режим. */
    val segmentKind: SegmentKind? = null,
    /** Живая дистанция по встреченным видам в авторежиме. */
    val segmentDistancesMeters: Map<SegmentKind, Double> = emptyMap(),
) {
    val isActive: Boolean
        get() = status == RecordingStatus.WAITING_FOR_GPS ||
            status == RecordingStatus.RECORDING ||
            status == RecordingStatus.MANUAL_PAUSE ||
            status == RecordingStatus.AUTO_PAUSE
}
