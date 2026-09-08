package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Погрешность, приписываемая фиксу, о точности которого провайдер ничего не сказал.
 * Важно не само число, а то, что такая точка не должна ни подменять собой хороший фикс,
 * ни быть отброшенной как безнадёжная.
 */
private const val UNKNOWN_ACCURACY_METERS = 20.0

/**
 * Погрешность точки, переставленной пользователем вручную. Он видел карту и знает, где
 * шёл, — это самое точное измерение, какое вообще бывает у трека.
 */
private const val MANUAL_CORRECTION_ACCURACY_METERS = 1.0

/** Метров в градусе широты; тот же радиус Земли, что и у [haversineMeters]. */
private const val METERS_PER_DEGREE_LATITUDE = 6_371_000.0 * Math.PI / 180.0

/**
 * Косинус широты не даёт масштабу по долготе обратиться в ноль у полюсов. Трек там
 * маловероятен, но деление на ноль превратило бы координаты в NaN на весь остаток участка.
 */
private const val MINIMUM_LONGITUDE_SCALE = 1e-6

/**
 * Скорость, ниже которой у вектора скорости нет осмысленного направления: делить на неё
 * значило бы получить случайный единичный вектор.
 */
private const val DEGENERATE_SPEED_METERS_PER_SECOND = 1e-6

/**
 * Ниже этой скорости у предсказания нет направления, которое стоило бы одалживать
 * измерению: единичный вектор от почти нулевой скорости — это случайное направление.
 */
private const val DIRECTIONLESS_SPEED_METERS_PER_SECOND = 0.3

/**
 * Насколько неизвестен курс, одолженный у предсказания. Радиан — это около 57°, то есть
 * «направление скорее угадано, чем известно»: модуль скорости остаётся измерением,
 * а поперечная составляющая почти ни на что не влияет.
 */
private const val DIRECTION_FROM_PREDICTION_SIGMA_RADIANS = 1.0

/** Индексы состояния: смещение на восток и на север, затем скорости по тем же осям. */
private const val EAST = 0
private const val NORTH = 1

/**
 * С какой компоненты состояния начинается измеряемая пара. Обе матрицы наблюдения в этом
 * фильтре просто выбирают две соседние компоненты, поэтому вместо умножения на матрицу из
 * нулей и единиц везде берётся подматрица со сдвигом.
 */
private const val POSITION_OFFSET = EAST
private const val VELOCITY_OFFSET = 2

/**
 * Состояние фильтра на одном шаге — всё, что нужно обратному проходу.
 *
 * Хранится и предсказание, и поправленная оценка: сглаживание Рауха—Тунга—Штрибеля
 * сравнивает будущую сглаженную оценку именно с предсказанием, чтобы понять, насколько
 * прямой проход в этом месте промахнулся.
 */
internal class StepState(
    private val anchorLatitude: Double,
    private val anchorLongitude: Double,
    private val metersPerDegreeLongitude: Double,
    val transition: DoubleArray,
    val predicted: DoubleArray,
    val predictedCovariance: DoubleArray,
    val filtered: DoubleArray,
    val filteredCovariance: DoubleArray,
) {
    fun positionOf(state: DoubleArray): Position = Position(
        latitude = anchorLatitude + state[NORTH] / METERS_PER_DEGREE_LATITUDE,
        longitude = anchorLongitude + state[EAST] / metersPerDegreeLongitude,
    )
}

/** Шаг фильтра вместе с местом точки в исходном списке. */
internal class SmootherStep(val index: Int, val state: StepState)

/**
 * Фильтр одного непрерывного участка трека.
 *
 * Все расчёты идут в локальной метрической системе с началом в первой точке участка:
 * смещения на восток и на север в метрах. Прямая равноугольная развёртка искажает
 * масштаб вдали от начала, но прямое и обратное преобразования здесь строго взаимно
 * обратны, так что на координатах это не сказывается вовсе — искажается лишь то,
 * насколько точно метры в модели соответствуют метрам на местности, и на масштабах
 * тренировки это доли процента.
 */
internal class TrackKalmanFilter(origin: GeoPoint, config: TrackProcessingConfig) {

    private val anchorLatitude = origin.latitude
    private val anchorLongitude = origin.longitude
    private val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE *
        max(abs(cos(Math.toRadians(origin.latitude))), MINIMUM_LONGITUDE_SCALE)

    var timestampEpochMillis: Long = origin.timestampEpochMillis
        private set

