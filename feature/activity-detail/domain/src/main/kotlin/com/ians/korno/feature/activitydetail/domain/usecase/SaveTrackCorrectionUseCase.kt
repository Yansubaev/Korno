package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.trackprocessing.FinalizeTrackUseCase
import com.ians.korno.core.trackprocessing.FinalizedTrack

class SaveTrackCorrectionUseCase(
    private val trackRepository: TrackRepository,
    private val finalizeTrack: FinalizeTrackUseCase,
) {
    suspend operator fun invoke(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
        removedPointIds: Set<Long>,
    ): EmptyResult<DataError.Local> {
        val track = finalizeTrack.corrected(activity, points, segments, removedPointIds)
        return trackRepository.replaceTrack(track.activity, track.points, track.segments)
    }
}

/** Трек без удалённых точек: и превью, и сохранение правки считают его одинаково. */
internal fun FinalizeTrackUseCase.corrected(
    activity: Activity,
    points: List<GeoPoint>,
    segments: List<ActivitySegment>,
    removedPointIds: Set<Long>,
): FinalizedTrack = this(
    activity = activity.copy(isManuallyEdited = true),
    points = points.filterNot { it.id in removedPointIds }.map { it.copy(isManuallyEdited = true) },
    segments = segments,
)
