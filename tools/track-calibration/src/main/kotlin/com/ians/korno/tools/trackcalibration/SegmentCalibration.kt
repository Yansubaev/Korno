package com.ians.korno.tools.trackcalibration

import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.trackprocessing.ActivityModeDecoder
import com.ians.korno.core.trackprocessing.DetectorEvent
import com.ians.korno.core.trackprocessing.DetectorSignal
import com.ians.korno.core.trackprocessing.IncrementalTrackProcessor
import com.ians.korno.core.trackprocessing.ModeDecoderConfig
import com.ians.korno.core.trackprocessing.ModeDecoderInput
import com.ians.korno.core.trackprocessing.SegmentDetector
import com.ians.korno.core.trackprocessing.SegmentDetectorConfig
import com.ians.korno.core.trackprocessing.SegmentDetectorState
import com.ians.korno.core.trackprocessing.TrackProcessingConfig
import com.ians.korno.core.trackprocessing.TrackProcessor
import com.ians.korno.core.trackprocessing.TrackSource
import com.ians.korno.core.trackprocessing.segmentDistanceMeters

/**
 * ponytail: весь трек обрабатывается одним профилем `DRIVING` — самым терпимым, с которого
 * запись и стартует в авторежиме. Приложение будет переключать профиль по сегменту;
 * здесь это понадобится, если пешие участки начнут расходиться по дистанции.
 */
private val SEGMENT_TRACK_CONFIG = TrackProcessingConfig.forActivityType(ActivityType.DRIVING)

/** Всё, что детектору нужно от одной записи; скорость посчитана заранее, она от порогов детектора не зависит. */
class SegmentInput(
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
    if (loggedSignals.isEmpty() || points.isEmpty()) return null
    val live = IncrementalTrackProcessor(SEGMENT_TRACK_CONFIG)
    val speeds = points.mapNotNull { point ->
        val update = live.append(point)
        if (point.recordingStatus == RecordingStatus.MANUAL_PAUSE) return@mapNotNull null
        update.filterSpeed?.let { DetectorSignal.Speed(point.timestampEpochMillis, it.metersPerSecond) }
    }
    val signals = (loggedSignals + speeds).sortedBy(DetectorSignal::timestampMillis)
    val validPoints = TrackProcessor().process(points, ActivityType.DRIVING, SEGMENT_TRACK_CONFIG, TrackSource.RAW)
        .points.filterNot(GeoPoint::isOutlier)
    return SegmentInput(
        signals = signals,
        points = points,
        recognitions = recognitions,
        validPoints = validPoints,
        startMillis = minOf(points.first().timestampEpochMillis, signals.first().timestampMillis),
        endMillis = maxOf(points.last().timestampEpochMillis, signals.last().timestampMillis),
    )
}

/**
 * Прогон детектора так же, как в приложении: сигналы по одному, сегмент режется на
 * подтверждении, при «сохранении» короткие сливаются. Первый подтверждённый вид тянется
 * к началу записи; без подтверждений вся запись — один `UNKNOWN`.
 */
fun runSegmentDetector(input: SegmentInput, config: SegmentDetectorConfig): SegmentRun {
    val detector = SegmentDetector(config)
    val events = mutableListOf<DetectorEvent>()
    input.signals.fold(SegmentDetectorState()) { state, signal ->
        detector.reduce(state, signal).also { events += it.events }.state
    }

    val confirmations = events.filterIsInstance<DetectorEvent.SegmentConfirmed>()
    val starts = confirmations.mapIndexed { index, confirmation ->
        (if (index == 0) input.startMillis else confirmation.boundaryMillis) to confirmation.to
    }.ifEmpty { listOf(input.startMillis to SegmentKind.UNKNOWN) }

    val readings = input.signals.filterIsInstance<DetectorSignal.StepCounter>()
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
    return SegmentRun(detected, detector.mergeShortSegments(detected), events)
}

/** Как при сохранении записи: декодер по всей записи, шаги по границам, слияние коротких. */
fun runModeDecoder(input: SegmentInput, config: ModeDecoderConfig, detectorConfig: SegmentDetectorConfig): SegmentRun {
    val readings = input.signals.filterIsInstance<DetectorSignal.StepCounter>()
    val detected = ActivityModeDecoder(config).decode(
        ModeDecoderInput(
            startMillis = input.startMillis,
            endMillis = input.endMillis,
            points = input.points,
            recognitions = input.recognitions,
            steps = readings,
        ),
    ).map { it.copy(stepCount = stepsBetween(readings, it.startTimeEpochMillis, it.endTimeEpochMillis ?: input.endMillis)) }
    return SegmentRun(detected, SegmentDetector(detectorConfig).mergeShortSegments(detected), events = emptyList())
}

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
        name = "minConfidencePercent",
        currentValue = { it.minConfidencePercent.toDouble() },
        values = listOf(30.0, 50.0, 70.0, 90.0),
        apply = { config, value -> config.copy(minConfidencePercent = value.toInt()) },
    ),
    DetectorSweep(
        name = "runningCadenceStepsPerMinute",
        currentValue = { it.runningCadenceStepsPerMinute },
        values = listOf(120.0, 140.0, 160.0),
        apply = { config, value -> config.copy(runningCadenceStepsPerMinute = value) },
    ),
    DetectorSweep(
        name = "maxOnFootSpeedMetersPerSecond",
        currentValue = { it.maxOnFootSpeedMetersPerSecond },
        values = listOf(4.0, 7.0, 10.0),
        apply = { config, value -> config.copy(maxOnFootSpeedMetersPerSecond = value) },
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