    private var state = DoubleArray(STATE_SIZE)
    private var covariance = DoubleArray(STATE_SIZE * STATE_SIZE)

    init {
        val accuracy = origin.measurementAccuracyMeters(config)
        // Направления у первой точки взяться неоткуда, поэтому доплер без курса даёт здесь
        // только «стоит или нет»; вектор появится со второй точки.
        val velocity = origin.velocityMeasurement(config, DoubleArray(STATE_SIZE))
        // Скорость первой точки берётся из доплера, если он есть. Иначе она неизвестна, и
        // сказать об этом нужно честно — широкой начальной неопределённостью, а не нулём:
        // ноль означал бы «стоит на месте», и первые секунды фильтр сопротивлялся бы
        // движению.
        if (velocity != null) {
            state[VELOCITY_OFFSET] = velocity.value[0]
            state[VELOCITY_OFFSET + 1] = velocity.value[1]
        }
        covariance[index(POSITION_OFFSET, POSITION_OFFSET)] = accuracy * accuracy
        covariance[index(POSITION_OFFSET + 1, POSITION_OFFSET + 1)] = accuracy * accuracy
        val velocityVariance = velocity
            ?.let { max(it.covariance[0], it.covariance[3]) }
            ?: (config.maximumSpeedMetersPerSecond * config.maximumSpeedMetersPerSecond)
        covariance[index(VELOCITY_OFFSET, VELOCITY_OFFSET)] = velocityVariance
        covariance[index(VELOCITY_OFFSET + 1, VELOCITY_OFFSET + 1)] = velocityVariance
    }

    /**
     * Оценка скорости вместе с её собственной неопределённостью.
     *
     * Это то, ради чего детектору остановок вообще нужен фильтр. Раньше он выбирал большее
     * из двух: медианы доплеровской скорости по окну и скорости по смещению за окно. Первая
     * есть не всегда и изредка выдаёт одиночные выбросы в 3–4 м/с на стоящем месте, вторая
     * при погрешности 20 м и шаге 5 с состоит из шума на две трети. Фильтр объединяет оба
     * источника с весами по их погрешностям — то есть делает ровно то, что делалось руками,
     * но правильно, — и вдобавок говорит, насколько сам себе верит.
     */
    fun estimatedSpeed(): EstimatedSpeed {
        val east = state[VELOCITY_OFFSET]
        val north = state[VELOCITY_OFFSET + 1]
        val speed = hypot(east, north)
        val eastVariance = covariance[index(VELOCITY_OFFSET, VELOCITY_OFFSET)]
        val northVariance = covariance[index(VELOCITY_OFFSET + 1, VELOCITY_OFFSET + 1)]
        val crossVariance = covariance[index(VELOCITY_OFFSET, VELOCITY_OFFSET + 1)]
        // Неопределённость модуля — это неопределённость вдоль самого вектора скорости.
        // На месте вектор вырождается, направления нет, и остаётся средняя по осям.
        val variance = if (speed > DEGENERATE_SPEED_METERS_PER_SECOND) {
            val unitEast = east / speed
            val unitNorth = north / speed
            unitEast * unitEast * eastVariance +
                2.0 * unitEast * unitNorth * crossVariance +
                unitNorth * unitNorth * northVariance
        } else {
            (eastVariance + northVariance) / 2.0
        }
        return EstimatedSpeed(
            metersPerSecond = speed,
            uncertaintyMetersPerSecond = sqrt(variance.coerceAtLeast(0.0)),
        )
    }

    fun position(): Position = Position(
        latitude = anchorLatitude + state[NORTH] / METERS_PER_DEGREE_LATITUDE,
        longitude = anchorLongitude + state[EAST] / metersPerDegreeLongitude,
    )

    fun snapshot(): StepState = StepState(
        anchorLatitude = anchorLatitude,
        anchorLongitude = anchorLongitude,
        metersPerDegreeLongitude = metersPerDegreeLongitude,
        transition = identityMatrix(),
        predicted = state.copyOf(),
        predictedCovariance = covariance.copyOf(),
        filtered = state.copyOf(),
        filteredCovariance = covariance.copyOf(),
    )

