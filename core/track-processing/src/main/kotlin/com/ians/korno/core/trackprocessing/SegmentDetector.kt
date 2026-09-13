package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognizedActivity
import com.ians.korno.core.domain.model.SegmentKind

/**
 * Все пороги детектора сегментов в одном месте: подбираются прогоном по логам в
 * `:tools:track-calibration`, стартовые значения — оптимистичная вера документации.
 */
data class SegmentDetectorConfig(
    /** Сколько новый вердикт должен продержаться, чтобы смена вида засчиталась. */
    val confirmWindowMillis: Long = 40_000L,
    /** Сколько после ручной поправки игнорируются противоречащие ей вердикты. */
    val manualOverrideHoldMillis: Long = 120_000L,
    /** Вердикт распознавания с меньшей уверенностью ничего не решает. */
    val minConfidencePercent: Int = 50,
    /** Окно, по которому считается каденс шагов. */
    val cadenceWindowMillis: Long = 20_000L,
    /** Каденс, с которого пеший вердикт считается бегом. */
    val runningCadenceStepsPerMinute: Double = 140.0,
    /** Пеший вердикт на такой скорости — тряска в транспорте, а не ходьба. */
    val maxOnFootSpeedMetersPerSecond: Double = 7.0,
    /** Велосипедный вердикт на такой скорости — транспорт. */
    val maxCyclingSpeedMetersPerSecond: Double = 18.0,
    /** Сегменты короче этого при сохранении сливаются с соседом. */
    val minSegmentDurationMillis: Long = 60_000L,
    /** Сегменты с меньшей дистанцией при сохранении сливаются с соседом. */
    val minSegmentDistanceMeters: Double = 50.0,
)

sealed interface DetectorSignal {
    val timestampMillis: Long

    data class Recognized(
        override val timestampMillis: Long,
        val activity: RecognizedActivity,
        val confidencePercent: Int,
    ) : DetectorSignal

    /** Кумулятивное показание `TYPE_STEP_COUNTER`. */
    data class StepCounter(override val timestampMillis: Long, val counter: Long) : DetectorSignal

    /** Скорость после фильтра Калмана. */
    data class Speed(override val timestampMillis: Long, val metersPerSecond: Double) : DetectorSignal

    data class Paused(override val timestampMillis: Long) : DetectorSignal

    data class Resumed(override val timestampMillis: Long) : DetectorSignal

    /** Пользователь сам сменил вид текущего сегмента. */
    data class ManualKind(override val timestampMillis: Long, val kind: SegmentKind) : DetectorSignal
}

enum class RejectionReason { LOW_CONFIDENCE, NOT_MOVEMENT, SPEED, MANUAL_HOLD, PAUSED }

/** Решения детектора — то, что пишется в диагностический лог. */
sealed interface DetectorEvent {
    val timestampMillis: Long

    /** Вердикт разошёлся с текущим видом: пошло окно гистерезиса. */
    data class CandidateStarted(override val timestampMillis: Long, val kind: SegmentKind) : DetectorEvent

    /** Вердикт не продержался окно. */
    data class CandidateDropped(override val timestampMillis: Long, val kind: SegmentKind) : DetectorEvent

    data class VerdictRejected(
        override val timestampMillis: Long,
        val activity: RecognizedActivity,
        val confidencePercent: Int,
        val reason: RejectionReason,
    ) : DetectorEvent

    /**
     * Смена вида подтверждена. [boundaryMillis] — момент первого признака, а не
     * подтверждения: сегмент режется задним числом.
     */
    data class SegmentConfirmed(
        override val timestampMillis: Long,
        val from: SegmentKind?,
        val to: SegmentKind,
        val boundaryMillis: Long,
        val isManual: Boolean,
    ) : DetectorEvent
}

data class SegmentCandidate(val kind: SegmentKind, val sinceMillis: Long)

