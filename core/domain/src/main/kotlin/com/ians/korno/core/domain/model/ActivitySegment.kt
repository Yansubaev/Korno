package com.ians.korno.core.domain.model

/**
 * Чем человек передвигался на участке — единственное понятие о том, что он делал.
 * Перечень шире того, что умеют датчики: хайкинг они не выдают никогда, его ставит
 * только человек (см. `docs/adr/0005`).
 */
enum class SegmentKind {
    ON_FOOT,
    RUNNING,
    CYCLING,
    VEHICLE,

    /** Датчики его не выдают: только ручная правка (см. [applyHikingRule]). */
    HIKING,

    /** Детектор ещё не ответил или ответить не смог. */
    UNKNOWN,

    /** Ручная пауза: движения в это время не записывали, вида у участка нет. */
    PAUSE,
}

/** Виды, по которым есть что считать: на паузе и до ответа детектора движения нет. */
val SegmentKind.isMovement: Boolean
    get() = this != SegmentKind.UNKNOWN && this != SegmentKind.PAUSE

/** Виды на своих ногах, где шагомер что-то значит: велосипед и машина шагов не считают. */
val SegmentKind.countsSteps: Boolean
    get() = this == SegmentKind.ON_FOOT || this == SegmentKind.RUNNING || this == SegmentKind.HIKING

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

/**
 * Вид, на который пришлась наибольшая дистанция; не-движение не в счёт. `null` — определить
 * не из чего. Он и есть лицо активности: иконка, подпись, темп или скорость, вид спорта
 * при экспорте.
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
fun Activity.toSingleSegment(kind: SegmentKind): ActivitySegment =
    ActivitySegment(
        activityId = id,
        kind = kind,
        startTimeEpochMillis = startTimeEpochMillis,
        endTimeEpochMillis = endTimeEpochMillis,
        stepCount = stepCount,
        distanceMeters = distanceMeters,
        // Ноль у активности — время ещё не считали (запись только началась), а не «нисколько
        // не двигались»: такому участку время потом посчитает пересчёт трека.
        movingTimeMillis = movingTimeMillis.takeIf { it > 0L },
    )

/**
 * Правило хайкинга: есть хоть один сегмент [SegmentKind.HIKING] — все пешие сегменты
 * записи читаются как хайкинг.
 *
 * Хайкинг датчики не выдают, поэтому его наличие само по себе означает «человек сказал».
 * Правило выводится из данных — ни метки, ни момента правки хранить не нужно, — и
 * переживает редекод: новые пешие куски выходят из декодера как `ON_FOOT`, а правило
 * накладывается поверх. Обратное не распространяется: бег детектор говорит сам.
 */
fun List<ActivitySegment>.applyHikingRule(): List<ActivitySegment> {
    if (none { it.kind == SegmentKind.HIKING }) return this
    return map { if (it.kind == SegmentKind.ON_FOOT) it.copy(kind = SegmentKind.HIKING) else it }
}
