package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isCloseTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import org.junit.jupiter.api.Test

class TrackProcessorTest {
    private val processor = TrackProcessor()

    @Test
    fun `teleport is marked as outlier and excluded from statistics`() {
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 0.0, timestamp = 0L),
                point(id = 2L, latitude = 1.0, timestamp = 5_000L),
                point(id = 3L, latitude = 0.0001, timestamp = 10_000L),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points[1].isOutlier).isTrue()
        assertThat(result.points[2].isOutlier).isFalse()
        assertThat(result.statistics.distanceMeters).isGreaterThan(10.0)
        assertThat(result.statistics.distanceMeters).isLessThan(12.0)
    }

    @Test
    fun `accuracy radius prevents stationary gps noise from becoming an outlier`() {
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.238_949, timestamp = 0L, accuracy = 10f),
                point(id = 2L, latitude = 43.239_000, timestamp = 5_000L, accuracy = 10f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.none(GeoPoint::isOutlier)).isTrue()
    }

    @Test
    fun `smoothing averages an interior point but keeps endpoints`() {
        val points = listOf(
            point(id = 1L, latitude = 0.0, timestamp = 0L),
            point(id = 2L, latitude = 0.0003, timestamp = 5_000L),
            point(id = 3L, latitude = 0.0, timestamp = 10_000L),
        )

        val smoothed = SmoothingUseCase()(points, radius = 1)

        assertThat(smoothed.first().latitude).isEqualTo(0.0)
        assertThat(smoothed[1].latitude).isCloseTo(0.0001, 0.0000001)
        assertThat(smoothed.last().latitude).isEqualTo(0.0)
    }

    @Test
    fun `stationary window is marked as stop and excluded from moving time`() {
        val config = TrackProcessingConfig(
            maximumSpeedMetersPerSecond = 10.0,
            maximumAccelerationMetersPerSecondSquared = 10.0,
            smoothingRadius = 0,
            stopWindowMillis = 15_000L,
            minimumStopDurationMillis = 10_000L,
            stopRadiusMeters = 15.0,
            stopSpeedMetersPerSecond = 0.8,
            resumeSpeedMetersPerSecond = 1.2,
        )
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L),
                point(id = 2L, latitude = 43.000_001, timestamp = 5_000L),
                point(id = 3L, latitude = 43.000_002, timestamp = 10_000L),
                point(id = 4L, latitude = 43.000_003, timestamp = 15_000L),
            ),
            activityType = ActivityType.WALKING,
            config = config,
        )

        assertThat(result.points.all(GeoPoint::isStop)).isTrue()
        assertThat(result.statistics.movingTimeMillis).isEqualTo(0L)
        assertThat(result.statistics.distanceMeters).isEqualTo(0.0)
    }

    @Test
    fun `movement after a stop resumes statistics`() {
        val config = TrackProcessingConfig(
            maximumSpeedMetersPerSecond = 20.0,
            maximumAccelerationMetersPerSecondSquared = 20.0,
            smoothingRadius = 0,
            stopWindowMillis = 10_000L,
            minimumStopDurationMillis = 10_000L,
            stopRadiusMeters = 12.0,
            stopSpeedMetersPerSecond = 0.8,
            resumeSpeedMetersPerSecond = 1.2,
        )
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 0.0, timestamp = 0L),
                point(id = 2L, latitude = 0.0, timestamp = 5_000L),
                point(id = 3L, latitude = 0.0, timestamp = 10_000L),
                point(id = 4L, latitude = 0.00012, timestamp = 15_000L),
            ),
            activityType = ActivityType.RUNNING,
            config = config,
        )

        assertThat(result.points.last().isStop).isFalse()
        assertThat(result.statistics.movingTimeMillis).isEqualTo(5_000L)
        assertThat(result.statistics.distanceMeters).isGreaterThan(10.0)
    }

    @Test
    fun `walking fixes are not treated as a stop when accuracy circles overlap`() {
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 8f),
                point(id = 2L, latitude = 43.000_063, timestamp = 5_000L, accuracy = 8f),
                point(id = 3L, latitude = 43.000_126, timestamp = 10_000L, accuracy = 8f),
                point(id = 4L, latitude = 43.000_189, timestamp = 15_000L, accuracy = 8f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.last().isStop).isFalse()
        assertThat(result.statistics.movingTimeMillis).isEqualTo(15_000L)
    }

    @Test
    fun `reported fused speed prevents auto pause during continuous movement`() {
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 10f, speed = 1.4f),
                point(id = 2L, latitude = 43.000_063, timestamp = 5_000L, accuracy = 10f, speed = 1.4f),
                point(id = 3L, latitude = 43.000_126, timestamp = 10_000L, accuracy = 10f, speed = 1.4f),
                point(id = 4L, latitude = 43.000_189, timestamp = 15_000L, accuracy = 10f, speed = 1.4f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.none(GeoPoint::isStop)).isTrue()
    }

    @Test
    fun `zero provider speed does not hide sustained positional movement`() {
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 8f, speed = 0f),
                point(id = 2L, latitude = 43.000_063, timestamp = 5_000L, accuracy = 8f, speed = 0f),
                point(id = 3L, latitude = 43.000_126, timestamp = 10_000L, accuracy = 8f, speed = 0f),
                point(id = 4L, latitude = 43.000_189, timestamp = 15_000L, accuracy = 8f, speed = 0f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.last().isStop).isFalse()
    }

    @Test
    fun `stationary gps jitter remains auto paused`() {
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 8f, speed = 0f),
                point(id = 2L, latitude = 43.000_018, timestamp = 5_000L, accuracy = 8f, speed = 0f),
                point(id = 3L, latitude = 42.999_991, timestamp = 10_000L, accuracy = 8f, speed = 0f),
                point(id = 4L, latitude = 43.000_009, timestamp = 15_000L, accuracy = 8f, speed = 0f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.last().isStop).isTrue()
        assertThat(result.statistics.movingTimeMillis).isEqualTo(0L)
    }

    @Test
    fun `speed between stop and resume thresholds does not toggle auto pause`() {
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 8f, speed = 0f),
                point(id = 2L, latitude = 43.000_009, timestamp = 5_000L, accuracy = 8f, speed = 0f),
                point(id = 3L, latitude = 43.0, timestamp = 10_000L, accuracy = 8f, speed = 0f),
                point(id = 4L, latitude = 43.000_018, timestamp = 15_000L, accuracy = 8f, speed = 0.4f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.last().isStop).isTrue()
    }

    @Test
    fun `reported movement exits auto pause despite gps accuracy overlap`() {
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 10f, speed = 0f),
                point(id = 2L, latitude = 43.000_009, timestamp = 5_000L, accuracy = 10f, speed = 0f),
                point(id = 3L, latitude = 43.0, timestamp = 10_000L, accuracy = 10f, speed = 0f),
                point(id = 4L, latitude = 43.000_063, timestamp = 15_000L, accuracy = 10f, speed = 1.4f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points[2].isStop).isTrue()
        assertThat(result.points.last().isStop).isFalse()
    }

    @Test
    fun `sparse auto pause samples still detect that movement resumed`() {
        // Регрессия на залипшую автопаузу: на паузе частота опроса GPS снижается, шаг между
        // точками становится больше stopWindowMillis, и окно схлопывается до одной точки.
        // Раньше проверка возобновления в этом случае просто не выполнялась, и запись
        // оставалась на автопаузе всю поездку.
        val config = TrackProcessingConfig.forActivityType(ActivityType.DRIVING)
            .copy(smoothingRadius = 0)
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 8f, speed = 0f),
                point(id = 2L, latitude = 43.000_009, timestamp = 5_000L, accuracy = 8f, speed = 0f),
                point(id = 3L, latitude = 43.0, timestamp = 10_000L, accuracy = 8f, speed = 0f),
                point(id = 4L, latitude = 43.000_009, timestamp = 15_000L, accuracy = 8f, speed = 0f),
                // Автобус тронулся: ~165 м за шаг, но провайдер скорости не отдаёт.
                point(id = 5L, latitude = 43.001_484, timestamp = 31_500L, accuracy = 8f),
                point(id = 6L, latitude = 43.002_968, timestamp = 48_000L, accuracy = 8f),
                point(id = 7L, latitude = 43.004_452, timestamp = 64_500L, accuracy = 8f),
            ),
            activityType = ActivityType.DRIVING,
            config = config,
        )

        assertThat(result.points[3].isStop).isTrue()
        assertThat(result.points.drop(4).none(GeoPoint::isStop)).isTrue()
        assertThat(result.statistics.distanceMeters).isGreaterThan(300.0)
    }

    @Test
    fun `resuming does not bounce back into a stop on the very next sample`() {
        // Точки стоянки оставались в окне и после возобновления, поэтому следующий же
        // сэмпл возвращал автопаузу — в логах это видно как переход туда-обратно за 100 мс.
        val config = TrackProcessingConfig.forActivityType(ActivityType.DRIVING)
            .copy(smoothingRadius = 0)
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 8f, speed = 0f),
                point(id = 2L, latitude = 43.0, timestamp = 5_000L, accuracy = 8f, speed = 0f),
                point(id = 3L, latitude = 43.0, timestamp = 10_000L, accuracy = 8f, speed = 0f),
                point(id = 4L, latitude = 43.0, timestamp = 15_000L, accuracy = 8f, speed = 2.5f),
                point(id = 5L, latitude = 43.0, timestamp = 15_100L, accuracy = 8f, speed = 0f),
            ),
            activityType = ActivityType.DRIVING,
            config = config,
        )

        assertThat(result.points[3].isStop).isFalse()
        assertThat(result.points[4].isStop).isFalse()
    }

    @Test
    fun `statistics calculate average maximum speed and meaningful elevation gain`() {
        val calculator = StatisticsCalculatorUseCase()
        val stats = calculator(
            points = listOf(
                point(id = 1L, latitude = 0.0, timestamp = 0L, altitude = 100.0),
                point(id = 2L, latitude = 0.0001, timestamp = 10_000L, altitude = 102.0),
                point(id = 3L, latitude = 0.0002, timestamp = 20_000L, altitude = 107.0),
            ),
            elevationNoiseMeters = 3.0,
        )

        assertThat(stats.distanceMeters).isGreaterThan(22.0)
        assertThat(stats.distanceMeters).isLessThan(23.0)
        assertThat(stats.movingTimeMillis).isEqualTo(20_000L)
        assertThat(stats.averageSpeedMetersPerSecond).isGreaterThan(1.1)
        assertThat(stats.maxSpeedMetersPerSecond).isGreaterThan(1.1)
        assertThat(stats.elevationGainMeters).isEqualTo(5.0)
    }

    @Test
    fun `manual pause route is preserved but excluded from activity statistics`() {
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 0.0, timestamp = 0L),
                point(id = 2L, latitude = 0.0001, timestamp = 10_000L),
                point(
                    id = 3L,
                    latitude = 1.0,
                    timestamp = 20_000L,
                    recordingStatus = RecordingStatus.MANUAL_PAUSE,
                ),
                point(
                    id = 4L,
                    latitude = 1.0001,
                    timestamp = 30_000L,
                    recordingStatus = RecordingStatus.MANUAL_PAUSE,
                ),
                point(id = 5L, latitude = 0.0002, timestamp = 40_000L),
                point(id = 6L, latitude = 0.0003, timestamp = 50_000L),
            ),
            activityType = ActivityType.RUNNING,
        )

        assertThat(result.points).hasSize(6)
        assertThat(result.points.filter { it.recordingStatus == RecordingStatus.MANUAL_PAUSE }).hasSize(2)
        assertThat(result.statistics.distanceMeters).isGreaterThan(20.0)
        assertThat(result.statistics.distanceMeters).isLessThan(25.0)
        assertThat(result.statistics.movingTimeMillis).isEqualTo(20_000L)
        assertThat(result.statistics.elapsedTimeMillis).isEqualTo(20_000L)
    }

    @Test
    fun `manual correction removes selected points and marks retained points`() {
        val correction = ManualCorrectionUseCase(processor)

        val result = correction(
            points = listOf(
                point(id = 1L, latitude = 0.0, timestamp = 0L),
                point(id = 2L, latitude = 1.0, timestamp = 5_000L),
                point(id = 3L, latitude = 0.0001, timestamp = 10_000L),
            ),
            removedPointIds = setOf(2L),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.map(GeoPoint::id)).isEqualTo(listOf(1L, 3L))
        assertThat(result.points.all(GeoPoint::isManuallyEdited)).isTrue()
        assertThat(result.statistics.distanceMeters).isGreaterThan(10.0)
    }

    private fun point(
        id: Long,
        latitude: Double,
        timestamp: Long,
        accuracy: Float? = null,
        altitude: Double? = null,
        speed: Float? = null,
        recordingStatus: RecordingStatus = RecordingStatus.RECORDING,
    ) = GeoPoint(
        id = id,
        activityId = 1L,
        latitude = latitude,
        longitude = 0.0,
        altitudeMeters = altitude,
        timestampEpochMillis = timestamp,
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        recordingStatus = recordingStatus,
    )
}
