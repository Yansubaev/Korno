package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random
import org.junit.jupiter.api.Test

class KalmanTrackFilterUseCaseTest {

    private val filter = KalmanTrackFilterUseCase()
    private val config = TrackProcessingConfig.forSegmentKind(SegmentKind.ON_FOOT)

    @Test
    fun `stationary noise is suppressed at least twofold`() {
        // То самое, на что жалуется пользователь: стоишь на месте, а точки разлетаются.
        val noise = Random(20260908)
        val points = List(60) { index ->
            point(
                id = index.toLong(),
                north = noise.nextGaussianLike() * 15.0,
                east = noise.nextGaussianLike() * 15.0,
                timestamp = index * 1_000L,
                accuracy = 15f,
            )
        }

        val filtered = filter(points, config).points

        assertThat(filtered.spreadMeters()).isLessThan(points.spreadMeters() / 2.0)
    }

    @Test
    fun `the gate rejects a jump that no reported accuracy can explain`() {
        val filtered = filter(steadyWalk() + strayPoint(accuracy = 5f), config).points

        assertThat(filtered.last().isOutlier).isTrue()
    }

    @Test
    fun `the gate widens when the receiver admits the fix is poor`() {
        // Тот же прыжок, что и в предыдущем тесте, но приёмник сам говорит, что знает
        // положение с точностью до сорока метров. Тогда это уже не противоречие: точка
        // укладывается в собственную неопределённость, и отбрасывать её не за что.
        // Порог в метрах так не умеет — его пришлось бы менять руками под каждый сигнал.
        val filtered = filter(steadyWalk(accuracy = 40f) + strayPoint(accuracy = 40f), config).points

        assertThat(filtered.last().isOutlier).isFalse()
    }

    @Test
    fun `three rejections in a row restart the filter from the first rejected fix`() {
        // Приёмник действительно переезжает разом — на выходе из здания или догоняя позицию
        // после стоянки. Если продолжать верить старому состоянию, забракован будет весь
        // остаток трека; если начать участок после отказов — пропадёт сам переезд.
        val relocated = (0..5).map { index ->
            point(
                id = (10 + index).toLong(),
                north = 300.0 + index * 1.4,
                timestamp = (10 + index) * 1_000L,
                accuracy = 5f,
            )
        }

        val filtered = filter(steadyWalk() + relocated, config).points

        assertThat(filtered.none(GeoPoint::isOutlier)).isTrue()
    }

    @Test
    fun `scattered rejections stay outliers after the restart`() {
        // Три дикие точки в разные стороны друг с другом не согласны: это выбросы, а не
        // переезд, и участок начинает следующая нормальная точка.
        val scattered = listOf(300.0 to 0.0, 0.0 to 300.0, -300.0 to 0.0).mapIndexed { index, (north, east) ->
            point(id = (10 + index).toLong(), north = north, east = east, timestamp = (6 + index) * 1_000L, accuracy = 5f)
        }
        val resumed = point(id = 20L, north = 9 * 1.4, timestamp = 9_000L, accuracy = 5f)

        val filtered = filter(steadyWalk() + scattered + resumed, config).points

        assertThat(filtered.drop(6).take(3).all(GeoPoint::isOutlier)).isTrue()
        assertThat(filtered.last().isOutlier).isFalse()
    }

    @Test
    fun `doppler velocity keeps the estimate on course through a strayed fix`() {
        // Курс и скорость приёмник знает точнее координат. Точка, сбившаяся вбок, должна
        // подвинуть оценку меньше, когда доплер утверждает, что движение ровное.
        fun track(withVelocity: Boolean) = (0..6).map { index ->
            point(
                id = index.toLong(),
                north = index * 1.4,
                east = if (index == 5) 25.0 else 0.0,
                timestamp = index * 1_000L,
                accuracy = 12f,
                speed = if (withVelocity) 1.4f else null,
                speedAccuracy = if (withVelocity) 0.2f else null,
                bearing = if (withVelocity) 0f else null,
                bearingAccuracy = if (withVelocity) 5f else null,
            )
        }

        val withoutVelocity = filter(track(withVelocity = false), config).points[5]
        val withVelocity = filter(track(withVelocity = true), config).points[5]

        assertThat(abs(withVelocity.longitude)).isLessThan(abs(withoutVelocity.longitude))
    }

