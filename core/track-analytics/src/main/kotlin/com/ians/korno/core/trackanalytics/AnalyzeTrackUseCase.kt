package com.ians.korno.core.trackanalytics

import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.trackprocessing.haversineMeters
import kotlin.math.abs

/**
 * Считает по записанному треку всё, что показывает экран активности: выборку для карты и
 * графиков, сплиты, остановки и профиль высоты.
 *
 * Правила учёта совпадают с [com.ians.korno.core.trackprocessing.StatisticsCalculatorUseCase]
 * намеренно: сумма сплитов обязана сходиться с дистанцией активности, а время движения — с
 * тем, что уже показано в шапке экрана.
 */
class AnalyzeTrackUseCase {

    /**
     * @param splitLengthMeters длина сплита: километр или миля, в зависимости от настройки.
     */
    operator fun invoke(
        points: List<GeoPoint>,
        splitLengthMeters: Double,
        config: TrackAnalysisConfig = TrackAnalysisConfig(),
    ): TrackAnalysis {
        require(splitLengthMeters > 0.0) { "splitLengthMeters must be positive" }

        val ordered = points
            .filterNot(GeoPoint::isOutlier)
            .sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id))
        if (ordered.size < 2) return TrackAnalysis.Empty

        val track = ordered.toTrackEdges()
        val speeds = track.smoothSpeeds(config.speedWindowMillis)
        val altitudes = ordered.smoothAltitudes(track.cumulativeDistance, config.elevationWindowMeters)

        val samples = ordered.mapIndexed { index, point ->
            val isPaused = point.recordingStatus == RecordingStatus.MANUAL_PAUSE
            TrackSample(
                latitude = point.latitude,
                longitude = point.longitude,
                timeOffsetMillis = track.timeOffset[index],
                distanceMeters = track.cumulativeDistance[index],
                altitudeMeters = altitudes[index],
                // На ручной паузе скорости нет, а не ноль: пользователь мог за это время
                // проехать на транспорте, и показывать такое как медленный участок нельзя.
                speedMetersPerSecond = if (isPaused) null else speeds[index],
                isPaused = isPaused,
                isStop = point.isStop,
            )
        }

        val splits = track.splits(splitLengthMeters, altitudes)
        return TrackAnalysis(
            samples = samples,
            totalDistanceMeters = track.totalDistanceMeters,
            splits = splits,
            fastestSplit = splits
                .filterNot(TrackSplit::isPartial)
                .maxByOrNull(TrackSplit::speedMetersPerSecond),
            stops = StopAnalysis(
                movingMillis = track.movingMillis,
                autoStopMillis = track.autoStopMillis,
                manualPauseMillis = track.manualPauseMillis,
                segments = samples.stopSegments(config.minimumStopMillis),
            ),
            peakSpeedMetersPerSecond = samples
                .filter { !it.isPaused && !it.isStop }
                .mapNotNull(TrackSample::speedMetersPerSecond)
                .maxOrNull(),
            elevation = altitudes.toElevationAnalysis(config.elevationNoiseMeters),
            speedScale = samples.toSpeedScale(config),
            elevationScale = altitudes.toElevationScale(),
        )
    }
}

/**
 * Трек, разложенный по рёбрам.
 *
 * Скорость, дистанция и время принадлежат отрезку между двумя измерениями, а не отдельной
 * координате, поэтому все массивы, кроме [timeOffset] и [cumulativeDistance], описывают
 * ребро, приходящее в точку с этим индексом. У нулевой точки ребра нет.
 */
private class TrackEdges(
    val timeOffset: LongArray,
    val cumulativeDistance: DoubleArray,
    /** Длина ребра; на стоянке ноль: расстояние там — дрожание GPS. */
    val edgeDistance: DoubleArray,
    /** Время движения по ребру; на стоянке ноль. */
    val edgeMovingMillis: LongArray,
    /** Полное время ребра без ручных пауз. */
    val edgeElapsedMillis: LongArray,
    /** Ребро учитывается в статистике: обе его точки вне ручной паузы. */
    val edgeCounts: BooleanArray,
    val totalDistanceMeters: Double,
    val movingMillis: Long,
    val autoStopMillis: Long,
    val manualPauseMillis: Long,
) {
    val size: Int get() = timeOffset.size
}

