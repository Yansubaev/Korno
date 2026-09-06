package com.ians.korno.core.domain.model

data class UserPreferences(
    val measurementSystem: MeasurementSystem,
    val autoPauseEnabled: Boolean,
    val gpsAccuracy: GpsAccuracy,
    val autoPauseSensitivity: AutoPauseSensitivity,
    // Material You: брать цвета из обоев устройства вместо фирменной палитры.
    // Хранится независимо от версии Android — применимость решает UI-слой.
    val dynamicColorEnabled: Boolean,
    val privacyZone: PrivacyZone,
    val onboardingCompleted: Boolean,
    // Тип последней выбранной активности: подставляется на главном экране и в настройке
    // записи, чтобы не выбирать его заново перед каждой тренировкой.
    val lastActivityType: ActivityType,
)

/**
 * Радиус, который вырезается из начала и конца трека при экспорте и отправке файла.
 *
 * Записанный трек не меняется — обрезка применяется только к тому, что уходит из
 * приложения наружу. Иначе выключить настройку задним числом было бы уже нечего:
 * дом и работа пропали бы из собственных данных вместе с чужими глазами.
 */
enum class PrivacyZone(val radiusMeters: Int) {
    OFF(radiusMeters = 0),
    SMALL(radiusMeters = 100),
    MEDIUM(radiusMeters = 250),
    LARGE(radiusMeters = 500),
}

enum class MeasurementSystem {
    METRIC,
    IMPERIAL,
}

enum class GpsAccuracy {
    HIGH,
    BALANCED,
    BATTERY_SAVER,
}

enum class AutoPauseSensitivity {
    LOW,
    MEDIUM,
    HIGH,
}
