package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import org.junit.jupiter.api.Test

/**
 * Ускорение, при котором модель движения ничего не ограничивает и фильтр практически
 * повторяет измерения.
 *
 * Это замена прежнему `smoothingRadius = 0`: тестам детектора остановок и статистики
 * нужны ровно те координаты, которые в них записаны, а не оценка по ним. Выключателя у
 * фильтра нет и быть не должно, но физически «выключить» его можно — сказав, что человек
 * вправе ускоряться как угодно. Тогда предсказанию верить нечему, и вес целиком уходит
 * измерению.
 */
private const val UNCONSTRAINED_ACCELERATION = 50.0

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
        // Между уцелевшими точками 11 м. Оценка выходит чуть короче: на второй точке
        // фильтр ещё не знает скорости и потому не берёт смещение целиком. Это плата за
        // холодный старт, и длится она первые пару фиксов.
        assertThat(result.statistics.distanceMeters).isGreaterThan(8.0)
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
    fun `filter pulls a lone excursion back towards its neighbours`() {
        // Средняя точка уехала на 33 м в сторону при погрешности 10 м — это дрожание,
        // а не манёвр, и модель движения ему не верит.
        val points = listOf(
            point(id = 1L, latitude = 0.0, timestamp = 0L, accuracy = 10f),
            point(id = 2L, latitude = 0.0003, timestamp = 5_000L, accuracy = 10f),
            point(id = 3L, latitude = 0.0, timestamp = 10_000L, accuracy = 10f),
        )

        val filtered = processor.process(points, ActivityType.WALKING).points

        assertThat(filtered[1].latitude).isLessThan(0.0002)
        assertThat(filtered[1].latitude).isGreaterThan(0.0)
    }

    @Test
    fun `filter keeps the last point smoothed just like the rest`() {
        // Скользящее среднее по определению не могло сгладить конец трека: соседей справа
        // нет. Именно эта точка уходила на живую карту, поэтому маркер во время записи
        // показывал сырой фикс. Каузальная оценка обрабатывает её наравне со всеми.
        val points = listOf(
            point(id = 1L, latitude = 0.0, timestamp = 0L, accuracy = 8f),
            point(id = 2L, latitude = 0.000_009, timestamp = 5_000L, accuracy = 8f),
            point(id = 3L, latitude = 0.000_018, timestamp = 10_000L, accuracy = 8f),
            point(id = 4L, latitude = 0.000_027, timestamp = 15_000L, accuracy = 8f),
            point(id = 5L, latitude = 0.000_200, timestamp = 20_000L, accuracy = 8f),
        )

        val filtered = processor.process(points, ActivityType.WALKING).points

        // Последний фикс прыгнул на 19 м вбок от ровного хода в 1 м за пять секунд.
        // Оценка обязана оказаться заметно ближе к продолжению траектории, чем к нему.
        assertThat(filtered.last().latitude).isLessThan(0.000_150)
        assertThat(filtered.last().isOutlier).isFalse()
    }

    @Test
    fun `stationary window is marked as stop and excluded from moving time`() {
        val config = TrackProcessingConfig(
            maximumSpeedMetersPerSecond = 10.0,
            maximumAccuracyMeters = 40.0,
            processNoiseAccelerationMetersPerSecondSquared = UNCONSTRAINED_ACCELERATION,
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
            maximumAccuracyMeters = 40.0,
            processNoiseAccelerationMetersPerSecondSquared = UNCONSTRAINED_ACCELERATION,
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
            .copy(processNoiseAccelerationMetersPerSecondSquared = UNCONSTRAINED_ACCELERATION)
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
            .copy(processNoiseAccelerationMetersPerSecondSquared = UNCONSTRAINED_ACCELERATION)
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
            config = TrackProcessingConfig.forActivityType(ActivityType.RUNNING),
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
    fun `poor accuracy fix is dropped instead of dragging the track`() {
        // Регрессия по логу прогулки: фиксы с погрешностью 100 м приходили от сети,
        // попадали в трек как есть и давали прыжки в полторы сотни метров.
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 10f),
                point(id = 2L, latitude = 43.000_063, timestamp = 5_000L, accuracy = 10f),
                point(id = 3L, latitude = 43.001_500, timestamp = 10_000L, accuracy = 100f),
                point(id = 4L, latitude = 43.000_126, timestamp = 15_000L, accuracy = 10f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points[2].isOutlier).isTrue()
        assertThat(result.points.filterIndexed { index, _ -> index != 2 }.none(GeoPoint::isOutlier))
            .isTrue()
        assertThat(result.statistics.distanceMeters).isLessThan(20.0)
    }

    @Test
    fun `a walk does not accelerate to forty kilometres an hour`() {
        // Регрессия: на прогулке фильтр не срабатывал ни разу за всю запись — 123 точки,
        // ноль выбросов, потому что из отрезка вычиталась сумма погрешностей обеих точек.
        // Допуск теперь растёт вместе с погрешностью, но остаётся допуском: при хорошем
        // сигнале 78 м за 7 с шумом не объяснить, и предельная скорость такую точку ловит.
        val steady = (0..9).map { index ->
            point(
                id = index.toLong(),
                latitude = 43.0 + index * 0.000_063,
                timestamp = index * 7_000L,
                accuracy = 8f,
            )
        }
        val leap = point(
            id = 99L,
            latitude = 43.0 + 9 * 0.000_063 + 0.000_701,
            timestamp = 70_000L,
            accuracy = 8f,
        )

        val result = processor.process(steady + leap, ActivityType.WALKING)

        assertThat(result.points.last().isOutlier).isTrue()
        assertThat(result.points.dropLast(1).none(GeoPoint::isOutlier)).isTrue()
    }

    @Test
    fun `the same leap on a poor signal is damped instead of dropped`() {
        // А вот при погрешности 25 м тот же рывок шумом объясняется: 38 м прошёл, 40 м
        // намерил — это чуть больше сигмы, ничего невероятного. Отбрасывать такую точку
        // не за что, и раньше это была настоящая проблема, потому что отбросить было
        // единственным, что фильтр умел. Теперь есть третий вариант: точку взять, но
        // сдвинуться на малую часть её рывка.
        val steady = (0..9).map { index ->
            point(
                id = index.toLong(),
                latitude = 43.0 + index * 0.000_063,
                timestamp = index * 7_000L,
                accuracy = 25f,
            )
        }
        val leapLatitude = 43.0 + 9 * 0.000_063 + 0.000_701
        val leap = point(id = 99L, latitude = leapLatitude, timestamp = 70_000L, accuracy = 25f)

        val result = processor.process(steady + leap, ActivityType.WALKING)

        assertThat(result.points.last().isOutlier).isFalse()
        // Сырая точка ушла бы на все 71 м от продолжения прогулки; оценка проходит около
        // 38 м — примерно половину. Больше на таком сигнале и не выжать: при погрешности
        // 25 м и шаге 7 с сама модель знает положение с точностью порядка 27 м, и повода
        // предпочесть предсказание измерению у неё немного.
        val estimated = result.points.last().latitude
        val expectedWithoutLeap = 43.0 + 10 * 0.000_063
        val leapOffset = leapLatitude - expectedWithoutLeap
        assertThat(estimated - expectedWithoutLeap).isLessThan(leapOffset * 0.65)
    }


    @Test
    fun `sparse stationary fixes are detected as a stop`() {
        // Точный сценарий из лога активности 15: человек стоял на месте, шаг опроса
        // просел до 30 с, окно детектора схлопывалось до одной точки, длительность
        // выходила нулевой — и стоянка не объявлялась никогда.
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 11f, speed = 1.2f),
                point(id = 2L, latitude = 43.000_063, timestamp = 7_000L, accuracy = 11f, speed = 1.2f),
                point(id = 3L, latitude = 43.000_126, timestamp = 14_000L, accuracy = 11f, speed = 1.2f),
                // Сигнал разредился, скорость провайдер отдавать перестал, координата
                // при этом не меняется вовсе.
                point(id = 4L, latitude = 43.000_130, timestamp = 44_000L, accuracy = 11f),
                point(id = 5L, latitude = 43.000_130, timestamp = 74_000L, accuracy = 11f),
                point(id = 6L, latitude = 43.000_130, timestamp = 104_000L, accuracy = 11f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.last().isStop).isTrue()
        assertThat(result.statistics.movingTimeMillis).isLessThan(45_000L)
    }

    @Test
    fun `walking with city grade accuracy is not mistaken for a stop`() {
        // 1.15 м/с (4.1 км/ч) при погрешности 12 м и шаге 4 с — обычная городская
        // прогулка. Раньше из смещения вычиталась погрешность: у пешехода за окно
        // оставалось 0.6 м/с при пороге 0.5, дрожание регулярно опрокидывало вердикт,
        // и автопауза включалась посреди ходьбы. На эмуляторе так терялось две трети
        // дистанции, а при погрешности за двадцать метров не осталось бы и её.
        val points = (0..14).map { index ->
            point(
                id = index + 1L,
                latitude = 43.0 + index * 0.000_041_3,
                timestamp = index * 4_000L,
                accuracy = 12f,
                // Провайдер отдаёт скорость, близкую к нулю, — так ведёт себя и эмулятор,
                // и реальный приёмник на слабом сигнале.
                speed = 0.00001f,
            )
        }

        val result = processor.process(points, ActivityType.WALKING)

        assertThat(result.points.none(GeoPoint::isStop)).isTrue()
        assertThat(result.statistics.movingTimeMillis).isEqualTo(56_000L)
    }

    @Test
    fun `a stop is detected on a track with no provider speed at all`() {
        // Регрессия, существовавшая до фильтра: необходимая длительность окна считалась
        // как время, за которое пешеход со скоростью порога вышел бы из круга погрешности.
        // При 15 м и 0.5 м/с это тридцать секунд, а само окно ограничено пятнадцатью —
        // условие не выполнялось никогда, и без доплеровской скорости стоянка не
        // объявлялась вовсе. Смещение считается по сглаженным координатам, и такого
        // запаса больше не требуется.
        val walking = (0..9).map { index ->
            point(id = index + 1L, latitude = 43.0 + index * 0.000_063, timestamp = index * 5_000L, accuracy = 15f)
        }
        val standing = (0..9).map { index ->
            point(
                id = 100L + index,
                latitude = 43.0 + 9 * 0.000_063,
                timestamp = 50_000L + index * 5_000L,
                accuracy = 15f,
            )
        }

        val result = processor.process(walking + standing, ActivityType.WALKING)

        assertThat(result.points.takeLast(5).all(GeoPoint::isStop)).isTrue()
    }

    @Test
    fun `the first seconds of a walk are not mistaken for a stop`() {
        // Оценка скорости стартует с нуля и подбирается к истинной не сразу. Если решать
        // по ней, начало каждой прогулки читается как стоянка: на этих данных так терялись
        // первые двадцать восемь секунд. Смещение за окно стартовой оценки не имеет.
        val points = (0..14).map { index ->
            point(
                id = index + 1L,
                latitude = 43.0 + index * 0.000_041_3,
                timestamp = index * 4_000L,
                accuracy = 12f,
            )
        }

        val result = processor.process(points, ActivityType.WALKING)

        assertThat(result.points.none(GeoPoint::isStop)).isTrue()
    }

    @Test
    fun `a walker leaving the stop circle resumes without any provider speed`() {
        // Выход из автопаузы по одной лишь пороговой скорости недостижим для пешехода:
        // после вычитания погрешности он её просто не набирает, и запись залипает
        // в паузе до ручного вмешательства. Уход из круга стоянки — признак, который
        // не зависит ни от скорости провайдера, ни от длины окна.
        val stationary = (0..7).map { index ->
            point(id = index + 1L, latitude = 43.0, timestamp = index * 5_000L, accuracy = 12f, speed = 0f)
        }
        val walking = (1..12).map { index ->
            point(
                id = 100L + index,
                latitude = 43.0 + index * 0.000_041_3,
                timestamp = 35_000L + index * 4_000L,
                accuracy = 12f,
                speed = 0f,
            )
        }

        val result = processor.process(stationary + walking, ActivityType.WALKING)

        assertThat(result.points.take(8).all(GeoPoint::isStop)).isTrue()
        assertThat(result.points.last().isStop).isFalse()
        // Путь до выхода из круга стоянки в дистанцию не идёт — это цена гистерезиса:
        // из пройденных 55 м засчитываются те, что после возобновления. Порог выхода
        // теперь в полтора раза дальше круга стоянки, и цена соответственно выросла:
        // так стоянка перестаёт разваливаться надвое от обычного сноса координат.
        assertThat(result.statistics.distanceMeters).isGreaterThan(10.0)
    }

    @Test
    fun `stale evidence does not turn a long gap into a stop`() {
        val config = TrackProcessingConfig.forActivityType(ActivityType.WALKING)
            .copy(processNoiseAccelerationMetersPerSecondSquared = UNCONSTRAINED_ACCELERATION)
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 11f),
                // Между фиксами прошло на порядок больше окна детектора: за это время
                // можно уйти и вернуться, и прямая между концами ничего не доказывает.
                point(id = 2L, latitude = 43.000_001, timestamp = 600_000L, accuracy = 11f),
            ),
            activityType = ActivityType.WALKING,
            config = config,
        )

        assertThat(result.points.last().isStop).isFalse()
    }

    @Test
    fun `the walk up to a stop is not swallowed by it`() {
        val config = TrackProcessingConfig.forActivityType(ActivityType.WALKING)
            .copy(processNoiseAccelerationMetersPerSecondSquared = UNCONSTRAINED_ACCELERATION)
        // Человек подошёл и встал. Стоянка объявляется по окну целиком, а окно к этому
        // моменту дотягивается до подхода: раньше та ходьба помечалась стоянкой заодно
        // и пропадала из трека дважды — временем из движения и дистанцией вместе
        // с переносом опорной точки.
        val walking = (0..5).map { index ->
            point(
                id = index + 1L,
                latitude = 43.0 + index * 0.000_045,
                timestamp = index * 4_000L,
                accuracy = 8f,
                speed = 1.25f,
                speedAccuracy = 0.3f,
            )
        }
        val standing = (0..7).map { index ->
            point(
                id = 100L + index,
                latitude = 43.000_225,
                timestamp = 24_000L + index * 4_000L,
                accuracy = 8f,
                speed = 0.05f,
                speedAccuracy = 0.2f,
            )
        }

        val result = processor.process(walking + standing, ActivityType.WALKING, config)

        assertThat(result.points.take(walking.size).none(GeoPoint::isStop)).isTrue()
        assertThat(result.points.last().isStop).isTrue()
    }

    @Test
    fun `a point without provider speed stays inside the stop`() {
        val config = TrackProcessingConfig.forActivityType(ActivityType.WALKING)
            .copy(processNoiseAccelerationMetersPerSecondSquared = UNCONSTRAINED_ACCELERATION)
        // Обрезается только то, про что доплер прямо говорит «идёт». Отсутствие скорости
        // ничего не доказывает, и вырезать по нему начало стоянки значило бы терять её
        // всякий раз, когда провайдер скорость не отдаёт.
        val standing = (0..9).map { index ->
            point(
                id = index + 1L,
                latitude = 43.0,
                timestamp = index * 4_000L,
                accuracy = 8f,
            )
        }

        val result = processor.process(standing, ActivityType.WALKING, config)

        assertThat(result.points.first().isStop).isTrue()
    }

    @Test
    fun `doppler overrides a collapsed window that looks like rest`() {
        val config = TrackProcessingConfig.forActivityType(ActivityType.WALKING)
            .copy(processNoiseAccelerationMetersPerSecondSquared = UNCONSTRAINED_ACCELERATION)
        // Схлопнутое окно: два фикса в 34 секундах друг от друга, между ними всё
        // отбраковано по погрешности. Прямая между концами короткая и сама по себе
        // читается как покой — но приёмник всё это время меряет скорость по сдвигу
        // несущей и знает её с точностью до трети метра в секунду.
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 30f, speed = 1.35f, speedAccuracy = 0.36f),
                point(
                    id = 2L,
                    latitude = 43.000_100,
                    timestamp = 34_000L,
                    accuracy = 30f,
                    speed = 1.35f,
                    speedAccuracy = 0.36f,
                ),
            ),
            activityType = ActivityType.WALKING,
            config = config,
        )

        assertThat(result.points.none(GeoPoint::isStop)).isTrue()
    }

    @Test
    fun `doppler speed keeps a curved walk out of auto pause`() {
        val config = TrackProcessingConfig.forActivityType(ActivityType.WALKING)
            .copy(processNoiseAccelerationMetersPerSecondSquared = UNCONSTRAINED_ACCELERATION)
        // Ходьба по дуге: смещение по прямой за окно мало и само по себе читается как
        // покой, но приёмник всё это время меряет скорость по сдвигу несущей и уверенно
        // показывает шаг. Именно так на прогулке включалась автопауза посреди движения.
        val walking = (0..14).map { index ->
            val angle = Math.PI * index / 14.0
            point(
                id = index + 1L,
                latitude = 43.0 + 0.000_06 * kotlin.math.sin(angle),
                longitude = 76.0 + 0.000_06 * (1 - kotlin.math.cos(angle)),
                timestamp = index * 1_000L,
                accuracy = 8f,
                speed = 1.2f,
                speedAccuracy = 0.3f,
            )
        }

        val result = processor.process(walking, ActivityType.WALKING, config)

        assertThat(result.points.none(GeoPoint::isStop)).isTrue()
    }

    @Test
    fun `unmeasured gap does not produce an impossible maximum speed`() {
        // На прогулке сохранялся максимум 10.9 м/с — 39 км/ч: прямая через двухминутную
        // дыру делилась на время дыры и попадала в максимум как настоящий отрезок.
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 43.0, timestamp = 0L, accuracy = 10f),
                point(id = 2L, latitude = 43.000_063, timestamp = 7_000L, accuracy = 10f),
                // 220 м за две минуты: пешеход прошёл их не по прямой, и «скорость»
                // этого отрезка ничего не описывает.
                point(id = 3L, latitude = 43.002_063, timestamp = 127_000L, accuracy = 10f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.statistics.maxSpeedMetersPerSecond).isLessThan(2.0)
        assertThat(result.statistics.gapCount).isEqualTo(1)
        assertThat(result.statistics.unmeasuredTimeMillis).isEqualTo(120_000L)
        // Дистанция разрыва остаётся: прямая между фиксами — нижняя оценка пройденного.
        assertThat(result.statistics.distanceMeters).isGreaterThan(200.0)
    }

    @Test
    fun `stationary jitter does not accumulate distance`() {
        // Дрожание координат в пределах погрешности накапливалось в дистанцию:
        // на прогулке так набегали лишние сотни метров зигзага.
        val jitter = listOf(0.0, 0.000_027, -0.000_018, 0.000_009, -0.000_027, 0.000_018)
        val result = processor.process(
            points = jitter.mapIndexed { index, offset ->
                point(
                    id = index + 1L,
                    latitude = 43.0 + offset,
                    timestamp = index * 3_000L,
                    accuracy = 12f,
                    speed = 0f,
                )
            },
            activityType = ActivityType.WALKING,
        )

        assertThat(result.statistics.distanceMeters).isLessThan(3.0)
    }

    @Test
    fun `filter weighs a fix by the accuracy it reports`() {
        // Точный фикс и грубый описывают разные вещи: первый — положение, второй — круг,
        // в котором оно где-то есть. Вес должен это различать.
        val points = listOf(
            point(id = 1L, latitude = 0.0, timestamp = 0L, accuracy = 5f),
            point(id = 2L, latitude = 0.0, timestamp = 5_000L, accuracy = 5f),
            point(id = 3L, latitude = 0.000_300, timestamp = 10_000L, accuracy = 39f),
        )

        val filtered = processor.process(points, ActivityType.WALKING).points

        // 33 м при заявленных 39 м погрешности — фикс проходит гейт, но сдвинуть оценку
        // на всю свою величину не вправе.
        assertThat(filtered.last().isOutlier).isFalse()
        assertThat(filtered.last().latitude).isLessThan(0.000_200)
    }

    @Test
    fun `korno export keeps its geometry and stop verdicts untouched`() {
        // Файл нашего же экспорта: середина уже сглажена, стоянка уже размечена.
        // Второй проход сдвинул бы координату и мог бы передумать про остановку.
        val points = listOf(
            point(id = 1L, latitude = 0.0, timestamp = 0L, accuracy = 5f),
            point(id = 2L, latitude = 0.0001, timestamp = 5_000L, accuracy = 5f).copy(isStop = true),
            point(id = 3L, latitude = 0.0, timestamp = 10_000L, accuracy = 5f),
        )

        val result = processor.process(
            points = points,
            activityType = ActivityType.WALKING,
            source = TrackSource.KORNO_EXPORT,
        )

        assertThat(result.points).isEqualTo(points)
    }

    @Test
    fun `the same points are reshaped when they come from a foreign file`() {
        val points = listOf(
            point(id = 1L, latitude = 0.0, timestamp = 0L, accuracy = 5f),
            point(id = 2L, latitude = 0.0001, timestamp = 5_000L, accuracy = 5f),
            point(id = 3L, latitude = 0.0, timestamp = 10_000L, accuracy = 5f),
        )

        val result = processor.process(points, ActivityType.WALKING, source = TrackSource.RAW)

        assertThat(result.points[1].latitude).isLessThan(0.0001)
    }

    @Test
    fun `korno export is sorted before statistics are counted`() {
        val result = processor.process(
            points = listOf(
                point(id = 3L, latitude = 0.0002, timestamp = 10_000L),
                point(id = 1L, latitude = 0.0, timestamp = 0L),
                point(id = 2L, latitude = 0.0001, timestamp = 5_000L),
            ),
            activityType = ActivityType.WALKING,
            source = TrackSource.KORNO_EXPORT,
        )

        assertThat(result.points.map(GeoPoint::id)).isEqualTo(listOf(1L, 2L, 3L))
    }

    @Test
    fun `each segment is processed with its own profile and the vehicle stays out of the speed`() {
        // 100 с пешком на 1.4 м/с, затем 100 с автобусом на 15 м/с, фикс раз в 5 с.
        val walk = (0..20).map { i -> point(id = i.toLong(), latitude = i * 0.000_063, timestamp = i * 5_000L) }
        val bus = (1..20).map { i ->
            point(id = 20L + i, latitude = 20 * 0.000_063 + i * 0.000_674, timestamp = 100_000L + i * 5_000L)
        }
        val segments = listOf(
            ActivitySegment(kind = SegmentKind.ON_FOOT, startTimeEpochMillis = 0L, endTimeEpochMillis = 100_000L),
            ActivitySegment(kind = SegmentKind.VEHICLE, startTimeEpochMillis = 100_000L, endTimeEpochMillis = 200_000L),
        )
        val profiles = mapOf(
            SegmentKind.ON_FOOT to TrackProcessingConfig.forActivityType(ActivityType.WALKING),
            SegmentKind.VEHICLE to TrackProcessingConfig.forActivityType(ActivityType.DRIVING),
        )

        val result = processor.processSegments(walk + bus, segments) { profiles.getValue(it) }
        val walkingOnly = processor.processSegments(walk + bus, segments) { profiles.getValue(SegmentKind.ON_FOOT) }

        // Пешие пороги выкинули бы автобус целиком: 15 м/с — выброс для пешехода.
        assertThat(result.segments[1].distanceMeters).isGreaterThan(1_200.0)
        assertThat(walkingOnly.segments[1].distanceMeters).isLessThan(1_000.0)
        assertThat(result.statistics.distanceMeters).isGreaterThan(1_300.0)
        // Средняя и максимальная — только по ходьбе.
        assertThat(result.statistics.averageSpeedMetersPerSecond).isLessThan(3.0)
        assertThat(result.statistics.maxSpeedMetersPerSecond).isLessThan(5.0)
    }

    @Test
    fun `fix without doppler and bearing is rejected when the receiver reports motion`() {
        // Сетевой фикс от GNSS-фикса отличается не погрешностью — тридцать метров бывают
        // у обоих, — а тем, что о движении он не сообщает ничего.
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 0.0, timestamp = 0L, accuracy = 8f, speed = 1.2f, bearing = 90f),
                point(id = 2L, latitude = 0.000_100, timestamp = 5_000L, accuracy = 30f),
                point(id = 3L, latitude = 0.000_200, timestamp = 10_000L, accuracy = 8f, speed = 1.2f, bearing = 90f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.map(GeoPoint::isOutlier)).isEqualTo(listOf(false, true, false))
    }

    @Test
    fun `fix without doppler and bearing survives when no point in the track has either`() {
        // Импортированный GPX: ни скорости, ни курса нет ни у одной точки. Безусловное
        // правило выкосило бы такой файл целиком, и импорт возвращал бы пустой трек.
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 0.0, timestamp = 0L, accuracy = 30f),
                point(id = 2L, latitude = 0.000_100, timestamp = 5_000L, accuracy = 30f),
                point(id = 3L, latitude = 0.000_200, timestamp = 10_000L, accuracy = 30f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.none(GeoPoint::isOutlier)).isTrue()
    }

    @Test
    fun `precise fix without doppler and bearing is kept`() {
        // Приёмник, отдающий точное положение без доплера, — не сетевой фикс:
        // порог networkFixAccuracyMeters и стоит для того, чтобы его не задеть.
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 0.0, timestamp = 0L, accuracy = 8f, speed = 1.2f, bearing = 90f),
                point(id = 2L, latitude = 0.000_100, timestamp = 5_000L, accuracy = 5f),
                point(id = 3L, latitude = 0.000_200, timestamp = 10_000L, accuracy = 8f, speed = 1.2f, bearing = 90f),
            ),
            activityType = ActivityType.WALKING,
        )

        assertThat(result.points.none(GeoPoint::isOutlier)).isTrue()
    }

    @Test
    fun `accuracy exactly at the threshold is rejected`() {
        // Приёмник упирается в круглые значения, и в разобранном логе поездки самым
        // частым была ровно сотня — при пороге ровно в сотню.
        val config = TrackProcessingConfig(
            maximumSpeedMetersPerSecond = 10.0,
            maximumAccuracyMeters = 40.0,
            processNoiseAccelerationMetersPerSecondSquared = UNCONSTRAINED_ACCELERATION,
        )
        val result = processor.process(
            points = listOf(
                point(id = 1L, latitude = 0.0, timestamp = 0L, accuracy = 8f, speed = 1.2f, bearing = 90f),
                point(id = 2L, latitude = 0.000_100, timestamp = 5_000L, accuracy = 40f, speed = 1.2f, bearing = 90f),
                point(id = 3L, latitude = 0.000_200, timestamp = 10_000L, accuracy = 8f, speed = 1.2f, bearing = 90f),
            ),
            activityType = ActivityType.WALKING,
            config = config,
        )

        assertThat(result.points.map(GeoPoint::isOutlier)).isEqualTo(listOf(false, true, false))
    }

    @Test
    fun `the edge crossing a segment boundary is counted exactly once`() {
        // Транспортный участок, от которого после отбраковки сетевых фиксов остались
        // только края: последняя его точка отстоит от первой пешей на 500 м. Без
        // приставки этот отрезок не измерял бы никто, и поездка пропадала бы целиком.
        val bus = listOf(
            point(id = 1L, latitude = 0.0, timestamp = 0L, accuracy = 8f, speed = 12f, bearing = 0f),
            point(id = 2L, latitude = 0.000_500, timestamp = 40_000L, accuracy = 8f, speed = 12f, bearing = 0f),
        )
        val walk = listOf(
            point(id = 3L, latitude = 0.005_000, timestamp = 100_000L, accuracy = 8f, speed = 1.2f, bearing = 0f),
            point(id = 4L, latitude = 0.005_063, timestamp = 105_000L, accuracy = 8f, speed = 1.2f, bearing = 0f),
        )
        val segments = listOf(
            ActivitySegment(kind = SegmentKind.VEHICLE, startTimeEpochMillis = 0L, endTimeEpochMillis = 100_000L),
            ActivitySegment(kind = SegmentKind.ON_FOOT, startTimeEpochMillis = 100_000L, endTimeEpochMillis = 105_000L),
        )

        val result = processor.processSegments(bus + walk, segments) { TrackProcessingConfig.forSegmentKind(it) }

        // ~500 м через границу достаются транспортному участку — тому, во время
        // которого перемещение и произошло.
        assertThat(result.segments[0].distanceMeters).isGreaterThan(400.0)
        // Сумма по участкам и есть общая дистанция: отрезок засчитан один раз.
        assertThat(result.statistics.distanceMeters)
            .isEqualTo(result.segments.sumOf(ActivitySegment::distanceMeters))
        // Приставка осталась в своём участке и не продублировалась.
        assertThat(result.points.map(GeoPoint::id)).isEqualTo(listOf(1L, 2L, 3L, 4L))
    }

    private fun point(
        id: Long,
        latitude: Double,
        timestamp: Long,
        longitude: Double = 0.0,
        accuracy: Float? = null,
        altitude: Double? = null,
        speed: Float? = null,
        speedAccuracy: Float? = null,
        bearing: Float? = null,
        recordingStatus: RecordingStatus = RecordingStatus.RECORDING,
    ) = GeoPoint(
        id = id,
        activityId = 1L,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitude,
        timestampEpochMillis = timestamp,
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        speedAccuracyMetersPerSecond = speedAccuracy,
        bearingDegrees = bearing,
        recordingStatus = recordingStatus,
    )
}
