package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RecognizedActivity
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max

/**
 * Пороги декодера. Всё в натах (натуральный логарифм правдоподобия): штраф 10 значит, что
 * путь со сменой вида должен объяснять наблюдения в e^10 раз лучше пути без неё.
 * Подбираются прогоном по логам в `:tools:track-calibration`.
 */
data class ModeDecoderConfig(
    /** Шаг сетки времени. Меньше интервала распознавания смысла нет, больше — грубые границы. */
    val epochMillis: Long = 5_000L,

    /**
     * Вес распознавания за секунду. Вердикт держится до следующего и засчитывается в каждой
     * эпохе, поэтому вес — за время, а не за вердикт: ответы соседних секунд не независимы.
     */
    val recognitionWeightPerSecond: Double = 0.05,
    /** Добавка к уверенности каждого вида: ноль в ответе не должен запрещать вид насовсем. */
    val recognitionSmoothingPercent: Double = 5.0,
    /** Дольше этого вердикт без подтверждения ничего не говорит. */
    val recognitionMaxAgeMillis: Long = 60_000L,

    /** Любая смена вида: без неё вердикт-одиночка резал бы трек на куски. */
    val switchPenalty: Double = 6.0,
    /**
     * Смена вида с транспортом или на транспорт без остановки. Сесть в машину или автобус,
     * слезть с велосипеда и погрузить его на ходу нельзя — нужна остановка, пропуск GPS
     * или ручная пауза. Штраф конечный: очень длинный уверенный транспорт всё же победит,
     * если остановку на границе съела погрешность GPS.
     */
    val transferPenalty: Double = 30.0,
    /** Остановка такой длины снимает [transferPenalty] полностью; короче — частично. */
    val transferStopMillis: Long = 20_000L,
    /** Доплеровская скорость, ниже которой точка считается стоянием. */
    val stationarySpeedMetersPerSecond: Double = 0.5,
    /**
     * Скорость, ниже которой транспорт уже тормозит под посадку. У стоящего приёмника
     * доплер шумит в районе 0.4–1.3 м/с, поэтому остановку нельзя мерить только по
     * [stationarySpeedMetersPerSecond]: настоящая остановка автобуса в записи 41 (17:54–18:06)
     * распалась на одиночные точки, кредит не выдался, и высадка на ходу оказалась дешевле
     * пересадки. Длину остановки меряем этим порогом, а факт остановки — предыдущим.
     */
    val crawlSpeedMetersPerSecond: Double = 2.0,
    /** Промежуток без фиксов, в котором пересадка могла пройти незамеченной. */
    val transferGapMillis: Long = 30_000L,

    /**
     * Физические пределы: выше них вид невозможен, а не маловероятен. Отличать ими
     * велосипед от машины нельзя — в городе у них одни скорости, — поэтому у транспорта
     * предела нет, а у велосипеда он с запасом на спуск.
     */
    val maxOnFootSpeedMetersPerSecond: Double = 3.0,
    val maxRunningSpeedMetersPerSecond: Double = 7.0,
    val maxCyclingSpeedMetersPerSecond: Double = 25.0,
    /** Превышение предела на эту величину стоит [speedWeightPerSecond] за секунду; растёт квадратично. */
    val speedToleranceMetersPerSecond: Double = 0.5,
    val speedWeightPerSecond: Double = 0.4,

    /**
     * Крейсерская скорость велосипеда — не предел, а граница правдоподобия: выше неё едут
     * на транспорте. Пределом велосипед от транспорта не отличить (в городе у них одни
     * скорости), но верх диапазона различает их надёжно: по записям 41/45/46 автобус и
     * машина проводят 15–20 % эпох выше 30 км/ч и разгоняются до 44–50, а настоящая
     * велопоездка — ни одной эпохи выше 30 при максимуме 26.8.
     *
     * Поэтому штраф мягкий и с потолком [cruisingSpeedMaxExcess]: спуск на велосипеде
     * добавляет голос за транспорт, но в одиночку пересадку не оплачивает.
     */
    val cruisingCyclingSpeedMetersPerSecond: Double = 8.3,
    val cruisingSpeedToleranceMetersPerSecond: Double = 1.5,
    val cruisingSpeedWeightPerSecond: Double = 0.12,
    /** Выше этого превышения быстрее не значит увереннее. */
    val cruisingSpeedMaxExcess: Double = 4.0,
    /**
     * Обратная сторона той же улики: эпоха, которая едет, но не быстрее велосипеда, — слабый
     * голос против транспорта. Без неё транспорт, нигде не разогнавшийся, не стоит ничего, и
     * настоящая велопоездка (46: ни одной эпохи выше 30 км/ч) уезжает в транспорт по паре
     * случайных вердиктов. Голос плоский и маленький: он должен копиться десятками минут, а
     * не решать по одной эпохе. Стоянки не в счёт — стоящий в пробке автобус нормален.
     */
    val slowVehicleWeightPerSecond: Double = 0.05,

    /** Каденс шагов различает только ходьбу и бег: при педалировании шагомер тоже считает. */
    val runningCadenceStepsPerMinute: Double = 140.0,
    val cadenceScaleStepsPerMinute: Double = 20.0,
    val cadenceWeightPerSecond: Double = 0.05,
    val cadenceWindowMillis: Long = 20_000L,
)

