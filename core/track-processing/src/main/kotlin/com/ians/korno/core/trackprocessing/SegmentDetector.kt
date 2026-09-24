package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.SegmentKind

/**
 * Пороги детектора сегментов: подбираются прогоном по логам в `:tools:track-calibration`.
 * Вид движения решает декодер ([ActivityModeDecoder.liveVerdict]), здесь — только то,
 * когда его вердикт режет запись.
 */
data class SegmentDetectorConfig(
    /** Сколько новый вердикт должен продержаться, чтобы смена вида засчиталась. */
    val confirmWindowMillis: Long = 40_000L,
    /** Сегменты короче этого при сохранении сливаются с соседом. */
    val minSegmentDurationMillis: Long = 60_000L,
    /** Сегменты с меньшей дистанцией при сохранении сливаются с соседом. */
    val minSegmentDistanceMeters: Double = 50.0,
)

sealed interface DetectorSignal {
    val timestampMillis: Long

    /** Кумулятивное показание `TYPE_STEP_COUNTER` — вход декодера, не детектора. */
    data class StepCounter(override val timestampMillis: Long, val counter: Long) : DetectorSignal

    /** Живой вердикт декодера на [timestampMillis]: вид и с какого момента путь в нём. */
    data class Verdict(
        override val timestampMillis: Long,
        val kind: SegmentKind,
        val sinceMillis: Long,
    ) : DetectorSignal

    data class Paused(override val timestampMillis: Long) : DetectorSignal

    data class Resumed(override val timestampMillis: Long) : DetectorSignal

    /** Пользователь сам сменил вид текущего сегмента. */
    data class ManualKind(override val timestampMillis: Long, val kind: SegmentKind) : DetectorSignal
}

/** Решения детектора — то, что пишется в диагностический лог. */
sealed interface DetectorEvent {
    val timestampMillis: Long

    /** Вердикт разошёлся с текущим видом: пошло окно гистерезиса. */
    data class CandidateStarted(override val timestampMillis: Long, val kind: SegmentKind) : DetectorEvent

    /** Вердикт не продержался окно. */
    data class CandidateDropped(override val timestampMillis: Long, val kind: SegmentKind) : DetectorEvent

    /**
     * Смена вида подтверждена. [boundaryMillis] — где путь декодера сменил вид, а не
     * момент подтверждения: сегмент режется задним числом.
     */
    data class SegmentConfirmed(
        override val timestampMillis: Long,
        val from: SegmentKind?,
        val to: SegmentKind,
        val boundaryMillis: Long,
        val isManual: Boolean,
    ) : DetectorEvent
}

/** [firstSeenMillis] отсчитывает окно, [boundaryMillis] — граница из последнего вердикта. */
data class SegmentCandidate(val kind: SegmentKind, val firstSeenMillis: Long, val boundaryMillis: Long)

data class SegmentDetectorState(
    /** `null` — ни одного подтверждённого вердикта ещё не было. */
    val currentKind: SegmentKind? = null,
    val candidate: SegmentCandidate? = null,
    val isPaused: Boolean = false,
)

data class DetectorStep(val state: SegmentDetectorState, val events: List<DetectorEvent>)

/**
 * Машина состояний живых сегментов: чистая функция «состояние + сигнал → состояние + решения».
 * Вид приходит из декодера — он же решает итоговую разметку, — здесь только гистерезис.
 *
 * - Смена вида засчитывается, только если вердикт держится [SegmentDetectorConfig.confirmWindowMillis];
 *   граница — там, где путь декодера на этот вид перешёл.
 * - Ручная пауза сбрасывает окно; вердикты за паузу не учитываются.
 * - Ручная поправка ставит границу «сейчас». Спорить с ней декодер перестаёт сам — до
 *   логической паузы (`ADR 0008`), таймера здесь нет.
 */
class SegmentDetector(private val config: SegmentDetectorConfig = SegmentDetectorConfig()) {

