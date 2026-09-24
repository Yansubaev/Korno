package com.ians.korno.tools.trackcalibration

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.trackprocessing.DetectorSignal
import org.junit.jupiter.api.Test

class GroundTruthTest {

    @Test
    fun `reference runs from each mark to the next one or to the end`() {
        // Эталон: пешком 100–400 с, дальше транспорт до конца (1000 с). Декодер сменил вид на 500 с.
        val match = groundTruthMatch(
            marks = listOf(mark(100, SegmentKind.ON_FOOT), mark(400, SegmentKind.VEHICLE)),
            segments = listOf(segment(SegmentKind.ON_FOOT, 0, 500), segment(SegmentKind.VEHICLE, 500, 1_000)),
            signals = emptyList(),
            endMillis = seconds(1_000),
        )

        // До первой метки эталона нет: 0–100 с не считаются, хотя декодер там что-то сказал.
        assertThat(match.referenceMillis).isEqualTo(seconds(900))
        // Не совпали только 400–500 с.
        assertThat(match.matchedMillis).isEqualTo(seconds(800))
    }

    @Test
    fun `manual pause is left out of the reference`() {
        val match = groundTruthMatch(
            marks = listOf(mark(0, SegmentKind.CYCLING)),
            segments = listOf(segment(SegmentKind.VEHICLE, 0, 1_000)),
            signals = listOf(DetectorSignal.Paused(seconds(200)), DetectorSignal.Resumed(seconds(500))),
            endMillis = seconds(1_000),
        )

        assertThat(match).isEqualTo(GroundTruthMatch(referenceMillis = seconds(700), matchedMillis = 0L))
    }

    @Test
    fun `an unfinished pause lasts to the end of the recording`() {
        val match = groundTruthMatch(
            marks = listOf(mark(0, SegmentKind.RUNNING)),
            segments = listOf(segment(SegmentKind.RUNNING, 0, 1_000)),
            signals = listOf(DetectorSignal.Paused(seconds(600))),
            endMillis = seconds(1_000),
        )

        assertThat(match).isEqualTo(GroundTruthMatch(referenceMillis = seconds(600), matchedMillis = seconds(600)))
    }

    private fun mark(atSeconds: Long, kind: SegmentKind) = GroundTruthMark(seconds(atSeconds), kind)

    private fun segment(kind: SegmentKind, fromSeconds: Long, toSeconds: Long) = ActivitySegment(
        kind = kind,
        startTimeEpochMillis = seconds(fromSeconds),
        endTimeEpochMillis = seconds(toSeconds),
    )

    private fun seconds(value: Long) = value * 1_000L
}
