package com.ians.korno.core.domain.model

/**
 * Грубый вид движения, который различают датчики. Не путать с [ActivityType] —
 * пользовательской меткой: хайкинг от прогулки по датчикам не отличить.
 */
enum class SegmentKind {
    ON_FOOT,
    RUNNING,
    CYCLING,
    VEHICLE,

    /** Детектор его не выдаёт: только активности типа [ActivityType.OTHER]. */
    UNKNOWN,

    /** Ручная пауза: движения в это время не записывали, вида у участка нет. */
    PAUSE,
}

/** Виды, по которым есть что считать: на паузе и до ответа детектора движения нет. */
val SegmentKind.isMovement: Boolean
    get() = this != SegmentKind.UNKNOWN && this != SegmentKind.PAUSE

/** Участок записи с одним видом движения. Точки привязываются к нему по времени. */
data class ActivitySegment(
    val id: Long = 0L,
    val activityId: Long = 0L,
    val kind: SegmentKind,
    val startTimeEpochMillis: Long,
    /** `null` — сегмент открыт: запись ещё идёт. */
    val endTimeEpochMillis: Long? = null,
    /** `null` — шаги не считались. */
    val stepCount: Int? = null,
    val distanceMeters: Double = 0.0,
    /** `null` — время в движении по участку не считали: запись старше миграции БД 9 → 10. */
    val movingTimeMillis: Long? = null,
    val isManuallyEdited: Boolean = false,
)

/**
 * Время в движении участка. Там, где его не считали, берётся вся длительность: ручная
 * пауза из неё уже вырезана отдельным участком, а остальное уточнится при первом же
 * пересчёте трека.
 */
val ActivitySegment.movingTime: Long
    get() = movingTimeMillis ?: ((endTimeEpochMillis ?: startTimeEpochMillis) - startTimeEpochMillis)

val ActivityType.segmentKind: SegmentKind
    get() = when (this) {
        ActivityType.WALKING, ActivityType.HIKING -> SegmentKind.ON_FOOT
        ActivityType.RUNNING -> SegmentKind.RUNNING
        ActivityType.CYCLING -> SegmentKind.CYCLING
        ActivityType.DRIVING -> SegmentKind.VEHICLE
        ActivityType.OTHER -> SegmentKind.UNKNOWN
    }

/** Тип активности, который соответствует виду: обратное к [segmentKind] там, где оно однозначно. */
val SegmentKind.activityType: ActivityType
    get() = when (this) {
        SegmentKind.ON_FOOT -> ActivityType.WALKING
        SegmentKind.RUNNING -> ActivityType.RUNNING
        SegmentKind.CYCLING -> ActivityType.CYCLING
        SegmentKind.VEHICLE -> ActivityType.DRIVING
        SegmentKind.UNKNOWN, SegmentKind.PAUSE -> ActivityType.OTHER
    }

/**
 * Вид, на который пришлась наибольшая дистанция; не-движение не в счёт. `null` — определить
 * не из чего. По нему авторежим выводит тип активности.
 */
fun List<ActivitySegment>.dominantKind(): SegmentKind? =
    filter { it.kind.isMovement }
        .groupBy(ActivitySegment::kind)
        .maxByOrNull { (_, segments) -> segments.sumOf(ActivitySegment::distanceMeters) }
        ?.key

/**
 * Точки каждого сегмента по времени. Точки до начала первого сегмента достаются ему,
 * после начала последнего — последнему: край записи не должен теряться из-за того, что
 * первый фикс пришёл чуть раньше старта.
 */
fun List<ActivitySegment>.slicePoints(points: List<GeoPoint>): List<Pair<ActivitySegment, List<GeoPoint>>> {
    val ordered = sortedBy(ActivitySegment::startTimeEpochMillis)
    val sortedPoints = points.sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id))
    return ordered.mapIndexed { index, segment ->
        val next = ordered.getOrNull(index + 1)
        segment to sortedPoints.filter { point ->
            (index == 0 || point.timestampEpochMillis >= segment.startTimeEpochMillis) &&
                (next == null || point.timestampEpochMillis < next.startTimeEpochMillis)
        }
    }
}

/**
 * Один сегмент на всю активность — так пишутся фиксированный режим и импорт.
 * То же правило, что и в миграции БД 5 → 6.
 */
fun Activity.toSingleSegment(): ActivitySegment =
    ActivitySegment(
        activityId = id,
        kind = activityType.segmentKind,
        startTimeEpochMillis = startTimeEpochMillis,
        endTimeEpochMillis = endTimeEpochMillis,
        stepCount = stepCount,
        distanceMeters = distanceMeters,
        // Ноль у активности — время ещё не считали (запись только началась), а не «нисколько
        // не двигались»: такому участку время потом посчитает пересчёт трека.
        movingTimeMillis = movingTimeMillis.takeIf { it > 0L },
    )
