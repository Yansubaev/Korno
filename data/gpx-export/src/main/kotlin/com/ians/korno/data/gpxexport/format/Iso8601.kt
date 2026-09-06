package com.ians.korno.data.gpxexport.format

/**
 * Отметки времени GPX/TCX в UTC.
 *
 * Своя реализация вместо `java.time`: minSdk проекта — 24, где `java.time` доступен
 * только через desugaring, а формат здесь фиксированный и занимает пару десятков строк.
 * Заодно модуль остаётся чистым Kotlin и переносимым.
 */
internal object Iso8601 {

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
    private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
    private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR

    /** `2025-09-05T06:12:00Z`; доли секунды выводятся, только если они есть. */
    fun format(epochMillis: Long): String {
        val days = floorDiv(epochMillis, MILLIS_PER_DAY)
        val millisOfDay = epochMillis - days * MILLIS_PER_DAY
        val (year, month, day) = civilFromDays(days)

        val hour = (millisOfDay / MILLIS_PER_HOUR).toInt()
        val minute = ((millisOfDay % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE).toInt()
        val second = ((millisOfDay % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND).toInt()
        val millis = (millisOfDay % MILLIS_PER_SECOND).toInt()

        return buildString {
            append(pad(year, width = 4))
            append('-').append(pad(month, width = 2))
            append('-').append(pad(day, width = 2))
            append('T').append(pad(hour, width = 2))
            append(':').append(pad(minute, width = 2))
            append(':').append(pad(second, width = 2))
            if (millis != 0) append('.').append(pad(millis, width = 3))
            append('Z')
        }
    }

    /**
     * Разбирает `YYYY-MM-DDThh:mm:ss[.sss][Z|±hh:mm]`. Смещение необязательно —
     * файлы без него встречаются, и трактуются как UTC. `null`, если строка не подходит.
     */
    fun parse(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.length < MINIMAL_LENGTH) return null

        val year = trimmed.substring(0, 4).toIntOrNull() ?: return null
        if (trimmed[4] != '-' || trimmed[7] != '-') return null
        val month = trimmed.substring(5, 7).toIntOrNull() ?: return null
        val day = trimmed.substring(8, 10).toIntOrNull() ?: return null
        if (trimmed[10] != 'T' && trimmed[10] != 't' && trimmed[10] != ' ') return null
        if (trimmed[13] != ':' || trimmed[16] != ':') return null
        val hour = trimmed.substring(11, 13).toIntOrNull() ?: return null
        val minute = trimmed.substring(14, 16).toIntOrNull() ?: return null
        val second = trimmed.substring(17, 19).toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) {
            return null
        }

        var cursor = 19
        var millis = 0
        if (cursor < trimmed.length && (trimmed[cursor] == '.' || trimmed[cursor] == ',')) {
            cursor++
            val fractionStart = cursor
            while (cursor < trimmed.length && trimmed[cursor].isDigit()) cursor++
            if (cursor == fractionStart) return null
            val fraction = trimmed.substring(fractionStart, cursor)
            millis = fraction.take(3).padEnd(3, '0').toIntOrNull() ?: return null
        }

        val offsetMillis = parseOffset(trimmed, cursor) ?: return null

        val days = daysFromCivil(year, month, day)
        // Секунда 60 — високосная: календарь её не знает, поэтому она приходится
        // на ту же миллисекунду, что и начало следующей минуты.
        val timeOfDay = hour * MILLIS_PER_HOUR +
            minute * MILLIS_PER_MINUTE +
            second.coerceAtMost(59) * MILLIS_PER_SECOND +
            millis
        return days * MILLIS_PER_DAY + timeOfDay - offsetMillis
    }

    private const val MINIMAL_LENGTH = 19

    /** Возвращает смещение в миллисекундах или `null`, если хвост строки — мусор. */
    private fun parseOffset(value: String, from: Int): Long? {
        if (from >= value.length) return 0L
        return when (val marker = value[from]) {
            'Z', 'z' -> if (from == value.lastIndex) 0L else null
            '+', '-' -> {
                val rest = value.substring(from + 1).replace(":", "")
                if (rest.length != 4) return null
                val hours = rest.substring(0, 2).toIntOrNull() ?: return null
                val minutes = rest.substring(2, 4).toIntOrNull() ?: return null
                if (hours > 23 || minutes > 59) return null
                val magnitude = hours * MILLIS_PER_HOUR + minutes * MILLIS_PER_MINUTE
                if (marker == '+') magnitude else -magnitude
            }
            else -> null
        }
    }

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

    private fun floorDiv(value: Long, divisor: Long): Long {
        val quotient = value / divisor
        return if (value % divisor != 0L && (value xor divisor) < 0L) quotient - 1 else quotient
    }

    /** Число дней от 1970-01-01 по алгоритму Говарда Хиннанта (days_from_civil). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val shiftedYear = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (shiftedYear >= 0) shiftedYear else shiftedYear - 399) / 400
        val yearOfEra = shiftedYear - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    /** Обратное преобразование (civil_from_days). */
    private fun civilFromDays(epochDays: Long): Triple<Int, Int, Int> {
        val shifted = epochDays + 719_468L
        val era = (if (shifted >= 0) shifted else shifted - 146_096) / 146_097
        val dayOfEra = shifted - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val year = yearOfEra + era * 400
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthPortion = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * monthPortion + 2) / 5 + 1).toInt()
        val month = (monthPortion + (if (monthPortion < 10) 3 else -9)).toInt()
        return Triple((if (month <= 2) year + 1 else year).toInt(), month, day)
    }
}
