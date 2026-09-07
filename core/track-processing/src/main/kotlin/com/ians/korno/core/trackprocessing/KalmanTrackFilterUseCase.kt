package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint

/**
 * Оценка положения фильтром Калмана с моделью постоянной скорости.
 *
 * Прежнее сглаживание было скользящим средним по координатам. У него нет представления
 * о том, что человек движется: оно усредняет положения, поэтому на повороте срезает угол,
 * на прямой отстаёт, а стоящую на месте точку успокаивает лишь во столько раз, во сколько
 * усреднение вообще давит шум. Хуже того, окно симметрично, значит для последней точки
 * трека соседей справа нет — и живая карта показывала сырой фикс, вообще не сглаженный.
 *
 * Здесь оценивается не координата, а состояние: положение **и** скорость. Тогда
 * появляется предсказание — «через секунду вы должны быть примерно вот здесь», — и
 * измерение уже не заменяет оценку, а лишь поправляет её с весом по своей погрешности.
 * Из этого следуют три вещи, которых у среднего не было:
 *
 * 1. **Оценка каузальна.** Она не ждёт будущих точек, поэтому последняя точка трека
 *    сглажена ровно так же, как все остальные.
 * 2. **Доплеровская скорость становится вторым измерением.** Приёмник считает её по
 *    сдвигу несущей, а не по разнице координат, и знает обычно на порядок точнее
 *    положения. Для среднего по координатам этой информации просто не существовало.
 * 3. **Выброс отсеивается по невязке, а не по порогу.** Точка отбрасывается, если она
 *    слишком далеко от предсказания в единицах собственной неопределённости — то есть
 *    порог подстраивается сам: на хорошем сигнале он узкий, на плохом широкий.
 *
 * Чего фильтр не делает: он не убирает систематический сдвиг. Если из-за переотражений
 * от домов приёмник полминуты считает, что вы на соседней улице, — это не дрожание, а
 * согласованная ошибка, и модель движения ей не противоречит. Против неё помогает только
 * привязка к карте, и это отдельная задача.
 */
class KalmanTrackFilterUseCase {

    /**
     * @param backwardPass считать ли обратный проход (сглаживание Рауха—Тунга—Штрибеля).
     *   Он использует будущие точки, поэтому доступен только готовому треку; живой записи
     *   достаётся прямой проход.
     */
    operator fun invoke(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
        backwardPass: Boolean = false,
    ): FilteredTrack {
        val estimated = points.toMutableList()
        val runs = mutableListOf<MutableList<SmootherStep>>()
        var filter: TrackKalmanFilter? = null
        var rejectionStreak = 0

        points.forEachIndexed { index, point ->
            // Точку, уже забракованную грубыми проверками, фильтр не трогает: она не
            // измерение, и подмешивать её в состояние нечем.
            if (point.isOutlier) return@forEachIndexed

            val active = filter
            val elapsedMillis = active?.let { point.timestampEpochMillis - it.timestampEpochMillis }
            // Через разрыв модель движения ничего не предсказывает: за минуту без единого
            // фикса можно уйти куда угодно, и продлевать старую скорость в эту дыру
            // значило бы придумывать траекторию. Участок начинается заново.
            val startsNewRun = active == null ||
                elapsedMillis == null ||
                elapsedMillis > config.maximumSegmentDurationMillis

            if (startsNewRun) {
                val started = TrackKalmanFilter(point, config)
                filter = started
                runs += mutableListOf(SmootherStep(index, started.snapshot()))
                rejectionStreak = 0
                return@forEachIndexed
            }

            val current = requireNotNull(active)
            val step = current.step(point, config)
            if (step == null) {
                rejectionStreak++
                estimated[index] = point.copy(isOutlier = true, isStop = false)
                // Три отказа подряд — это уже не выброс, а разошедшийся фильтр: приёмник
                // действительно переехал (вышли из здания, кончился тоннель), а модель
                // всё ещё держится за старое состояние и будет браковать всё подряд.
                // Дешевле поверить измерениям и начать участок заново.
                if (rejectionStreak >= config.maximumConsecutiveGateRejections) {
                    filter = null
                    rejectionStreak = 0
                }
                return@forEachIndexed
            }

            rejectionStreak = 0
            runs.last() += SmootherStep(index, step)
            // Точку, поправленную вручную, пользователь поставил сам — её координаты
            // остаются на месте, хотя состояние фильтра она обновляет как очень точное
            // измерение.
            if (!point.isManuallyEdited) {
                estimated[index] = point.withPosition(current.position())
            }
        }

        val smoothed = if (backwardPass) smoothBackwards(points.size, runs) else null
        return FilteredTrack(estimated, smoothed)
    }

