package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint

/**
 * Сколько последних точек всегда пересчитывается заново, каким бы плотным ни был опрос.
 * Детектор остановок помечает стоянкой всё окно разом, а фильтру нужно несколько замеров,
 * чтобы сойтись, — запас взят с избытком, пересчёт двух десятков точек ничего не стоит.
 */
private const val REPROCESSED_TAIL_POINTS = 16

/**
 * Во сколько раз пересчитываемый хвост длиннее окна детектора остановок. При разрежённом
 * опросе счёта точек мало: шестнадцать фиксов раз в полминуты — это восемь минут, а вот
 * при опросе раз в секунду шестнадцати точек не хватает даже на одно окно.
 */
private const val REPROCESSED_TAIL_WINDOW_FACTOR = 4

/**
 * Насколько вердикт по точке может ещё измениться: запас на точку, до которой дотягивается
 * схлопнутое окно детектора остановок, и на то, что гейт фильтра судит о точке по
 * предсказанию, а предсказание уточняется следующими измерениями.
 */
private const val UNSTABLE_TAIL_MARGIN_POINTS = 2

/**
 * Итог обработки очередного сэмпла.
 *
 * Полный список точек здесь намеренно не возвращается: живой карте он приходит из Room,
 * а собирать его заново на каждый сэмпл значило бы вернуть ту самую O(n²), ради ухода от
 * которой всё это и сделано.
 */
data class LiveTrackUpdate(
    /** Вердикт по только что добавленной точке. */
    val point: GeoPoint,
    /** Последняя точка трека, не признанная выбросом, — то, что показывает карта. */
    val lastValidPoint: GeoPoint?,
    val statistics: TrackStatistics,
)

/**
 * Обработка живого трека по мере поступления точек.
 *
 * Раньше на каждый сэмпл целиком пересчитывался весь накопленный трек. Это давало две
 * беды. Первая — стоимость: четыре прохода с копией каждой точки на каждом, то есть за
 * двухчасовую запись под миллиард лишних объектов. Вторая заметна пользователю: сглаживание
 * двигает предпоследние точки, длина последнего отрезка меняется задним числом, и дистанция
 * на экране регулярно уезжает назад — в разобранном логе так набралось 73 метра отката.
 *
 * Здесь два горизонта, и они намеренно разные.
 *
 * **Вердикты** пересчитываются по длинному хвосту — иначе детектор остановок не увидит
 * своего окна, а фильтр Калмана не успеет сойтись к тому состоянию, в котором он был бы,
 * если бы шёл по треку с самого начала. Поэтому `isStop` последней точки (а значит, и
 * решение об автопаузе) всегда свежий.
 *
 * **Накопленные величины** — дистанция, время в движении, максимальная скорость — считаются
 * только по точкам, которые уже не изменятся: дальше [UNSTABLE_TAIL_MARGIN_POINTS] от конца
 * трека вердикты до них не дотянутся. Накопитель работает только на добавление, поэтому
 * дистанция может расти и не может уменьшаться. Платой служит отставание на те же
 * несколько точек: экран показывает чуть меньше, чем уже пройдено, и догоняет со следующим
 * сэмплом. Мгновенная скорость отставать не должна и потому берётся из хвоста напрямую.
 *
 * Итоговый трек при остановке записи по-прежнему считается полным проходом
 * [TrackProcessor.process] — там нет ни спешки, ни ограничений на память.
 */
