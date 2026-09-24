package com.ians.korno.tools.trackcalibration

import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.RecordingMode
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.trackprocessing.ActivityModeDecoder
import com.ians.korno.core.trackprocessing.DetectorEvent
import com.ians.korno.core.trackprocessing.DetectorSignal
import com.ians.korno.core.trackprocessing.FinalizeTrackUseCase
import com.ians.korno.core.trackprocessing.ModeDecoderConfig
import com.ians.korno.core.trackprocessing.ModeDecoderInput
import com.ians.korno.core.trackprocessing.SegmentDetector
import com.ians.korno.core.trackprocessing.SegmentDetectorConfig
import com.ians.korno.core.trackprocessing.SegmentDetectorState
import com.ians.korno.core.trackprocessing.TrackProcessingConfig
import com.ians.korno.core.trackprocessing.TrackProcessor
import com.ians.korno.core.trackprocessing.TrackSource
import com.ians.korno.core.trackprocessing.segmentDistanceMeters

/** Профиль, по которому отбираются точки для дистанции сегментов, — стартовый профиль авторежима. */
private val SEGMENT_TRACK_CONFIG = TrackProcessingConfig.forSegmentKind(SegmentKind.UNKNOWN)

/** Всё, что живому виду и декодеру нужно от одной записи. */
class SegmentInput(
    /** Показания шагомера и ручные паузы, по времени. */
    val signals: List<DetectorSignal>,
    /** Сырые точки: декодер берёт из них доплеровскую скорость и остановки. */
    val points: List<GeoPoint>,
    val recognitions: List<RecognitionSample>,
    val validPoints: List<GeoPoint>,
    val startMillis: Long,
    val endMillis: Long,
)

class SegmentRun(
    /** Сегменты сразу после детектора, до слияния коротких. */
    val detected: List<ActivitySegment>,
    val merged: List<ActivitySegment>,
    val events: List<DetectorEvent>,
)

fun segmentInput(
    points: List<GeoPoint>,
    loggedSignals: List<DetectorSignal>,
    recognitions: List<RecognitionSample>,
): SegmentInput? {
    if (points.isEmpty() || (loggedSignals.isEmpty() && recognitions.isEmpty())) return null
    val signals = loggedSignals.sortedBy(DetectorSignal::timestampMillis)
    val times = points.map(GeoPoint::timestampEpochMillis) + signals.map(DetectorSignal::timestampMillis) +
        recognitions.map(RecognitionSample::timestampEpochMillis)
    val validPoints = TrackProcessor().process(points, SEGMENT_TRACK_CONFIG, TrackSource.RAW)
        .points.filterNot(GeoPoint::isOutlier)
    return SegmentInput(
        signals = signals,
        points = points,
        recognitions = recognitions,
        validPoints = validPoints,
        startMillis = times.min(),
        endMillis = times.max(),
    )
}

/** Та же запись, но декодер не видит распознавания — как с переключателем «Игнорировать AR». */
fun SegmentInput.withoutRecognition() = SegmentInput(signals, points, emptyList(), validPoints, startMillis, endMillis)

/**
 * Живой вид так же, как в приложении: раз в эпоху декодер прогоняется по записи до
 * «сейчас», вердикт идёт в детектор, сегмент режется на подтверждении, при «сохранении»
 * короткие сливаются. Первый подтверждённый вид тянется к началу записи; без
 * подтверждений вся запись — один `UNKNOWN`.
 *
 * ponytail: O(N²) — каждый тик декодирует всю запись с начала, как и приложение; на
 * многочасовых логах перебор порогов заметно медленный.
 */
