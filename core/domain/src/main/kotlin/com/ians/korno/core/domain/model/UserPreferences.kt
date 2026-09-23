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
    // Последний выбранный вид движения: подставляется на главном экране и в настройке
    // записи, чтобы не выбирать его заново перед каждой тренировкой.
    val lastSegmentKind: SegmentKind,
    // Последний выбор режима записи на старте: авто или фиксированный вид. Новые
    // пользователи начинают с авто.
    val lastRecordingAutoMode: Boolean = true,
    // Отладочный выбор вида уведомления записи. В release переключателя нет — там всегда AUTO.
    val recordingNotificationStyle: RecordingNotificationStyle = RecordingNotificationStyle.AUTO,
)

/**
 * Вид уведомления идущей записи.
 *
 * [LIVE_UPDATE] — стандартный шаблон, который Android 16+ продвигает наверх экрана блокировки
 * и в чип статус-бара. [CUSTOM] — своя разметка с круглыми кнопками: продвинуть её система
 * не даст. [AUTO] берёт первый, когда система разрешает продвижение, иначе второй.
 */
enum class RecordingNotificationStyle {
    AUTO,
    LIVE_UPDATE,
    CUSTOM,
}

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

/**
 * Длина сплита и шаг отметок на карте: километр или миля.
 *
 * Живёт рядом с системой мер, а не в аналитике: делить трек на километры для человека,
 * который меряет всё в милях, бессмысленно, даже если считать умеет любой модуль.
 */
val MeasurementSystem.splitLengthMeters: Double
    get() = when (this) {
        MeasurementSystem.METRIC -> 1_000.0
        MeasurementSystem.IMPERIAL -> 1_609.344
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
