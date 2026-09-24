package com.ians.korno.core.data.local

import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RoutePoint
import java.nio.ByteBuffer

/**
 * Сколько точек хранится в превью. Силуэт трека читается на квадрате в полсотни пикселей;
 * дальше подробности всё равно не видно.
 */
internal const val ROUTE_PREVIEW_POINTS = 64

private const val BYTES_PER_POINT = 2 * Double.SIZE_BYTES

/** Превью из полного трека: без выбросов, в порядке времени — как `TrackDao.routePoints`. */
internal fun List<GeoPoint>.routePreview(): List<RoutePoint> =
    filterNot(GeoPoint::isOutlier)
        .sortedBy(GeoPoint::timestampEpochMillis)
        .map { RoutePoint(latitude = it.latitude, longitude = it.longitude) }
        .downsampledTo(ROUTE_PREVIEW_POINTS)

internal fun List<RoutePoint>.encodeRoutePreview(): ByteArray =
    ByteBuffer.allocate(size * BYTES_PER_POINT).apply {
        forEach { putDouble(it.latitude).putDouble(it.longitude) }
    }.array()

internal fun ByteArray.decodeRoutePreview(): List<RoutePoint> {
    val buffer = ByteBuffer.wrap(this)
    return List(size / BYTES_PER_POINT) { RoutePoint(latitude = buffer.getDouble(), longitude = buffer.getDouble()) }
}