    /**
     * Учитывает точку; `null` — точка забракована гейтом невязки.
     *
     * Состояние меняется только при успехе. Иначе отброшенный фикс всё равно сдвигал бы
     * время фильтра вперёд, и следующая точка предсказывалась бы не от той опоры.
     */
    fun step(point: GeoPoint, config: TrackProcessingConfig): StepState? {
        val elapsedSeconds = ((point.timestampEpochMillis - timestampEpochMillis) / 1_000.0)
            .coerceAtLeast(0.0)
        val transition = transitionMatrix(elapsedSeconds)
        val predicted = multiplyVector(transition, state)
        val predictedCovariance = add(
            multiply(multiply(transition, covariance), transpose(transition)),
            processNoise(elapsedSeconds, config),
        )

        var currentState = predicted
        var currentCovariance = predictedCovariance

        val accuracy = point.measurementAccuracyMeters(config)
        val positionCorrection = correction(
            state = currentState,
            covariance = currentCovariance,
            offset = POSITION_OFFSET,
            measurement = point.positionMeasurement(),
            noise = isotropicNoise(accuracy),
        )
        if (positionCorrection != null) {
            // Точку, поставленную вручную, гейт не проверяет: она и есть эталон, с которым
            // фильтру полагается согласиться, даже если он с ней не согласен.
            if (!point.isManuallyEdited &&
                positionCorrection.distanceSquared > config.outlierGateChiSquare
            ) {
                return null
            }
            currentState = positionCorrection.state
            currentCovariance = positionCorrection.covariance
        }

        val velocity = point.velocityMeasurement(config, currentState)
        if (velocity != null) {
            val velocityCorrection = correction(
                state = currentState,
                covariance = currentCovariance,
                offset = VELOCITY_OFFSET,
                measurement = velocity.value,
                noise = velocity.covariance,
            )
            // Одиночные выбросы доплеровской скорости в 3–4 м/с на стоящем месте
            // провайдер выдаёт регулярно. Отбрасывается при этом только измерение
            // скорости: координаты этой же точки гейт уже принял, и терять их незачем.
            if (velocityCorrection != null &&
                velocityCorrection.distanceSquared <= config.outlierGateChiSquare
            ) {
                currentState = velocityCorrection.state
                currentCovariance = velocityCorrection.covariance
            }
        }

        state = currentState
        covariance = currentCovariance
        timestampEpochMillis = point.timestampEpochMillis

        return StepState(
            anchorLatitude = anchorLatitude,
            anchorLongitude = anchorLongitude,
            metersPerDegreeLongitude = metersPerDegreeLongitude,
            transition = transition,
            predicted = predicted,
            predictedCovariance = predictedCovariance,
            filtered = currentState.copyOf(),
            filteredCovariance = currentCovariance.copyOf(),
        )
    }

    private fun GeoPoint.positionMeasurement(): DoubleArray = doubleArrayOf(
        (longitude - anchorLongitude) * metersPerDegreeLongitude,
        (latitude - anchorLatitude) * METERS_PER_DEGREE_LATITUDE,
    )
}

/** Поправка оценки по измерению двух составляющих состояния. */
private class Correction(
    val state: DoubleArray,
    val covariance: DoubleArray,
    /**
     * Квадрат махаланобисова расстояния невязки: насколько измерение разошлось с
     * предсказанием, считая в единицах их общей неопределённости. Это и есть гейт —
     * порог в метрах подстраивать под сигнал пришлось бы вручную, а этот подстраивается
     * сам: чем шире ковариация, тем дальше позволено уехать измерению.
     */
    val distanceSquared: Double,
)