    fun reduce(state: SegmentDetectorState, signal: DetectorSignal): DetectorStep {
        val events = mutableListOf<DetectorEvent>()
        val now = signal.timestampMillis
        val verdict = when (signal) {
            is DetectorSignal.Paused -> {
                state.candidate?.let { events += DetectorEvent.CandidateDropped(now, it.kind) }
                return DetectorStep(state.copy(isPaused = true, candidate = null), events)
            }
            is DetectorSignal.Resumed -> return DetectorStep(state.copy(isPaused = false, candidate = null), events)
            is DetectorSignal.ManualKind -> {
                events += DetectorEvent.SegmentConfirmed(now, state.currentKind, signal.kind, now, isManual = true)
                return DetectorStep(state.copy(currentKind = signal.kind, candidate = null), events)
            }
            is DetectorSignal.StepCounter -> return DetectorStep(state, events)
            is DetectorSignal.Verdict -> if (state.isPaused) return DetectorStep(state, events) else signal
        }

        val candidate = state.candidate
        var next = when {
            verdict.kind == state.currentKind -> {
                candidate?.let { events += DetectorEvent.CandidateDropped(now, it.kind) }
                state.copy(candidate = null)
            }
            candidate?.kind == verdict.kind -> state.copy(candidate = candidate.copy(boundaryMillis = verdict.sinceMillis))
            else -> {
                candidate?.let { events += DetectorEvent.CandidateDropped(now, it.kind) }
                events += DetectorEvent.CandidateStarted(now, verdict.kind)
                state.copy(candidate = SegmentCandidate(verdict.kind, now, verdict.sinceMillis))
            }
        }

        val pending = next.candidate
        if (pending != null && now - pending.firstSeenMillis >= config.confirmWindowMillis) {
            events += DetectorEvent.SegmentConfirmed(
                timestampMillis = now,
                from = next.currentKind,
                to = pending.kind,
                boundaryMillis = pending.boundaryMillis,
                isManual = false,
            )
            next = next.copy(currentKind = pending.kind, candidate = null)
        }
        return DetectorStep(next, events)
    }

    /**
     * Слияние при сохранении: сегмент короче [SegmentDetectorConfig.minSegmentDurationMillis]
     * или с дистанцией меньше [SegmentDetectorConfig.minSegmentDistanceMeters] поглощается
     * более длинным соседом, соседи одного вида склеиваются. Сегменты, поправленные
     * пользователем, не поглощаются. Ожидаются закрытые сегменты одной активности.
     */
    fun mergeShortSegments(segments: List<ActivitySegment>): List<ActivitySegment> {
        val result = coalesce(segments.sortedBy(ActivitySegment::startTimeEpochMillis)).toMutableList()
        while (result.size > 1) {
            val shortIndex = result.indices
                .filter { index -> result[index].let { !it.isManuallyEdited && it.isShort() } }
                .minByOrNull { result[it].durationMillis() }
                ?: break
            val neighborIndex = listOf(shortIndex - 1, shortIndex + 1)
                .filter { it in result.indices }
                .maxBy { result[it].durationMillis() }
            val merged = result[neighborIndex].absorb(result[shortIndex])
            result[minOf(shortIndex, neighborIndex)] = merged
            result.removeAt(maxOf(shortIndex, neighborIndex))
            val coalesced = coalesce(result)
            result.clear()
            result += coalesced
        }
        return result
    }

    /**
     * ponytail: «короткий» — это либо по времени, либо по дистанции, и с тех пор как фикс
     * без доплера и курса отбраковывается целиком ([OutlierFilterUseCase]), у сегмента
     * может быть двадцать минут настоящей поездки и почти нулевая измеренная дистанция.
     * Такой сегмент поглощается соседом, и автобус сохраняется как продолжение прогулки.
     *
     * Сейчас это не стреляет: сохраняются сегменты декодера, а он считает дистанцию по
     * сырым точкам, не по отфильтрованным. Защита держится на этом совпадении. Развести
     * условия — дистанцию проверять только у коротких по времени, — когда появится запись,
     * где поглощение видно.
     */
    private fun ActivitySegment.isShort(): Boolean =
        durationMillis() < config.minSegmentDurationMillis || distanceMeters < config.minSegmentDistanceMeters

    /** Склейка соседей одного вида: границы, за которыми больше нет разницы, не нужны. */
    internal fun coalesce(segments: List<ActivitySegment>): List<ActivitySegment> =
        segments.fold(mutableListOf()) { acc, segment ->
            val last = acc.lastOrNull()
            if (last != null && last.kind == segment.kind) acc[acc.lastIndex] = last.absorb(segment) else acc += segment
            acc
        }
}

/**
 * Дистанция сегмента по уже обработанным точкам: отрезки между соседними не-выбросами
 * внутри его времени.
 */
fun segmentDistanceMeters(points: List<GeoPoint>, startMillis: Long, endMillis: Long): Double =
    points.filter { !it.isOutlier && it.timestampEpochMillis in startMillis..endMillis }
        .zipWithNext()
        .sumOf { (a, b) -> haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }

private fun ActivitySegment.durationMillis(): Long = (endTimeEpochMillis ?: startTimeEpochMillis) - startTimeEpochMillis

/** Вид, id и ручная пометка остаются от поглощающего сегмента. */
private fun ActivitySegment.absorb(other: ActivitySegment): ActivitySegment = copy(
    startTimeEpochMillis = minOf(startTimeEpochMillis, other.startTimeEpochMillis),
    endTimeEpochMillis = maxOf(endTimeEpochMillis ?: startTimeEpochMillis, other.endTimeEpochMillis ?: other.startTimeEpochMillis),
    stepCount = if (stepCount == null && other.stepCount == null) null else (stepCount ?: 0) + (other.stepCount ?: 0),
    distanceMeters = distanceMeters + other.distanceMeters,
)
