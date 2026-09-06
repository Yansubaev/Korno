package com.ians.korno.data.gpxexport.model

import com.ians.korno.core.domain.model.ActivityType

/**
 * Трек в том виде, в каком он живёт в файле: без идентификаторов БД и без
 * пересчитанной статистики. Промежуточная модель между доменом и XML —
 * так writer и parser тестируются без Room и без Android.
 */
data class TrackDocument(
    val name: String?,
    val activityType: ActivityType,
    val points: List<TrackDocumentPoint>,
    val note: String? = null,
    /**
     * Итоги записи. Нужны формату TCX, который требует их в `<Lap>`.
     * При импорте игнорируются: статистика чужого файла может быть посчитана
     * как угодно, поэтому её всегда считает наш собственный pipeline.
     */
    val summary: TrackDocumentSummary? = null,
) {
    val startTimeEpochMillis: Long?
        get() = summary?.startTimeEpochMillis ?: points.firstNotNullOfOrNull { it.timestampEpochMillis }
}

data class TrackDocumentPoint(
    val latitude: Double,
    val longitude: Double,
    /** `null` допустим: в GPX элемент `<time>` необязателен. */
    val timestampEpochMillis: Long?,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
    val accuracyMeters: Float? = null,
    val isStop: Boolean = false,
)

data class TrackDocumentSummary(
    val startTimeEpochMillis: Long,
    val elapsedTimeMillis: Long,
    val movingTimeMillis: Long,
    val distanceMeters: Double,
    val averageSpeedMetersPerSecond: Double,
    val maxSpeedMetersPerSecond: Double,
    val elevationGainMeters: Double,
)