private fun correction(
    state: DoubleArray,
    covariance: DoubleArray,
    offset: Int,
    measurement: DoubleArray,
    noise: DoubleArray,
): Correction? {
    // S = H P Hᵀ + R.
    val s00 = covariance[index(offset, offset)] + noise[0]
    val s01 = covariance[index(offset, offset + 1)] + noise[1]
    val s10 = covariance[index(offset + 1, offset)] + noise[2]
    val s11 = covariance[index(offset + 1, offset + 1)] + noise[3]
    val determinant = s00 * s11 - s01 * s10
    if (!determinant.isFinite() || abs(determinant) < INNOVATION_EPSILON) return null

    val inverse = doubleArrayOf(
        s11 / determinant, -s01 / determinant,
        -s10 / determinant, s00 / determinant,
    )
    val innovation = doubleArrayOf(
        measurement[0] - state[offset],
        measurement[1] - state[offset + 1],
    )
    val distanceSquared = innovation[0] * (inverse[0] * innovation[0] + inverse[1] * innovation[1]) +
        innovation[1] * (inverse[2] * innovation[0] + inverse[3] * innovation[1])
    if (!distanceSquared.isFinite()) return null

    // K = P Hᵀ S⁻¹, где P Hᵀ — это два столбца ковариации.
    val gain = DoubleArray(STATE_SIZE * 2)
    for (row in 0 until STATE_SIZE) {
        val first = covariance[index(row, offset)]
        val second = covariance[index(row, offset + 1)]
        gain[row * 2] = first * inverse[0] + second * inverse[2]
        gain[row * 2 + 1] = first * inverse[1] + second * inverse[3]
    }

    val corrected = DoubleArray(STATE_SIZE) { row ->
        state[row] + gain[row * 2] * innovation[0] + gain[row * 2 + 1] * innovation[1]
    }

    // Форма Джозефа вместо короткой (I − KH)P: она остаётся симметричной и неотрицательно
    // определённой даже когда накопленная ошибка округления уводит короткую форму в минус.
    // На двухчасовом треке это несколько тысяч шагов подряд, и такой увод — вопрос времени.
    val identityMinusGain = identityMatrix()
    for (row in 0 until STATE_SIZE) {
        identityMinusGain[index(row, offset)] -= gain[row * 2]
        identityMinusGain[index(row, offset + 1)] -= gain[row * 2 + 1]
    }
    val gainNoiseGain = DoubleArray(STATE_SIZE * STATE_SIZE)
    for (row in 0 until STATE_SIZE) {
        for (column in 0 until STATE_SIZE) {
            var sum = 0.0
            for (first in 0 until 2) {
                for (second in 0 until 2) {
                    sum += gain[row * 2 + first] * noise[first * 2 + second] * gain[column * 2 + second]
                }
            }
            gainNoiseGain[index(row, column)] = sum
        }
    }
    val correctedCovariance = add(
        multiply(multiply(identityMinusGain, covariance), transpose(identityMinusGain)),
        gainNoiseGain,
    )

    return Correction(corrected, correctedCovariance, distanceSquared)
}

private const val INNOVATION_EPSILON = 1e-9

private fun index(row: Int, column: Int): Int = row * STATE_SIZE + column

/** F: за время dt положение прирастает скоростью, сама скорость не меняется. */
private fun transitionMatrix(elapsedSeconds: Double): DoubleArray =
    identityMatrix().also { matrix ->
        matrix[index(0, VELOCITY_OFFSET)] = elapsedSeconds
        matrix[index(1, VELOCITY_OFFSET + 1)] = elapsedSeconds
    }

/**
 * Шум процесса модели непрерывного белого ускорения.
 *
 * Это и есть та самая «свобода», которую модель оставляет человеку: постоянная скорость —
 * лишь предположение, а ускоряться он вправе, и параметр говорит насколько. Слишком малое
 * значение делает фильтр упрямым — он срезает повороты; слишком большое возвращает
 * доверие к каждому шумному фиксу.
 */
private fun processNoise(elapsedSeconds: Double, config: TrackProcessingConfig): DoubleArray {
    val acceleration = config.processNoiseAccelerationMetersPerSecondSquared
    val spectralDensity = acceleration * acceleration
    val positionVariance = spectralDensity * elapsedSeconds * elapsedSeconds * elapsedSeconds / 3.0
    val crossVariance = spectralDensity * elapsedSeconds * elapsedSeconds / 2.0
    val velocityVariance = spectralDensity * elapsedSeconds

    val noise = DoubleArray(STATE_SIZE * STATE_SIZE)
    for (axis in 0 until 2) {
        val position = POSITION_OFFSET + axis
        val velocity = VELOCITY_OFFSET + axis
        noise[index(position, position)] = positionVariance
        noise[index(position, velocity)] = crossVariance
        noise[index(velocity, position)] = crossVariance
        noise[index(velocity, velocity)] = velocityVariance
    }
    return noise
}

private fun isotropicNoise(sigma: Double): DoubleArray {
    val variance = sigma * sigma
    return doubleArrayOf(variance, 0.0, 0.0, variance)
}

private fun GeoPoint.measurementAccuracyMeters(config: TrackProcessingConfig): Double {
    if (isManuallyEdited) return MANUAL_CORRECTION_ACCURACY_METERS
    val reported = accuracyMeters
        ?.toDouble()
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: UNKNOWN_ACCURACY_METERS
    // Нижняя граница нужна, потому что приёмник регулярно занижает свою погрешность:
    // заявленный метр в городе означает лишь хорошую геометрию спутников, а не то, что
    // переотражений нет. Без пола фильтр поверил бы такому фиксу почти безоговорочно.
    return max(reported, config.positionNoiseFloorMeters)
}