    @Test
    fun `a reported speed of zero holds the estimate still without a bearing`() {
        // Стоящий приёмник обычно отдаёт скорость 0 и не отдаёт курса. Раньше это
        // измерение пропадало впустую, хотя оно и есть самое прямое доказательство покоя.
        fun track(withSpeed: Boolean) = List(20) { index ->
            point(
                id = index.toLong(),
                north = if (index % 2 == 0) 8.0 else -8.0,
                timestamp = index * 1_000L,
                accuracy = 12f,
                speed = if (withSpeed) 0f else null,
                speedAccuracy = if (withSpeed) 0.1f else null,
            )
        }

        val withoutSpeed = filter(track(withSpeed = false), config).points.spreadMeters()
        val withSpeed = filter(track(withSpeed = true), config).points.spreadMeters()

        assertThat(withSpeed).isLessThan(withoutSpeed)
    }

    @Test
    fun `the backward pass roughly halves what the forward pass leaves`() {
        // Прямой проход знает о точке только прошлое и потому отстаёт на манёврах.
        // Обратный видит поворот с обеих сторон. Эталон здесь бесшумный, а измерения —
        // зашумлённые: иначе сглаживать было бы нечего.
        val noise = Random(7)
        val truth = (0..29).map { index ->
            point(
                id = index.toLong(),
                north = if (index <= 14) index * 1.4 else 14 * 1.4,
                east = if (index <= 14) 0.0 else (index - 14) * 1.4,
                timestamp = index * 1_000L,
                accuracy = 8f,
            )
        }
        val measured = truth.map { truePoint ->
            truePoint.copy(
                latitude = truePoint.latitude + noise.nextGaussianLike() * 8.0 * METERS_TO_DEGREES,
                longitude = truePoint.longitude +
                    noise.nextGaussianLike() * 8.0 * METERS_TO_DEGREES / LONGITUDE_SCALE,
            )
        }

        val forward = filter(measured, config, backwardPass = false).points
        val smoothed = filter(measured, config, backwardPass = true)
            .let { it.withSmoothedPositions(it.points) }

        assertThat(forward.deviationFrom(truth)).isLessThan(measured.deviationFrom(truth))
        assertThat(smoothed.deviationFrom(truth)).isLessThan(forward.deviationFrom(truth) * 0.7)
    }

    @Test
    fun `a gap starts a new run instead of extrapolating through it`() {
        // За минуту без единого фикса можно уйти куда угодно. Продлевать в эту дыру
        // прежнюю скорость значило бы придумать траекторию.
        val after = point(id = 9L, north = 500.0, timestamp = 400_000L, accuracy = 5f)

        val filtered = filter(steadyWalk() + after, config).points

        // Точка за разрывом не сравнивается с предсказанием и остаётся ровно там, где её
        // измерили: предсказывать было нечем.
        assertThat(filtered.last().isOutlier).isFalse()
        assertThat(filtered.last().latitude).isEqualTo(after.latitude)
    }

    @Test
    fun `a manually corrected point keeps the coordinates the user gave it`() {
        val corrected = 40.0
        val points = steadyWalk().toMutableList()
        points[2] = point(id = 2L, north = corrected, timestamp = 2_000L, accuracy = 5f)
            .copy(isManuallyEdited = true)

        val filtered = filter(points, config, backwardPass = true)
        val smoothed = filtered.withSmoothedPositions(filtered.points)

        assertThat(filtered.points[2].latitude).isEqualTo(points[2].latitude)
        assertThat(filtered.points[2].isOutlier).isFalse()
        assertThat(smoothed[2].latitude).isEqualTo(points[2].latitude)
    }

    @Test
    fun `points already rejected upstream are left untouched`() {
        val points = listOf(
            point(id = 0L, north = 0.0, timestamp = 0L, accuracy = 5f),
            point(id = 1L, north = 900.0, timestamp = 1_000L, accuracy = 5f).copy(isOutlier = true),
            point(id = 2L, north = 1.4, timestamp = 2_000L, accuracy = 5f),
        )

        val filtered = filter(points, config).points

        assertThat(filtered[1].latitude).isEqualTo(points[1].latitude)
        assertThat(filtered[2].isOutlier).isFalse()
    }

