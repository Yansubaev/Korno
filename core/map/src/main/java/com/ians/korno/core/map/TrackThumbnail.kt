package com.ians.korno.core.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Миниатюра маршрута для списка активностей.
 *
 * Рисует только силуэт трека: в маленьком размере тайлы карты не добавляют полезных
 * деталей, зато требуют отдельного нативного рендерера для каждой миниатюры.
 */
@Composable
fun TrackThumbnail(
    points: List<MapPoint>,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = modifier.background(background),
    ) {
        RouteSketch(points = points, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Схематичная линия трека без тайлов: мгновенно и без сети.
 *
 * Путь строится в [drawWithCache] — заново только при смене размера или точек, а не
 * на каждый кадр анимации перехода, в котором строка списка перерисовывается.
 */
@Composable
internal fun RouteSketch(
    points: List<MapPoint>,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 5f,
) {
    val color = MaterialTheme.colorScheme.primary
    Spacer(
        modifier = modifier.drawWithCache {
            val path = routePath(points, size)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                if (path != null) drawPath(path, color, style = stroke)
            }
        },
    )
}

private fun routePath(points: List<MapPoint>, size: Size): Path? {
    if (points.size < 2) return null
    val minLatitude = points.minOf { it.latitude }
    val maxLatitude = points.maxOf { it.latitude }
    val minLongitude = points.minOf { it.longitude }
    val maxLongitude = points.maxOf { it.longitude }
    val latitudeSpan = (maxLatitude - minLatitude).takeIf { it > 0 } ?: 1.0
    val longitudeSpan = (maxLongitude - minLongitude).takeIf { it > 0 } ?: 1.0

    // Отступ в 10%: линия впритык к краю читается как обрезанная.
    val inset = size.minDimension * 0.1f
    // Оси растягиваются независимо: силуэт заполняет квадрат, пропорции маршрута не
    // сохраняются. Сохранить их — взять общий масштаб min(scaleX, scaleY) для обеих осей,
    // умножив долготу на cos(средней широты), и отцентрировать силуэт по свободной оси.
    val scaleX = (size.width - inset * 2) / longitudeSpan
    val scaleY = (size.height - inset * 2) / latitudeSpan

    return Path().apply {
        points.forEachIndexed { index, point ->
            val x = inset + ((point.longitude - minLongitude) * scaleX).toFloat()
            val y = inset + ((maxLatitude - point.latitude) * scaleY).toFloat()
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
}