fun runLiveDetector(
    input: SegmentInput,
    config: SegmentDetectorConfig,
    decoderConfig: ModeDecoderConfig = ModeDecoderConfig(),
    sensitivity: AutoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
): SegmentRun {
    val detector = SegmentDetector(config)
    val decoder = ActivityModeDecoder(decoderConfig)
    val readings = input.signals.filterIsInstance<DetectorSignal.StepCounter>()
    val pauses = input.signals.filter { it is DetectorSignal.Paused || it is DetectorSignal.Resumed }
    val points = input.points.sortedBy(GeoPoint::timestampEpochMillis)
    val recognitions = input.recognitions.sortedBy(RecognitionSample::timestampEpochMillis)
    val events = mutableListOf<DetectorEvent>()
    var state = SegmentDetectorState()
    fun feed(signal: DetectorSignal) {
        val step = detector.reduce(state, signal)
        state = step.state
        events += step.events
    }

    var pauseIndex = 0
    var tick = input.startMillis + decoderConfig.epochMillis
    while (tick <= input.endMillis) {
        while (pauseIndex < pauses.size && pauses[pauseIndex].timestampMillis <= tick) feed(pauses[pauseIndex++])
        val verdict = decoder.liveVerdict(
            ModeDecoderInput(
                startMillis = input.startMillis,
                endMillis = tick,
                points = points.takeWhile { it.timestampEpochMillis <= tick },
                recognitions = recognitions.takeWhile { it.timestampEpochMillis <= tick },
                steps = readings.takeWhile { it.timestampMillis <= tick },
            ),
        )
        if (verdict != null) feed(DetectorSignal.Verdict(tick, verdict.kind, verdict.sinceMillis))
        tick += decoderConfig.epochMillis
    }

    val confirmations = events.filterIsInstance<DetectorEvent.SegmentConfirmed>()
    val starts = confirmations.mapIndexed { index, confirmation ->
        (if (index == 0) input.startMillis else confirmation.boundaryMillis) to confirmation.to
    }.ifEmpty { listOf(input.startMillis to SegmentKind.UNKNOWN) }

    val detected = starts.mapIndexed { index, (start, kind) ->
        val end = starts.getOrNull(index + 1)?.first ?: input.endMillis
        ActivitySegment(
            kind = kind,
            startTimeEpochMillis = start,
            endTimeEpochMillis = end,
            stepCount = stepsBetween(readings, start, end),
            distanceMeters = segmentDistanceMeters(input.validPoints, start, end),
        )
    }
    return SegmentRun(detected, input.finalize(detected, sensitivity), events)
}

/** Как при сохранении записи: декодер по всей записи, шаги по границам, слияние коротких. */
fun runModeDecoder(
    input: SegmentInput,
    config: ModeDecoderConfig,
    sensitivity: AutoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
): SegmentRun {
    val readings = input.signals.filterIsInstance<DetectorSignal.StepCounter>()
    val detected = ActivityModeDecoder(config).decode(
        ModeDecoderInput(
            startMillis = input.startMillis,
            endMillis = input.endMillis,
            points = input.points,
            recognitions = input.recognitions,
            steps = readings,
        ),
    ).map { segment ->
        val end = segment.endTimeEpochMillis ?: input.endMillis
        segment.copy(
            stepCount = stepsBetween(readings, segment.startTimeEpochMillis, end),
            distanceMeters = segmentDistanceMeters(input.validPoints, segment.startTimeEpochMillis, end),
        )
    }
    return SegmentRun(detected, input.finalize(detected, sensitivity), events = emptyList())
}

/**
 * Сегменты так, как их запишет приложение: через тот же [FinalizeTrackUseCase], то есть
 * со слиянием коротких и с дистанцией по профилю каждого участка.
 *
 * Прежде таблица «после слияния» считала дистанцию одним профилем на весь трек и мимо
 * итогового пересчёта. Пока участки отличались только порогами, цифра выходила близкой.
 * Сейчас нет: профиль решает, попадёт ли точка в трек вообще, а отрезок через границу
 * достаётся тому участку, во время которого произошёл. На разобранной поездке приближение
 * расходилось с итогом втрое — а по этой таблице как раз и судят о результате.
 *
 * Таблицы «до слияния» остаются на дешёвом приближении намеренно: они показывают, где
 * встали границы, а не сколько километров получится.
 */
private fun SegmentInput.finalize(
    segments: List<ActivitySegment>,
    sensitivity: AutoPauseSensitivity,
): List<ActivitySegment> = FinalizeTrackUseCase()(
    activity = Activity(
        startTimeEpochMillis = startMillis,
        endTimeEpochMillis = endMillis,
        recordingMode = RecordingMode.AUTO,
        autoPauseSensitivity = sensitivity,
    ),
    points = points,
    segments = segments,
).segments

/**
 * Шаги как разница кумулятивного счётчика на границах; `null` — показаний нет вовсе.
 * ponytail: перезагрузка устройства посреди записи даст ноль за сегмент со сбросом.
 */
private fun stepsBetween(readings: List<DetectorSignal.StepCounter>, start: Long, end: Long): Int? {
    if (readings.isEmpty()) return null
    fun counterAt(time: Long) = (readings.lastOrNull { it.timestampMillis <= time } ?: readings.first()).counter
    return (counterAt(end) - counterAt(start)).coerceAtLeast(0L).toInt()
}