    @Test
    fun `raising the plausibility gate does not loosen the filter start`() {
        // maximumSpeedMetersPerSecond — ворота правдоподобия в OutlierFilterUseCase, а не
        // потолок скорости. Здесь оно читается один раз — начальной дисперсией скорости,
        // когда у первой точки нет доплера, — и там зажато тридцатью (`docs/adr/0006`).
        // Поднятые до 60 ворота транспорта не должны менять старт фильтра ни на метр.
        val walk = steadyWalk(accuracy = 12f)

        val atThirty = filter(walk, config.copy(maximumSpeedMetersPerSecond = 30.0)).points
        val atSixty = filter(walk, config.copy(maximumSpeedMetersPerSecond = 60.0)).points

        assertThat(atSixty.map(GeoPoint::latitude)).isEqualTo(atThirty.map(GeoPoint::latitude))
        assertThat(atSixty.map(GeoPoint::longitude)).isEqualTo(atThirty.map(GeoPoint::longitude))
    }

    /** Ровный ход пешехода: шесть секундных шагов по 1.4 м на север. */
    private fun steadyWalk(accuracy: Float = 5f) = (0..5).map { index ->
        point(id = index.toLong(), north = index * 1.4, timestamp = index * 1_000L, accuracy = accuracy)
    }

    /**
     * Точка, ушедшая на 60 м вбок за одну секунду после [steadyWalk]. Расхождение с
     * предсказанием здесь во много раз больше собственной погрешности хорошего фикса —
     * и укладывается в неё же, если фикс объявлен грубым.
     */
    private fun strayPoint(accuracy: Float) =
        point(id = 9L, north = 5 * 1.4, east = 60.0, timestamp = 6_000L, accuracy = accuracy)

    /**
     * @param north смещение на север в метрах от [BASE_LATITUDE]
     * @param east смещение на восток в метрах
     */
    private fun point(
        id: Long,
        north: Double,
        timestamp: Long,
        east: Double = 0.0,
        accuracy: Float? = null,
        speed: Float? = null,
        speedAccuracy: Float? = null,
        bearing: Float? = null,
        bearingAccuracy: Float? = null,
    ) = GeoPoint(
        id = id,
        activityId = 1L,
        latitude = BASE_LATITUDE + north * METERS_TO_DEGREES,
        longitude = east * METERS_TO_DEGREES / LONGITUDE_SCALE,
        timestampEpochMillis = timestamp,
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        speedAccuracyMetersPerSecond = speedAccuracy,
        bearingDegrees = bearing,
        bearingAccuracyDegrees = bearingAccuracy,
    )
}

/** Широта, на которой считаются тесты: 55° — примерно широта Москвы. */
private const val BASE_LATITUDE = 55.0

/** Градусов широты в метре: обратная величина к длине градуса. */
private const val METERS_TO_DEGREES = 1.0 / (6_371_000.0 * Math.PI / 180.0)

/**
 * Во сколько раз градус долготы короче градуса широты на [BASE_LATITUDE]. Без этого
 * множителя «метры на восток» оказывались вдвое короче метров на север, и тест мерил бы
 * не то расстояние, которое в нём написано.
 */
private val LONGITUDE_SCALE = cos(Math.toRadians(BASE_LATITUDE))

/** Средний разброс точек вокруг их собственного центра — мера дрожания трека. */
private fun List<GeoPoint>.spreadMeters(): Double {
    val centerLatitude = map(GeoPoint::latitude).average()
    val centerLongitude = map(GeoPoint::longitude).average()
    return map { haversineMeters(centerLatitude, centerLongitude, it.latitude, it.longitude) }.average()
}

/** Среднее расхождение с эталонной траекторией — мера того, что осталось от шума. */
private fun List<GeoPoint>.deviationFrom(truth: List<GeoPoint>): Double =
    mapIndexed { index, point ->
        haversineMeters(point.latitude, point.longitude, truth[index].latitude, truth[index].longitude)
    }.average()

/**
 * Приближение нормального распределения суммой равномерных: настоящего гаусса в
 * `kotlin.random` нет, а тесту нужен воспроизводимый шум, а не точная статистика.
 */
private fun Random.nextGaussianLike(): Double =
    (0 until 6).sumOf { nextDouble(-1.0, 1.0) } / 2.0
