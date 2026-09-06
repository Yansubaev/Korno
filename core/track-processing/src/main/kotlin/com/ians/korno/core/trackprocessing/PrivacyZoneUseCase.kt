package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint

/**
 * Вырезает начало и конец трека в пределах заданного радиуса.
 *
 * Радиус отсчитывается от первой и последней точки, а не от «дома»: приложение не
 * знает адресов пользователя и не должно их узнавать. Для кольцевого маршрута обе
 * зоны совпадают, и трек обрезается с двух сторон одинаково.
 *
 * Если весь трек уложился в радиус, результат пуст — это честнее, чем отдать
 * наружу единственную оставшуюся точку рядом с домом.
 */
class PrivacyZoneUseCase {

    operator fun invoke(points: List<GeoPoint>, radiusMeters: Int): List<GeoPoint> {
        if (radiusMeters <= 0 || points.size < 2) return points

        val radius = radiusMeters.toDouble()
        val start = points.first()
        val finish = points.last()

        val fromIndex = points.indexOfFirst { distanceMeters(start, it) > radius }
        if (fromIndex < 0) return emptyList()

        val toIndex = points.indexOfLast { distanceMeters(finish, it) > radius }
        if (toIndex < fromIndex) return emptyList()

        return points.subList(fromIndex, toIndex + 1)
    }
}