/** Всё, из чего определяется вид движения записи. */
class ModeDecoderInput(
    val startMillis: Long,
    val endMillis: Long,
    /** Сырые точки с `recordingStatus`: скорость и остановки берутся из них. */
    val points: List<GeoPoint>,
    val recognitions: List<RecognitionSample>,
    /** Кумулятивный счётчик шагов во времени. */
    val steps: List<DetectorSignal.StepCounter> = emptyList(),
    /** Участки, где вид задал пользователь: внутри них декодер не спорит. */
    val manualSegments: List<ActivitySegment> = emptyList(),
)

private val MODES = listOf(SegmentKind.ON_FOOT, SegmentKind.RUNNING, SegmentKind.CYCLING, SegmentKind.VEHICLE)
private val VEHICLE_INDEX = MODES.indexOf(SegmentKind.VEHICLE)
private val CYCLING_INDEX = MODES.indexOf(SegmentKind.CYCLING)
private const val FORBIDDEN = -1e12

/**
 * Вид движения всей записи — скрытая марковская модель, декодируемая алгоритмом Витерби.
 *
 * Время режется на эпохи [ModeDecoderConfig.epochMillis]; скрытое состояние эпохи — вид
 * движения. Наблюдения:
 * - ответ Activity Recognition — главный голос, но шумный: велосипед он путает с машиной,
 *   а на записях 41 и 46 две трети времени вообще не сказал ничего;
 * - доплеровская скорость — физический предел вида и, отдельно, улика «велосипед против
 *   транспорта»: пределом эти два не различить, верхом диапазона — надёжно;
 * - каденс шагов — ходьба против бега.
 *
 * Главное знание — в переходах: смена вида стоит штрафа, а смена с транспортом или на
 * транспорт без остановки — большого штрафа. Витерби ищет разметку, которая лучше всего
 * объясняет всю запись целиком, поэтому минута «машины» посреди велопоездки, не начатая и
 * не законченная остановкой, проигрывает велосипеду, а граница настоящей пересадки сама
 * встаёт на остановку, даже если распознавание заметило смену позже.
 *
 * `STILL` вида не задаёт: остановка — часть текущего вида, её разбирает автопауза.
 */
class ActivityModeDecoder(private val config: ModeDecoderConfig = ModeDecoderConfig()) {

    /**
     * Сегменты записи по порядку, от [ModeDecoderInput.startMillis] до
     * [ModeDecoderInput.endMillis], с дистанцией по не-выбросам. Шаги не заполняются.
     * Пусто — судить не по чему: ни одного ответа о движении и ни одной ручной поправки.
     */
    fun decode(input: ModeDecoderInput): List<ActivitySegment> {
        if (input.endMillis <= input.startMillis) return emptyList()
        if (input.recognitions.none { it.movementConfidences() != null } && input.manualSegments.isEmpty()) {
            return emptyList()
        }
        val epochCount = ceil((input.endMillis - input.startMillis).toDouble() / config.epochMillis).toInt()
        val emissions = emissions(input, epochCount)
        val transferCosts = transferCosts(input, epochCount)
        val path = viterbi(emissions, transferCosts)
        return segments(input, path)
    }

