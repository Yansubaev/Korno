package com.ians.korno.feature.activitydetail.domain.usecase

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RecognizedActivity
import com.ians.korno.core.domain.model.RecordingMode
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.trackprocessing.FinalizeTrackUseCase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RedecodeActivitySegmentsUseCaseTest {

    @Test
    fun `a stored ride is relabelled by the current decoder and keeps its steps`() = runTest {
        // Запись 41 в миниатюре: сохранена велосипедом, а ехала на транспортной скорости.
        val repository = FakeTrackRepository()
        repository.recognitionSamples = (0 until DURATION_MILLIS step 20_000L).map { at ->
            RecognitionSample(
                activityId = ACTIVITY_ID,
                timestampEpochMillis = at,
                confidences = mapOf(RecognizedActivity.IN_VEHICLE to 90),
            )
        }
        val stored = listOf(
            segment(SegmentKind.CYCLING, start = 0L, end = DURATION_MILLIS, steps = 600),
        )

        val result = useCase(repository)(activity(), points(speed = 12f), stored)

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(repository.replacedSegments?.map(ActivitySegment::kind)).isEqualTo(listOf(SegmentKind.VEHICLE))
        // Дорожки шагомера у сохранённой записи нет, но сумма сегментов не должна пропасть.
        assertThat(repository.replacedSegments?.sumOf { it.stepCount ?: 0 }).isEqualTo(600)
    }

    @Test
    fun `steps of the old segments are split between the new boundaries`() = runTest {
        val repository = FakeTrackRepository()
        // Граница ровно посередине: пешком, потом бегом. Транспорт тут не годится — в него
        // без остановки не сесть, и декодер такую границу справедливо не поставит.
        repository.recognitionSamples = (0 until DURATION_MILLIS step 20_000L).map { at ->
            RecognitionSample(
                activityId = ACTIVITY_ID,
                timestampEpochMillis = at,
                confidences = if (at < DURATION_MILLIS / 2) {
                    mapOf(RecognizedActivity.WALKING to 90, RecognizedActivity.ON_FOOT to 90)
                } else {
                    mapOf(RecognizedActivity.RUNNING to 90, RecognizedActivity.ON_FOOT to 90)
                },
            )
        }
        val stored = listOf(segment(SegmentKind.CYCLING, start = 0L, end = DURATION_MILLIS, steps = 600))

        useCase(repository)(activity(), points(speed = 2.5f), stored)

        val segments = repository.replacedSegments.orEmpty()
        assertThat(segments.map(ActivitySegment::kind)).isEqualTo(listOf(SegmentKind.ON_FOOT, SegmentKind.RUNNING))
        // Один сохранённый сегмент на 600 шагов, граница посередине — значит поровну.
        assertThat(segments.map { it.stepCount }).isEqualTo(listOf(300, 300))
    }

    @Test
    fun `a recording without movement verdicts keeps the segments it has`() = runTest {
        val repository = FakeTrackRepository()
        repository.recognitionSamples = listOf(
            RecognitionSample(ACTIVITY_ID, 0L, mapOf(RecognizedActivity.STILL to 100)),
        )
        val stored = listOf(segment(SegmentKind.CYCLING, start = 0L, end = DURATION_MILLIS, steps = 600))

        val result = useCase(repository)(activity(), points(speed = 12f), stored)

        assertThat(result).isEqualTo(Result.Error(RedecodeActivitySegmentsUseCase.Skipped.NO_RECOGNITION))
        assertThat(repository.replacedSegments).isNull()
    }

    @Test
    fun `a fixed-type recording is not touched`() = runTest {
        val repository = FakeTrackRepository()
        val stored = listOf(segment(SegmentKind.CYCLING, start = 0L, end = DURATION_MILLIS, steps = 600))

        val result = useCase(repository)(activity(autoMode = false), points(speed = 12f), stored)

        assertThat(result).isEqualTo(Result.Error(RedecodeActivitySegmentsUseCase.Skipped.NOT_AUTO_MODE))
        assertThat(repository.replacedSegments).isNull()
    }

    private fun useCase(repository: FakeTrackRepository) =
        RedecodeActivitySegmentsUseCase(repository, FinalizeTrackUseCase())

    private fun activity(autoMode: Boolean = true) = Activity(
        id = ACTIVITY_ID,
        startTimeEpochMillis = 0L,
        endTimeEpochMillis = DURATION_MILLIS,
        recordingMode = if (autoMode) RecordingMode.AUTO else RecordingMode.FIXED,
    )

    private fun segment(kind: SegmentKind, start: Long, end: Long, steps: Int) = ActivitySegment(
        activityId = ACTIVITY_ID,
        kind = kind,
        startTimeEpochMillis = start,
        endTimeEpochMillis = end,
        stepCount = steps,
    )

    /** Точка каждые 4 с с доплеровской скоростью: из неё декодер и берёт скорость. */
    private fun points(speed: Float): List<GeoPoint> {
        var latitude = 55.0
        return (0 until DURATION_MILLIS step 4_000L).map { at ->
            GeoPoint(
                activityId = ACTIVITY_ID,
                latitude = latitude,
                longitude = 37.0,
                timestampEpochMillis = at,
                speedMetersPerSecond = speed,
            ).also { latitude += speed * 4 / 111_000.0 }
        }
    }

    private companion object {
        const val ACTIVITY_ID = 7L
        const val DURATION_MILLIS = 600_000L
    }
}
