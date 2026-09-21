package com.ians.korno.data.gpxexport.mapper

import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.data.gpxexport.model.TrackDocument
import com.ians.korno.data.gpxexport.model.TrackDocumentPoint
import com.ians.korno.data.gpxexport.model.TrackDocumentSummary

/** Интервал, которым заполняются точки без времени: одна секунда — типичный шаг трека. */
private const val SYNTHETIC_INTERVAL_MILLIS = 1_000L

fun trackDocumentOf(activity: Activity, points: List<GeoPoint>, kind: SegmentKind): TrackDocument = TrackDocument(
    name = activity.title,
    kind = kind,
    note = activity.note,
    // Точки, помеченные как ошибка GPS, в файл не попадают: экспортируется трек
    // в том виде, в каком его видит пользователь на карте.
    points = points
        .filterNot(GeoPoint::isOutlier)
        .sortedWith(compareBy(GeoPoint::timestampEpochMillis, GeoPoint::id))
        .map { point ->
            TrackDocumentPoint(
                latitude = point.latitude,
                longitude = point.longitude,
                timestampEpochMillis = point.timestampEpochMillis,
                altitudeMeters = point.altitudeMeters,
                speedMetersPerSecond = point.speedMetersPerSecond,
                accuracyMeters = point.accuracyMeters,
                isStop = point.isStop,
            )
        },
    summary = TrackDocumentSummary(
        startTimeEpochMillis = activity.startTimeEpochMillis,
        elapsedTimeMillis = activity.elapsedTimeMillis,
        movingTimeMillis = activity.movingTimeMillis,
        distanceMeters = activity.distanceMeters,
        averageSpeedMetersPerSecond = activity.averageSpeedMetersPerSecond,
        maxSpeedMetersPerSecond = activity.maxSpeedMetersPerSecond,
        elevationGainMeters = activity.elevationGainMeters,
    ),
)

/**
 * Точки для записи в БД.
 *
 * В GPX элемент `<time>` необязателен, а без времени не считается ни скорость, ни
 * длительность. Пропуски восстанавливаются линейно между известными отметками, а
 * файл вовсе без времени раскладывается посекундно от [fallbackStartEpochMillis]:
 * дистанция и форма трека при этом точны, и только темп становится условным.
 */
fun TrackDocument.toGeoPoints(
    activityId: Long,
    fallbackStartEpochMillis: Long,
): List<GeoPoint> {
    val timestamps = synthesizeTimestamps(points, fallbackStartEpochMillis)
    return points.mapIndexed { index, point ->
        GeoPoint(
            activityId = activityId,
            latitude = point.latitude,
            longitude = point.longitude,
            altitudeMeters = point.altitudeMeters,
            timestampEpochMillis = timestamps[index],
            accuracyMeters = point.accuracyMeters,
            speedMetersPerSecond = point.speedMetersPerSecond,
            isStop = point.isStop,
        )
    }.sortedBy(GeoPoint::timestampEpochMillis)
}

private fun synthesizeTimestamps(
    points: List<TrackDocumentPoint>,
    fallbackStartEpochMillis: Long,
): LongArray {
    val result = LongArray(points.size)
    val knownIndices = points.indices.filter { points[it].timestampEpochMillis != null }

    if (knownIndices.isEmpty()) {
        points.indices.forEach { index ->
            result[index] = fallbackStartEpochMillis + index * SYNTHETIC_INTERVAL_MILLIS
        }
        return result
    }

    knownIndices.forEach { index -> result[index] = points[index].timestampEpochMillis!! }

    val first = knownIndices.first()
    for (index in 0 until first) {
        result[index] = result[first] - (first - index) * SYNTHETIC_INTERVAL_MILLIS
    }

    val last = knownIndices.last()
    for (index in last + 1 until points.size) {
        result[index] = result[last] + (index - last) * SYNTHETIC_INTERVAL_MILLIS
    }

    knownIndices.zipWithNext().forEach { (start, end) ->
        val span = end - start
        if (span <= 1) return@forEach
        val step = (result[end] - result[start]).toDouble() / span
        for (offset in 1 until span) {
            result[start + offset] = result[start] + (step * offset).toLong()
        }
    }

    return result
}
