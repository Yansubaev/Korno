package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.feature.tracking.domain.repository.TrackingController

class RecoverActiveSessionUseCase(
    private val trackingController: TrackingController,
) {
    suspend operator fun invoke() = trackingController.recoverActiveSession()
}
