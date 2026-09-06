package com.ians.korno.core.presentation

import android.content.Context
import com.ians.korno.core.domain.model.MeasurementSystem
import java.util.Locale

data class FormattedMeasurement(
    val value: String,
    val unit: String,
) {
    val text: String get() = "$value $unit"
}

fun Context.formatDistance(
    meters: Double,
    system: MeasurementSystem,
    decimals: Int = 2,
): FormattedMeasurement {
    val value = when (system) {
        MeasurementSystem.METRIC -> meters / 1_000.0
        MeasurementSystem.IMPERIAL -> meters / 1_609.344
    }
    val unit = getString(
        if (system == MeasurementSystem.METRIC) R.string.unit_km else R.string.unit_mi,
    )
    return FormattedMeasurement(String.format(Locale.getDefault(), "%.${decimals}f", value), unit)
}

fun Context.formatSpeed(
    metersPerSecond: Double,
    system: MeasurementSystem,
    decimals: Int = 1,
): FormattedMeasurement {
    val value = when (system) {
        MeasurementSystem.METRIC -> metersPerSecond * 3.6
        MeasurementSystem.IMPERIAL -> metersPerSecond * 2.2369362921
    }
    val unit = getString(
        if (system == MeasurementSystem.METRIC) R.string.unit_kmh else R.string.unit_mph,
    )
    return FormattedMeasurement(String.format(Locale.getDefault(), "%.${decimals}f", value), unit)
}

/**
 * Короткое расстояние — десятки и сотни метров, где километры бессмысленны.
 * Имперский вариант округляется до десятков футов: точнее исходных данных всё равно нет.
 */
fun Context.formatShortDistance(
    meters: Int,
    system: MeasurementSystem,
): FormattedMeasurement {
    val value = when (system) {
        MeasurementSystem.METRIC -> meters
        MeasurementSystem.IMPERIAL -> ((meters * 3.280839895) / 10).toInt() * 10
    }
    val unit = getString(
        if (system == MeasurementSystem.METRIC) R.string.unit_m else R.string.unit_ft,
    )
    return FormattedMeasurement(value.toString(), unit)
}

fun Context.formatElevation(
    meters: Double,
    system: MeasurementSystem,
): FormattedMeasurement {
    val value = when (system) {
        MeasurementSystem.METRIC -> meters
        MeasurementSystem.IMPERIAL -> meters * 3.280839895
    }
    val unit = getString(
        if (system == MeasurementSystem.METRIC) R.string.unit_m else R.string.unit_ft,
    )
    return FormattedMeasurement(String.format(Locale.getDefault(), "%.0f", value), unit)
}
