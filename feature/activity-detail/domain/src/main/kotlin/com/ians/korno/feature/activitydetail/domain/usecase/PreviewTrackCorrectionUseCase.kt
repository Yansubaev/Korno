package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.trackprocessing.FinalizeTrackUseCase

class PreviewTrackCorrectionUseCase(
    private val finalizeTrack: FinalizeTrackUseCase,
) {
    operator fun invoke(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
        removedPointIds: Set<Long>,
    ): Double = finalizeTrack.corrected(activity, points, segments, removedPointIds).activity.distanceMeters
}
