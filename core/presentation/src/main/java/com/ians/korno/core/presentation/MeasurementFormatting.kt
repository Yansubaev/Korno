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
 * Темп — время на километр или милю, `4:35`.
 *
 * Для ходьбы, бега и хайкинга он читается лучше скорости: «5:30 мин/км» человек соотносит
 * со своим опытом, а «10,9 км/ч» — нет.
 *
 * Возвращает `null`, когда темпа нет: нулевая скорость даёт бесконечность, а очень малая —
 * трёхзначные минуты, и то и другое лучше не показывать вовсе.
 */
fun Context.formatPace(
    metersPerSecond: Double,
    system: MeasurementSystem,
): FormattedMeasurement? {
    if (metersPerSecond <= 0.0 || !metersPerSecond.isFinite()) return null
    val unitMeters = when (system) {
        MeasurementSystem.METRIC -> 1_000.0
        MeasurementSystem.IMPERIAL -> 1_609.344
    }
    val secondsPerUnit = (unitMeters / metersPerSecond).toLong()
    if (secondsPerUnit >= MAXIMUM_PACE_SECONDS) return null
    val unit = getString(
        if (system == MeasurementSystem.METRIC) R.string.unit_pace_km else R.string.unit_pace_mi,
    )
    return FormattedMeasurement(
        value = String.format(Locale.US, "%d:%02d", secondsPerUnit / 60, secondsPerUnit % 60),
        unit = unit,
    )
}

/** Темп медленнее часа на километр — это уже не движение, а стоянка с дрожащим GPS. */
private const val MAXIMUM_PACE_SECONDS = 3_600L

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
