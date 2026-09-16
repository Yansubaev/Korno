package com.ians.korno.core.domain.model

import java.util.Calendar

/** Единица календарного периода — период всегда календарный, не скользящее окно (см. docs/adr/0001). */
enum class CalendarPeriodUnit { WEEK, MONTH, YEAR }

/**
 * Начало текущего календарного периода в epoch-millis. Использует java.util.Calendar, а не
 * java.time/WeekFields: minSdk 24 без core-library-desugaring, а Calendar.getInstance() уже
 * уважает локальный firstDayOfWeek без дополнительных зависимостей.
 */
fun startOfCalendarPeriodMillis(
    unit: CalendarPeriodUnit,
    nowMillis: Long = System.currentTimeMillis(),
): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        when (unit) {
            CalendarPeriodUnit.WEEK -> set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            CalendarPeriodUnit.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
            CalendarPeriodUnit.YEAR -> set(Calendar.DAY_OF_YEAR, 1)
        }
    }
    return calendar.timeInMillis
}