class IncrementalTrackProcessor(
    private val config: TrackProcessingConfig,
    private val processor: TrackProcessor = TrackProcessor(),
) {
    private val rawPoints = mutableListOf<GeoPoint>()

    /** Сколько точек с начала трека уже учтено в [accumulator] и больше не пересчитывается. */
    private var accumulatedCount = 0
    private var accumulator = TrackStatisticsAccumulator(config)
    private var lastUpdate = EMPTY_UPDATE

    /** Полностью заменяет содержимое — для восстановления сессии после перезапуска процесса. */
    fun reset(points: List<GeoPoint> = emptyList()) {
        rawPoints.clear()
        accumulatedCount = 0
        accumulator = TrackStatisticsAccumulator(config)
        lastUpdate = EMPTY_UPDATE
        points.sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id))
            .forEach(::append)
    }

    fun append(point: GeoPoint): LiveTrackUpdate {
        // Провайдер изредка отдаёт сэмпл задним числом. Разбирать такой случай внутри
        // инкрементального состояния незачем: он редкий, а пересбор стоит одного прохода.
        val previous = rawPoints.lastOrNull()
        if (previous != null && point.timestampEpochMillis < previous.timestampEpochMillis) {
            reset(rawPoints.toList() + point)
            return lastUpdate
        }

        rawPoints += point
        val windowStart = reprocessedWindowStart()
        val processed = processor.processPoints(
            points = rawPoints.subList(windowStart, rawPoints.size),
            config = config,
            // Живой записи будущее недоступно по определению, поэтому обратный проход
            // фильтра здесь выключен. Прямой проход её вполне устраивает: он каузален,
            // и последняя точка сглажена наравне со всеми остальными — именно она уходит
            // на карту.
            backwardPass = false,
        )

        val stableCount = (rawPoints.size - UNSTABLE_TAIL_MARGIN_POINTS).coerceAtLeast(accumulatedCount)
        while (accumulatedCount < stableCount) {
            accumulator.add(processed[accumulatedCount - windowStart])
            accumulatedCount++
        }

        lastUpdate = LiveTrackUpdate(
            point = processed.last(),
            lastValidPoint = processed.lastOrNull { !it.isOutlier } ?: lastUpdate.lastValidPoint,
            statistics = accumulator.snapshot().copy(
                currentSpeedMetersPerSecond = latestSpeed(processed),
            ),
        )
        return lastUpdate
    }

    /**
     * Мгновенная скорость по последнему наблюдённому отрезку хвоста.
     *
     * Она единственная не может отставать: цифра на экране записи должна реагировать на
     * шаг, а не на запечатывание точки. Разрыв и стоянку скорость показывает нулём —
     * делить прямую через дыру на время дыры бессмысленно.
     */
    private fun latestSpeed(processed: List<GeoPoint>): Double {
        val last = processed.lastOrNull { !it.isOutlier } ?: return 0.0
        if (last.isStop) return 0.0
        val previous = processed.asSequence()
            .filterNot(GeoPoint::isOutlier)
            .takeWhile { it !== last }
            .lastOrNull()
            ?: return 0.0
        val elapsedMillis = last.timestampEpochMillis - previous.timestampEpochMillis
        if (elapsedMillis <= 0L || elapsedMillis > config.maximumSegmentDurationMillis) return 0.0
        return distanceMeters(previous, last) / (elapsedMillis / 1_000.0)
    }

    /**
     * Начало заново пересчитываемого окна. Оба ограничения обязательны: по количеству —
     * чтобы фильтр успел сойтись на плотном потоке, по времени — чтобы окно детектора
     * остановок помещалось целиком на разрежённом.
     *
     * Окно не может начинаться позже уже учтённых точек: вердикты для них тоже берутся
     * отсюда.
     */
    private fun reprocessedWindowStart(): Int {
        val byCount = rawPoints.size - REPROCESSED_TAIL_POINTS
        val horizon = rawPoints.last().timestampEpochMillis -
            config.stopWindowMillis * REPROCESSED_TAIL_WINDOW_FACTOR
        var start = byCount
        while (start > 0 && rawPoints[start - 1].timestampEpochMillis >= horizon) {
            start--
        }
        return start.coerceIn(0, accumulatedCount)
    }

    private companion object {
        val EMPTY_UPDATE = LiveTrackUpdate(
            point = GeoPoint(activityId = 0L, latitude = 0.0, longitude = 0.0, timestampEpochMillis = 0L),
            lastValidPoint = null,
            statistics = TrackStatistics(),
        )
    }
}
