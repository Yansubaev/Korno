package com.ians.korno.feature.tracking.domain.model

import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingMode
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.countsSteps

data class RecordingSession(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val activityId: Long? = null,
    val startTimeEpochMillis: Long = 0L,
    val pausedAccumulatedMillis: Long = 0L,
    val currentPauseStartMillis: Long? = null,
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    val currentSpeedMetersPerSecond: Float = 0f,
    val maxSpeedMetersPerSecond: Float = 0f,
    val elevationGainMeters: Double = 0.0,
    val lastPoint: GeoPoint? = null,
    /** `null` — шаги для этого вида движения не считаются (см. [countsSteps]). */
    val stepCount: Int? = null,
    /** Как идёт запись. [RecordingMode.IMPORTED] сюда не попадает: импорт не записывают. */
    val recordingMode: RecordingMode = RecordingMode.FIXED,
    /**
     * Вид текущего сегмента. В фиксированном режиме он выбран до старта и не меняется,
     * в авторежиме начинается с [SegmentKind.UNKNOWN] и его уточняет детектор.
     */
    val segmentKind: SegmentKind = SegmentKind.UNKNOWN,
    /** Живая дистанция по встреченным видам в авторежиме. */
    val segmentDistancesMeters: Map<SegmentKind, Double> = emptyMap(),
) {
    val isAutoMode: Boolean
        get() = recordingMode == RecordingMode.AUTO

    val isActive: Boolean
        get() = status == RecordingStatus.WAITING_FOR_GPS ||
            status == RecordingStatus.RECORDING ||
            status == RecordingStatus.MANUAL_PAUSE ||
            status == RecordingStatus.AUTO_PAUSE
}
