package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Свёрнутая/развёрнутая шторка поверх карты: жест, форма и ручка — общие для
 * экрана обзора активности и экрана её редактирования, различается только
 * контент внутри.
 */
@Composable
internal fun DraggableSheet(
    heightPx: Float,
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
    onHandleClick: () -> Unit,
    handleContentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val height = with(density) { heightPx.toDp() }
    val draggableState = rememberDraggableState(onDelta = onDrag)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
            .background(MaterialTheme.colorScheme.background)
            // Жест висит на всей шторке, не только на ручке — ни одно касание не
            // проваливается на карту под ней.
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = { onDragStopped() },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDLE_HEIGHT)
                .clickable(onClickLabel = handleContentDescription, onClick = onHandleClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
internal fun CompactStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

internal val SHEET_CORNER = 20.dp
internal val HANDLE_HEIGHT = 34.dp
internal val PEEK_SHEET_HEIGHT = 128.dp
internal val SHEET_ANIMATION = tween<Float>(durationMillis = 360, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
