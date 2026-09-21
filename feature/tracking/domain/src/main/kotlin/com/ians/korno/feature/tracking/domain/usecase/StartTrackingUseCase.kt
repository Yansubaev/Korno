package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.feature.tracking.domain.repository.TrackingController

class StartTrackingUseCase(
    private val trackingController: TrackingController,
) {
    suspend operator fun invoke(kind: SegmentKind, autoMode: Boolean) =
        trackingController.start(kind, autoMode)
}
