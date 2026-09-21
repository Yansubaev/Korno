package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.applyHikingRule
import com.ians.korno.core.domain.model.countsSteps

/** Итоговый трек: всё, что ложится в БД одной перезаписью активности. */
data class FinalizedTrack(
    val activity: Activity,
    val points: List<GeoPoint>,
    val segments: List<ActivitySegment>,
    /** Итоги вместе с диагностикой разрывов, которой в [Activity] нет. */
    val statistics: TrackStatistics,
)

/**
 * Итоговый трек активности — при сохранении записи и после каждой правки. Единственное
 * место, где решается, как смешанная запись превращается в итоги: одинаково для
 * фиксированного режима (один сегмент) и авторежима.
 *
 * Время записи ([Activity.elapsedTimeMillis]) не трогается: его знает только тот, кто
 * вёл запись.
 *
 * @param segments закрытые сегменты активности по порядку; короткие сливаются здесь же.
 */
class FinalizeTrackUseCase(
    private val trackProcessor: TrackProcessor = TrackProcessor(),
    private val segmentDetector: SegmentDetector = SegmentDetector(),
) {
    operator fun invoke(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
    ): FinalizedTrack {
        val track = trackProcessor.processSegments(
            // Вердикты фильтров пересчитываются с нуля: `isOutlier` липкий, и точка,
            // отброшенная прежними порогами, осталась бы выбросом навсегда.
            points = points.map { it.copy(isOutlier = false, isStop = false) },
            // Разрез — после слияния: сегмент паузы короткий и почти без дистанции,
            // слияние поглотило бы его соседом. Обратный порядок делает пересчёт
            // устойчивым — повторный проход режет ровно так же.
            // Склейка после разреза: пауза на границе видов режет оба соседних сегмента,
            // и её половинки — один участок, а не два.
            // Правило хайкинга — раньше всех: оно красит пешие куски, и склеивать
            // соседей одного вида нужно уже по покрашенной разметке.
            segments = segmentDetector.coalesce(
                splitAtManualPauses(
                    segmentDetector.mergeShortSegments(segments.applyHikingRule()),
                    points,
                ),
            ),
            configFor = { kind ->
                TrackProcessingConfig.forSegmentKind(kind)
                    .withAutoPauseSensitivity(activity.autoPauseSensitivity)
            },
        )
        val statistics = track.statistics
        return FinalizedTrack(
            activity = activity.copy(
                distanceMeters = statistics.distanceMeters,
                movingTimeMillis = statistics.movingTimeMillis,
                averageSpeedMetersPerSecond = statistics.averageSpeedMetersPerSecond,
                maxSpeedMetersPerSecond = statistics.maxSpeedMetersPerSecond,
                elevationGainMeters = statistics.elevationGainMeters,
                // В итог идут только шаги на ногах: при педалировании шагомер может считать
                // шаги, но человек их не делал.
                stepCount = activity.stepCount?.let {
                    track.segments.filter { it.kind.countsSteps }.sumOf { it.stepCount ?: 0 }
                },
            ),
            points = track.points,
            segments = track.segments,
            statistics = statistics,
        )
    }

    /**
     * Ручная пауза — отдельный участок, а не часть соседнего вида: иначе её время уходит
     * в соседа и раздувает его длительность и темп (в записи 46 двадцать три минуты паузы
     * стали пешей прогулкой на 0.28 км).
     *
     * Вид у неё [SegmentKind.PAUSE]: на паузе никакого движения не записывалось, и в
     * разбивку по видам и доминирующий вид такой участок не идёт.
     *
     * Границы — по точкам: пауза начинается на первой точке со статусом
     * [RecordingStatus.MANUAL_PAUSE] и кончается на первой точке после неё.
     * ponytail: точность границы — интервал записи на паузе (минута); точных отметок
     * паузы в треке нет. Хватит, пока паузу смотрят глазами в списке участков.
     */
    private fun splitAtManualPauses(segments: List<ActivitySegment>, points: List<GeoPoint>): List<ActivitySegment> {
        val ordered = points.sortedBy(GeoPoint::timestampEpochMillis)
        val pauses = ordered.indices
            .filter { index ->
                ordered[index].recordingStatus == RecordingStatus.MANUAL_PAUSE &&
                    ordered.getOrNull(index - 1)?.recordingStatus != RecordingStatus.MANUAL_PAUSE
            }
            .map { start ->
                val resumed = ordered.drop(start).firstOrNull { it.recordingStatus != RecordingStatus.MANUAL_PAUSE }
                ordered[start].timestampEpochMillis..(resumed?.timestampEpochMillis ?: Long.MAX_VALUE)
            }
        if (pauses.isEmpty()) return segments

        return segments.flatMap { segment ->
            val end = segment.endTimeEpochMillis ?: return@flatMap listOf(segment)
            val edges = (listOf(segment.startTimeEpochMillis, end) + pauses.flatMap { listOf(it.first, it.last) })
                .filter { it in segment.startTimeEpochMillis..end }
                .distinct()
                .sorted()
            // Вырожденный сегмент (пустая запись — старт равен концу) остаётся как есть:
            // резать нечего, а выкинуть его нельзя — вместе с ним пропали бы его точки.
            if (edges.size < 2) return@flatMap listOf(segment)
            edges.zipWithNext { from, to ->
                segment.copy(
                    kind = if (pauses.any { from >= it.first && to <= it.last }) SegmentKind.PAUSE else segment.kind,
                    startTimeEpochMillis = from,
                    endTimeEpochMillis = to,
                    // Шаги остаются у первой части: поделить их точнее нечем, а сумма по
                    // записи от разреза меняться не должна.
                    stepCount = segment.stepCount?.let { if (from == segment.startTimeEpochMillis) it else 0 },
                )
            }
        }
    }
}
