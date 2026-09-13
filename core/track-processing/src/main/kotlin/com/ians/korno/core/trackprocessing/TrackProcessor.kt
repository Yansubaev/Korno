package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.slicePoints

data class ProcessedTrack(
    val points: List<GeoPoint>,
    val statistics: TrackStatistics,
)

/** Запись из нескольких сегментов после обработки: сегменты — с дистанцией по своим порогам. */
data class SegmentedTrack(
    val points: List<GeoPoint>,
    val statistics: TrackStatistics,
    val segments: List<ActivitySegment>,
)

/** Откуда пришли точки — от этого зависит, нужна ли им обработка вообще. */
enum class TrackSource {
    /** Сырые фиксы GPS или чужой файл: нужен весь pipeline. */
    RAW,

    /**
     * Файл, записанный самим Korno.
     *
     * Его точки — уже результат этого же pipeline: выбросы отброшены при экспорте,
     * остановки размечены, координаты сглажены. Второй проход сгладил бы сглаженное
     * и слегка увёл бы трек от того, что пользователь видел на карте до выгрузки,
     * поэтому здесь считается только статистика.
     */
    KORNO_EXPORT,
}

class TrackProcessor(
    private val outlierFilter: OutlierFilterUseCase = OutlierFilterUseCase(),
    private val kalmanFilter: KalmanTrackFilterUseCase = KalmanTrackFilterUseCase(),
    private val stopDetection: StopDetectionUseCase = StopDetectionUseCase(),
    private val statisticsCalculator: StatisticsCalculatorUseCase = StatisticsCalculatorUseCase(),
) {
    fun process(
        points: List<GeoPoint>,
        activityType: ActivityType,
        config: TrackProcessingConfig = TrackProcessingConfig.forActivityType(activityType),
        source: TrackSource = TrackSource.RAW,
    ): ProcessedTrack {
        val processedPoints = when (source) {
            TrackSource.RAW -> processPoints(points, config)
            // Порядок всё равно восстанавливается: статистика считается по соседним
            // точкам и на перемешанном списке посчитала бы не тот трек.
            TrackSource.KORNO_EXPORT -> points.sortedWith(
                compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id),
            )
        }
        return ProcessedTrack(
            points = processedPoints,
            statistics = statisticsCalculator(processedPoints, config),
        )
    }

    /**
     * Смешанная запись: точки делятся по времени сегментов, и каждый участок проходит
     * pipeline со своим профилем порогов — пешие пороги выкинули бы автобус, транспортные
     * пропустили бы дрожание пешехода.
     *
     * Дистанция, время и набор высоты — по всей записи. Средняя и максимальная скорость —
     * без транспорта: проеханный автобус не делает прогулку быстрее. Если кроме транспорта
     * ничего нет, скорость считается по нему.
     *
     * ponytail: участки обрабатываются независимо, фильтр на каждой границе стартует заново,
     * а отрезок между последней точкой одного участка и первой следующего в дистанцию не
     * входит (метры на границу). Сквозной проход с профилем на точку — если это станет видно.
     *
     * @param segments сегменты одной записи по порядку, закрытые; первые точки до начала
     *   первого сегмента и после конца последнего достаются им.
     */
    fun processSegments(
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
        configFor: (SegmentKind) -> TrackProcessingConfig,
    ): SegmentedTrack {
        val parts = segments.slicePoints(points).map { (segment, slice) ->
            val config = configFor(segment.kind)
            val processed = processPoints(slice, config)
            val statistics = statisticsCalculator(processed, config)
            SegmentPart(segment.copy(distanceMeters = statistics.distanceMeters), processed, statistics)
        }
        val speedParts = parts.filter { it.segment.kind != SegmentKind.VEHICLE }.ifEmpty { parts }
        val speedDistance = speedParts.sumOf { it.statistics.distanceMeters }
        val speedMovingTime = speedParts.sumOf { it.statistics.movingTimeMillis }
        return SegmentedTrack(
            points = parts.flatMap(SegmentPart::points),
            statistics = TrackStatistics(
                distanceMeters = parts.sumOf { it.statistics.distanceMeters },
                movingTimeMillis = parts.sumOf { it.statistics.movingTimeMillis },
                elapsedTimeMillis = parts.sumOf { it.statistics.elapsedTimeMillis },
                averageSpeedMetersPerSecond = if (speedMovingTime > 0L) {
                    speedDistance / (speedMovingTime / 1_000.0)
                } else {
                    0.0
                },
                maxSpeedMetersPerSecond = speedParts.maxOfOrNull { it.statistics.maxSpeedMetersPerSecond } ?: 0.0,
                elevationGainMeters = parts.sumOf { it.statistics.elevationGainMeters },
                unmeasuredTimeMillis = parts.sumOf { it.statistics.unmeasuredTimeMillis },
                gapCount = parts.sumOf { it.statistics.gapCount },
            ),
            segments = parts.map(SegmentPart::segment),
        )
    }

    private class SegmentPart(
        val segment: ActivitySegment,
        val points: List<GeoPoint>,
        val statistics: TrackStatistics,
    )

    /**
     * Вердикты по точкам без подсчёта статистики: живой записи статистика нужна
     * накопительная, а не пересчитанная с нуля (см. [IncrementalTrackProcessor]).
     *
     * @param backwardPass разрешён ли фильтру обратный проход. Он смотрит в будущее точки,
     *   поэтому доступен только готовому треку; живой записи будущего взять негде, и она
     *   получает каузальную оценку. Это единственное, чем обработка на экране записи
     *   отличается от итоговой.
     */
    internal fun processPoints(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
        backwardPass: Boolean = true,
    ): List<GeoPoint> = processTrack(points, config, backwardPass).points

    /**
     * То же самое вместе с оценкой скорости фильтром.
     *
     * Оценка нужна не расчёту, а диагностике: по логу должно быть видно, почему включилась
     * автопауза. Статистика для этого не годится — её `currentSpeed` на стоянке равен нулю
     * по определению (см. [TrackStatisticsAccumulator]), то есть показывает уже принятое
     * решение, а не то, из чего оно принято.
     */
    internal fun processTrack(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
        backwardPass: Boolean = true,
    ): ProcessedPoints = points
        .sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id))
        .splitWhen { previous, current ->
            previous.recordingStatus.isManualPause != current.recordingStatus.isManualPause
        }
        .map { segment ->
            // Грубые проверки идут до фильтра, а не вместо него. Гейт невязки судит
            // измерение по тому, насколько оно расходится с предсказанием, — но пока
            // фильтр не сошёлся, предсказывать ему нечем, и первую же дикую точку он
            // принял бы за истину и построил бы состояние вокруг неё.
            val filtered = outlierFilter(segment, config)
            val estimated = kalmanFilter(filtered, config, backwardPass)
            // Остановки ищутся по каузальной оценке, до обратного прохода. Обратный
            // проход подмешивает в точку её будущее, а детектор как раз и решает, когда
            // движение началось: сглаженная точка уже «знает», что через десять секунд
            // вы пошли, и граница стоянки уезжает назад по времени.
            val detected = if (segment.first().recordingStatus.isManualPause) {
                // Ручное перемещение на паузе нужно сохранить для карты, но оно не
                // участвует ни в автопаузе, ни в расчёте остановок.
                estimated.points.map { it.copy(isStop = false) }
            } else {
                stopDetection(estimated.points, config, estimated.speeds)
            }
            ProcessedPoints(estimated.withSmoothedPositions(detected), estimated.speeds)
        }
        .fold(ProcessedPoints(emptyList(), emptyList())) { accumulated, segment ->
            ProcessedPoints(accumulated.points + segment.points, accumulated.speeds + segment.speeds)
        }
}

/** Точки с вердиктами и оценка скорости фильтром для каждой из них; индексы совпадают. */
internal class ProcessedPoints(
    val points: List<GeoPoint>,
    val speeds: List<EstimatedSpeed?>,
)

private val RecordingStatus.isManualPause: Boolean
    get() = this == RecordingStatus.MANUAL_PAUSE

private inline fun <T> List<T>.splitWhen(crossinline boundary: (T, T) -> Boolean): List<List<T>> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<MutableList<T>>()
    forEach { item ->
        val current = result.lastOrNull()
        if (current == null || boundary(current.last(), item)) {
            result += mutableListOf(item)
        } else {
            current += item
        }
    }
    return result
}
