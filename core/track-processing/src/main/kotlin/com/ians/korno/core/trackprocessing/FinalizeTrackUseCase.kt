package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.activityType
import com.ians.korno.core.domain.model.dominantKind
import com.ians.korno.core.domain.model.segmentKind

/** Итоговый трек: всё, что ложится в БД одной перезаписью активности. */
data class FinalizedTrack(
    val activity: Activity,
    val points: List<GeoPoint>,
    val segments: List<ActivitySegment>,
)

/**
 * Итоговый трек активности — при сохранении записи и после каждой правки. Единственное
 * место, где решается, как смешанная запись превращается в итоги: одинаково для
 * фиксированного режима (один сегмент) и авторежима.
 *
 * Время записи ([Activity.elapsedTimeMillis]) не трогается: его знает только тот, кто
 * вёл запись.
 *
 * @param segments закрытые сегменты активности по порядку; короткие сливаются здесь же.
 */
class FinalizeTrackUseCase(
    private val trackProcessor: TrackProcessor = TrackProcessor(),
    private val segmentDetector: SegmentDetector = SegmentDetector(),
) {
    operator fun invoke(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
    ): FinalizedTrack {
        val track = trackProcessor.processSegments(
            // Вердикты фильтров пересчитываются с нуля: `isOutlier` липкий, и точка,
            // отброшенная прежними порогами, осталась бы выбросом навсегда.
            points = points.map { it.copy(isOutlier = false, isStop = false) },
            segments = segmentDetector.mergeShortSegments(segments),
            configFor = { kind -> profileFor(activity, kind) },
        )
        val statistics = track.statistics
        return FinalizedTrack(
            activity = activity.copy(
                activityType = if (activity.isTypeChosenByUser) {
                    activity.activityType
                } else {
                    track.segments.dominantKind()?.activityType ?: ActivityType.OTHER
                },
                distanceMeters = statistics.distanceMeters,
                movingTimeMillis = statistics.movingTimeMillis,
                averageSpeedMetersPerSecond = statistics.averageSpeedMetersPerSecond,
                maxSpeedMetersPerSecond = statistics.maxSpeedMetersPerSecond,
                elevationGainMeters = statistics.elevationGainMeters,
                // В итог идут только шаги на ногах: при педалировании шагомер может считать
                // шаги, но человек их не делал.
                stepCount = activity.stepCount?.let {
                    track.segments.filter { it.kind == SegmentKind.ON_FOOT || it.kind == SegmentKind.RUNNING }
                        .sumOf { it.stepCount ?: 0 }
                },
            ),
            points = track.points,
            segments = track.segments,
        )
    }

    /**
     * Профиль записи сегмента. Выбранный пользователем тип уточняет вид: хайкинг и ходьба —
     * один вид для датчиков, но пороги у хайкинга свои.
     */
    private fun profileFor(activity: Activity, kind: SegmentKind): TrackProcessingConfig {
        val base = if (activity.isTypeChosenByUser && activity.activityType.segmentKind == kind) {
            TrackProcessingConfig.forActivityType(activity.activityType)
        } else {
            TrackProcessingConfig.forSegmentKind(kind)
        }
        return base.withAutoPauseSensitivity(activity.autoPauseSensitivity)
    }
}
