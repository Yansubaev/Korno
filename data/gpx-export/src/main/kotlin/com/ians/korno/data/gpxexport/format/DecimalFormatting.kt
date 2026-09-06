package com.ians.korno.data.gpxexport.format

import kotlin.math.abs
import kotlin.math.round

/**
 * Числа в файле трека — машинные, с точкой как разделителем и без экспоненты.
 * `toString()` для Double даёт `1.0E-5`, а `String.format` зависит от локали
 * устройства, поэтому форматируем сами.
 */
internal fun formatDecimal(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    require(decimals in 0..9) { "Unsupported precision: $decimals" }

    var scale = 1L
    repeat(decimals) { scale *= 10 }

    val scaled = round(abs(value) * scale).toLong()
    val integerPart = scaled / scale
    val fractionPart = scaled % scale
    val sign = if (value < 0.0 && scaled != 0L) "-" else ""

    return if (decimals == 0) {
        "$sign$integerPart"
    } else {
        "$sign$integerPart.${fractionPart.toString().padStart(decimals, '0')}"
    }
}

/** Терпимо к пробелам и запятой как разделителю: чужие файлы встречаются всякие. */
internal fun parseDecimal(value: String?): Double? =
    value?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
