package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.feature.tracking.domain.repository.TrackingController

class ResumeTrackingUseCase(
    private val trackingController: TrackingController,
) {
    operator fun invoke() = trackingController.resume()
}