    /**
     * Обратный проход по каждому непрерывному участку.
     *
     * Прямой проход знает о точке только прошлое, поэтому на манёвре неизбежно отстаёт:
     * он поворачивает уже после того, как повернули вы. Обратный проход добавляет к оценке
     * будущее — и отставание пропадает, потому что поворот виден с обеих сторон. Это то же
     * самое, что делало скользящее среднее, только с моделью движения и с правильными
     * весами: вклад соседа определяется его неопределённостью, а не расстоянием по списку.
     */
    private fun smoothBackwards(
        size: Int,
        runs: List<List<SmootherStep>>,
    ): List<Position?> {
        val smoothed = arrayOfNulls<Position>(size)
        runs.forEach { run ->
            if (run.size < 2) return@forEach
            var nextState = run.last().state.filtered
            var nextCovariance = run.last().state.filteredCovariance
            smoothed[run.last().index] = run.last().state.positionOf(nextState)

            for (position in run.lastIndex - 1 downTo 0) {
                val step = run[position]
                val next = run[position + 1]
                val predictedCovarianceInverse = invert(next.state.predictedCovariance)
                    // Вырожденная ковариация означает, что дальше назад расчёт
                    // недостоверен: остаток участка остаётся с оценкой прямого прохода.
                    ?: break

                val gain = multiply(
                    multiply(step.state.filteredCovariance, transpose(next.state.transition)),
                    predictedCovarianceInverse,
                )
                val stateCorrection = subtract(nextState, next.state.predicted)
                val state = add(step.state.filtered, multiplyVector(gain, stateCorrection))
                val covarianceCorrection = subtract(nextCovariance, next.state.predictedCovariance)
                val covariance = add(
                    step.state.filteredCovariance,
                    multiply(multiply(gain, covarianceCorrection), transpose(gain)),
                )

                smoothed[step.index] = step.state.positionOf(state)
                nextState = state
                nextCovariance = covariance
            }
        }
        return smoothed.toList()
    }
}

/** Географические координаты оценки — то, что фильтр возвращает вместо измеренных. */
internal data class Position(val latitude: Double, val longitude: Double)

/**
 * Результат прямого прохода.
 *
 * Координаты обратного прохода держатся отдельно, а не накладываются сразу, потому что
 * между проходами работает детектор остановок, и работать он должен по каузальной оценке.
 * Иначе будущее протекает в прошлое: сглаженная точка знает, что через десять секунд вы
 * пошли, и вердикт «стоит» сдвигается назад по времени.
 */
class FilteredTrack internal constructor(
    /** Точки с координатами прямого прохода и вердиктами гейта. */
    val points: List<GeoPoint>,
    private val smoothedPositions: List<Position?>?,
) {
    /**
     * Накладывает координаты обратного прохода на [points], сохраняя все прочие поля —
     * в том числе вердикты, проставленные между проходами.
     *
     * @param points список той же длины и того же порядка, что и [FilteredTrack.points].
     */
    fun withSmoothedPositions(points: List<GeoPoint>): List<GeoPoint> {
        val positions = smoothedPositions ?: return points
        return points.mapIndexed { index, point ->
            val position = positions.getOrNull(index)
            if (position == null || point.isOutlier || point.isManuallyEdited) {
                point
            } else {
                point.withPosition(position)
            }
        }
    }
}

private fun GeoPoint.withPosition(position: Position): GeoPoint =
    copy(latitude = position.latitude, longitude = position.longitude)
