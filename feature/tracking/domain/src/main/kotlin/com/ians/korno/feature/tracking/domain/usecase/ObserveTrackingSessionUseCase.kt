package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.feature.tracking.domain.model.RecordingSession
import com.ians.korno.feature.tracking.domain.repository.TrackingController
import kotlinx.coroutines.flow.Flow

class ObserveTrackingSessionUseCase(
    private val trackingController: TrackingController,
) {
    operator fun invoke(): Flow<RecordingSession> = trackingController.observeSession()
}
