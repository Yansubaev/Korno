package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.activityType
import com.ians.korno.core.domain.model.dominantKind
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.trackprocessing.SegmentDetector
import com.ians.korno.core.trackprocessing.TrackProcessingConfig
import com.ians.korno.core.trackprocessing.TrackProcessor

/**
 * Смена вида сегмента на экране активности. Трек пересчитывается заново по сегментам —
 * порогами нового вида, — и вместе с ним дистанции сегментов, статистика и шаги.
 *
 * Тип активности следует за доминирующим видом, только если пользователь его не менял:
 * «Хайкинг», поставленный на экране итога, пересчёт не перетирает.
 */
class ChangeActivitySegmentKindUseCase(
    private val trackRepository: TrackRepository,
    private val trackProcessor: TrackProcessor,
    private val segmentDetector: SegmentDetector = SegmentDetector(),
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
        // Вердикты фильтров пересчитываются с нуля: `isOutlier` липкий, и точка, отброшенная
        // пешими порогами, так и осталась бы выбросом в транспортном сегменте.
        val track = trackProcessor.processSegments(
            points = points.map { it.copy(isOutlier = false, isStop = false) },
            segments = segmentDetector.mergeShortSegments(edited),
            configFor = TrackProcessingConfig::forSegmentKind,
        )
        val statistics = track.statistics
        val derivedType = segments.dominantKind()?.activityType ?: ActivityType.OTHER
        val footSteps = track.segments
            .filter { it.kind == SegmentKind.ON_FOOT || it.kind == SegmentKind.RUNNING }
            .mapNotNull(ActivitySegment::stepCount)
        return trackRepository.replaceTrack(
            activity = activity.copy(
                activityType = if (activity.activityType == derivedType) {
                    track.segments.dominantKind()?.activityType ?: ActivityType.OTHER
                } else {
                    activity.activityType
                },
                distanceMeters = statistics.distanceMeters,
                movingTimeMillis = statistics.movingTimeMillis,
                averageSpeedMetersPerSecond = statistics.averageSpeedMetersPerSecond,
                maxSpeedMetersPerSecond = statistics.maxSpeedMetersPerSecond,
                elevationGainMeters = statistics.elevationGainMeters,
                stepCount = if (activity.stepCount == null) null else footSteps.sum(),
            ),
            points = track.points,
            segments = track.segments,
        )
    }
}
