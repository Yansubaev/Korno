package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.toSingleSegment
import org.junit.jupiter.api.Test

class FinalizeTrackUseCaseTest {
    private val finalize = FinalizeTrackUseCase()

    private val autoActivity = Activity(
        id = 7L,
        startTimeEpochMillis = 0L,
        endTimeEpochMillis = 300_000L,
        activityType = ActivityType.OTHER,
        stepCount = 0,
        isAutoMode = true,
    )

    @Test
    fun `each segment gets its own distance and the type follows the dominant kind`() {
        val result = finalize(autoActivity, mixedPoints, mixedSegments)

        assertThat(result.segments.map { it.kind })
            .isEqualTo(listOf(SegmentKind.ON_FOOT, SegmentKind.VEHICLE, SegmentKind.ON_FOOT))
        // Пешие пороги выкинули бы автобус целиком: 15 м/с — выброс для пешехода.
        assertThat(result.segments[1].distanceMeters).isGreaterThan(1_200.0)
        assertThat(result.activity.distanceMeters).isEqualTo(result.segments.sumOf { it.distanceMeters })
        assertThat(result.activity.activityType).isEqualTo(ActivityType.DRIVING)
    }

    @Test
    fun `a type chosen by the user is kept`() {
        val hike = autoActivity.copy(activityType = ActivityType.HIKING, isTypeChosenByUser = true)

        assertThat(finalize(hike, mixedPoints, mixedSegments).activity.activityType).isEqualTo(ActivityType.HIKING)
    }

    @Test
    fun `short segments are merged into their neighbours`() {
        val blip = ActivitySegment(kind = SegmentKind.CYCLING, startTimeEpochMillis = 50_000L, endTimeEpochMillis = 60_000L)
        val segments = listOf(
            mixedSegments[0].copy(endTimeEpochMillis = 50_000L),
            blip,
            mixedSegments[0].copy(startTimeEpochMillis = 60_000L),
        ) + mixedSegments.drop(1)

        val result = finalize(autoActivity, mixedPoints, segments)

        assertThat(result.segments.map { it.kind })
            .isEqualTo(listOf(SegmentKind.ON_FOOT, SegmentKind.VEHICLE, SegmentKind.ON_FOOT))
    }

    @Test
    fun `only steps on foot count, and an activity without steps stays without them`() {
        val withSteps = finalize(autoActivity, mixedPoints, mixedSegments.map { it.copy(stepCount = 100) })
        val withoutSteps = finalize(autoActivity.copy(stepCount = null), mixedPoints, mixedSegments)

        assertThat(withSteps.activity.stepCount).isEqualTo(200)
        assertThat(withoutSteps.activity.stepCount).isNull()
    }

    @Test
    fun `stale outlier verdicts are recomputed`() {
        val stale = mixedPoints.map { it.copy(isOutlier = true) }

        assertThat(finalize(autoActivity, stale, mixedSegments).activity.distanceMeters)
            .isEqualTo(finalize(autoActivity, mixedPoints, mixedSegments).activity.distanceMeters)
    }

    @Test
    fun `a fixed-mode activity is one segment processed with its own type profile`() {
        val hike = Activity(
            id = 7L,
            startTimeEpochMillis = 0L,
            endTimeEpochMillis = 300_000L,
            activityType = ActivityType.HIKING,
            isTypeChosenByUser = true,
        )

        val result = finalize(hike, walkWithStop, listOf(hike.toSingleSegment()))
        val direct = TrackProcessor().process(walkWithStop, ActivityType.HIKING)

        assertThat(result.activity.distanceMeters).isEqualTo(direct.statistics.distanceMeters)
        assertThat(result.activity.movingTimeMillis).isEqualTo(direct.statistics.movingTimeMillis)
        assertThat(result.segments.single().distanceMeters).isEqualTo(direct.statistics.distanceMeters)
    }

