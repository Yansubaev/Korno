package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import kotlin.random.Random
import org.junit.jupiter.api.Test

class IncrementalTrackProcessorTest {

    private val config = TrackProcessingConfig.forActivityType(ActivityType.WALKING)

    @Test
    fun `live distance never goes backwards`() {
        // Полный пересчёт трека на каждый сэмпл двигал предпоследние точки сглаживанием,
        // и длина последнего отрезка менялась задним числом: в логе прогулки дистанция
        // на экране уезжала назад 17 раз, суммарно на 73 метра.
        val processor = IncrementalTrackProcessor(config)
        var previousDistance = 0.0

        walkWithNoise(pointCount = 120).forEach { point ->
            val distance = processor.append(point).statistics.distanceMeters
            assertThat(distance >= previousDistance).isTrue()
            previousDistance = distance
        }

        assertThat(previousDistance).isGreaterThan(0.0)
    }

    @Test
    fun `incremental statistics match a full pass`() {
        // Запечатанный префикс не должен расходиться с полным проходом: живое число и
        // сохранённое считаются разным кодом, и разъехаться им нельзя.
        val points = walkWithNoise(pointCount = 200)
        val processor = IncrementalTrackProcessor(config)
        points.forEach(processor::append)
        val live = processor.append(points.last().copy(id = points.size + 1L, timestampEpochMillis = points.last().timestampEpochMillis + 5_000L))

        val full = TrackProcessor().process(
            points = points + points.last().copy(
                id = points.size + 1L,
                timestampEpochMillis = points.last().timestampEpochMillis + 5_000L,
            ),
            activityType = ActivityType.WALKING,
            config = config,
        )

        // Раньше живое число не могло забежать вперёд итогового. Теперь может — и это не
        // упущение, а следствие обратного прохода: он доступен только готовому треку и
        // убирает остаток дрожания, а дрожание всегда прибавляет к длине. Итог поэтому
        // чуть короче живого. Требование осталось прежним по сути — числа обязаны
        // сходиться, — но допуск теперь двусторонний.
        assertThat(live.statistics.distanceMeters)
            .isGreaterThan(full.statistics.distanceMeters * (1.0 - TAIL_LAG_SHARE))
        assertThat(live.statistics.distanceMeters)
            .isLessThan(full.statistics.distanceMeters * (1.0 + BACKWARD_PASS_SHARE))
        assertThat(live.statistics.movingTimeMillis <= full.statistics.movingTimeMillis).isTrue()
        assertThat(live.statistics.gapCount).isEqualTo(full.statistics.gapCount)
    }

    @Test
    fun `stop detected on sparse fixes reaches the live session`() {
        // Сценарий из лога активности 15: шаг опроса просел до 30 с, координата стоит.
        val processor = IncrementalTrackProcessor(config)
        var timestamp = 0L
        repeat(6) { index ->
            processor.append(
                point(
                    id = index + 1L,
                    latitude = 43.0 + index * 0.000_063,
                    timestamp = timestamp,
                    speed = 1.2f,
                ),
            )
            timestamp += 7_000L
        }

        var update = processor.append(point(id = 7L, latitude = 43.000_315, timestamp = timestamp))
        assertThat(update.lastValidPoint?.isStop).isEqualTo(false)

        repeat(3) { index ->
            timestamp += 30_000L
            update = processor.append(point(id = 8L + index, latitude = 43.000_315, timestamp = timestamp))
        }

        assertThat(update.lastValidPoint?.isStop).isEqualTo(true)
    }

    @Test
    fun `an out of order sample rebuilds the state instead of corrupting it`() {
        val processor = IncrementalTrackProcessor(config)
        val points = walkWithNoise(pointCount = 40)
        points.forEach(processor::append)

        // Провайдер изредка отдаёт фикс задним числом.
        val stale = points[20].copy(id = 999L, timestampEpochMillis = points[20].timestampEpochMillis + 1L)
        val update = processor.append(stale)

        val full = TrackProcessor().process(
            points = points + stale,
            activityType = ActivityType.WALKING,
            config = config,
        )
        assertThat(update.statistics.distanceMeters)
            .isGreaterThan(full.statistics.distanceMeters * (1.0 - TAIL_LAG_SHARE))
        assertThat(update.statistics.distanceMeters)
            .isLessThan(full.statistics.distanceMeters * (1.0 + BACKWARD_PASS_SHARE))
    }

    @Test
    fun `a manual pause is not mistaken for a gap in the track`() {
        val processor = IncrementalTrackProcessor(config)
        repeat(6) { index ->
            processor.append(point(id = index + 1L, latitude = 43.0 + index * 0.000_075, timestamp = index * 7_000L))
        }

        // Полчаса на ручной паузе: точки продолжают приходить, но активности не касаются.
        repeat(4) { index ->
            processor.append(
                point(
                    id = 7L + index,
                    latitude = 43.000_375,
                    timestamp = 42_000L + index * 450_000L,
                    recordingStatus = RecordingStatus.MANUAL_PAUSE,
                ),
            )
        }
        var update = processor.append(
            point(
                id = 10_000L,
                latitude = 43.000_375,
                timestamp = 1_892_000L,
                recordingStatus = RecordingStatus.MANUAL_PAUSE,
            ),
        )
        repeat(6) { index ->
            update = processor.append(
                point(
                    id = 11L + index,
                    latitude = 43.000_375 + index * 0.000_075,
                    timestamp = 1_900_000L + index * 7_000L,
                ),
            )
        }

        // Пауза — это не потеря сигнала: ни разрывов, ни времени «вне наблюдения».
        assertThat(update.statistics.gapCount).isEqualTo(0)
        assertThat(update.statistics.unmeasuredTimeMillis).isEqualTo(0L)
    }

    /**
     * Прогулка со скоростью около 1.2 м/с и дрожанием координат в пределах погрешности —
     * то, как выглядит реальная запись, а не идеальная прямая.
     */
    private fun walkWithNoise(pointCount: Int): List<GeoPoint> {
        val random = Random(seed = 42)
        return (0 until pointCount).map { index ->
            val travelled = index * 0.000_075
            val noise = (random.nextDouble() - 0.5) * 0.000_180
            point(
                id = index + 1L,
                latitude = 43.0 + travelled + noise,
                timestamp = index * 7_000L,
                speed = 1.2f,
            )
        }
    }

    private fun point(
        id: Long,
        latitude: Double,
        timestamp: Long,
        accuracy: Float = 12f,
        speed: Float? = null,
        recordingStatus: RecordingStatus = RecordingStatus.RECORDING,
    ) = GeoPoint(
        id = id,
        activityId = 1L,
        latitude = latitude,
        longitude = 0.0,
        timestampEpochMillis = timestamp,
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        recordingStatus = recordingStatus,
    )

    private companion object {
        /** Доля дистанции, на которую живое число вправе отставать от полного прохода. */
        const val TAIL_LAG_SHARE = 0.1

        /**
         * И на которую вправе его превысить: обратный проход доступен только итоговому
         * треку и снимает остаток дрожания, а дрожание всегда прибавляет к длине.
         */
        const val BACKWARD_PASS_SHARE = 0.1
    }
}