private fun List<GeoPoint>.toTrackEdges(): TrackEdges {
    val startTime = first().timestampEpochMillis
    val timeOffset = LongArray(size)
    val cumulativeDistance = DoubleArray(size)
    val edgeDistance = DoubleArray(size)
    val edgeMovingMillis = LongArray(size)
    val edgeElapsedMillis = LongArray(size)
    val edgeCounts = BooleanArray(size)

    var distance = 0.0
    var movingMillis = 0L
    var autoStopMillis = 0L
    var manualPauseMillis = 0L

    for (index in 1..lastIndex) {
        val previous = this[index - 1]
        val current = this[index]
        timeOffset[index] = current.timestampEpochMillis - startTime
        cumulativeDistance[index] = distance

        val durationMillis = current.timestampEpochMillis - previous.timestampEpochMillis
        if (durationMillis <= 0L) continue

        // Обе границы ручной паузы выпадают целиком: скачок к первой паузной точке, путь
        // на паузе и скачок обратно не относятся к активности.
        if (
            previous.recordingStatus == RecordingStatus.MANUAL_PAUSE ||
            current.recordingStatus == RecordingStatus.MANUAL_PAUSE
        ) {
            manualPauseMillis += durationMillis
            continue
        }

        edgeCounts[index] = true
        edgeElapsedMillis[index] = durationMillis
        if (current.isStop) {
            autoStopMillis += durationMillis
        } else {
            val segment = haversineMeters(
                previous.latitude,
                previous.longitude,
                current.latitude,
                current.longitude,
            )
            distance += segment
            movingMillis += durationMillis
            edgeDistance[index] = segment
            edgeMovingMillis[index] = durationMillis
            cumulativeDistance[index] = distance
        }
    }

    return TrackEdges(
        timeOffset = timeOffset,
        cumulativeDistance = cumulativeDistance,
        edgeDistance = edgeDistance,
        edgeMovingMillis = edgeMovingMillis,
        edgeElapsedMillis = edgeElapsedMillis,
        edgeCounts = edgeCounts,
        totalDistanceMeters = distance,
        movingMillis = movingMillis,
        autoStopMillis = autoStopMillis,
        manualPauseMillis = manualPauseMillis,
    )
}

/**
 * Скорость как «пройдено за окно», а не как среднее мгновенных значений: усреднять
 * скорости неравных по времени рёбер значило бы взвешивать их одинаково.
 */
private fun TrackEdges.smoothSpeeds(windowMillis: Long): Array<Double?> {
    val result = arrayOfNulls<Double>(size)
    if (size < 2) return result

    // Момент ребра — середина отрезка: так окно вокруг точки симметрично по времени.
    val edgeTime = LongArray(size) { index ->
        if (index == 0) 0L else (timeOffset[index - 1] + timeOffset[index]) / 2
    }
    val half = windowMillis / 2

    var low = 1
    var high = 1
    var windowDistance = 0.0
    var windowMillis = 0L

    for (index in 0 until size) {
        val from = timeOffset[index] - half
        val to = timeOffset[index] + half
        while (high < size && edgeTime[high] <= to) {
            if (edgeCounts[high]) {
                windowDistance += edgeDistance[high]
                windowMillis += edgeElapsedMillis[high]
            }
            high++
        }
        while (low < high && edgeTime[low] < from) {
            if (edgeCounts[low]) {
                windowDistance -= edgeDistance[low]
                windowMillis -= edgeElapsedMillis[low]
            }
            low++
        }
        // Знаменатель — полное время окна, а не только время движения: стоянка внутри
        // окна должна тянуть скорость вниз, иначе на карте она ничем не отличается
        // от нормального хода.
        result[index] = if (windowMillis > 0L) windowDistance / (windowMillis / 1_000.0) else null
    }
    return result
}

/**
 * Высота сглаживается по дистанции, а не по числу точек: на стоянке точки копятся на месте
 * и окно из «десяти соседей» схлопнулось бы в одну координату.
 */
