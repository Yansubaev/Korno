package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.feature.tracking.domain.repository.TrackingController

class StartTrackingUseCase(
    private val trackingController: TrackingController,
) {
    suspend operator fun invoke(activityType: ActivityType, autoMode: Boolean) =
        trackingController.start(activityType, autoMode)
}
