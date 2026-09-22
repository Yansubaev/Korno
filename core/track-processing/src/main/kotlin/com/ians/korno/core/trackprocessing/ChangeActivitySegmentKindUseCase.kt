package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.repository.TrackRepository

/**
 * Смена вида сегмента. Трек пересчитывается заново по сегментам — порогами нового вида, —
 * и вместе с ним дистанции сегментов, статистика и шаги.
 *
 * Живёт рядом с [FinalizeTrackUseCase], потому что входов у правки два: экран активности
 * (любой участок) и экран сохранения фиксированной записи, где участок один (`docs/adr/0004`).
 */
class ChangeActivitySegmentKindUseCase(
    private val trackRepository: TrackRepository,
    private val finalizeTrack: FinalizeTrackUseCase,
) {
    suspend operator fun invoke(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
        segmentIndex: Int,
        kind: SegmentKind,
    ): EmptyResult<DataError.Local> {
        val edited = segments.mapIndexed { index, segment ->
            if (index == segmentIndex) segment.copy(kind = kind, isManuallyEdited = true) else segment
        }
        val track = finalizeTrack(activity, points, edited)
        return trackRepository.replaceTrack(track.activity, track.points, track.segments)
    }
}
