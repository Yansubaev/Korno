package com.ians.korno.tools.trackcalibration

import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.trackprocessing.DetectorSignal

/** Эталонная метка из лога: с [timestampMillis] человек двигался как [kind]. */
data class GroundTruthMark(val timestampMillis: Long, val kind: SegmentKind)

/** Сколько эталонного времени было у записи и на скольких из него разметка совпала. */
data class GroundTruthMatch(val referenceMillis: Long, val matchedMillis: Long) {
    val share: Double? get() = if (referenceMillis > 0L) matchedMillis.toDouble() / referenceMillis else null

    operator fun plus(other: GroundTruthMatch) =
        GroundTruthMatch(referenceMillis + other.referenceMillis, matchedMillis + other.matchedMillis)
}

/**
 * Совпадение разметки [segments] с эталоном: вид метки действует от её времени до
 * следующей метки или до [endMillis]. До первой метки и на ручной паузе (по [signals])
 * эталона нет — это время не считается вовсе.
 */
fun groundTruthMatch(
    marks: List<GroundTruthMark>,
    segments: List<ActivitySegment>,
    signals: List<DetectorSignal>,
    endMillis: Long,
): GroundTruthMatch {
    val sortedMarks = marks.sortedBy(GroundTruthMark::timestampMillis)
    val pauses = manualPauses(signals, endMillis)
    // Эталон, пауза и сегмент внутри каждого промежутка между соседними границами постоянны.
    val edges = (
        sortedMarks.map(GroundTruthMark::timestampMillis) +
            pauses.flatMap { listOf(it.first, it.last) } +
            segments.flatMap { listOfNotNull(it.startTimeEpochMillis, it.endTimeEpochMillis) } +
            endMillis
        ).filter { it <= endMillis }.distinct().sorted()

    var reference = 0L
    var matched = 0L
    edges.zipWithNext { from, to ->
        val kind = sortedMarks.lastOrNull { it.timestampMillis <= from }?.kind ?: return@zipWithNext
        if (pauses.any { from >= it.first && from < it.last }) return@zipWithNext
        reference += to - from
        val decided = segments.firstOrNull { from >= it.startTimeEpochMillis && from < (it.endTimeEpochMillis ?: endMillis) }
        if (decided?.kind == kind) matched += to - from
    }
    return GroundTruthMatch(reference, matched)
}

/** Ручные паузы как `[начало, конец)`; незакрытая тянется до конца записи. */
private fun manualPauses(signals: List<DetectorSignal>, endMillis: Long): List<LongRange> {
    val pauses = mutableListOf<LongRange>()
    var pausedAt: Long? = null
    signals.sortedBy(DetectorSignal::timestampMillis).forEach { signal ->
        when (signal) {
            is DetectorSignal.Paused -> if (pausedAt == null) pausedAt = signal.timestampMillis
            is DetectorSignal.Resumed -> {
                pausedAt?.let { pauses += it..signal.timestampMillis }
                pausedAt = null
            }
            else -> Unit
        }
    }
    pausedAt?.let { pauses += it..endMillis }
    return pauses
}
