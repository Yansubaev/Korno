package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isTrue
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RecognizedActivity
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import org.junit.jupiter.api.Test

class ActivityModeDecoderTest {
    private val decoder = ActivityModeDecoder()

    @Test
    fun `a vehicle verdict in the middle of a ride without stops stays cycling`() {
        // Ровно запись 31: полторы минуты уверенного IN_VEHICLE на велосипедной скорости.
        val track = Track()
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)
            .move(seconds = 90, speed = 4.5, RecognizedActivity.IN_VEHICLE to 85, RecognizedActivity.ON_BICYCLE to 3)
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.CYCLING)
    }

    @Test
    fun `a real transfer is found and its boundary snaps to the stop even when recognition lags`() {
        val track = Track()
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)
        val stopStart = track.now
        track.move(seconds = 60, speed = 0.0, RecognizedActivity.STILL to 100)
        val departure = track.now
        // Машина уже поехала, а распознавание ещё минуту говорит «велосипед».
        track.move(seconds = 60, speed = 12.0, RecognizedActivity.ON_BICYCLE to 60)
            .move(seconds = 600, speed = 12.0, RecognizedActivity.IN_VEHICLE to 90)

        val segments = track.decode()

        assertThat(segments.kinds()).containsExactly(SegmentKind.CYCLING, SegmentKind.VEHICLE)
        assertThat(segments[1].startTimeEpochMillis).isBetween(stopStart, departure)
    }

    @Test
    fun `a gap in gps is enough for a transfer`() {
        val track = Track()
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)
            .gap(seconds = 120)
            .move(seconds = 600, speed = 4.5, RecognizedActivity.IN_VEHICLE to 90)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.CYCLING, SegmentKind.VEHICLE)
    }

    @Test
    fun `walking and cycling switch without a stop`() {
        val track = Track()
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)
            .move(seconds = 180, speed = 1.2, RecognizedActivity.WALKING to 85, RecognizedActivity.ON_FOOT to 85)
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)

        assertThat(track.decode().kinds())
            .containsExactly(SegmentKind.CYCLING, SegmentKind.ON_FOOT, SegmentKind.CYCLING)
    }

    @Test
    fun `a walking verdict faster than a human walks is running`() {
        val track = Track().move(seconds = 300, speed = 4.5, RecognizedActivity.WALKING to 80)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.RUNNING)
    }

    @Test
    fun `step cadence tells running from walking under the on foot umbrella`() {
        val track = Track().move(seconds = 300, speed = 2.5, RecognizedActivity.ON_FOOT to 90, cadence = 165)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.RUNNING)
    }

    @Test
    fun `the user's kind wins inside a manual segment`() {
        val track = Track().move(seconds = 600, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)
        val manual = ActivitySegment(
            kind = SegmentKind.VEHICLE,
            startTimeEpochMillis = 300_000L,
            endTimeEpochMillis = 600_000L,
            isManuallyEdited = true,
        )

        val segments = track.decode(manualSegments = listOf(manual))

        assertThat(segments.kinds()).containsExactly(SegmentKind.CYCLING, SegmentKind.VEHICLE)
        assertThat(segments[1].isManuallyEdited).isTrue()
    }

    @Test
    fun `nothing is decoded without a single movement verdict`() {
        val track = Track().move(seconds = 300, speed = 0.0, RecognizedActivity.STILL to 100)

        assertThat(track.decode()).isEmpty()
    }

    private fun List<ActivitySegment>.kinds() = map(ActivitySegment::kind)

    /** Запись по участкам: точка каждые 4 с, ответ распознавания каждые 10 с. */
    private inner class Track {
        var now = 0L
            private set
        private val points = mutableListOf<GeoPoint>()
        private val recognitions = mutableListOf<RecognitionSample>()
        private val steps = mutableListOf<DetectorSignal.StepCounter>()
        private var latitude = 55.0
        private var stepCount = 0.0

        fun move(
            seconds: Int,
            speed: Double,
            vararg confidences: Pair<RecognizedActivity, Int>,
            cadence: Int? = null,
        ): Track {
            val end = now + seconds * 1_000L
            var nextRecognition = now
            while (now < end) {
                if (now >= nextRecognition) {
                    recognitions += RecognitionSample(timestampEpochMillis = now, confidences = confidences.toMap())
                    nextRecognition += 10_000L
                }
                points += GeoPoint(
                    activityId = 1L,
                    latitude = latitude,
                    longitude = 37.0,
                    timestampEpochMillis = now,
                    speedMetersPerSecond = speed.toFloat(),
                    recordingStatus = RecordingStatus.RECORDING,
                )
                if (cadence != null) {
                    steps += DetectorSignal.StepCounter(now, stepCount.toLong())
                    stepCount += cadence * 4 / 60.0
                }
                latitude += speed * 4 / 111_000.0
                now += 4_000L
            }
            now = end
            return this
        }

        fun gap(seconds: Int): Track {
            now += seconds * 1_000L
            return this
        }

        fun decode(manualSegments: List<ActivitySegment> = emptyList()) = decoder.decode(
            ModeDecoderInput(
                startMillis = 0L,
                endMillis = now,
                points = points,
                recognitions = recognitions,
                steps = steps,
                manualSegments = manualSegments,
            ),
        )
    }
}