    @Test
    fun `the recording's auto-pause sensitivity decides the moving time`() {
        fun movingTime(sensitivity: AutoPauseSensitivity): Long {
            val walk = Activity(
                startTimeEpochMillis = 0L,
                activityType = ActivityType.WALKING,
                isTypeChosenByUser = true,
                autoPauseSensitivity = sensitivity,
            )
            return finalize(walk, walkWithStop, listOf(walk.toSingleSegment())).activity.movingTimeMillis
        }

        assertThat(movingTime(AutoPauseSensitivity.HIGH)).isNotEqualTo(movingTime(AutoPauseSensitivity.LOW))
    }

    @Test
    fun `a manual pause becomes a segment of its own instead of padding the neighbour`() {
        val walk = ActivitySegment(kind = SegmentKind.ON_FOOT, startTimeEpochMillis = 0L, endTimeEpochMillis = 300_000L, stepCount = 300)

        val result = finalize(autoActivity, walkWithManualPause, listOf(walk))

        assertThat(result.segments.map { it.kind })
            .isEqualTo(listOf(SegmentKind.ON_FOOT, SegmentKind.PAUSE, SegmentKind.ON_FOOT))
        assertThat(result.segments[1].startTimeEpochMillis).isEqualTo(100_000L)
        assertThat(result.segments[1].endTimeEpochMillis).isEqualTo(200_000L)
        // Шаги записи от разреза не меняются, и на паузу их не приписывают.
        assertThat(result.activity.stepCount).isEqualTo(300)
        // Повторный пересчёт режет ровно так же: слияние не съедает участок паузы.
        assertThat(finalize(autoActivity, walkWithManualPause, result.segments).segments.map { it.kind })
            .isEqualTo(result.segments.map { it.kind })
    }

    /** 100 с пешком, 100 с ручной паузы, 100 с пешком; фикс раз в 5 с. */
    private val walkWithManualPause: List<GeoPoint> = run {
        var latitude = 0.0
        (0..60).map { i ->
            val timestamp = i * 5_000L
            if (i > 0) latitude += 0.000_063
            GeoPoint(
                id = i.toLong(),
                activityId = 7L,
                latitude = latitude,
                longitude = 0.0,
                timestampEpochMillis = timestamp,
                accuracyMeters = 5f,
                recordingStatus = if (timestamp in 100_000L..195_000L) {
                    RecordingStatus.MANUAL_PAUSE
                } else {
                    RecordingStatus.RECORDING
                },
            )
        }
    }

    /** 100 с пешком, 100 с автобусом на 15 м/с, 100 с пешком; фикс раз в 5 с. */
    private val mixedPoints: List<GeoPoint> = run {
        var latitude = 0.0
        (0..60).map { i ->
            val timestamp = i * 5_000L
            if (i > 0) latitude += if (timestamp in 105_000L..200_000L) 0.000_674 else 0.000_063
            GeoPoint(id = i.toLong(), activityId = 7L, latitude = latitude, longitude = 0.0, timestampEpochMillis = timestamp)
        }
    }

    private val mixedSegments = listOf(
        ActivitySegment(kind = SegmentKind.ON_FOOT, startTimeEpochMillis = 0L, endTimeEpochMillis = 100_000L, distanceMeters = 140.0),
        ActivitySegment(kind = SegmentKind.VEHICLE, startTimeEpochMillis = 100_000L, endTimeEpochMillis = 200_000L, distanceMeters = 1_500.0),
        ActivitySegment(kind = SegmentKind.ON_FOOT, startTimeEpochMillis = 200_000L, endTimeEpochMillis = 300_000L, distanceMeters = 140.0),
    )

    /** Ходьба 1.4 м/с, стоянка [STOP_MILLIS] на месте, снова ходьба; фикс раз в секунду. */
    private val walkWithStop: List<GeoPoint> = run {
        var latitude = 0.0
        (0..(120 + STOP_MILLIS / 1_000).toInt()).map { i ->
            val timestamp = i * 1_000L
            val standing = timestamp in 60_001L..(60_000L + STOP_MILLIS)
            if (i > 0 && !standing) latitude += 0.000_012_6
            GeoPoint(id = i.toLong(), activityId = 7L, latitude = latitude, longitude = 0.0, timestampEpochMillis = timestamp, accuracyMeters = 5f)
        }
    }

    private companion object {
        const val STOP_MILLIS = 20_000L
    }
}
