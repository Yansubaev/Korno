package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.Error
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.trackprocessing.ActivityModeDecoder
import com.ians.korno.core.trackprocessing.FinalizeTrackUseCase
import com.ians.korno.core.trackprocessing.ModeDecoderInput

/**
 * Заново размечает сохранённую запись авторежима текущим декодером.
 *
 * Вид движения определяется один раз, при остановке записи, и живёт в БД дальше сам по
 * себе: правка трека сегменты не трогает. Поэтому после изменения декодера старые записи
 * остаются с прежней разметкой, и проверить правку можно только новой поездкой. Всё, из
 * чего декодер решает, у сохранённой записи есть: доплеровская скорость лежит в точках,
 * ответы распознавания — в `recognition_samples`.
 *
 * Не сохранилась только дорожка шагомера: она жила в памяти сессии. Каденс различает
 * ходьбу и бег, и без него эти два вида решаются по одному распознаванию; шаги сегментов
 * не теряются — они делятся между новыми границами по пересечению времени.
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
        if (!activity.isAutoMode || endMillis == null) return Result.Error(Skipped.NOT_AUTO_MODE)

        val recognitions = (trackRepository.getRecognitionSamples(activity.id) as? Result.Success)
            ?.data
            .orEmpty()
        val decoded = modeDecoder.decode(
            ModeDecoderInput(
                startMillis = activity.startTimeEpochMillis,
                endMillis = endMillis,
                points = points,
                recognitions = recognitions,
                manualSegments = segments.filter(ActivitySegment::isManuallyEdited),
            ),
        )
        // Пусто — судить не по чему: у записи нет ни одного ответа о движении (их не было
        // вовсе или распознавание всю дорогу отвечало «не знаю»). Прежняя разметка лучше
        // пустой, поэтому не трогаем её.
        if (decoded.isEmpty()) return Result.Error(Skipped.NO_RECOGNITION)

        val withSteps = decoded.map { segment ->
            segment.copy(
                activityId = activity.id,
                stepCount = stepsBetween(segments, segment.startTimeEpochMillis, segment.endTimeEpochMillis ?: endMillis),
            )
        }
        val track = finalizeTrack(activity, points, withSteps)
        trackRepository.replaceTrack(track.activity, track.points, track.segments)
        return Result.Success(Unit)
    }

    /**
     * Шаги старых сегментов, поделённые по времени между новыми границами. Точнее уже не
     * восстановить: в БД лежит сумма за сегмент, а не показания счётчика во времени.
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