/** Пороги детектора для перебора — по одному за раз, как и пороги трека. */
class DetectorSweep(
    val name: String,
    val currentValue: (SegmentDetectorConfig) -> Double,
    val values: List<Double>,
    val apply: (SegmentDetectorConfig, Double) -> SegmentDetectorConfig,
)

val DETECTOR_SWEEPS: List<DetectorSweep> = listOf(
    DetectorSweep(
        name = "confirmWindowSeconds",
        currentValue = { it.confirmWindowMillis / 1_000.0 },
        values = listOf(15.0, 30.0, 40.0, 60.0, 90.0),
        apply = { config, value -> config.copy(confirmWindowMillis = (value * 1_000).toLong()) },
    ),
    DetectorSweep(
        name = "minSegmentDurationSeconds",
        currentValue = { it.minSegmentDurationMillis / 1_000.0 },
        values = listOf(30.0, 60.0, 120.0),
        apply = { config, value -> config.copy(minSegmentDurationMillis = (value * 1_000).toLong()) },
    ),
)

/** Пороги декодера для перебора — по одному за раз. */
class DecoderSweep(
    val name: String,
    val currentValue: (ModeDecoderConfig) -> Double,
    val values: List<Double>,
    val apply: (ModeDecoderConfig, Double) -> ModeDecoderConfig,
)

val DECODER_SWEEPS: List<DecoderSweep> = listOf(
    DecoderSweep(
        name = "recognitionWeightPerSecond",
        currentValue = { it.recognitionWeightPerSecond },
        values = listOf(0.02, 0.05, 0.1, 0.2),
        apply = { config, value -> config.copy(recognitionWeightPerSecond = value) },
    ),
    DecoderSweep(
        name = "switchPenalty",
        currentValue = { it.switchPenalty },
        values = listOf(2.0, 6.0, 12.0),
        apply = { config, value -> config.copy(switchPenalty = value) },
    ),
    DecoderSweep(
        name = "transferPenalty",
        currentValue = { it.transferPenalty },
        values = listOf(0.0, 10.0, 30.0, 60.0),
        apply = { config, value -> config.copy(transferPenalty = value) },
    ),
    DecoderSweep(
        name = "transferStopSeconds",
        currentValue = { it.transferStopMillis / 1_000.0 },
        values = listOf(5.0, 10.0, 20.0, 40.0),
        apply = { config, value -> config.copy(transferStopMillis = (value * 1_000).toLong()) },
    ),
    DecoderSweep(
        name = "cruisingCyclingSpeedMetersPerSecond",
        currentValue = { it.cruisingCyclingSpeedMetersPerSecond },
        values = listOf(7.0, 8.3, 10.0, 12.0),
        apply = { config, value -> config.copy(cruisingCyclingSpeedMetersPerSecond = value) },
    ),
    DecoderSweep(
        name = "cruisingSpeedWeightPerSecond",
        currentValue = { it.cruisingSpeedWeightPerSecond },
        values = listOf(0.0, 0.04, 0.08, 0.16),
        apply = { config, value -> config.copy(cruisingSpeedWeightPerSecond = value) },
    ),
    DecoderSweep(
        name = "slowVehicleWeightPerSecond",
        currentValue = { it.slowVehicleWeightPerSecond },
        values = listOf(0.0, 0.025, 0.05, 0.1),
        apply = { config, value -> config.copy(slowVehicleWeightPerSecond = value) },
    ),
    DecoderSweep(
        name = "crawlSpeedMetersPerSecond",
        currentValue = { it.crawlSpeedMetersPerSecond },
        values = listOf(0.5, 1.0, 2.0, 3.0),
        apply = { config, value -> config.copy(crawlSpeedMetersPerSecond = value) },
    ),
    DecoderSweep(
        name = "stationarySpeedMetersPerSecond",
        currentValue = { it.stationarySpeedMetersPerSecond },
        values = listOf(0.3, 0.5, 1.0),
        apply = { config, value -> config.copy(stationarySpeedMetersPerSecond = value) },
    ),
    DecoderSweep(
        name = "maxOnFootSpeedMetersPerSecond",
        currentValue = { it.maxOnFootSpeedMetersPerSecond },
        values = listOf(2.5, 3.0, 4.0),
        apply = { config, value -> config.copy(maxOnFootSpeedMetersPerSecond = value) },
    ),
)
