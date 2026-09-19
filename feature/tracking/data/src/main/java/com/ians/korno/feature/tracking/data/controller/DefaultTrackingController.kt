package com.ians.korno.feature.tracking.data.controller

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.data.locationtracking.diagnostics.ActivityRecognitionSignalSource
import com.ians.korno.feature.tracking.data.service.LocationTrackingService
import com.ians.korno.feature.tracking.data.session.TrackingSessionCoordinator
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import com.ians.korno.feature.tracking.domain.repository.TrackingController
import kotlinx.coroutines.flow.Flow

internal class DefaultTrackingController(
    private val context: Context,
    private val coordinator: TrackingSessionCoordinator,
    private val activityRecognition: ActivityRecognitionSignalSource,
) : TrackingController {

    override fun observeSession(): Flow<RecordingSession> = coordinator.session

    override fun prepare() {
        startServiceForeground(LocationTrackingService.ACTION_PREPARE)
    }

    override fun cancelPreparation() {
        sendServiceAction(LocationTrackingService.ACTION_CANCEL_PREPARATION)
    }

    override suspend fun start(activityType: ActivityType, autoMode: Boolean) {
        // Разрешение могли отозвать между выбором и стартом: без распознавания авторежим слеп.
        val activityId = coordinator.beginSession(activityType, autoMode && activityRecognition.isAvailable())
        if (activityId != null) {
            startServiceForeground(LocationTrackingService.ACTION_START)
        }
    }

    override fun isAutoModeAvailable(): Boolean = activityRecognition.isAvailable()

    override suspend fun changeSegmentKind(kind: SegmentKind) = coordinator.changeSegmentKind(kind)

    override fun pause() {
        sendServiceAction(LocationTrackingService.ACTION_PAUSE)
    }

    override fun resume() {
        sendServiceAction(LocationTrackingService.ACTION_RESUME)
    }

    override suspend fun stop(): Result<Long, DataError.Local> {
        val activityId = coordinator.session.value.activityId
        sendServiceAction(LocationTrackingService.ACTION_STOP)
        return Result.Success(activityId ?: 0L)
    }

    override suspend fun recoverActiveSession() {
        if (coordinator.recoverActiveSessionIfNeeded()) {
            startServiceForeground(LocationTrackingService.ACTION_START)
        } else if (coordinator.session.value.activityId == null) {
            activityRecognition.removeStaleUpdates()
        }
    }

    private fun startServiceForeground(action: String) {
        val intent = Intent(context, LocationTrackingService::class.java).setAction(action)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun sendServiceAction(action: String) {
        context.startService(Intent(context, LocationTrackingService::class.java).setAction(action))
    }
}
