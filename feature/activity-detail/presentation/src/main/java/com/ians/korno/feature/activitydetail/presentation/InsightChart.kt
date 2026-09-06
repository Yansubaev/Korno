package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ChartHeight = 140.dp

/**
 * Линейный график по дистанции.
 *
 * Ось X у всех графиков экрана общая — доля пройденного пути, поэтому касание в любом из
 * них означает одно и то же место маршрута, и карта подсвечивает его без пересчётов.
 */
@Composable
internal fun InsightChart(
    chart: InsightChartUi,
    title: String,
    modifier: Modifier = Modifier,
    height: Dp = ChartHeight,
    filled: Boolean = false,
    selectionX: Float? = null,
    onSelect: ((Float) -> Unit)? = null,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val averageColor = MaterialTheme.colorScheme.onSurfaceVariant
    val stopColor = MaterialTheme.colorScheme.tertiary
    val selectionColor = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surfaceContainer

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            chart.averageLabel?.let { average ->
                Text(
                    text = stringResource(R.string.activity_detail_chart_average, average),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(12.dp))
                .background(surface),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 10.dp)
                    .then(
                        if (onSelect == null) {
                            Modifier
                        } else {
                            Modifier
                                .pointerInput(chart) {
                                    detectTapGestures { offset ->
                                        onSelect(offset.x / size.width)
                                    }
                                }
                                .pointerInput(chart) {
                                    detectHorizontalDragGestures { change, _ ->
                                        onSelect(change.position.x / size.width)
                                    }
                                }
                        },
                    ),
            ) {
                drawStopMarks(chart, stopColor)
                if (filled) drawAreaFill(chart, lineColor)
                drawSeries(chart, lineColor)
                drawAverage(chart, averageColor)
                selectionX?.let { drawSelection(it, selectionColor) }
            }

            Text(
                text = chart.topLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
            Text(
                text = chart.bottomLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

/** Позиция значения по вертикали: у перевёрнутой оси меньшее значение оказывается сверху. */
private fun InsightChartUi.offsetY(value: Float, height: Float): Float {
    val span = maxY - minY
    if (span <= 0f) return height / 2f
    val fraction = ((value - minY) / span).coerceIn(0f, 1f)
    return if (invertY) fraction * height else (1f - fraction) * height
}

/**
 * Разрывы не соединяются: каждый непрерывный участок — своя линия. Соединить их значило
 * бы дорисовать движение там, где его не было.
 */
private fun DrawScope.drawSeries(chart: InsightChartUi, color: Color) {
    chart.segments().forEach { segment ->
        val path = Path()
        segment.forEachIndexed { index, value ->
            val x = value.x * size.width
            val y = chart.offsetY(requireNotNull(value.y), size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 2.5.dp.toPx()))
    }
}

private fun DrawScope.drawAreaFill(chart: InsightChartUi, color: Color) {
    chart.segments().forEach { segment ->
        if (segment.size < 2) return@forEach
        val path = Path()
        path.moveTo(segment.first().x * size.width, size.height)
        segment.forEach { value ->
            path.lineTo(value.x * size.width, chart.offsetY(requireNotNull(value.y), size.height))
        }
        path.lineTo(segment.last().x * size.width, size.height)
        path.close()
        drawPath(
            path = path,
            brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f))),
        )
    }
}

private fun DrawScope.drawAverage(chart: InsightChartUi, color: Color) {
    val average = chart.averageY ?: return
    val y = chart.offsetY(average, size.height)
    drawLine(
        color = color.copy(alpha = 0.6f),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
    )
}

/** Остановки на оси дистанции схлопываются в точку, поэтому это метка, а не область. */
private fun DrawScope.drawStopMarks(chart: InsightChartUi, color: Color) {
    chart.stopMarks.forEach { mark ->
        val x = mark * size.width
        drawLine(
            color = color.copy(alpha = 0.35f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

private fun DrawScope.drawSelection(x: Float, color: Color) {
    val position = x.coerceIn(0f, 1f) * size.width
    drawLine(
        color = color,
        start = Offset(position, 0f),
        end = Offset(position, size.height),
        strokeWidth = 1.5.dp.toPx(),
    )
}

/** Непрерывные участки серии: разрыв — это `null` в значении. */
private fun InsightChartUi.segments(): List<List<ChartValueUi>> {
    val segments = mutableListOf<List<ChartValueUi>>()
    var current = mutableListOf<ChartValueUi>()
    values.forEach { value ->
        if (value.y == null) {
            if (current.size > 1) segments += current
            current = mutableListOf()
        } else {
            current += value
        }
    }
    if (current.size > 1) segments += current
    return segments
}
