package com.ians.korno.core.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap

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
 */
@Composable
internal fun RouteSketch(
    points: List<MapPoint>,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 5f,
) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val sample = points.takeIf { it.size >= 2 } ?: return@Canvas
        val minLatitude = sample.minOf { it.latitude }
        val maxLatitude = sample.maxOf { it.latitude }
        val minLongitude = sample.minOf { it.longitude }
        val maxLongitude = sample.maxOf { it.longitude }
        val latitudeSpan = (maxLatitude - minLatitude).takeIf { it > 0 } ?: 1.0
        val longitudeSpan = (maxLongitude - minLongitude).takeIf { it > 0 } ?: 1.0

        // Отступ в 10%: линия впритык к краю читается как обрезанная.
        val inset = size.minDimension * 0.1f
        val width = size.width - inset * 2
        val height = size.height - inset * 2

        val offsets = sample.map { point ->
            Offset(
                x = inset + ((point.longitude - minLongitude) / longitudeSpan * width).toFloat(),
                y = inset + ((maxLatitude - point.latitude) / latitudeSpan * height).toFloat(),
            )
        }
        for (index in 1 until offsets.size) {
            drawLine(color, offsets[index - 1], offsets[index], strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
    }
}