data class SegmentDetectorState(
    /** `null` — ни одного подтверждённого вердикта ещё не было. */
    val currentKind: SegmentKind? = null,
    val candidate: SegmentCandidate? = null,
    /** Последний вердикт распознавания: он действует, пока не придёт следующий. */
    val recognized: DetectorSignal.Recognized? = null,
    val stepReadings: List<DetectorSignal.StepCounter> = emptyList(),
    val speedMetersPerSecond: Double? = null,
    val isPaused: Boolean = false,
    val manualHoldUntilMillis: Long? = null,
)

data class DetectorStep(val state: SegmentDetectorState, val events: List<DetectorEvent>)

/**
 * Детектор вида движения: чистая функция «состояние + сигнал → состояние + решения».
 *
 * - Смена вида засчитывается, только если вердикт держится [SegmentDetectorConfig.confirmWindowMillis];
 *   граница ставится на момент первого признака.
 * - `STILL` и неуверенные вердикты нейтральны: сегмент не рвут и окно не сбрасывают —
 *   остановки дело автопаузы.
 * - Ручная пауза сбрасывает окно; вердикты за паузу не учитываются.
 * - Ручная поправка ставит границу «сейчас» и на время удержания глушит противоречащие вердикты.
 */
class SegmentDetector(private val config: SegmentDetectorConfig = SegmentDetectorConfig()) {

    fun reduce(state: SegmentDetectorState, signal: DetectorSignal): DetectorStep {
        val events = mutableListOf<DetectorEvent>()
        val now = signal.timestampMillis
        var next = when (signal) {
            is DetectorSignal.Paused -> {
                state.candidate?.let { events += DetectorEvent.CandidateDropped(now, it.kind) }
                return DetectorStep(state.copy(isPaused = true, candidate = null), events)
            }
            is DetectorSignal.Resumed -> {
                // Окно начинается заново: ни вердикт, ни каденс до паузы о настоящем не говорят.
                return DetectorStep(
                    state.copy(isPaused = false, candidate = null, recognized = null, stepReadings = emptyList()),
                    events,
                )
            }
            is DetectorSignal.ManualKind -> {
                events += DetectorEvent.SegmentConfirmed(now, state.currentKind, signal.kind, now, isManual = true)
                return DetectorStep(
                    state.copy(
                        currentKind = signal.kind,
                        candidate = null,
                        manualHoldUntilMillis = now + config.manualOverrideHoldMillis,
                    ),
                    events,
                )
            }
            is DetectorSignal.Recognized -> {
                if (state.isPaused) {
                    events += signal.rejected(RejectionReason.PAUSED)
                    return DetectorStep(state, events)
                }
                state.copy(recognized = signal)
            }
            is DetectorSignal.StepCounter -> {
                val readings = if (state.stepReadings.lastOrNull()?.let { signal.counter < it.counter } == true) {
                    // Перезагрузка обнулила датчик: старые показания с новыми не сравнить.
                    listOf(signal)
                } else {
                    state.stepReadings + signal
                }
                state.copy(stepReadings = readings.filter { now - it.timestampMillis <= config.cadenceWindowMillis })
            }
            is DetectorSignal.Speed -> state.copy(speedMetersPerSecond = signal.metersPerSecond)
        }
        if (next.isPaused) return DetectorStep(next, events)

        val (verdict, rejection) = verdictOf(next, now)
        // Отказы пишутся только на сам вердикт: шаги и скорость приходят каждую секунду.
        val recognized = next.recognized
        if (signal is DetectorSignal.Recognized && rejection != null && recognized != null) {
            events += recognized.rejected(rejection)
        }

        val holdActive = next.manualHoldUntilMillis?.let { now < it } == true
        val candidate = next.candidate
        next = when {
            verdict == null -> next
            verdict == next.currentKind -> {
                candidate?.let { events += DetectorEvent.CandidateDropped(now, it.kind) }
                next.copy(candidate = null)
            }
            holdActive -> {
                if (signal is DetectorSignal.Recognized && recognized != null) {
                    events += recognized.rejected(RejectionReason.MANUAL_HOLD)
                }
                next.copy(candidate = null)
            }
            candidate?.kind == verdict -> next
            else -> {
                candidate?.let { events += DetectorEvent.CandidateDropped(now, it.kind) }
                events += DetectorEvent.CandidateStarted(now, verdict)
                next.copy(candidate = SegmentCandidate(verdict, now))
            }
        }

        val pending = next.candidate
        if (pending != null && now - pending.sinceMillis >= config.confirmWindowMillis) {
            events += DetectorEvent.SegmentConfirmed(
                timestampMillis = now,
                from = next.currentKind,
                to = pending.kind,
                boundaryMillis = pending.sinceMillis,
                isManual = false,
            )
            next = next.copy(currentKind = pending.kind, candidate = null)
        }
        return DetectorStep(next, events)
    }

