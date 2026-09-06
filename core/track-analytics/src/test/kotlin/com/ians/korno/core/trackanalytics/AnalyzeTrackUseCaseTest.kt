package com.ians.korno.core.trackanalytics

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import org.junit.jupiter.api.Test

private const val METERS_PER_LATITUDE_DEGREE = 111_194.93
private const val KILOMETER = 1_000.0

class AnalyzeTrackUseCaseTest {

    private val analyze = AnalyzeTrackUseCase()

    @Test
    fun `track shorter than two points yields nothing`() {
        val analysis = analyze(listOf(point(index = 0)), splitLengthMeters = KILOMETER)

        assertThat(analysis.isEmpty).isTrue()
        assertThat(analysis.splits).isEmpty()
    }

    @Test
    fun `splits cover the whole distance and the tail stays partial`() {
        // 5 м/с в течение 500 с — 2500 м: два полных километра и остаток.
        val analysis = analyze(straightTrack(steps = 500), splitLengthMeters = KILOMETER)

        assertThat(analysis.splits).hasSize(3)
        assertThat(analysis.splits[0].isPartial).isFalse()
        assertThat(analysis.splits[1].isPartial).isFalse()
        assertThat(analysis.splits[2].isPartial).isTrue()
        assertThat(analysis.splits.last().endDistanceMeters)
            .isCloseTo(analysis.totalDistanceMeters, 0.001)
        assertThat(analysis.splits.sumOf { it.distanceMeters })
            .isCloseTo(analysis.totalDistanceMeters, 0.001)
    }

    @Test
    fun `split pace matches the constant speed of the track`() {
        val analysis = analyze(straightTrack(steps = 500), splitLengthMeters = KILOMETER)

        val fullSplit = analysis.splits.first()
        assertThat(fullSplit.speedMetersPerSecond).isCloseTo(5.0, 0.05)
        assertThat(fullSplit.movingMillis).isBetween(199_000L, 201_000L)
    }

    @Test
    fun `fastest split ignores the partial tail`() {
        // Остаток проходится вдвое быстрее полных километров, но полным сплитом не является.
        val points = straightTrack(steps = 400) +
            straightTrack(steps = 100, startIndex = 401, metersPerStep = 10.0, startLatitudeMeters = 2_000.0)
        val analysis = analyze(points, splitLengthMeters = KILOMETER)

        val fastest = analysis.fastestSplit
        assertThat(fastest).isNotNull()
        assertThat(fastest!!.isPartial).isFalse()
        assertThat(fastest.speedMetersPerSecond).isLessThan(6.0)
    }

    @Test
    fun `automatic stop is reported separately from moving time`() {
        val points = straightTrack(steps = 100) +
            List(60) { offset ->
                point(
                    index = 101 + offset,
                    latitudeMeters = 500.0,
                    isStop = true,
                )
            }

        val analysis = analyze(points, splitLengthMeters = KILOMETER)

        assertThat(analysis.stops.count).isEqualTo(1)
        assertThat(analysis.stops.segments.single().isManualPause).isFalse()
        assertThat(analysis.stops.autoStopMillis).isBetween(59_000L, 61_000L)
        assertThat(analysis.stops.manualPauseMillis).isEqualTo(0L)
        assertThat(analysis.stops.longestMillis).isBetween(59_000L, 60_000L)
        // Стоянка не удлиняет маршрут, даже если координаты за это время подрожали.
        assertThat(analysis.totalDistanceMeters).isCloseTo(495.0, 1.0)
    }

    @Test
    fun `manual pause is excluded from distance and reported on its own`() {
        val points = straightTrack(steps = 100) +
            List(30) { offset ->
                point(
                    index = 101 + offset,
                    // На паузе пользователь уехал на километр — в дистанцию это не идёт.
                    latitudeMeters = 500.0 + offset * 40.0,
                    status = RecordingStatus.MANUAL_PAUSE,
                )
            }

        val analysis = analyze(points, splitLengthMeters = KILOMETER)

        assertThat(analysis.totalDistanceMeters).isCloseTo(495.0, 1.0)
        assertThat(analysis.stops.manualPauseMillis).isGreaterThan(0L)
        assertThat(analysis.stops.segments.single().isManualPause).isTrue()
        assertThat(analysis.samples.last().speedMetersPerSecond).isNull()
    }

