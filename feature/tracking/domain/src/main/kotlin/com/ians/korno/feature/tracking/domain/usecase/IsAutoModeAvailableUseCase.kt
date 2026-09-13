package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.feature.tracking.domain.repository.TrackingController

class IsAutoModeAvailableUseCase(
    private val trackingController: TrackingController,
) {
    operator fun invoke(): Boolean = trackingController.isAutoModeAvailable()
}