/** Измерение скорости: вектор в осях восток—север и его ковариация. */
private class VelocityMeasurement(val value: DoubleArray, val covariance: DoubleArray)

private fun GeoPoint.velocityMeasurement(
    config: TrackProcessingConfig,
    predicted: DoubleArray,
): VelocityMeasurement? {
    val speed = speedMetersPerSecond
        ?.toDouble()
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?: return null
    val reportedSpeedSigma = speedAccuracyMetersPerSecond
        ?.toDouble()
        ?.takeIf { it.isFinite() && it > 0.0 }
    val speedSigma = reportedSpeedSigma ?: config.defaultSpeedAccuracyMetersPerSecond

    val reportedBearing = bearingDegrees?.toDouble()?.takeIf { it.isFinite() }
    // Курс есть не у всех приёмников, а модуль скорости — самое точное, что они сообщают,
    // и терять его нельзя. Когда направления нет, оно берётся из предсказания фильтра:
    // модуль при этом остаётся измерением, а вот поперечная составляющая объявляется
    // почти неизвестной, чтобы предсказанное направление не выдавалось за измеренное.
    val predictedEast = predicted[VELOCITY_OFFSET]
    val predictedNorth = predicted[VELOCITY_OFFSET + 1]
    val predictedSpeed = hypot(predictedEast, predictedNorth)

    if (reportedBearing == null && predictedSpeed <= DIRECTIONLESS_SPEED_METERS_PER_SECOND) {
        // Направления нет ни у измерения, ни у предсказания. Утверждать про вектор можно
        // только одно и только пока сам модуль мал: если приёмник говорит «ноль», то обе
        // составляющие близки к нулю, куда бы вы ни смотрели. Ровно этот случай и гасит
        // дрожание на месте.
        //
        // Условие проверяется по измеренному модулю, а не по модулю с запасом на
        // погрешность. Иначе приёмник, который не сообщает точность скорости, лишался бы
        // этого измерения совсем: неизвестная точность подставляется большой, и запас
        // сразу перекрывал бы порог. Насколько верить нулю, решает не это условие,
        // а вес — им и служит погрешность.
        if (speed > config.stopSpeedMetersPerSecond) return null
        // Ноль без заявленной точности — это не измерение покоя, а его отсутствие.
        // `Location.hasSpeed()` возвращает true и у провайдеров, которые скорость не меряют
        // вовсе: поле просто остаётся нулевым. Отличить «намерил ноль» от «не мерил» можно
        // единственным способом — по тому, сообщил ли провайдер погрешность скорости.
        // Раньше такой ноль принимался за доказательство, и на слабом сигнале автопауза
        // включалась посреди ходьбы.
        if (reportedSpeedSigma == null) return null
        return VelocityMeasurement(doubleArrayOf(0.0, 0.0), isotropicNoise(speed + speedSigma))
    }

    val heading = reportedBearing?.let(Math::toRadians)
        ?: kotlin.math.atan2(predictedEast, predictedNorth)
    val bearingSigma = if (reportedBearing == null) {
        DIRECTION_FROM_PREDICTION_SIGMA_RADIANS
    } else {
        Math.toRadians(
            bearingAccuracyDegrees?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
                ?: config.defaultBearingAccuracyDegrees,
        )
    }
    // Вдоль курса погрешность задаёт доплер, поперёк — неуверенность в самом курсе, и
    // тем сильнее, чем быстрее движение. Ниже доплеровской она опуститься не может:
    // на малой скорости курс вырождается, и утверждать, что поперечная составляющая
    // известна точнее продольной, было бы неправдой.
    val alongVariance = speedSigma * speedSigma
    val crossSigma = max(speed * bearingSigma, speedSigma)
    val crossVariance = crossSigma * crossSigma
    val alongEast = sin(heading)
    val alongNorth = cos(heading)

    return VelocityMeasurement(
        value = doubleArrayOf(speed * alongEast, speed * alongNorth),
        covariance = doubleArrayOf(
            alongVariance * alongEast * alongEast + crossVariance * alongNorth * alongNorth,
            (alongVariance - crossVariance) * alongEast * alongNorth,
            (alongVariance - crossVariance) * alongEast * alongNorth,
            alongVariance * alongNorth * alongNorth + crossVariance * alongEast * alongEast,
        ),
    )
}