    @Test
    fun `short stop below the threshold is not reported`() {
        val points = straightTrack(steps = 100) +
            List(3) { offset -> point(index = 101 + offset, latitudeMeters = 500.0, isStop = true) }

        val analysis = analyze(points, splitLengthMeters = KILOMETER)

        assertThat(analysis.stops.segments).isEmpty()
    }

    @Test
    fun `elevation noise does not accumulate into a fake gain`() {
        // Пила ±2 м вокруг постоянной высоты: порог шума 3 м её съедает.
        val points = straightTrack(steps = 200) { index ->
            100.0 + if (index % 2 == 0) 2.0 else -2.0
        }

        val elevation = analyze(points, splitLengthMeters = KILOMETER).elevation

        assertThat(elevation).isNotNull()
        assertThat(elevation!!.gainMeters).isLessThan(1.0)
        assertThat(elevation.lossMeters).isLessThan(1.0)
    }

    @Test
    fun `steady climb is counted as gain`() {
        val points = straightTrack(steps = 200) { index -> 100.0 + index * 0.5 }

        val elevation = analyze(points, splitLengthMeters = KILOMETER).elevation

        assertThat(elevation).isNotNull()
        assertThat(elevation!!.gainMeters).isBetween(90.0, 101.0)
        assertThat(elevation.lossMeters).isLessThan(1.0)
        assertThat(elevation.maximumMeters).isGreaterThan(elevation.minimumMeters)
    }

    @Test
    fun `track without altitude has no elevation analysis`() {
        val analysis = analyze(straightTrack(steps = 50), splitLengthMeters = KILOMETER)

        assertThat(analysis.elevation).isNull()
        assertThat(analysis.elevationScale).isNull()
        assertThat(analysis.samples.all { it.altitudeMeters == null }).isTrue()
    }

    @Test
    fun `a single gps spike does not stretch the colour scale`() {
        val points = straightTrack(steps = 400).toMutableList()
        // Одна точка «улетела» на 300 м за секунду — 95-й процентиль её не замечает.
        points[300] = points[300].copy(latitude = points[300].latitude + 300.0 / METERS_PER_LATITUDE_DEGREE)

        val scale = analyze(points, splitLengthMeters = KILOMETER).speedScale

        assertThat(scale).isNotNull()
        assertThat(scale!!.fastestMetersPerSecond).isLessThan(15.0)
    }

    @Test
    fun `outliers never reach the samples`() {
        val points = straightTrack(steps = 20).toMutableList()
        points[10] = points[10].copy(isOutlier = true)

        val analysis = analyze(points, splitLengthMeters = KILOMETER)

        assertThat(analysis.samples).hasSize(19)
    }
}

private fun straightTrack(
    steps: Int,
    startIndex: Int = 0,
    metersPerStep: Double = 5.0,
    startLatitudeMeters: Double = 0.0,
    altitudeAt: ((Int) -> Double)? = null,
): List<GeoPoint> = List(steps) { step ->
    point(
        index = startIndex + step,
        latitudeMeters = startLatitudeMeters + step * metersPerStep,
        altitudeMeters = altitudeAt?.invoke(step),
    )
}

private fun point(
    index: Int,
    latitudeMeters: Double = 0.0,
    altitudeMeters: Double? = null,
    status: RecordingStatus = RecordingStatus.RECORDING,
    isStop: Boolean = false,
): GeoPoint = GeoPoint(
    id = index.toLong() + 1L,
    activityId = 1L,
    latitude = latitudeMeters / METERS_PER_LATITUDE_DEGREE,
    longitude = 0.0,
    altitudeMeters = altitudeMeters,
    timestampEpochMillis = index * 1_000L,
    recordingStatus = status,
    isStop = isStop,
)
