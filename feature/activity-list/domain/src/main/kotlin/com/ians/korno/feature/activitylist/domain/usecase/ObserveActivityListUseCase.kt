package com.ians.korno.feature.activitylist.domain.usecase

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.movingTime
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Чипы списка в порядке показа — виды движения, а не типы записей: у авто-записи видов
 * несколько, и тип, выбранный по главному из них, ничего не говорит об остальных.
 * `UNKNOWN` последним: это ведро для нераспознанного. `PAUSE` чипом не бывает.
 */
val ACTIVITY_LIST_CHIP_KINDS = listOf(
    SegmentKind.RUNNING,
    SegmentKind.CYCLING,
    SegmentKind.ON_FOOT,
    SegmentKind.VEHICLE,
    SegmentKind.UNKNOWN,
)

/** Записи под выбранным чипом и суммы по ним. */
data class ActivityListSelection(
    val activities: List<Activity>,
    val distanceMeters: Double,
    val movingTimeMillis: Long,
)

/**
 * Активности вместе с сегментами всей базы.
 *
 * Запись попадает под чип, если в ней есть хоть один сегмент этого вида, любой длины;
 * тип записи в фильтре не участвует вовсе. Под чипом считаются только сегменты его вида,
 * поэтому чипы не складываются в «Все»: метры смешанной записи посчитаны под каждым её
 * видом.
 */
class ActivityListSnapshot(
    private val activities: List<Activity>,
    segments: List<ActivitySegment>,
) {
    private val segmentsByActivity = segments.groupBy(ActivitySegment::activityId)

    /** Чипы, под которыми есть хоть одна запись. По всей базе: период и поиск их не двигают. */
    val chipKinds: List<SegmentKind> =
        ACTIVITY_LIST_CHIP_KINDS.filter { kind -> activities.any { it.hasSegmentOf(kind) } }

    /**
     * @param kind выбранный чип; `null` — «Все»: сумма по строкам активностей целиком,
     *   вместе с транспортом и нераспознанным, сегменты при этом не читаются вовсе.
     * @param include период и поиск — по активности целиком, сегменты по границе не режутся.
     */
    fun select(kind: SegmentKind?, include: (Activity) -> Boolean): ActivityListSelection {
        val selected = activities.filter { (kind == null || it.hasSegmentOf(kind)) && include(it) }
        if (kind == null) {
            return ActivityListSelection(
                activities = selected,
                distanceMeters = selected.sumOf(Activity::distanceMeters),
                movingTimeMillis = selected.sumOf(Activity::movingTimeMillis),
            )
        }
        val parts = selected.flatMap { activity -> activity.segments().filter { it.kind == kind } }
        return ActivityListSelection(
            activities = selected,
            distanceMeters = parts.sumOf(ActivitySegment::distanceMeters),
            movingTimeMillis = parts.sumOf(ActivitySegment::movingTime),
        )
    }

    private fun Activity.hasSegmentOf(kind: SegmentKind): Boolean = segments().any { it.kind == kind }

    private fun Activity.segments(): List<ActivitySegment> = segmentsByActivity[id].orEmpty()
}

class ObserveActivityListUseCase(
    private val trackRepository: TrackRepository,
) {
    operator fun invoke(): Flow<ActivityListSnapshot> = combine(
        trackRepository.observeActivities(),
        trackRepository.observeAllSegments(),
    ) { activities, segments ->
        ActivityListSnapshot(
            activities = (activities as? Result.Success)?.data.orEmpty(),
            segments = (segments as? Result.Success)?.data.orEmpty(),
        )
    }
}
