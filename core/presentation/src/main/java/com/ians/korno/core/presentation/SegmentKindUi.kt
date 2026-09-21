package com.ians.korno.core.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Explore
import androidx.compose.ui.graphics.vector.ImageVector
import com.ians.korno.core.domain.model.SegmentKind

/** Подпись вида движения — одна на экран записи и экран активности. */
fun SegmentKind.labelRes(): Int = when (this) {
    SegmentKind.ON_FOOT -> R.string.segment_kind_on_foot
    SegmentKind.RUNNING -> R.string.segment_kind_running
    SegmentKind.CYCLING -> R.string.segment_kind_cycling
    SegmentKind.VEHICLE -> R.string.segment_kind_vehicle
    SegmentKind.UNKNOWN -> R.string.segment_kind_unknown
}

fun SegmentKind.icon(): ImageVector = when (this) {
    SegmentKind.ON_FOOT -> Icons.AutoMirrored.Filled.DirectionsWalk
    SegmentKind.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    SegmentKind.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    SegmentKind.VEHICLE -> Icons.Filled.DirectionsCar
    SegmentKind.UNKNOWN -> Icons.Filled.Explore
}

/** Виды, которые пользователь может выбрать сам: `UNKNOWN` — не выбор, а отсутствие ответа. */
val SELECTABLE_SEGMENT_KINDS = listOf(
    SegmentKind.ON_FOOT,
    SegmentKind.RUNNING,
    SegmentKind.CYCLING,
    SegmentKind.VEHICLE,
)
