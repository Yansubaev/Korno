package com.ians.korno.core.presentation

import java.util.Locale

/**
 * Длительность как на секундомере: `12:34` или `1:02:03`.
 *
 * Локаль фиксирована: это не число и не дата, а позиционная запись времени, одинаковая
 * везде, — подставлять сюда локальные разделители нечего.
 */
fun formatTimerDuration(millis: Long): String {
    val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
