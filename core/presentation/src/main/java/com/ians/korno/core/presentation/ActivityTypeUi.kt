package com.ians.korno.core.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.ui.graphics.vector.ImageVector
import com.ians.korno.core.domain.model.ActivityType

/**
 * Подпись типа активности — одна на все экраны.
 *
 * `OTHER` выбрать нельзя (см. `SETUP_ACTIVITY_TYPES`): он остаётся только там, где тип
 * не определён — авто-детект не подтвердил ни одного сегмента или импорт принёс чужой вид.
 * Поэтому и подпись, и иконка у него «не определено», а не «ходьба».
 */
fun ActivityType.labelRes(): Int = when (this) {
    ActivityType.RUNNING -> R.string.activity_type_run
    ActivityType.CYCLING -> R.string.activity_type_ride
    ActivityType.WALKING -> R.string.activity_type_walk
    ActivityType.HIKING -> R.string.activity_type_hike
    ActivityType.DRIVING -> R.string.activity_type_drive
    ActivityType.OTHER -> R.string.activity_type_unknown
}

fun ActivityType.icon(): ImageVector = when (this) {
    ActivityType.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityType.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityType.HIKING -> Icons.Filled.Hiking
    ActivityType.DRIVING -> Icons.Filled.DirectionsCar
    ActivityType.OTHER -> Icons.Filled.Explore
}