private fun List<GeoPoint>.smoothAltitudes(
    cumulativeDistance: DoubleArray,
    windowMeters: Double,
): Array<Double?> {
    val result = arrayOfNulls<Double>(size)
    if (none { it.altitudeMeters != null }) return result

    val half = windowMeters / 2
    var low = 0
    var high = 0
    var sum = 0.0
    var count = 0

    for (index in indices) {
        val from = cumulativeDistance[index] - half
        val to = cumulativeDistance[index] + half
        while (high < size && cumulativeDistance[high] <= to) {
            this[high].altitudeMeters?.let { sum += it; count++ }
            high++
        }
        while (low < high && cumulativeDistance[low] < from) {
            this[low].altitudeMeters?.let { sum -= it; count-- }
            low++
        }
        result[index] = if (count > 0) sum / count else this[index].altitudeMeters
    }
    return result
}

/**
 * Рёбра распределяются между сплитами пропорционально длине: ребро длиной в десяток метров
 * может пересечь границу километра, и целиком отдать его одному из сплитов значило бы
 * сдвинуть отметку.
 */
private fun TrackEdges.splits(
    splitLengthMeters: Double,
    altitudes: Array<Double?>,
): List<TrackSplit> {
    if (totalDistanceMeters <= 0.0) return emptyList()

    val splits = mutableListOf<TrackSplit>()
    var number = 1
    var boundary = splitLengthMeters
    var splitStartDistance = 0.0
    var splitMoving = 0L
    var splitElapsed = 0L
    var splitStartAltitude = altitudes.firstOrNull()

    for (index in 1 until size) {
        if (!edgeCounts[index]) continue

        var remainingDistance = edgeDistance[index]
        var remainingMoving = edgeMovingMillis[index]
        var remainingElapsed = edgeElapsedMillis[index]
        var cursor = cumulativeDistance[index - 1]

        while (remainingDistance > 0.0 && cursor + remainingDistance >= boundary) {
            val take = boundary - cursor
            val fraction = take / remainingDistance
            val movingPart = (remainingMoving * fraction).toLong()
            val elapsedPart = (remainingElapsed * fraction).toLong()
            val endAltitude = altitudes[index]

            splits += TrackSplit(
                number = number,
                startDistanceMeters = splitStartDistance,
                endDistanceMeters = boundary,
                movingMillis = splitMoving + movingPart,
                elapsedMillis = splitElapsed + elapsedPart,
                speedMetersPerSecond = speedOf(boundary - splitStartDistance, splitMoving + movingPart),
                elevationDeltaMeters = elevationDelta(splitStartAltitude, endAltitude),
                isPartial = false,
            )

            remainingDistance -= take
            remainingMoving -= movingPart
            remainingElapsed -= elapsedPart
            cursor = boundary

            number++
            splitStartDistance = boundary
            boundary += splitLengthMeters
            splitMoving = 0L
            splitElapsed = 0L
            splitStartAltitude = endAltitude
        }

        splitMoving += remainingMoving
        splitElapsed += remainingElapsed
    }

    val tailDistance = totalDistanceMeters - splitStartDistance
    if (tailDistance > 1.0) {
        splits += TrackSplit(
            number = number,
            startDistanceMeters = splitStartDistance,
            endDistanceMeters = totalDistanceMeters,
            movingMillis = splitMoving,
            elapsedMillis = splitElapsed,
            speedMetersPerSecond = speedOf(tailDistance, splitMoving),
            elevationDeltaMeters = elevationDelta(splitStartAltitude, altitudes.lastNotNull()),
            isPartial = true,
        )
    }
    return splits
}

private fun speedOf(distanceMeters: Double, movingMillis: Long): Double =
    if (movingMillis > 0L) distanceMeters / (movingMillis / 1_000.0) else 0.0

private fun elevationDelta(start: Double?, end: Double?): Double? =
    if (start != null && end != null) end - start else null

private fun Array<Double?>.lastNotNull(): Double? = lastOrNull { it != null }

/**
 * Набор и сброс с гистерезисом: пока профиль не отошёл от опорной высоты дальше шума,
 * колебание не засчитывается. Без этого шум GPS-высоты за час превращается в километр
 * несуществующего набора.
 */
