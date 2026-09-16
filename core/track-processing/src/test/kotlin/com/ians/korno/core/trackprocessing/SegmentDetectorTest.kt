package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.RecognizedActivity
import com.ians.korno.core.domain.model.SegmentKind
import org.junit.jupiter.api.Test

class SegmentDetectorTest {
    private val config = SegmentDetectorConfig(confirmWindowMillis = 30_000L)
    private val detector = SegmentDetector(config)

    @Test
    fun `a verdict is confirmed only after holding the window, with the boundary at its first sign`() {
        val run = Run()
        run.feed(recognized(10_000L, RecognizedActivity.ON_BICYCLE))
        run.feed(speed(35_000L, 5.0))
        assertThat(run.state.currentKind).isNull()

        run.feed(speed(40_000L, 5.0))

        assertThat(run.confirmations()).containsExactly(confirmed(null, SegmentKind.CYCLING, boundary = 10_000L))
    }

    @Test
    fun `a verdict that flips back before the window does not break the segment`() {
        val run = Run(SegmentDetectorState(currentKind = SegmentKind.CYCLING))
        run.feed(recognized(0L, RecognizedActivity.WALKING))
        run.feed(recognized(20_000L, RecognizedActivity.ON_BICYCLE))
        run.feed(speed(60_000L, 5.0))

        assertThat(run.confirmations()).isEmpty()
        assertThat(run.events.filterIsInstance<DetectorEvent.CandidateDropped>().single().kind)
            .isEqualTo(SegmentKind.ON_FOOT)
    }

    @Test
    fun `still neither breaks the segment nor resets the window`() {
        val run = Run(SegmentDetectorState(currentKind = SegmentKind.ON_FOOT))
        run.feed(recognized(0L, RecognizedActivity.IN_VEHICLE))
        run.feed(recognized(10_000L, RecognizedActivity.STILL))
        run.feed(speed(30_000L, 0.0))

        assertThat(run.confirmations())
            .containsExactly(confirmed(SegmentKind.ON_FOOT, SegmentKind.VEHICLE, boundary = 0L))
    }

    @Test
    fun `a low confidence verdict is rejected`() {
        val run = Run()
        run.feed(recognized(0L, RecognizedActivity.ON_BICYCLE, confidence = 20))
        run.feed(speed(60_000L, 5.0))

        assertThat(run.confirmations()).isEmpty()
        assertThat(run.rejections()).containsExactly(RejectionReason.LOW_CONFIDENCE)
    }

    @Test
    fun `an on foot verdict at vehicle speed is rejected`() {
        val run = Run(SegmentDetectorState(currentKind = SegmentKind.VEHICLE))
        run.feed(speed(0L, 15.0))
        run.feed(recognized(1_000L, RecognizedActivity.WALKING))
        run.feed(speed(60_000L, 15.0))

        assertThat(run.confirmations()).isEmpty()
        assertThat(run.rejections()).containsExactly(RejectionReason.SPEED)
    }

    @Test
    fun `step cadence tells running from walking under the on foot umbrella`() {
        val run = Run()
        run.feed(steps(0L, 1_000L))
        run.feed(steps(10_000L, 1_027L))
        run.feed(recognized(10_000L, RecognizedActivity.ON_FOOT))
        run.feed(steps(20_000L, 1_055L))
        run.feed(steps(40_000L, 1_110L))

        // 165 шагов в минуту — бег, хотя распознавание сказало только «пешком».
        assertThat(run.confirmations().single().to).isEqualTo(SegmentKind.RUNNING)
    }

    @Test
    fun `manual pause restarts the window and verdicts during it are ignored`() {
        val run = Run(SegmentDetectorState(currentKind = SegmentKind.ON_FOOT))
        run.feed(recognized(0L, RecognizedActivity.ON_BICYCLE))
        run.feed(DetectorSignal.Paused(20_000L))
        run.feed(recognized(25_000L, RecognizedActivity.IN_VEHICLE))
        run.feed(DetectorSignal.Resumed(100_000L))
        run.feed(speed(110_000L, 5.0))
        assertThat(run.confirmations()).isEmpty()
        assertThat(run.rejections()).containsExactly(RejectionReason.PAUSED)

        run.feed(recognized(120_000L, RecognizedActivity.ON_BICYCLE))
        run.feed(speed(150_000L, 5.0))

        assertThat(run.confirmations())
            .containsExactly(confirmed(SegmentKind.ON_FOOT, SegmentKind.CYCLING, boundary = 120_000L))
    }

    @Test
    fun `a manual correction sets the boundary now and holds off contradicting verdicts`() {
        val run = Run(SegmentDetectorState(currentKind = SegmentKind.ON_FOOT))
        run.feed(DetectorSignal.ManualKind(0L, SegmentKind.CYCLING))
        run.feed(recognized(1_000L, RecognizedActivity.WALKING))
        run.feed(speed(100_000L, 1.0))
        assertThat(run.state.currentKind).isEqualTo(SegmentKind.CYCLING)
        assertThat(run.rejections()).containsExactly(RejectionReason.MANUAL_HOLD)

        // Удержание кончилось: вердикт снова в силе, но окно отсчитывается заново.
        run.feed(speed(125_000L, 1.0))
        run.feed(speed(150_000L, 1.0))
        assertThat(run.state.currentKind).isEqualTo(SegmentKind.CYCLING)
        run.feed(speed(155_000L, 1.0))

        assertThat(run.confirmations()).containsExactly(
            DetectorEvent.SegmentConfirmed(0L, SegmentKind.ON_FOOT, SegmentKind.CYCLING, 0L, isManual = true),
            confirmed(SegmentKind.CYCLING, SegmentKind.ON_FOOT, boundary = 125_000L),
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

        fun rejections() = events.filterIsInstance<DetectorEvent.VerdictRejected>().map { it.reason }
    }

    private fun recognized(at: Long, activity: RecognizedActivity, confidence: Int = 80) =
        DetectorSignal.Recognized(at, activity, confidence)

    private fun speed(at: Long, metersPerSecond: Double) = DetectorSignal.Speed(at, metersPerSecond)

    private fun steps(at: Long, counter: Long) = DetectorSignal.StepCounter(at, counter)

    /** Время подтверждения здесь не проверяется — только что и откуда подтверждено. */
    private fun confirmed(from: SegmentKind?, to: SegmentKind, boundary: Long) =
        DetectorEvent.SegmentConfirmed(boundary + config.confirmWindowMillis, from, to, boundary, isManual = false)

    private fun segment(kind: SegmentKind, start: Long, end: Long, distance: Double, steps: Int? = null) =
        ActivitySegment(
            kind = kind,
            startTimeEpochMillis = start,
            endTimeEpochMillis = end,
            distanceMeters = distance,
            stepCount = steps,
        )
}
