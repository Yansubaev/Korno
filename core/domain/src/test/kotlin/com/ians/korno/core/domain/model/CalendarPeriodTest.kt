package com.ians.korno.core.domain.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.util.Calendar
import org.junit.jupiter.api.Test

class CalendarPeriodTest {
    @Test
    fun `week start is midnight on the locale's first day of week`() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 18, 15, 30, 0) }
        val expected = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val result = startOfCalendarPeriodMillis(CalendarPeriodUnit.WEEK, now.timeInMillis)

        assertThat(result).isEqualTo(expected.timeInMillis)
    }

    @Test
    fun `month start is midnight on the 1st`() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 18, 15, 30, 0) }
        val expected = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }

        val result = startOfCalendarPeriodMillis(CalendarPeriodUnit.MONTH, now.timeInMillis)

        assertThat(result).isEqualTo(expected.timeInMillis)
    }

    @Test
    fun `year start is midnight on Jan 1st`() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 18, 15, 30, 0) }
        val expected = Calendar.getInstance().apply { set(2026, Calendar.JANUARY, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }

        val result = startOfCalendarPeriodMillis(CalendarPeriodUnit.YEAR, now.timeInMillis)

        assertThat(result).isEqualTo(expected.timeInMillis)
    }

    @Test
    fun `now already at period start is returned unchanged`() {
        val periodStart = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }

        val result = startOfCalendarPeriodMillis(CalendarPeriodUnit.MONTH, periodStart.timeInMillis)

        assertThat(result).isEqualTo(periodStart.timeInMillis)
    }
}