    /** Вид по текущим сигналам; `null` — судить не по чему, вторым — почему. */
    private fun verdictOf(state: SegmentDetectorState, now: Long): Pair<SegmentKind?, RejectionReason?> {
        val recognized = state.recognized ?: return null to null
        if (recognized.confidencePercent < config.minConfidencePercent) {
            return null to RejectionReason.LOW_CONFIDENCE
        }
        val kind = when (recognized.activity) {
            RecognizedActivity.IN_VEHICLE -> SegmentKind.VEHICLE
            RecognizedActivity.ON_BICYCLE -> SegmentKind.CYCLING
            RecognizedActivity.ON_FOOT, RecognizedActivity.WALKING, RecognizedActivity.RUNNING -> {
                // Каденс различает ходьбу и бег надёжнее самого распознавания; без шагов
                // остаётся его слово.
                val cadence = cadenceStepsPerMinute(state, now)
                when {
                    cadence != null -> if (cadence >= config.runningCadenceStepsPerMinute) {
                        SegmentKind.RUNNING
                    } else {
                        SegmentKind.ON_FOOT
                    }
                    recognized.activity == RecognizedActivity.RUNNING -> SegmentKind.RUNNING
                    else -> SegmentKind.ON_FOOT
                }
            }
            RecognizedActivity.STILL, RecognizedActivity.TILTING, RecognizedActivity.UNKNOWN ->
                return null to RejectionReason.NOT_MOVEMENT
        }
        // ponytail: вето по последней скорости, без сглаживания; окно скорости — если
        // единичные выбросы фильтра начнут рвать сегменты.
        val speed = state.speedMetersPerSecond
        val tooFast = speed != null && when (kind) {
            SegmentKind.ON_FOOT, SegmentKind.RUNNING -> speed > config.maxOnFootSpeedMetersPerSecond
            SegmentKind.CYCLING -> speed > config.maxCyclingSpeedMetersPerSecond
            else -> false
        }
        return if (tooFast) null to RejectionReason.SPEED else kind to null
    }

    private fun cadenceStepsPerMinute(state: SegmentDetectorState, now: Long): Double? {
        val readings = state.stepReadings.filter { now - it.timestampMillis <= config.cadenceWindowMillis }
        val first = readings.firstOrNull() ?: return null
        val last = readings.last()
        val spanMillis = last.timestampMillis - first.timestampMillis
        if (spanMillis < config.cadenceWindowMillis / 2) return null
        return (last.counter - first.counter) * 60_000.0 / spanMillis
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

    private fun ActivitySegment.isShort(): Boolean =
        durationMillis() < config.minSegmentDurationMillis || distanceMeters < config.minSegmentDistanceMeters

    private fun coalesce(segments: List<ActivitySegment>): List<ActivitySegment> =
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

private fun DetectorSignal.Recognized.rejected(reason: RejectionReason) =
    DetectorEvent.VerdictRejected(timestampMillis, activity, confidencePercent, reason)
