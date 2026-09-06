package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.feature.tracking.domain.repository.TrackingController

class StopTrackingUseCase(
    private val trackingController: TrackingController,
) {
    suspend operator fun invoke(): Result<Long, DataError.Local> = trackingController.stop()
}