    private fun epochStart(input: ModeDecoderInput, epoch: Int) = input.startMillis + epoch * config.epochMillis

    private fun emissions(input: ModeDecoderInput, epochCount: Int): Array<DoubleArray> {
        val seconds = config.epochMillis / 1_000.0
        val emissions = Array(epochCount) { DoubleArray(MODES.size) }

        val recognitions = input.recognitions.sortedBy(RecognitionSample::timestampEpochMillis)
        var next = 0
        var latest: RecognitionSample? = null
        val movingPoints = input.points
            .filter { it.recordingStatus != RecordingStatus.MANUAL_PAUSE }
            .sortedBy(GeoPoint::timestampEpochMillis)
        var pointIndex = 0
        val steps = input.steps.sortedBy(DetectorSignal.StepCounter::timestampMillis)

        for (epoch in 0 until epochCount) {
            val start = epochStart(input, epoch)
            val end = start + config.epochMillis
            val row = emissions[epoch]

            // Держится последний ответ **о движении**, а не последний вообще: Play Services
            // отдаёт `IN_VEHICLE` и следом `STILL` в ту же секунду (транспорт встал), и
            // раньше остановка стирала вердикт, ради которого весь разбор. В записи 46 так
            // пропали четыре вердикта `IN_VEHICLE` из пяти, и автобус стал велосипедом.
            while (next < recognitions.size && recognitions[next].timestampEpochMillis < end) {
                val sample = recognitions[next++]
                if (sample.movementConfidences() != null) latest = sample
            }
            latest?.takeIf { end - it.timestampEpochMillis <= config.recognitionMaxAgeMillis }
                ?.movementConfidences()
                ?.let { confidences ->
                    val smoothing = config.recognitionSmoothingPercent
                    val total = confidences.sum() + smoothing * MODES.size
                    MODES.indices.forEach { mode ->
                        row[mode] += config.recognitionWeightPerSecond * seconds * ln((confidences[mode] + smoothing) / total)
                    }
                }

            val speeds = mutableListOf<Double>()
            while (pointIndex < movingPoints.size && movingPoints[pointIndex].timestampEpochMillis < end) {
                movingPoints[pointIndex++].dopplerSpeed()?.let(speeds::add)
            }
            speeds.sorted().getOrNull(speeds.size / 2)?.let { speed ->
                MODES.forEachIndexed { mode, kind ->
                    val limit = speedLimit(kind) ?: return@forEachIndexed
                    val excess = max(0.0, speed - limit) / config.speedToleranceMetersPerSecond
                    row[mode] -= config.speedWeightPerSecond * seconds * excess * excess
                }
                // Скорость как улика «велосипед против транспорта», а не только как запрет:
                // быстрая эпоха — сильный голос за транспорт, едущая, но медленная — слабый
                // против него. Предел в 25 м/с эти два вида не различал никогда: в городе
                // они ездят одинаково, расходятся только на верхнем конце диапазона.
                val cruising = ((speed - config.cruisingCyclingSpeedMetersPerSecond) /
                    config.cruisingSpeedToleranceMetersPerSecond).coerceIn(0.0, config.cruisingSpeedMaxExcess)
                if (cruising > 0.0) {
                    row[CYCLING_INDEX] -= config.cruisingSpeedWeightPerSecond * seconds * cruising * cruising
                } else if (speed > config.stationarySpeedMetersPerSecond) {
                    row[VEHICLE_INDEX] -= config.slowVehicleWeightPerSecond * seconds
                }
            }

            cadence(steps, end)?.let { cadence ->
                val vote = ((cadence - config.runningCadenceStepsPerMinute) / config.cadenceScaleStepsPerMinute)
                    .coerceIn(-1.0, 1.0) * config.cadenceWeightPerSecond * seconds
                row[MODES.indexOf(SegmentKind.RUNNING)] += vote
                row[MODES.indexOf(SegmentKind.ON_FOOT)] -= vote
            }

            input.manualSegments.firstOrNull { segment ->
                val middle = start + config.epochMillis / 2
                middle >= segment.startTimeEpochMillis && middle < (segment.endTimeEpochMillis ?: Long.MAX_VALUE)
            }?.let { manual ->
                MODES.forEachIndexed { mode, kind -> if (kind != manual.kind) row[mode] = FORBIDDEN }
            }
        }
        return emissions
    }

