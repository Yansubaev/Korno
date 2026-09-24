package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.SegmentKind
import org.junit.jupiter.api.Test

class SegmentDetectorTest {
    private val config = SegmentDetectorConfig(confirmWindowMillis = 30_000L)
    private val detector = SegmentDetector(config)

    @Test
    fun `a verdict is confirmed only after holding the window, with the boundary from the decoder`() {
        val run = Run()
        run.feed(verdict(10_000L, SegmentKind.CYCLING, since = 0L))
        run.feed(verdict(35_000L, SegmentKind.CYCLING, since = 0L))
        assertThat(run.state.currentKind).isNull()

        run.feed(verdict(40_000L, SegmentKind.CYCLING, since = 0L))

        assertThat(run.confirmations()).containsExactly(
            DetectorEvent.SegmentConfirmed(40_000L, null, SegmentKind.CYCLING, boundaryMillis = 0L, isManual = false),
        )
    }

    @Test
    fun `the boundary follows the latest verdict of the candidate`() {
        // Декодер видит всю запись и с новыми эпохами может передвинуть границу.
        val run = Run(SegmentDetectorState(currentKind = SegmentKind.ON_FOOT))
        run.feed(verdict(0L, SegmentKind.VEHICLE, since = -5_000L))
        run.feed(verdict(30_000L, SegmentKind.VEHICLE, since = -20_000L))

        assertThat(run.confirmations().single().boundaryMillis).isEqualTo(-20_000L)
    }

    @Test
    fun `a verdict that flips back before the window does not break the segment`() {
        val run = Run(SegmentDetectorState(currentKind = SegmentKind.CYCLING))
        run.feed(verdict(0L, SegmentKind.ON_FOOT, since = 0L))
        run.feed(verdict(20_000L, SegmentKind.CYCLING, since = -60_000L))
        run.feed(verdict(60_000L, SegmentKind.CYCLING, since = -60_000L))

        assertThat(run.confirmations()).isEmpty()
        assertThat(run.events.filterIsInstance<DetectorEvent.CandidateDropped>().single().kind)
            .isEqualTo(SegmentKind.ON_FOOT)
    }

    @Test
    fun `manual pause restarts the window and verdicts during it are ignored`() {
        val run = Run(SegmentDetectorState(currentKind = SegmentKind.ON_FOOT))
        run.feed(verdict(0L, SegmentKind.CYCLING, since = 0L))
        run.feed(DetectorSignal.Paused(20_000L))
        run.feed(verdict(60_000L, SegmentKind.CYCLING, since = 0L))
        run.feed(DetectorSignal.Resumed(100_000L))
        run.feed(verdict(110_000L, SegmentKind.CYCLING, since = 0L))
        assertThat(run.confirmations()).isEmpty()

        run.feed(verdict(150_000L, SegmentKind.CYCLING, since = 0L))

        assertThat(run.confirmations().single().to).isEqualTo(SegmentKind.CYCLING)
    }

    @Test
    fun `a manual correction sets the boundary now`() {
        val run = Run(SegmentDetectorState(currentKind = SegmentKind.ON_FOOT))
        run.feed(verdict(0L, SegmentKind.VEHICLE, since = 0L))
        run.feed(DetectorSignal.ManualKind(10_000L, SegmentKind.CYCLING))

        assertThat(run.state).isEqualTo(SegmentDetectorState(currentKind = SegmentKind.CYCLING))
        assertThat(run.confirmations()).containsExactly(
            DetectorEvent.SegmentConfirmed(10_000L, SegmentKind.ON_FOOT, SegmentKind.CYCLING, 10_000L, isManual = true),
        )
    }

    @Test
    fun `a short segment is absorbed by its longer neighbour and same kinds are glued`() {
        val merged = detector.mergeShortSegments(
            listOf(
                segment(SegmentKind.CYCLING, 0L, 600_000L, distance = 3_000.0, steps = 10),
                segment(SegmentKind.ON_FOOT, 600_000L, 630_000L, distance = 60.0, steps = 50),
                segment(SegmentKind.CYCLING, 630_000L, 1_200_000L, distance = 2_500.0, steps = 5),
            ),
        )

        assertThat(merged).containsExactly(
            segment(SegmentKind.CYCLING, 0L, 1_200_000L, distance = 5_560.0, steps = 65),
        )
    }

    @Test
    fun `a segment corrected by the user is not absorbed`() {
        val segments = listOf(
            segment(SegmentKind.CYCLING, 0L, 600_000L, distance = 3_000.0),
            segment(SegmentKind.ON_FOOT, 600_000L, 630_000L, distance = 20.0).copy(isManuallyEdited = true),
        )

        assertThat(detector.mergeShortSegments(segments)).isEqualTo(segments)
    }

    private inner class Run(initial: SegmentDetectorState = SegmentDetectorState()) {
        var state = initial
        val events = mutableListOf<DetectorEvent>()

        fun feed(signal: DetectorSignal) {
            val step = detector.reduce(state, signal)
            state = step.state
            events += step.events
        }

        fun confirmations() = events.filterIsInstance<DetectorEvent.SegmentConfirmed>()
    }

    private fun verdict(at: Long, kind: SegmentKind, since: Long) = DetectorSignal.Verdict(at, kind, since)

    private fun segment(kind: SegmentKind, start: Long, end: Long, distance: Double, steps: Int? = null) =
        ActivitySegment(
            kind = kind,
            startTimeEpochMillis = start,
            endTimeEpochMillis = end,
            distanceMeters = distance,
            stepCount = steps,
        )
}
