package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.feature.tracking.domain.repository.TrackingController

class PrepareTrackingUseCase(
    private val trackingController: TrackingController,
) {
    operator fun invoke() = trackingController.prepare()
}