    private fun speedLimit(kind: SegmentKind): Double? = when (kind) {
        SegmentKind.ON_FOOT -> config.maxOnFootSpeedMetersPerSecond
        SegmentKind.RUNNING -> config.maxRunningSpeedMetersPerSecond
        SegmentKind.CYCLING -> config.maxCyclingSpeedMetersPerSecond
        else -> null
    }

    private fun cadence(steps: List<DetectorSignal.StepCounter>, atMillis: Long): Double? {
        val window = steps.filter { it.timestampMillis in (atMillis - config.cadenceWindowMillis)..atMillis }
        val first = window.firstOrNull() ?: return null
        val last = window.last()
        val span = last.timestampMillis - first.timestampMillis
        if (span < config.cadenceWindowMillis / 2 || last.counter < first.counter) return null
        return (last.counter - first.counter) * 60_000.0 / span
    }

    /**
     * Штраф пересадки при входе в каждую эпоху: полный, если рядом нет ни остановки, ни
     * пропуска GPS, ни ручной паузы, и тем меньше, чем дольше остановка, перекрывающая эпоху.
     */
    private fun transferCosts(input: ModeDecoderInput, epochCount: Int): DoubleArray {
        val costs = DoubleArray(epochCount) { config.transferPenalty }
        val points = input.points.sortedBy(GeoPoint::timestampEpochMillis)

        fun credit(from: Long, to: Long, durationMillis: Long) {
            val cost = config.transferPenalty * (1.0 - durationMillis.toDouble() / config.transferStopMillis).coerceIn(0.0, 1.0)
            val first = ((from - input.startMillis) / config.epochMillis).toInt().coerceIn(0, epochCount - 1)
            val last = ((to - input.startMillis) / config.epochMillis).toInt().coerceIn(0, epochCount - 1)
            for (epoch in first..last) costs[epoch] = minOf(costs[epoch], cost)
        }

        val edges = listOf(input.startMillis) + points.map(GeoPoint::timestampEpochMillis) + input.endMillis
        edges.zipWithNext { from, to -> if (to - from > config.transferGapMillis) credit(from, to, Long.MAX_VALUE / 2) }

        // Остановка — это заезд под [ModeDecoderConfig.crawlSpeedMetersPerSecond], внутри
        // которого нашлась хоть одна точка в покое. Требовать покоя от каждой точки нельзя:
        // доплер стоящего приёмника шумит, и настоящая остановка рассыпается на одиночки.
        // Одна лишь тишина по шагу «ползёт» тоже не годится — так выглядит и пешая ходьба,
        // а она не пересадка.
        var runStart: GeoPoint? = null
        var runEnd: GeoPoint? = null
        var runHalted = false
        fun closeRun() {
            val start = runStart ?: return
            val end = runEnd ?: return
            if (runHalted) {
                val isManualPause = start.recordingStatus == RecordingStatus.MANUAL_PAUSE
                credit(start.timestampEpochMillis, end.timestampEpochMillis, if (isManualPause) Long.MAX_VALUE / 2 else end.timestampEpochMillis - start.timestampEpochMillis)
            }
            runStart = null
            runEnd = null
            runHalted = false
        }
        points.forEach { point ->
            val isManualPause = point.recordingStatus == RecordingStatus.MANUAL_PAUSE
            val speed = point.dopplerSpeed()
            val crawling = isManualPause || (speed?.let { it <= config.crawlSpeedMetersPerSecond } == true)
            val halted = isManualPause || (speed?.let { it <= config.stationarySpeedMetersPerSecond } == true)
            val sameRun = runStart?.let { (it.recordingStatus == RecordingStatus.MANUAL_PAUSE) == isManualPause } == true
            if (!crawling || !sameRun) closeRun()
            if (crawling) {
                if (runStart == null) runStart = point
                runEnd = point
                runHalted = runHalted || halted
            }
        }
        closeRun()
        return costs
    }