private fun Array<Double?>.toElevationAnalysis(noiseMeters: Double): ElevationAnalysis? {
    var minimum = Double.MAX_VALUE
    var maximum = -Double.MAX_VALUE
    var lowestIndex = -1
    var highestIndex = -1
    var gain = 0.0
    var loss = 0.0
    var reference: Double? = null

    forEachIndexed { index, value ->
        if (value == null) return@forEachIndexed
        if (value < minimum) {
            minimum = value
            lowestIndex = index
        }
        if (value > maximum) {
            maximum = value
            highestIndex = index
        }
        val previous = reference
        if (previous == null) {
            reference = value
            return@forEachIndexed
        }
        val delta = value - previous
        if (delta > noiseMeters) {
            gain += delta
            reference = value
        } else if (delta < -noiseMeters) {
            loss += -delta
            reference = value
        }
    }

    if (lowestIndex < 0) return null
    return ElevationAnalysis(
        minimumMeters = minimum,
        maximumMeters = maximum,
        gainMeters = gain,
        lossMeters = loss,
        lowestSampleIndex = lowestIndex,
        highestSampleIndex = highestIndex,
    )
}

private fun Array<Double?>.toElevationScale(): ElevationScale? {
    val values = filterNotNull()
    if (values.isEmpty()) return null
    return ElevationScale(lowestMeters = values.min(), highestMeters = values.max())
}

private fun List<TrackSample>.toSpeedScale(config: TrackAnalysisConfig): SpeedScale? {
    val moving = filter { !it.isPaused && !it.isStop }
        .mapNotNull(TrackSample::speedMetersPerSecond)
        .filter { it > 0.0 }
        .sorted()
    if (moving.isEmpty()) return null

    val low = moving.percentile(config.speedScaleLowPercentile)
    val high = moving.percentile(config.speedScaleHighPercentile)
    // Ровный участок трека даёт вырожденный диапазон; расширяем его, иначе шкала
    // покрасит весь маршрут одним цветом на границе округления.
    return if (abs(high - low) < 0.05) {
        SpeedScale(slowestMetersPerSecond = low * 0.9, fastestMetersPerSecond = high * 1.1 + 0.05)
    } else {
        SpeedScale(slowestMetersPerSecond = low, fastestMetersPerSecond = high)
    }
}

/** Ближайший ранг: интерполяция между соседями здесь ничего не уточняет. */
private fun List<Double>.percentile(fraction: Double): Double {
    val position = ((size - 1) * fraction).toInt().coerceIn(0, lastIndex)
    return this[position]
}

/**
 * Соседние точки одного состояния — одна остановка. Короткие отсеиваются: несколько секунд
 * «стоянки» посреди движения почти всегда означают потерю сигнала, а не остановку.
 */
private fun List<TrackSample>.stopSegments(minimumStopMillis: Long): List<StopSegment> {
    val segments = mutableListOf<StopSegment>()
    var start = -1
    var manual = false

    fun close(endIndex: Int) {
        if (start < 0) return
        val first = this[start]
        val last = this[endIndex]
        val duration = last.timeOffsetMillis - first.timeOffsetMillis
        if (duration >= minimumStopMillis) {
            val middle = this[(start + endIndex) / 2]
            segments += StopSegment(
                latitude = middle.latitude,
                longitude = middle.longitude,
                startTimeOffsetMillis = first.timeOffsetMillis,
                durationMillis = duration,
                distanceFromStartMeters = first.distanceMeters,
                isManualPause = manual,
            )
        }
        start = -1
    }

    forEachIndexed { index, sample ->
        val stopped = sample.isStop || sample.isPaused
        when {
            !stopped -> close(index - 1)
            start < 0 -> {
                start = index
                manual = sample.isPaused
            }
            // Ручная пауза посреди автоматической стоянки (и наоборот) — уже другая
            // остановка: у них разные причины и разное место в сводке.
            manual != sample.isPaused -> {
                close(index - 1)
                start = index
                manual = sample.isPaused
            }
        }
    }
    close(lastIndex)
    return segments
}
