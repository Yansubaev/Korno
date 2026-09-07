package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import kotlin.math.max

data class TrackStatistics(
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    val elapsedTimeMillis: Long = 0L,
    val averageSpeedMetersPerSecond: Double = 0.0,
    val maxSpeedMetersPerSecond: Double = 0.0,
    val currentSpeedMetersPerSecond: Double = 0.0,
    val elevationGainMeters: Double = 0.0,
    /**
     * Время, проведённое в разрывах трека: между соседними точками прошло больше
     * [TrackProcessingConfig.maximumSegmentDurationMillis], и что происходило внутри,
     * запись не знает. Величина диагностическая — по ней видно, какой доле активности
     * вообще можно верить.
     */
    val unmeasuredTimeMillis: Long = 0L,
    val gapCount: Int = 0,
)

class StatisticsCalculatorUseCase {
    operator fun invoke(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
    ): TrackStatistics {
        val accumulator = TrackStatisticsAccumulator(config)
        points
            .filterNot(GeoPoint::isOutlier)
            .sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id))
            .forEach(accumulator::add)
        return accumulator.snapshot()
    }
}

/**
 * Пошаговый счёт статистики трека.
 *
 * Вынесен из [StatisticsCalculatorUseCase] в отдельное состояние, потому что живой записи
 * нужен именно он: полный пересчёт всего трека на каждый сэмпл — это n копий каждой точки
 * на каждый шаг и, что заметнее пользователю, непостоянная дистанция (сглаживание двигает
 * предпоследние точки, и число на экране уезжает назад). См. [IncrementalTrackProcessor].
 *
 * Точки должны подаваться в порядке времени и уже без выбросов.
 */
class TrackStatisticsAccumulator private constructor(
    private val config: TrackProcessingConfig,
    private var previous: GeoPoint?,
    /**
     * Последняя точка, смещение от которой зачтено в дистанцию. Порог шума применяется
     * к смещению от неё, а не к каждому сегменту: при опросе раз в секунду шаг пешехода
     * короче погрешности, и посегментный порог съел бы весь маршрут.
     */
    private var anchor: GeoPoint?,
    /**
     * Точки последнего окна для максимальной скорости. Хранится не больше
     * [TrackProcessingConfig.maximumSpeedWindowMillis], то есть единицы точек.
     */
    private val speedWindow: ArrayDeque<GeoPoint>,
    private var distance: Double,
    private var movingTime: Long,
    private var elapsedTime: Long,
    private var maximumSpeed: Double,
    private var currentSpeed: Double,
    private var elevationGain: Double,
    private var unmeasuredTime: Long,
    private var gapCount: Int,
) {
    constructor(config: TrackProcessingConfig) : this(
        config = config,
        previous = null,
        anchor = null,
        speedWindow = ArrayDeque(),
        distance = 0.0,
        movingTime = 0L,
        elapsedTime = 0L,
        maximumSpeed = 0.0,
        currentSpeed = 0.0,
        elevationGain = 0.0,
        unmeasuredTime = 0L,
        gapCount = 0,
    )

    fun copy(): TrackStatisticsAccumulator = TrackStatisticsAccumulator(
        config = config,
        previous = previous,
        anchor = anchor,
        speedWindow = ArrayDeque(speedWindow),
        distance = distance,
        movingTime = movingTime,
        elapsedTime = elapsedTime,
        maximumSpeed = maximumSpeed,
        currentSpeed = currentSpeed,
        elevationGain = elevationGain,
        unmeasuredTime = unmeasuredTime,
        gapCount = gapCount,
    )

    fun add(point: GeoPoint) {
        val first = previous
        previous = point
        if (first == null) {
            anchor = point
            return
        }

        val elapsedMillis = point.timestampEpochMillis - first.timestampEpochMillis
        if (elapsedMillis <= 0L) return

        // Обе границы ручной паузы исключаются: скачок к первой паузной точке,
        // сам путь на паузе и скачок обратно не меняют статистику активности.
        if (
            first.recordingStatus == RecordingStatus.MANUAL_PAUSE ||
            point.recordingStatus == RecordingStatus.MANUAL_PAUSE
        ) {
            currentSpeed = 0.0
            anchor = point
            speedWindow.clear()
            return
        }
        elapsedTime += elapsedMillis

        if (point.isStop) {
            currentSpeed = 0.0
            // Дрожание на стоянке не должно накапливаться в дистанции после возобновления,
            // поэтому опорная точка переносится на конец стоянки.
            anchor = point
            speedWindow.clear()
            return
        }
        movingTime += elapsedMillis

        val anchorPoint = anchor ?: first
        val anchoredDistance = distanceMeters(anchorPoint, point)
        if (anchoredDistance >= noiseFloorMeters(anchorPoint, point, config.noiseDeadbandFactor)) {
            distance += anchoredDistance
            anchor = point
        }

        // Дистанцию разрыва мы всё же засчитываем: прямая между фиксами — нижняя оценка
        // пройденного, и выбросить её значит недосчитать реально пройденный путь. А вот
        // скорость по ней не значит ничего: именно так на прогулке появлялся максимум
        // в 39 км/ч.
        if (elapsedMillis > config.maximumSegmentDurationMillis) {
            unmeasuredTime += elapsedMillis
            gapCount++
            currentSpeed = 0.0
            // За разрывом окно начинается заново: прямая через дыру в него не входит.
            speedWindow.clear()
        } else {
            currentSpeed = distanceMeters(first, point) / (elapsedMillis / 1_000.0)
            updateMaximumSpeed(first, point)
        }

        val elevationDelta = first.altitudeMeters?.let { firstAltitude ->
            point.altitudeMeters?.minus(firstAltitude)
        }
        if (elevationDelta != null && elevationDelta > config.elevationNoiseMeters) {
            elevationGain += elevationDelta
        }
    }

    /**
     * Максимальная скорость по скользящему окну, а не по одному отрезку.
     *
     * За окно шум координат усредняется, а настоящий рывок — нет. По одному отрезку в семь
     * секунд при типичной городской погрешности неотличимы прогулка и велосипед: именно
     * так на прогулке сохранялся максимум в 39 км/ч.
     */
    private fun updateMaximumSpeed(previousPoint: GeoPoint, point: GeoPoint) {
        if (speedWindow.isEmpty()) speedWindow.addLast(previousPoint)
        speedWindow.addLast(point)
        while (
            speedWindow.size > 2 &&
            point.timestampEpochMillis - speedWindow.first().timestampEpochMillis >
            config.maximumSpeedWindowMillis
        ) {
            speedWindow.removeFirst()
        }
        val first = speedWindow.first()
        val windowMillis = point.timestampEpochMillis - first.timestampEpochMillis
        if (windowMillis < config.minimumSpeedWindowMillis) return
        maximumSpeed = max(maximumSpeed, distanceMeters(first, point) / (windowMillis / 1_000.0))
    }

    fun snapshot(): TrackStatistics = TrackStatistics(
        distanceMeters = distance,
        movingTimeMillis = movingTime,
        elapsedTimeMillis = elapsedTime,
        averageSpeedMetersPerSecond = if (movingTime > 0L) distance / (movingTime / 1_000.0) else 0.0,
        maxSpeedMetersPerSecond = maximumSpeed,
        currentSpeedMetersPerSecond = currentSpeed,
        elevationGainMeters = elevationGain,
        unmeasuredTimeMillis = unmeasuredTime,
        gapCount = gapCount,
    )
}