    private fun viterbi(emissions: Array<DoubleArray>, transferCosts: DoubleArray): IntArray {
        val epochCount = emissions.size
        val modes = MODES.size
        val back = IntArray(epochCount * modes)
        var score = emissions[0].copyOf()
        for (epoch in 1 until epochCount) {
            val nextScore = DoubleArray(modes)
            for (to in 0 until modes) {
                // Оставаться в виде проверяется первым: при равенстве смена не выигрывает.
                var best = score[to]
                var from = to
                for (candidate in 0 until modes) {
                    if (candidate == to) continue
                    var cost = config.switchPenalty
                    if (candidate == VEHICLE_INDEX || to == VEHICLE_INDEX) cost += transferCosts[epoch]
                    if (score[candidate] - cost > best) {
                        best = score[candidate] - cost
                        from = candidate
                    }
                }
                nextScore[to] = best + emissions[epoch][to]
                back[epoch * modes + to] = from
            }
            score = nextScore
        }
        val path = IntArray(epochCount)
        path[epochCount - 1] = score.indices.maxBy { score[it] }
        for (epoch in epochCount - 1 downTo 1) path[epoch - 1] = back[epoch * modes + path[epoch]]
        return path
    }

    private fun segments(input: ModeDecoderInput, path: IntArray): List<ActivitySegment> {
        val validPoints = input.points.filterNot(GeoPoint::isOutlier).sortedBy(GeoPoint::timestampEpochMillis)
        val result = mutableListOf<ActivitySegment>()
        var runStart = 0
        for (epoch in 1..path.size) {
            if (epoch < path.size && path[epoch] == path[runStart]) continue
            val kind = MODES[path[runStart]]
            val start = if (runStart == 0) input.startMillis else epochStart(input, runStart)
            val end = if (epoch == path.size) input.endMillis else epochStart(input, epoch)
            result += ActivitySegment(
                kind = kind,
                startTimeEpochMillis = start,
                endTimeEpochMillis = end,
                distanceMeters = segmentDistanceMeters(validPoints, start, end),
                isManuallyEdited = input.manualSegments.any { manual ->
                    manual.kind == kind && manual.startTimeEpochMillis < end &&
                        (manual.endTimeEpochMillis ?: Long.MAX_VALUE) > start
                },
            )
            runStart = epoch
        }
        return result
    }
}

/**
 * Уверенность по видам [MODES] в процентах; `null` — ответ о движении ничего не говорит
 * (`STILL`, `TILTING`, `UNKNOWN`). `ON_FOOT` — зонтик над ходьбой и бегом: ходьбе
 * достаётся то, что зонтик набрал сверх бега, а без разбивки он делится поровну —
 * решит каденс.
 *
 * «Ничего не говорит» проверяется по вердикту, а не по пустоте альтернатив. Своё незнание
 * Play Services выражает ответом `UNKNOWN 41%` с ровными `IN_VEHICLE=10, ON_BICYCLE=10,
 * ON_FOOT=10`, и альтернативы в нём есть: раньше такой ответ становился наблюдением,
 * которое голосовало против пеших видов и давало точную ничью велосипеду с транспортом.
 * Ничью разрешала одна модель переходов, а в ней платит за вход только транспорт — так
 * незнание распознавания и превращалось в велосипед. В записях 41 и 46 на такие ответы
 * пришлось 77 % и 65 % времени.
 */
private fun RecognitionSample.movementConfidences(): DoubleArray? {
    fun of(activity: RecognizedActivity) = (confidences[activity] ?: 0).toDouble()
    val onFoot = of(RecognizedActivity.ON_FOOT)
    val hasBreakdown = of(RecognizedActivity.WALKING) + of(RecognizedActivity.RUNNING) > 0.0
    val running = if (hasBreakdown) of(RecognizedActivity.RUNNING) else onFoot / 2
    val walking = if (hasBreakdown) max(of(RecognizedActivity.WALKING), onFoot - running) else onFoot / 2
    val values = doubleArrayOf(walking, running, of(RecognizedActivity.ON_BICYCLE), of(RecognizedActivity.IN_VEHICLE))
    val notMovement = maxOf(
        of(RecognizedActivity.UNKNOWN),
        of(RecognizedActivity.STILL),
        of(RecognizedActivity.TILTING),
    )
    return values.takeIf { it.sum() > 0.0 && it.max() > notMovement }
}

private fun GeoPoint.dopplerSpeed(): Double? =
    speedMetersPerSecond?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
