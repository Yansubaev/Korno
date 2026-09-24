package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.Error
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingMode
import com.ians.korno.core.domain.model.StepSample
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.trackprocessing.ActivityModeDecoder
import com.ians.korno.core.trackprocessing.DetectorSignal
import com.ians.korno.core.trackprocessing.FinalizeTrackUseCase
import com.ians.korno.core.trackprocessing.ModeDecoderInput

/**
 * Заново размечает сохранённую запись авторежима текущим декодером.
 *
 * Вид движения определяется один раз, при остановке записи, и живёт в БД дальше сам по
 * себе: правка трека сегменты не трогает. Поэтому после изменения декодера старые записи
 * остаются с прежней разметкой, и проверить правку можно только новой поездкой. Всё, из
 * чего декодер решает, у сохранённой записи есть: доплеровская скорость лежит в точках,
 * ответы распознавания — в `recognition_samples`, шаги во времени — в `step_samples`.
 *
 * У записей старше `step_samples` дорожки шагомера нет: каденса у декодера для них нет, а
 * шаги сегментов делятся между новыми границами по пересечению времени.
 */
class RedecodeActivitySegmentsUseCase(
    private val trackRepository: TrackRepository,
    private val finalizeTrack: FinalizeTrackUseCase,
    private val modeDecoder: ActivityModeDecoder = ActivityModeDecoder(),
) {
    /** Почему пересчёт ничего не дал: экрану нужно объяснить бездействие, а не промолчать. */
    enum class Skipped : Error { NOT_AUTO_MODE, NO_RECOGNITION }

    suspend operator fun invoke(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
    ): Result<Unit, Skipped> {
        val endMillis = activity.endTimeEpochMillis
        if (activity.recordingMode != RecordingMode.AUTO || endMillis == null) {
            return Result.Error(Skipped.NOT_AUTO_MODE)
        }

        val recognitions = (trackRepository.getRecognitionSamples(activity.id) as? Result.Success)
            ?.data
            .orEmpty()
        val steps = (trackRepository.getStepSamples(activity.id) as? Result.Success)?.data.orEmpty()
        val decoded = modeDecoder.decode(
            ModeDecoderInput(
                startMillis = activity.startTimeEpochMillis,
                endMillis = endMillis,
                points = points,
                recognitions = recognitions,
                steps = steps.map { DetectorSignal.StepCounter(it.timestampEpochMillis, it.stepCount.toLong()) },
                manualSegments = segments.filter(ActivitySegment::isManuallyEdited),
            ),
        )
        // Пусто — судить не по чему: у записи нет ни одного ответа о движении (их не было
        // вовсе или распознавание всю дорогу отвечало «не знаю»). Прежняя разметка лучше
        // пустой, поэтому не трогаем её.
        if (decoded.isEmpty()) return Result.Error(Skipped.NO_RECOGNITION)

        val withSteps = decoded.map { segment ->
            val start = segment.startTimeEpochMillis
            val end = segment.endTimeEpochMillis ?: endMillis
            segment.copy(
                activityId = activity.id,
                stepCount = if (steps.isEmpty()) stepsBetween(segments, start, end) else stepsAt(steps, end) - stepsAt(steps, start),
            )
        }
        val track = finalizeTrack(activity, points, withSteps)
        trackRepository.replaceTrack(track.activity, track.points, track.segments)
        return Result.Success(Unit)
    }

    /** Счёт шагов записи на момент [timeMillis]; до первого показания — ноль. */
    private fun stepsAt(steps: List<StepSample>, timeMillis: Long): Int =
        steps.lastOrNull { it.timestampEpochMillis <= timeMillis }?.stepCount ?: 0

    /**
     * Шаги старых сегментов, поделённые по времени между новыми границами. Точнее для
     * записей без `step_samples` не восстановить: в БД лежит сумма за сегмент.
     */
    private fun stepsBetween(segments: List<ActivitySegment>, start: Long, end: Long): Int? {
        val counted = segments.filter { it.stepCount != null }
        if (counted.isEmpty()) return null
        return counted.sumOf { segment ->
            val segmentEnd = segment.endTimeEpochMillis ?: return@sumOf 0
            val span = segmentEnd - segment.startTimeEpochMillis
            if (span <= 0L) return@sumOf 0
            val overlap = minOf(end, segmentEnd) - maxOf(start, segment.startTimeEpochMillis)
            if (overlap <= 0L) 0 else ((segment.stepCount ?: 0) * overlap / span).toInt()
        }
    }
}
