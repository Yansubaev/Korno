package com.ians.korno.feature.tracking.presentation

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.map.MapPoint

internal data class TrackingRecordingState(
    val recordingStatus: RecordingStatus = RecordingStatus.IDLE,
    val activityType: ActivityType = ActivityType.RUNNING,
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
)
